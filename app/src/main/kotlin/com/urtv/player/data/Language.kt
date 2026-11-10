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
    private const val URTV_AUTHORITY = "sa.urtv.home.lang"

    fun readUrtv(ctx: Context): String? = runCatching {
        // only trust the answer if the provider belongs to sa.urtv.home signed by UrTV's release certificate
        val owner = ctx.packageManager.resolveContentProvider(URTV_AUTHORITY, 0)?.packageName
        if (owner != "sa.urtv.home" || !Integrity.callerAllowed(owner, Integrity.certDigestsOf(ctx, owner))) return@runCatching null
        ctx.contentResolver.query(Uri.parse(URTV_URI), arrayOf("lang"), null, null, null)?.use { c -> if (c.moveToFirst()) normalize(c.getString(0)) else null }
    }.getOrNull()

    /** Result of the cold-start decision: the language to show and what to remember of UrTV's answer. */
    data class Start(val lang: String, val urtvToCache: String?)

    /**
     * Cold start (before the first frame): a manual choice wins without any lookup; in Auto, UrTV's provider is asked first
     * ([read] may return null on failure/timeout) and the last remembered answer is only the fallback. Unit-tested with a fake provider.
     */
    fun resolveAtStart(choice: String, cachedUrtv: String?, system: String, read: () -> String?): Start {
        if (choice == "ar" || choice == "en") return Start(choice, null)
        val fresh = runCatching(read).getOrNull()
        return Start(resolve(choice, fresh ?: cachedUrtv, system), fresh)
    }

    /** Runs [block] on a helper thread and waits at most [timeoutMs]; null when it is slower, throws or returns null. */
    fun <T> within(timeoutMs: Long, block: () -> T?): T? {
        var out: T? = null
        val t = Thread({ out = runCatching(block).getOrNull() }, "lang-read").apply { isDaemon = true; start() }
        t.join(timeoutMs)
        return if (t.isAlive) null else out
    }
}
