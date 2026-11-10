package com.urtv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.data.Diag
import com.urtv.player.data.Integrity
import com.urtv.player.data.Report

private sealed interface ShareStep {
    object Choice : ShareStep
    object Topic : ShareStep
    data class Code(val text: String, val link: String) : ShareStep
}

/** The facts for the summary, gathered from the app; nothing here is free text from a playlist or a server. */
private fun collect(app: UrtvApp): Report.Info {
    val ctx = app.applicationContext
    val look = app.store.appearance.value
    val lowRam = (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
    return Report.Info(
        version = com.urtv.player.BuildConfig.VERSION_NAME.removeSuffix("-debug"),
        official = when (app.integrity) { Integrity.Status.Official -> "official"; Integrity.Status.Debug -> "debug"; else -> "unofficial" },
        androidRelease = android.os.Build.VERSION.RELEASE.orEmpty(), sdk = android.os.Build.VERSION.SDK_INT,
        brand = android.os.Build.MANUFACTURER.orEmpty(), ram = if (lowRam) "low" else "normal",
        language = app.store.resolvedLang(), theme = look.theme.name, accent = look.accent.name,
        playlists = app.store.playlists.value, homeServers = app.servers.servers.value.size,
        codec = app.player.stats.value.codec.substringBefore(' '), lastPlayerError = Diag.lastPlayerError, recentErrors = Diag.recent(5),
    )
}

/** Settings > About > «Report or suggestion»: choose, pick a topic, then a QR. No typing, no network call from the Player. */
@Composable
fun ShareScreen(app: UrtvApp, onDone: () -> Unit) {
    var step by remember { mutableStateOf<ShareStep>(ShareStep.Choice) }
    var kind by remember { mutableStateOf(Report.Kind.Problem) }
    BackHandler { if (step is ShareStep.Choice) onDone() else step = if (step is ShareStep.Code && kind == Report.Kind.Problem) ShareStep.Topic else ShareStep.Choice }
    fun show(topic: String) {
        val text = Report.build(kind, topic, collect(app))
        step = ShareStep.Code(text, Report.link(text))
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(step) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 128.dp, vertical = 72.dp)) {
        Text(stringResource(R.string.share_row), style = T.display)
        Spacer(Modifier.height(40.dp))
        when (val s = step) {
            ShareStep.Choice -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TvButton(stringResource(R.string.share_report), { kind = Report.Kind.Problem; step = ShareStep.Topic }, primary = true, focusRequester = first)
                TvButton(stringResource(R.string.share_request), { kind = Report.Kind.Request; show("other") })
            }
            ShareStep.Topic -> {
                Text(stringResource(R.string.share_topic_title), style = T.title)
                Spacer(Modifier.height(24.dp))
                val topics = listOf(
                    "playback" to R.string.share_topic_playback, "lists" to R.string.share_topic_lists, "guide" to R.string.share_topic_guide,
                    "home server" to R.string.share_topic_server, "interface" to R.string.share_topic_ui, "other" to R.string.share_topic_other)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((i, t) in topics.withIndex()) TvButton(stringResource(t.second), { show(t.first) }, primary = i == 0, focusRequester = if (i == 0) first else null, modifier = Modifier.width(520.dp))
                }
            }
            is ShareStep.Code -> Row(horizontalArrangement = Arrangement.spacedBy(96.dp), verticalAlignment = Alignment.CenterVertically) {
                Qr(s.link, Modifier.size(560.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_scan), style = T.title)
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.share_contains), style = T.body.copy(color = C.muted))
                    Spacer(Modifier.height(32.dp))
                    TvButton(stringResource(R.string.fm_back), onDone, focusRequester = first)
                }
            }
        }
    }
}
