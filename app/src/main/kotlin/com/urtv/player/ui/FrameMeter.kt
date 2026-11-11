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
    DisposableEffect(label) {
        val window: Window? = (ctx as? Activity)?.window ?: (ctx as? android.content.ContextWrapper)?.baseContext?.let { (it as? Activity)?.window }
        val thread = HandlerThread("frame-meter").apply { start() }
        val main = Handler(android.os.Looper.getMainLooper())
        val frames = ArrayList<Double>(320); var lastPublish = 0L
        val interval = (1000.0 / (window?.windowManager?.defaultDisplay?.refreshRate?.takeIf { it > 20f } ?: 60f))
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val ms = m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
            synchronized(frames) { frames.add(ms); if (frames.size > 300) frames.removeAt(0) }
            val now = System.nanoTime()
            if (now - lastPublish > 2_000_000_000L) {
                lastPublish = now
                val r = synchronized(frames) { FrameStats.of(frames.toList(), interval + 0.2) }
                main.post { text = r.line }
            }
        }
        window?.addOnFrameMetricsAvailableListener(l, Handler(thread.looper))
        onDispose { runCatching { window?.removeOnFrameMetricsAvailableListener(l) }; thread.quitSafely() }
    }
    androidx.compose.foundation.text.BasicText("$label  $text", modifier.background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 6.dp),
        style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
}
