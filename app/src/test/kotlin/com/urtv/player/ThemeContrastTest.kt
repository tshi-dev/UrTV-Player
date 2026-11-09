package com.urtv.player

import androidx.compose.ui.graphics.Color
import com.urtv.player.data.AccentId
import com.urtv.player.data.Appearance
import com.urtv.player.data.FocusStyle
import com.urtv.player.data.ThemeId
import com.urtv.player.ui.blend
import com.urtv.player.ui.contrast
import com.urtv.player.ui.resolveSpec
import kotlin.test.Test
import kotlin.test.assertTrue

/** WCAG: text 4.5:1 (7:1 in High contrast), focus indicators 3:1, for every theme x accent x focus style. */
class ThemeContrastTest {
    private val failures = mutableListOf<String>()

    private fun check(name: String, a: Color, b: Color, min: Double) {
        val r = contrast(a, b)
        if (r < min) failures += "%s: %.2f < %.1f".format(name, r, min)
    }

    @Test fun everyCombinationPasses() {
        var n = 0
        for (t in ThemeId.entries) for (a in AccentId.entries) for (f in FocusStyle.entries) {
            n++
            val s = resolveSpec(Appearance(theme = t, accent = a, focus = f))
            val id = "$t/$a/$f"
            val text = if (t == ThemeId.HighContrast) 7.0 else 4.5
            val surfaces = listOf("bg" to s.bg, "surface" to s.surface, "surfaceHi" to s.surfaceHi, "focusSoft" to s.focusSoft)
            for ((n2, bg) in surfaces) {
                check("$id text/$n2", s.text, bg, text)
                check("$id muted/$n2", blend(s.text, bg, s.mutedAlpha), bg, text)
                check("$id accent/$n2", s.accent, bg, text)
                check("$id danger/$n2", s.danger, bg, text)
            }
            check("$id onAccent/accent", s.onAccent, s.accent, 4.5)
            check("$id onFocusSolid/focusSolid", s.onFocusSolid, s.focusSolid, text)
            check("$id mutedOnFocusSolid", blend(s.onFocusSolid, s.focusSolid, s.mutedAlpha), s.focusSolid, text)
            // Focus indicator against everything it can touch: >= 3:1.
            for ((n2, bg) in surfaces.take(3)) {
                val indicator = when (f) { FocusStyle.Solid -> s.focusSolid; FocusStyle.Ring -> s.ring; FocusStyle.Glow -> s.accent }
                check("$id focus($f)/$n2", indicator, bg, 3.0)
            }
        }
        assertTrue(n == 6 * 8 * 3)
        assertTrue(failures.isEmpty(), failures.take(40).joinToString("\n"))
    }
}
