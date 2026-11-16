package com.urtv.player.ui

import android.view.accessibility.CaptioningManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.urtv.player.data.Store

/** How big the subtitles are: the person's choice in Settings times the TV's own caption size (Android Settings, Accessibility, Captions). */
object SubStyle {
    val PRESETS = floatArrayOf(0.75f, 1f, 1.3f, 1.7f)
    const val BASE_SP = 26f          // «normal»: a comfortable 2-line subtitle on a 1080p TV (the old fixed 34 sp title size was far too big)
    fun sizeSp(preset: Int, systemScale: Float): Float = BASE_SP * PRESETS[preset.coerceIn(0, PRESETS.lastIndex)] * systemScale.coerceIn(0.5f, 2f)
}

/** The subtitle text of the picture, sized by [SubStyle]; the background plate and colours follow the TV's caption style when the person set one. */
@Composable
fun SubtitleText(text: String, store: Store, modifier: Modifier = Modifier) {
    val preset by store.subSize.collectAsState()
    val ctx = LocalContext.current
    val cm = remember(ctx) { runCatching { ctx.getSystemService(android.content.Context.CAPTIONING_SERVICE) as CaptioningManager }.getOrNull() }
    val scale = cm?.fontScale ?: 1f
    val st = cm?.userStyle
    val fg = st?.takeIf { it.hasForegroundColor() }?.foregroundColor?.let { Color(it) } ?: Color.White
    val bg = st?.takeIf { it.hasBackgroundColor() }?.backgroundColor?.let { Color(it) } ?: Color(0x99000000)
    val size = SubStyle.sizeSp(preset, scale)
    Text(text, style = TextStyle(color = fg, fontSize = size.sp, lineHeight = (size * 1.25f).sp, textAlign = TextAlign.Center), modifier = modifier.background(bg, Rd(8)).padding(horizontal = 14.dp, vertical = 4.dp))
}
