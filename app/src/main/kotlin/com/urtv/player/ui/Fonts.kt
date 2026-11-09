package com.urtv.player.ui

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * UI font. IBM Plex Sans Arabic (open licence, in the repository) is the fallback. When the private Thmanyah files were
 * put into assets/fonts at build time (CI "Private fonts" step, never committed) they become the primary family.
 */
object Fonts {
    @Volatile var current: FontFamily = Plex; private set
    @Volatile var usingThmanyah = false; private set

    /** Looks for fonts/Thmanyah-{Regular,Medium,Bold}.otf in the assets. Cheap; call once at start. */
    fun init(ctx: Context) {
        val names = runCatching { ctx.assets.list("fonts")?.toSet() }.getOrNull().orEmpty()
        fun f(n: String, w: FontWeight): Font? = if ("$n.otf" in names) Font("fonts/$n.otf", ctx.assets, w) else null
        val regular = f("Thmanyah-Regular", FontWeight.Normal) ?: return
        val medium = f("Thmanyah-Medium", FontWeight.Medium) ?: regular.let { Font("fonts/Thmanyah-Regular.otf", ctx.assets, FontWeight.Medium) }
        val bold = f("Thmanyah-Bold", FontWeight.SemiBold) ?: f("Thmanyah-Medium", FontWeight.SemiBold) ?: Font("fonts/Thmanyah-Regular.otf", ctx.assets, FontWeight.SemiBold)
        current = FontFamily(regular, medium, bold); usingThmanyah = true
    }
}
