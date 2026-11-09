package com.urtv.player.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.urtv.player.data.AccentId
import com.urtv.player.data.Appearance
import com.urtv.player.data.BgStyle
import com.urtv.player.data.CornerStyle
import com.urtv.player.data.FocusStyle
import com.urtv.player.data.ThemeId
import kotlin.math.pow

/**
 * Every colour, the type family, corner scale and focus treatment the UI uses. Screens never hard-code
 * these: they read [C]/[T] (or [LocalTheme]) so a change in Appearance applies instantly.
 */
@Immutable
data class ThemeSpec(
    val id: ThemeId,
    val dark: Boolean,
    val bg: Color,
    val bgTop: Color,          // top of the subtle gradient
    val surface: Color,
    val surfaceHi: Color,
    val text: Color,
    val mutedAlpha: Float,     // muted text = text at this alpha
    val accent: Color,         // readable as text/graphics on bg, surface, surfaceHi and focusSoft
    val onAccent: Color,
    val danger: Color,
    val focusSolid: Color,     // focus = solid fill (light on dark themes, dark on the light theme)
    val onFocusSolid: Color,
    val focusSoft: Color,      // focus fill for the Ring and Glow styles
    val ring: Color,
    val scrim: Color,          // dialogs
    val focus: FocusStyle,
    val cornerScale: Float,
    val background: BgStyle,
    val outlineAtRest: Boolean,
    val font: FontFamily = Fonts.current,
) {
    val solidFocus get() = focus == FocusStyle.Solid
    val focusFill get() = if (solidFocus) focusSolid else focusSoft
    /** Text colour on the focused fill. */
    val onFocus get() = if (solidFocus) onFocusSolid else text
}

// ---- contrast maths (WCAG 2.x), on opaque colours -------------------------------------------------------------
private fun lin(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
fun luminance(c: Color): Double = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
fun contrast(a: Color, b: Color): Double {
    val x = luminance(a); val y = luminance(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}
private fun argb(r: Float, g: Float, b: Float): Color =
    Color((0xFF shl 24) or ((r * 255 + 0.5f).toInt() shl 16) or ((g * 255 + 0.5f).toInt() shl 8) or (b * 255 + 0.5f).toInt())
/** [fg] at [alpha] over the opaque [bg]. */
fun blend(fg: Color, bg: Color, alpha: Float): Color =
    argb(fg.red * alpha + bg.red * (1 - alpha), fg.green * alpha + bg.green * (1 - alpha), fg.blue * alpha + bg.blue * (1 - alpha))

/** Nudges [c] toward white (or black) just far enough to reach [min] against every background in [on]. */
fun ensureContrast(c: Color, on: List<Color>, min: Double, towardWhite: Boolean): Color {
    val target = if (towardWhite) Color.White else Color.Black
    var t = 0f
    var cur = c
    while (on.any { contrast(cur, it) < min } && t < 1f) { t += 0.02f; cur = blend(target, c, t) }
    return cur
}

private fun rgb(v: Int) = Color(0xFF000000.toInt() or v)

private class Base(
    val dark: Boolean, val bg: Int, val top: Int, val surface: Int, val hi: Int, val text: Int,
    val focusSolid: Int, val onFocusSolid: Int, val soft: Int, val ring: Int, val scrim: Long,
    val mutedAlpha: Float = 0.7f, val minContrast: Double = 4.5, val outline: Boolean = false,
)

private fun base(t: ThemeId) = when (t) {
    ThemeId.Default -> Base(true, 0x0E0F11, 0x1A1D21, 0x16181B, 0x1E2125, 0xF2F2F2, 0xF2F2F2, 0x0E0F11, 0x2D3238, 0xF2F2F2, 0xCC050607)
    ThemeId.Oled -> Base(true, 0x000000, 0x0B0B0C, 0x0C0C0D, 0x17181A, 0xF2F2F2, 0xF2F2F2, 0x000000, 0x2A2C30, 0xF2F2F2, 0xDD000000)
    ThemeId.Midnight -> Base(true, 0x0A1020, 0x16213F, 0x111A2E, 0x1A2540, 0xEAF0FF, 0xEAF0FF, 0x0A1020, 0x2A3960, 0xEAF0FF, 0xCC050914)
    ThemeId.Sand -> Base(true, 0x171210, 0x2A201A, 0x211A16, 0x2D241E, 0xF5EBDD, 0xF5EBDD, 0x171210, 0x4A392C, 0xF5EBDD, 0xCC0D0907)
    ThemeId.Light -> Base(false, 0xF5F5F7, 0xFFFFFF, 0xFFFFFF, 0xE6E7EB, 0x111114, 0x111114, 0xF5F5F7, 0xCDD0D8, 0x111114, 0xB3F5F5F7)
    ThemeId.HighContrast -> Base(true, 0x000000, 0x000000, 0x0A0A0A, 0x1F1F1F, 0xFFFFFF, 0xFFE600, 0x000000, 0x333333, 0xFFFFFF, 0xEE000000,
        mutedAlpha = 0.86f, minContrast = 7.0, outline = true)
}

/** Eight tasteful accents. Each is adjusted per theme, so it always stays readable (see ThemeContrastTest). */
fun accentBase(a: AccentId): Color = rgb(when (a) {
    AccentId.Blue -> 0x0A84FF
    AccentId.Teal -> 0x1FB5A8
    AccentId.Green -> 0x34C759
    AccentId.Gold -> 0xD4AF37
    AccentId.Amber -> 0xFF9F0A
    AccentId.Coral -> 0xFF6B57
    AccentId.Rose -> 0xF0577D
    AccentId.Violet -> 0x9D8CFF
})

fun resolveSpec(a: Appearance): ThemeSpec {
    val b = base(a.theme)
    val bg = rgb(b.bg); val surface = rgb(b.surface); val hi = rgb(b.hi); val soft = rgb(b.soft)
    val surfaces = listOf(bg, surface, hi, soft)
    val accent = ensureContrast(accentBase(a.accent), surfaces, b.minContrast, towardWhite = b.dark)
    val dark = rgb(0x0B0B0C)
    val onAccent = if (contrast(dark, accent) >= contrast(Color.White, accent)) dark else Color.White
    return ThemeSpec(
        id = a.theme, dark = b.dark, bg = bg, bgTop = rgb(b.top), surface = surface, surfaceHi = hi,
        text = rgb(b.text), mutedAlpha = b.mutedAlpha, accent = accent, onAccent = onAccent,
        danger = ensureContrast(rgb(0xFF6B6B), surfaces, b.minContrast, towardWhite = b.dark),
        focusSolid = rgb(b.focusSolid), onFocusSolid = rgb(b.onFocusSolid), focusSoft = soft, ring = rgb(b.ring),
        scrim = Color(b.scrim), focus = a.focus,
        cornerScale = if (a.corner == CornerStyle.Square) 0.2f else 1f,
        background = a.background, outlineAtRest = b.outline,
    )
}
