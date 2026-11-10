package com.urtv.player.ui

import android.view.Choreographer
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.urtv.player.core.FrameStats

/**
 * Stats overlay line for UI smoothness: frame-to-frame time of the UI thread over the last ~300 frames, janky share (> 25 ms) and
 * percentiles, labelled with the screen, so a TV test can report them per screen. Only runs while the playback stats are switched on.
 */
@Composable
fun FrameMeterOverlay(label: String, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    DisposableEffect(label) {
        val ch = Choreographer.getInstance(); val iv = ArrayList<Double>(320); var last = 0L; var lastPublish = 0L; var running = true
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                if (!running) return
                if (last > 0) { iv.add((t - last) / 1e6); if (iv.size > 300) iv.removeAt(0) }
                last = t
                if (t - lastPublish > 2_000_000_000L) { lastPublish = t; text = FrameStats.of(iv).line }
                ch.postFrameCallback(this)
            }
        }
        ch.postFrameCallback(cb)
        onDispose { running = false }
    }
    androidx.compose.foundation.text.BasicText("$label  $text", modifier.background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 6.dp),
        style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
}
