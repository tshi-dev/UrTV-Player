package com.urtv.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Calm dark palette. Focus is always a bright ring plus lift, never colour alone. */
object C {
    /** True for the pure-black theme (OLED / dark rooms). Read inside composition, so toggling recomposes. */
    var oled by androidx.compose.runtime.mutableStateOf(false)
    val bg get() = if (oled) Color.Black else Color(0xFF0B0D12)
    val bgTop get() = if (oled) Color(0xFF050505) else Color(0xFF141826)
    val surface get() = if (oled) Color(0xFF0E0E10) else Color(0xFF151A24)
    val surfaceHi get() = if (oled) Color(0xFF1A1B20) else Color(0xFF1F2633)
    val focusFill = Color(0xFF2B3550)
    val ring = Color(0xFFFFFFFF)
    val accent = Color(0xFF7C6CFF)
    val accent2 = Color(0xFF22C1F2)
    val text = Color(0xFFF3F5FA)
    val muted = Color(0xFF98A2B3)
    val danger = Color(0xFFFF6B6B)
    val scrim = Color(0xCC05070B)
    val accentBrush = Brush.linearGradient(listOf(accent, accent2))
    val bgBrush get() = Brush.verticalGradient(listOf(bgTop, bg))
}

/** Large, generous line height so Arabic diacritics never clip at TV distance. */
object T {
    val display = TextStyle(fontSize = 44.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold, color = C.text)
    val title = TextStyle(fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, color = C.text)
    val body = TextStyle(fontSize = 22.sp, lineHeight = 32.sp, color = C.text)
    val bodyBold = body.copy(fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, color = C.muted)
    val tiny = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = C.muted)
}

@Composable
fun UrtvTheme(oled: Boolean = false, textScale: Int = 0, content: @Composable () -> Unit) {
    C.oled = oled
    val d = androidx.compose.ui.platform.LocalDensity.current
    val scale = listOf(1f, 1.15f, 1.3f).getOrElse(textScale) { 1f }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * scale)) {
    MaterialTheme(colorScheme = darkColorScheme(background = C.bg, surface = C.surface, primary = C.accent, onSurface = C.text, onBackground = C.text), content = content)
    }
}
