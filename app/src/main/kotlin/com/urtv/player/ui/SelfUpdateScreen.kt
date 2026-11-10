package com.urtv.player.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.SelfUpdate
import com.urtv.player.data.SelfUpdater
import com.urtv.player.data.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Phase { Explain, Working, Handed, Failed }

/** In-app update when UrTV is not installed: one explanation of the Android permission, then download, check, hand-over. Back cancels. */
@Composable
fun SelfUpdateScreen(app: UrtvApp, offer: Update.Offer, onDone: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onDone)
    var phase by remember { mutableStateOf(if (SelfUpdater.canInstall(ctx)) Phase.Working else Phase.Explain) }
    var pct by remember { mutableStateOf(0) }
    var verifying by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && phase == Phase.Explain && SelfUpdater.canInstall(ctx)) phase = Phase.Working }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(phase) {
        if (phase != Phase.Working) return@LaunchedEffect
        val rel = SelfUpdate.Release(offer.name, offer.code.toLong(), offer.apkUrl, offer.shaUrl)
        val r = withContext(Dispatchers.IO) { SelfUpdater.run(ctx.applicationContext, rel, { d, t -> if (t > 0) pct = (d * 100 / t).toInt().coerceIn(0, 100); if (d == t) verifying = true }) }
        phase = if (r is SelfUpdater.Outcome.Handed) Phase.Handed else Phase.Failed
        if (phase == Phase.Failed) { app.update = Update.State(Update.Kind.UpToDate) }     // quiet: tried again tomorrow
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(phase) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 128.dp, vertical = 72.dp)) {
        Text(stringResource(R.string.upd_available, offer.name), style = T.display)
        Spacer(Modifier.height(40.dp))
        when (phase) {
            Phase.Explain -> {
                Text(stringResource(R.string.su_allow_title), style = T.title)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.su_allow_body), style = T.body.copy(color = C.muted), modifier = Modifier.width(1000.dp))
                Spacer(Modifier.height(40.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvButton(stringResource(R.string.su_open_settings), {
                        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                        try { ctx.startActivity(i) } catch (_: Exception) { phase = Phase.Working }     // no such screen on this TV: Android's installer will ask by itself
                    }, primary = true, focusRequester = first)
                    TvButton(stringResource(R.string.fm_back), onDone)
                }
            }
            Phase.Working -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Spinner(48); Text(if (verifying) stringResource(R.string.su_verifying) else stringResource(R.string.su_downloading, pct), style = T.title)
            }
            Phase.Handed -> { Text(stringResource(R.string.su_handed), style = T.title); Spacer(Modifier.height(32.dp)); TvButton(stringResource(R.string.fm_back), onDone, primary = true, focusRequester = first) }
            Phase.Failed -> { Text(stringResource(R.string.su_failed), style = T.title, modifier = Modifier.width(1100.dp)); Spacer(Modifier.height(32.dp)); TvButton(stringResource(R.string.fm_back), onDone, primary = true, focusRequester = first) }
        }
    }
}
