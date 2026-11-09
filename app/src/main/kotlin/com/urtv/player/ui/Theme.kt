package com.urtv.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Calm dark palette. Focus is always a bright ring plus lift, never colour alone. */
object C {
    val bg = Color(0xFF0B0D12)
    val bgTop = Color(0xFF141826)
    val surface = Color(0xFF151A24)
    val surfaceHi = Color(0xFF1F2633)
    val focusFill = Color(0xFF2B3550)
    val ring = Color(0xFFFFFFFF)
    val accent = Color(0xFF7C6CFF)
    val accent2 = Color(0xFF22C1F2)
    val text = Color(0xFFF3F5FA)
    val muted = Color(0xFF98A2B3)
    val danger = Color(0xFFFF6B6B)
    val scrim = Color(0xCC05070B)
    val accentBrush = Brush.linearGradient(listOf(accent, accent2))
    val bgBrush = Brush.verticalGradient(listOf(bgTop, bg))
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
fun UrtvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = C.bg, surface = C.surface, primary = C.accent, onSurface = C.text, onBackground = C.text), content = content)
}
