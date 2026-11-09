package com.urtv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.urtv.player.player.PlayerController

/** Developer/diagnostic overlay (Settings > Playback): resolution, bitrates, buffer, dropped frames, codec, first-frame time. */
@Composable
fun StatsOverlay(ctrl: PlayerController, modifier: Modifier = Modifier) {
    val s by ctrl.stats.collectAsState()
    val mono = T.small.copy(color = Color.White, fontSize = 18.sp, lineHeight = 24.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    Column(modifier.background(Color(0xB3000000), Rd(10)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("${s.width}x${s.height}  ${"%.0f".format(java.util.Locale.US, s.fps)} fps  ${s.codec}", style = mono)
        Text("video ${s.videoKbps} kbit/s   net ${s.estimateKbps} kbit/s${if (s.capped) "   (capped)" else ""}", style = mono)
        Text("buffer ${s.bufferedMs / 1000.0}s   dropped ${s.dropped}", style = mono)
        Text("first frame ${s.ttffMs} ms   avg ${s.ttffAvgMs} ms", style = mono)
    }
}
