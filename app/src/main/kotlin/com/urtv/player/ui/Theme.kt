package com.urtv.player.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.urtv.player.data.Appearance
import com.urtv.player.data.BgStyle

val Plex = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(com.urtv.player.R.font.plex_regular, FontWeight.Normal),
    androidx.compose.ui.text.font.Font(com.urtv.player.R.font.plex_medium, FontWeight.Medium),
    androidx.compose.ui.text.font.Font(com.urtv.player.R.font.plex_semibold, FontWeight.SemiBold),
)

val LocalTheme = compositionLocalOf { resolveSpec(Appearance()) }
/** True inside a focused [Tv]: text and icons switch to the colour that reads on the focus fill. */
val LocalFocused = compositionLocalOf { false }

/** Colour tokens of the current theme. Reads happen in composition, so a theme change recomposes everything. */
object C {
    var spec by mutableStateOf(resolveSpec(Appearance()))
    val bg get() = spec.bg
    val bgTop get() = spec.bgTop
    val surface get() = spec.surface
    val surfaceHi get() = spec.surfaceHi
    val focusFill get() = spec.focusFill
    val ring get() = spec.ring
    val accent get() = spec.accent
    val onAccent get() = spec.onAccent
    /** Old name of [accent], kept so the player screens need no change. */
    val accent2 get() = spec.accent
    val danger get() = spec.danger
    val scrim get() = spec.scrim
    val text get() = spec.text
    val muted get() = spec.text.copy(alpha = spec.mutedAlpha)
    /** 45 % text: numbers, counts, hints. */
    val faint get() = spec.text.copy(alpha = if (spec.mutedAlpha > 0.8f) 0.7f else 0.45f)
    /** Layers over any background: quiet fill for the nav pill and the selected item, and hairlines. */
    val glass get() = spec.text.copy(alpha = 0.07f)
    val glass2 get() = spec.text.copy(alpha = 0.13f)
    val hairline get() = spec.text.copy(alpha = 0.08f)
    /** Text colour on the focused fill (dark on the solid light fill, the normal text colour otherwise). */
    val onFocus get() = spec.onFocus
    val onFocus2 get() = spec.onFocus.copy(alpha = 0.72f)
    val onFocus3 get() = spec.onFocus.copy(alpha = 0.5f)
    /** Fill of the thin progress bars and now-lines. */
    val accentBrush: Brush get() = SolidColor(spec.accent)
    /** Screens no longer paint their own background: the window does (see [UrtvTheme]). Kept as a transparent brush so existing `background(C.bgBrush)` calls cost nothing. */
    val bgBrush: Brush get() = SolidColor(androidx.compose.ui.graphics.Color.Transparent)
    /** Dark layers drawn over video (OSD, mini guide). Always dark, whatever the theme. */
    val videoScrim = Color(0xEE05070B)
    val videoPanel = Color(0xF005070B)
    val videoPanelFade = Color(0xE005070B)
    val videoRow = Color(0x66151A24)
    val subtitleBg = Color(0x99000000)
    val digitsBg = Color(0xAA000000)
}

/** Ink for text/icons: dark on a solid focus fill, the theme text colour otherwise. */
@Composable fun ink(): Color = if (LocalFocused.current) LocalTheme.current.onFocus else LocalTheme.current.text
@Composable fun mutedInk(): Color = ink().copy(alpha = LocalTheme.current.mutedAlpha)
/** Accent as text/icon; on a solid focus fill the accent would not reach contrast, so it follows the ink. */
@Composable fun accentInk(): Color = if (LocalFocused.current && LocalTheme.current.solidFocus) LocalTheme.current.onFocusSolid else LocalTheme.current.accent
@Composable fun dangerInk(): Color = if (LocalFocused.current && LocalTheme.current.solidFocus) LocalTheme.current.onFocusSolid else LocalTheme.current.danger

/** Rounded shape whose radius follows the corner style. */
@Composable fun Rd(dp: Int): RoundedCornerShape = RoundedCornerShape((dp * LocalTheme.current.cornerScale).dp)

/** Type scale for 1080p: title 32, row 24, body 22, small 19. Colours follow focus. Tabular figures line up times and numbers. */
object T {
    private val f @Composable get() = LocalTheme.current.font
    private fun s(size: Int, line: Int, w: FontWeight, f: FontFamily, c: Color) = TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = w, color = c, fontFamily = f, fontFeatureSettings = "tnum")
    val display @Composable get() = s(44, 60, FontWeight.SemiBold, f, ink())
    val title @Composable get() = s(34, 46, FontWeight.SemiBold, f, ink())
    val row @Composable get() = s(26, 36, FontWeight.Medium, f, ink())
    val body @Composable get() = s(24, 34, FontWeight.Normal, f, ink())
    val bodyBold @Composable get() = s(24, 34, FontWeight.Medium, f, ink())
    val small @Composable get() = s(21, 30, FontWeight.Normal, f, mutedInk())
    val tiny @Composable get() = s(18, 26, FontWeight.Normal, f, mutedInk().copy(alpha = 0.64f * LocalTheme.current.mutedAlpha / 0.7f))
}

@Composable
fun UrtvTheme(appearance: Appearance = Appearance(), textScale: Int = 0, content: @Composable () -> Unit) {
    val spec = remember(appearance) { resolveSpec(appearance) }
    C.spec = spec
    // The screen background is the WINDOW's background (one static drawable the system draws), not a full-screen gradient that Compose records again on every frame.
    val hostView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(spec) {
        val w = (hostView.context as? android.app.Activity)?.window ?: (hostView.context as? android.content.ContextWrapper)?.baseContext?.let { (it as? android.app.Activity)?.window }
        w?.setBackgroundDrawable(if (spec.background == BgStyle.Solid) android.graphics.drawable.ColorDrawable(spec.bg.toArgb()) else android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(spec.bgTop.toArgb(), spec.bg.toArgb())))
        onDispose { }
    }
    // Fixed 1920 x 1080 dp canvas on every TV (1 dp = 1 px at 1080p), independent of the box's density setting.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val widthPx = ctx.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val scale = listOf(1f, 1.15f, 1.3f).getOrElse(textScale) { 1f }
    CompositionLocalProvider(LocalTheme provides spec, LocalDensity provides Density(widthPx / 1920f, scale)) {
        MaterialTheme(
            colorScheme = if (spec.dark) darkColorScheme(background = spec.bg, surface = spec.surface, primary = spec.accent, onSurface = spec.text, onBackground = spec.text)
            else lightColorScheme(background = spec.bg, surface = spec.surface, primary = spec.accent, onSurface = spec.text, onBackground = spec.text),
            content = content,
        )
    }
}
