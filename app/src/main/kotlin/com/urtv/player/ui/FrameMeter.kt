package com.urtv.player.ui

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.urtv.player.core.FrameStats

/**
 * Stats overlay line for UI smoothness, measured like `dumpsys gfxinfo`: the TOTAL_DURATION of every frame the window really drew
 * (FrameMetrics), over the last 300 frames, with the share that missed one display interval and the percentiles. It starts empty each
 * time the screen [label] changes, so a number belongs to one screen. Runs only while the playback stats are switched on.
 */
@Composable
fun FrameMeterOverlay(label: String, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    // is the baseline profile compiled yet? (a fresh install is not: see docs/PERFORMANCE.md)
    val aot by produceState("aot ?") { value = withContext(Dispatchers.IO) { runCatching {
        val s = androidx.profileinstaller.ProfileVerifier.getCompilationStatusAsync().get(3, java.util.concurrent.TimeUnit.SECONDS)
        if (s.isCompiledWithProfile) "aot: compiled with profile" else if (s.hasProfileEnqueuedForCompilation()) "aot: profile waiting for the system's idle compile" else "aot: no profile compiled (code 0x" + Integer.toHexString(s.profileInstallResultCode) + ")"
    }.getOrDefault("aot ?") } }
    DisposableEffect(label) {
        val window: Window? = (ctx as? Activity)?.window ?: (ctx as? android.content.ContextWrapper)?.baseContext?.let { (it as? Activity)?.window }
        val thread = HandlerThread("frame-meter").apply { start() }
        val main = Handler(android.os.Looper.getMainLooper())
        val frames = ArrayList<FrameStats.Frame>(320); var lastPublish = 0L
        val interval = (1000.0 / (window?.windowManager?.defaultDisplay?.refreshRate?.takeIf { it > 20f } ?: 60f))
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
            val ms = total / 1e6
            // like `dumpsys gfxinfo`: janky = the frame missed its own deadline (API 31+ reports it); before that, more than 1.5 display intervals
            val missed = if (android.os.Build.VERSION.SDK_INT >= 31) total > m.getMetric(FrameMetrics.DEADLINE) else ms > interval * 1.5
            synchronized(frames) { frames.add(FrameStats.Frame(ms, missed)); if (frames.size > 300) frames.removeAt(0) }
            val now = System.nanoTime()
            if (now - lastPublish > 2_000_000_000L) {
                lastPublish = now
                val r = synchronized(frames) { FrameStats.of(frames.toList()) }
                main.post { text = r.line }
            }
        }
        window?.addOnFrameMetricsAvailableListener(l, Handler(thread.looper))
        onDispose { runCatching { window?.removeOnFrameMetricsAvailableListener(l) }; thread.quitSafely() }
    }
    androidx.compose.foundation.text.BasicText("$label  $aot\n$text", modifier.background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 6.dp),
        style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
}
