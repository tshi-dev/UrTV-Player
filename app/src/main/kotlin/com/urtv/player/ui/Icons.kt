package com.urtv.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A handful of vector glyphs drawn on a canvas, so no icon font or image asset is shipped. */
enum class Glyph { Heart, Lock, Search, Play, Plus, Refresh, Check }

@Composable
fun Icon(g: Glyph, tint: Color = C.text, size: Int = 28, filled: Boolean = false) {
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.minDimension; val w = s * 0.09f
        val st = Stroke(w, cap = StrokeCap.Round)
        when (g) {
            Glyph.Heart -> {
                val p = Path().apply {
                    moveTo(s * .5f, s * .88f)
                    cubicTo(s * -.1f, s * .5f, s * .2f, s * .05f, s * .5f, s * .32f)
                    cubicTo(s * .8f, s * .05f, s * 1.1f, s * .5f, s * .5f, s * .88f); close()
                }
                if (filled) drawPath(p, tint) else drawPath(p, tint, style = st)
            }
            Glyph.Lock -> {
                drawRoundRect(tint, Offset(s * .2f, s * .45f), Size(s * .6f, s * .45f), androidx.compose.ui.geometry.CornerRadius(s * .08f))
                drawArc(tint, 180f, 180f, false, Offset(s * .3f, s * .1f), Size(s * .4f, s * .7f), style = st)
            }
            Glyph.Search -> {
                drawCircle(tint, s * .28f, Offset(s * .43f, s * .43f), style = st)
                drawLine(tint, Offset(s * .63f, s * .63f), Offset(s * .88f, s * .88f), w, StrokeCap.Round)
            }
            Glyph.Play -> drawPath(Path().apply { moveTo(s * .25f, s * .12f); lineTo(s * .25f, s * .88f); lineTo(s * .85f, s * .5f); close() }, tint)
            Glyph.Plus -> { drawLine(tint, Offset(s * .5f, s * .15f), Offset(s * .5f, s * .85f), w, StrokeCap.Round); drawLine(tint, Offset(s * .15f, s * .5f), Offset(s * .85f, s * .5f), w, StrokeCap.Round) }
            Glyph.Refresh -> { drawArc(tint, 40f, 280f, false, Offset(s * .15f, s * .15f), Size(s * .7f, s * .7f), style = st); drawPath(Path().apply { moveTo(s * .68f, s * .05f); lineTo(s * .88f, s * .27f); lineTo(s * .58f, s * .3f); close() }, tint) }
            Glyph.Check -> drawPath(Path().apply { moveTo(s * .15f, s * .55f); lineTo(s * .4f, s * .78f); lineTo(s * .85f, s * .25f) }, tint, style = st)
        }
    }
}
