package com.urtv.player.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.player.PlayStatus
import kotlinx.coroutines.delay

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** Movies, episodes and catch-up: seek bar, ±10 s steps (accelerating), resume position, auto-next episode. */
@Composable
fun VodPlayerScreen(app: UrtvApp, items: List<VodPlay>, start: Int, startMs: Long, onExit: () -> Unit) {
    val ctrl = app.player
    var idx by remember { mutableStateOf(start) }
    val item = items[idx]
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var bar by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    var options by remember { mutableStateOf(false) }
    var firstStart by remember { mutableLongStateOf(startMs) }
    var seekStreak by remember { mutableStateOf(0) }
    val root = remember { FocusRequester() }
    val status by ctrl.status.collectAsState()
    val paused by ctrl.paused.collectAsState()
    val ended by ctrl.ended.collectAsState()
    val attempt by ctrl.attempt.collectAsState()

    LaunchedEffect(item.url) { ctrl.playVod(item.title, item.url, firstStart); firstStart = 0; bar = true; tick++ }
    LaunchedEffect(item.url) {
        var n = 0
        while (true) {
            delay(500)
            val p = ctrl.player ?: continue
            pos = p.currentPosition; dur = p.duration.takeIf { it > 0 } ?: 0
            if (++n % 10 == 0) app.store.savePosition(item.key, pos, dur)
        }
    }
    LaunchedEffect(ended) { if (ended) { app.store.savePosition(item.key, dur, dur); if (idx + 1 < items.size) idx++ else onExit() } }
    LaunchedEffect(tick, bar, paused) { if (bar && !paused && !options) { delay(4000); bar = false } }
    LaunchedEffect(options) { if (!options) runCatching { root.requestFocus() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { app.store.savePosition(item.key, pos, dur); ctrl.release() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { ctrl.resume() }
    DisposableEffect(item.key) { onDispose { app.store.savePosition(item.key, pos, dur) } }
    DisposableEffect(Unit) { onDispose { ctrl.stop() } }
    BackHandler { if (options) options = false else if (bar && !paused) bar = false else onExit() }

    fun seek(deltaMs: Long) {
        val p = ctrl.player ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0, if (dur > 0) dur - 1000 else Long.MAX_VALUE)
        p.seekTo(target); pos = target; bar = true; tick++
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(root).focusable().onPreviewKeyEvent { e ->
            if (options) return@onPreviewKeyEvent false
            if (e.type != KeyEventType.KeyDown) { if (e.type == KeyEventType.KeyUp) seekStreak = 0; return@onPreviewKeyEvent false }
            when (e.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekStreak++; seek(-(if (seekStreak > 12) 60_000L else if (seekStreak > 4) 30_000L else 10_000L)); true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekStreak++; seek(if (seekStreak > 12) 60_000L else if (seekStreak > 4) 30_000L else 10_000L); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { ctrl.togglePause(); bar = true; tick++; true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> { bar = true; tick++; true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { options = true; true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (idx + 1 < items.size) idx++; true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (idx > 0) idx--; true }
                else -> false
            }
        },
    ) {
        VideoSurface(ctrl)
        val sub by ctrl.cue.collectAsState()
        if (sub.isNotBlank()) Text(sub, style = T.title.copy(color = Color.White), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp, start = 120.dp, end = 120.dp).background(Color(0x99000000), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 4.dp))
        Box(Modifier.align(Alignment.Center)) {
            when {
                status == PlayStatus.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(stringResource(R.string.play_failed), style = T.title); Text(stringResource(R.string.press_ok_retry), style = T.small) }
                status == PlayStatus.Reconnecting -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Spinner(); Text(stringResource(R.string.reconnecting, attempt, com.urtv.player.player.PlayerController.MAX_ATTEMPTS), style = T.body) }
                status == PlayStatus.Loading -> Spinner()
                paused -> Text("❚❚", style = T.display)
            }
        }
        AnimatedVisibility(bar || paused, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE05070B)))).padding(horizontal = 64.dp).padding(top = 60.dp, bottom = 36.dp)) {
                Text(item.title, style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                ProgressBar(if (dur > 0) pos / dur.toFloat() else 0f)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(clock(pos), style = T.small); Spacer(Modifier.weight(1f)); Text(if (dur > 0) clock(dur) else "", style = T.small)
                }
            }
        }
        AnimatedVisibility(options, Modifier.align(Alignment.CenterEnd), enter = fadeIn(), exit = fadeOut()) {
            OptionsPanel(app, item.title, null, {}, null, onReload = { ctrl.retryNow() }, onClose = { options = false })
        }
    }
}
