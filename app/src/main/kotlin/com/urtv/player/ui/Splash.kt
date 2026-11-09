package com.urtv.player.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val PLAY = "M182,108 Q160,96 160,122 V390 Q160,416 182,404 L396,276 Q418,256 396,236 Z"

/**
 * Launch animation (about 800 ms, never blocks input): the gold mark eases in (0.92 to 1), the sun sinks a little toward the
 * dune line, then the whole splash cross-fades into the start screen. Skipped when the system has animations turned off.
 */
@Composable
fun LaunchSplash(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val animationsOff = remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
    val enter = remember { Animatable(0f) }      // 0..1: fade/scale in
    val sun = remember { Animatable(0f) }        // 0..1: sun sinks
    val fade = remember { Animatable(1f) }       // splash alpha
    LaunchedEffect(Unit) {
        if (animationsOff) { onDone(); return@LaunchedEffect }
        coroutineScope {
            launch { enter.animateTo(1f, tween(380, easing = LinearOutSlowInEasing)) }
            launch { sun.animateTo(1f, tween(560, easing = LinearOutSlowInEasing)) }
        }
        fade.animateTo(0f, tween(260))
        onDone()
    }
    val path = remember { PathParser().parsePathString(PLAY).toPath() }
    Box(Modifier.fillMaxSize().alpha(fade.value).background(Brush.linearGradient(listOf(Color(0xFF103322), Color(0xFF03100A)))), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(260.dp).alpha(enter.value)) {
            val k = size.minDimension / 512f * (0.92f + 0.08f * enter.value)
            scale(k, k, pivot = Offset(size.width / 2, size.height / 2)) {
                val off = Offset((size.width / k - 512f) / 2, (size.height / k - 512f) / 2)
                translate(off.x, off.y) {
                    drawPath(path, Brush.linearGradient(listOf(Color(0xFFF3D493), Color(0xFFC39B52)), Offset(160f, 96f), Offset(418f, 416f)))
                    clipPath(path) {
                        drawCircle(Color(0xFF0B2418), 30f, Offset(250f, 262f + 14f * sun.value))
                        drawPath(PathParser().parsePathString("M150,318 C206,282 262,286 314,304 C350,316 384,306 420,290").toPath(), Color(0xFF0B2418),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(14f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                }
            }
        }
    }
}
