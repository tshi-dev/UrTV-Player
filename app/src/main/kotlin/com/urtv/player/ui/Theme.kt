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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.urtv.player.data.Appearance
import com.urtv.player.data.BgStyle

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
    /** Fill of the thin progress bars and now-lines. */
    val accentBrush: Brush get() = SolidColor(spec.accent)
    val bgBrush: Brush get() = if (spec.background == BgStyle.Solid) SolidColor(spec.bg) else Brush.verticalGradient(listOf(spec.bgTop, spec.bg))
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

/** Large, generous line height so Arabic diacritics never clip at TV distance. Colours follow focus. */
object T {
    private val f @Composable get() = LocalTheme.current.font
    val display @Composable get() = TextStyle(fontSize = 44.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold, color = ink(), fontFamily = f)
    val title @Composable get() = TextStyle(fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, color = ink(), fontFamily = f)
    val body @Composable get() = TextStyle(fontSize = 22.sp, lineHeight = 32.sp, color = ink(), fontFamily = f)
    val bodyBold @Composable get() = body.copy(fontWeight = FontWeight.SemiBold)
    val small @Composable get() = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, color = mutedInk(), fontFamily = f)
    val tiny @Composable get() = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = mutedInk(), fontFamily = f)
}

@Composable
fun UrtvTheme(appearance: Appearance = Appearance(), textScale: Int = 0, content: @Composable () -> Unit) {
    val spec = remember(appearance) { resolveSpec(appearance) }
    C.spec = spec
    val d = LocalDensity.current
    val scale = listOf(1f, 1.15f, 1.3f).getOrElse(textScale) { 1f }
    CompositionLocalProvider(LocalTheme provides spec, LocalDensity provides Density(d.density, d.fontScale * scale)) {
        MaterialTheme(
            colorScheme = if (spec.dark) darkColorScheme(background = spec.bg, surface = spec.surface, primary = spec.accent, onSurface = spec.text, onBackground = spec.text)
            else lightColorScheme(background = spec.bg, surface = spec.surface, primary = spec.accent, onSurface = spec.text, onBackground = spec.text),
            content = content,
        )
    }
}
