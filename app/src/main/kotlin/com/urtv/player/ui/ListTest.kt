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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.ListProbe
import com.urtv.player.player.ListTester

/** Settings > Playlists > «Test the playlist»: a polite sample probe; the screen shows numbers only, never a link. */
@Composable
fun ListTestScreen(app: UrtvApp, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    var round by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf(0 to 0) }
    var summary by remember { mutableStateOf<ListProbe.Summary?>(null) }
    LaunchedEffect(round) {
        summary = null; progress = 0 to 0
        val results = ListTester.run(app.catalog.state.value.channels, onProgress = { d, t -> progress = d to t })   // cancelled with the screen
        summary = ListProbe.summarize(results)
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(summary != null) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 128.dp, vertical = 72.dp)) {
        Text(stringResource(R.string.lt_row), style = T.display)
        Spacer(Modifier.height(40.dp))
        val s = summary
        if (s == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { Spinner(48); Text(stringResource(R.string.lt_running, progress.first, progress.second.takeIf { it > 0 } ?: ListProbe.DEFAULT_SAMPLE), style = T.title) }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(when (s.verdict) { ListProbe.Verdict.Good -> R.string.lt_good; ListProbe.Verdict.Fair -> R.string.lt_fair; ListProbe.Verdict.Weak -> R.string.lt_weak; ListProbe.Verdict.NoData -> R.string.lt_nodata }), style = T.title)
                if (s.tested > 0) {
                    Text(stringResource(R.string.lt_reach, s.reachablePct, s.tested), style = T.body)
                    if (s.reachable > 0) Text(stringResource(R.string.lt_ttfb, "%.1f".format(java.util.Locale.US, s.medianMs / 1000.0), "%.1f".format(java.util.Locale.US, s.p90Ms / 1000.0)), style = T.body)
                    val f = s.fails
                    if (s.reachable < s.tested) Text(stringResource(R.string.lt_fails, (f[ListProbe.Fail.Timeout] ?: 0) + (f[ListProbe.Fail.Connection] ?: 0), f[ListProbe.Fail.Http5xx] ?: 0, f[ListProbe.Fail.Http4xx] ?: 0), style = T.body.copy(color = C.muted))
                }
                Text(stringResource(R.string.lt_note), style = T.small)
            }
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TvButton(stringResource(R.string.lt_again), { round++ }, primary = true, focusRequester = first)
                TvButton(stringResource(R.string.fm_back), onDone)
            }
        }
    }
}
