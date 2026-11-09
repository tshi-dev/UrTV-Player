package com.urtv.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.urtv.player.R

/**
 * Near-black neutral layers, one accent. Rest state has no outline; focus is a solid light surface with dark text,
 * a slight lift and (for image cards) a white ring.
 */
object C {
    /** True for the pure-black theme. Read inside composition, so toggling recomposes. */
    var oled by mutableStateOf(false)
    val bg get() = if (oled) Color.Black else Color(0xFF0E0F11)
    val bgTop get() = if (oled) Color(0xFF050506) else Color(0xFF121418)
    val surface get() = if (oled) Color(0xFF0C0C0D) else Color(0xFF16181B)
    val surfaceHi get() = if (oled) Color(0xFF17181A) else Color(0xFF1E2125)
    val glass = Color(0x12FFFFFF)
    val glass2 = Color(0x21FFFFFF)
    val hairline = Color(0x14FFFFFF)

    val text = Color(0xFFF2F2F2)
    val muted = Color(0xB3F2F2F2)      // 70 %
    val faint = Color(0x73F2F2F2)      // 45 %
    val onFocus = Color(0xFF111111)
    val onFocus2 = Color(0xFF4A4A4A)
    val onFocus3 = Color(0xFF6A6A6A)

    val focusFill = Color(0xFFF2F2F2)
    val ring = Color(0xFFFFFFFF)
    val accent = Color(0xFF0A84FF)
    val accent2 = accent               // kept for call sites; there is one accent
    val danger = Color(0xFFFF6B6B)
    val scrim = Color(0xCC05070B)
    val accentBrush get() = Brush.linearGradient(listOf(accent, accent))
    val bgBrush get() = Brush.verticalGradient(listOf(bgTop, bg))
}

val Plex = FontFamily(
    Font(R.font.plex_regular, FontWeight.Normal),
    Font(R.font.plex_medium, FontWeight.Medium),
    Font(R.font.plex_semibold, FontWeight.SemiBold),
)

/** Type scale for 1080p TVs: title 32, row 24, meta 18. Tabular figures so times and numbers line up. */
object T {
    private fun s(size: Int, line: Int, w: FontWeight, c: Color) =
        TextStyle(fontFamily = Plex, fontSize = size.sp, lineHeight = line.sp, fontWeight = w, color = c, fontFeatureSettings = "tnum")
    val display = s(44, 60, FontWeight.SemiBold, C.text)
    val title = s(32, 44, FontWeight.SemiBold, C.text)
    val row = s(24, 34, FontWeight.Medium, C.text)
    val body = s(22, 32, FontWeight.Normal, C.text)
    val bodyBold = s(22, 32, FontWeight.Medium, C.text)
    val small = s(19, 28, FontWeight.Normal, C.muted)
    val tiny = s(16, 24, FontWeight.Normal, C.faint)
}

/** Whether the nearest [Tv] container is focused: text inside swaps to dark tones on the light focus surface. */
val LocalFocused = staticCompositionLocalOf { false }

@Composable
fun UrtvTheme(oled: Boolean = false, textScale: Int = 0, content: @Composable () -> Unit) {
    C.oled = oled
    // Fixed 1920 x 1080 dp canvas on every TV (1 dp = 1 px at 1080p), so the layout never depends on the box's density setting.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val widthPx = ctx.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val scale = listOf(1f, 1.15f, 1.3f).getOrElse(textScale) { 1f }
    CompositionLocalProvider(LocalDensity provides Density(widthPx / 1920f, scale)) {
        MaterialTheme(colorScheme = darkColorScheme(background = C.bg, surface = C.surface, primary = C.accent, onSurface = C.text, onBackground = C.text), content = content)
    }
}
