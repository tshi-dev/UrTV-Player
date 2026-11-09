package com.urtv.player.data

import android.content.Context
import android.net.Uri

/**
 * UI language policy. Choices: "auto" (follow the UrTV home app, else the TV's language), "ar", "en".
 * A manual choice always wins. Pure decision logic lives in [resolve] (unit-tested).
 */
object Language {
    const val AUTO = "auto"
    /** UrTV exposes its language read-only through this provider (one row, column "lang" = ar|en; normal permission READ_LANG). */
    const val URTV_URI = "content://sa.urtv.home.lang/lang"

    fun resolve(choice: String, urtv: String?, system: String): String = when {
        choice == "ar" || choice == "en" -> choice
        urtv == "ar" || urtv == "en" -> urtv
        system == "en" -> "en"
        else -> "ar"                         // Arabic first: any other system language gets Arabic
    }

    fun next(choice: String) = when (choice) { AUTO -> "ar"; "ar" -> "en"; else -> AUTO }

    fun normalize(raw: String?): String? = raw?.trim()?.lowercase()?.take(2)?.takeIf { it == "ar" || it == "en" }

    fun systemLanguage(): String = android.content.res.Resources.getSystem().configuration.locales.let { if (it.isEmpty) "ar" else it[0].language }

    /** UrTV's language if the UrTV home app is installed and exposes it; otherwise null. Call off the main thread. */
    fun readUrtv(ctx: Context): String? = runCatching {
        ctx.contentResolver.query(Uri.parse(URTV_URI), arrayOf("lang"), null, null, null)?.use { c -> if (c.moveToFirst()) normalize(c.getString(0)) else null }
    }.getOrNull()
}
