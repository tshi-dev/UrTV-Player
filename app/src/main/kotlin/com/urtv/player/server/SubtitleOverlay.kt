package com.urtv.player.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text

/**
 * Draws the active subtitle cues with the user's style. The text engine of Android does Arabic shaping and bidi;
 * [Bidi] only fixes each line's base direction so punctuation lands on the correct side.
 */
@Composable
fun SubtitleOverlay(cues: List<Cue>, style: SubtitleStyle, modifier: Modifier = Modifier) {
    if (cues.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val px = with(LocalDensity.current) { (constraints.maxHeight * style.size).toSp() }
        val family = when (style.font) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; else -> FontFamily.SansSerif }
        val base = TextStyle(
            fontSize = px, lineHeight = px * 1.25f, color = Color(style.color), fontFamily = family, fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center, textDirection = TextDirection.Content,
        )
        val edge = Color(style.edgeColor)
        val k = style.size * constraints.maxHeight * 0.05f
        val drawn = when (style.edge) {
            Edge.Shadow -> base.copy(shadow = Shadow(edge, Offset(k, k), k * 2))
            Edge.Raised -> base.copy(shadow = Shadow(edge, Offset(-k, -k), k))
            Edge.Depressed -> base.copy(shadow = Shadow(edge, Offset(k, k), k))
            else -> base
        }
        for (top in listOf(false, true)) {
            val group = cues.filter { it.top == top }
            if (group.isEmpty()) continue
            val text = Bidi.fix(group.joinToString("\n") { it.text })
            val pad = with(LocalDensity.current) { (constraints.maxHeight * style.bottom).toDp() }
            val align = if (top) Alignment.TopCenter else Alignment.BottomCenter
            val pos = Modifier.align(align).padding(start = 120.dp, end = 120.dp, top = if (top) pad else 0.dp, bottom = if (top) 0.dp else pad)
            Box(pos) {
                val bg = if (style.background ushr 24 != 0L) Modifier.background(Color(style.background), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 4.dp) else Modifier
                Box(bg) {
                    if (style.edge == Edge.Outline) Text(text, style = base.copy(color = edge, drawStyle = Stroke(width = k * 2.2f, join = StrokeJoin.Round)))
                    Text(text, style = drawn)
                }
            }
        }
    }
}
