package com.urtv.player.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Updates come through the UrTV home app and nowhere else: the Player never downloads an APK. UrTV owns the files, checks their
 * sha256 and signing certificate, and hands them to Android's installer. The Player only asks UrTV, at most once a day on a cold
 * start and never during playback, whether a newer signed Player exists, and can ask UrTV to start that installation.
 *
 * Contract (UrTV side): read-only provider content://sa.urtv.home.player/update, one row with version_code (int),
 * version_name (text), notes (text, at most 120 chars, may be empty); no row = nothing newer known. The provider must belong to
 * sa.urtv.home signed by UrTV's release certificate (same check as the language provider).
 * Install: explicit intent, action [ACTION_INSTALL], package sa.urtv.home, extra version_code (int), startActivityForResult;
 * RESULT_OK = handed to Android's installer.
 */
object Update {
    const val AUTHORITY = "sa.urtv.home.player"
    const val URI = "content://sa.urtv.home.player/update"
    const val ACTION_INSTALL = "sa.urtv.home.action.INSTALL_PLAYER"
    const val UPDATE_PACKAGE = "sa.urtv.home"
    const val INTERVAL_MS = 24L * 3_600_000L

    data class Offer(val code: Int, val name: String, val notes: String)
    enum class Kind { NoUrtv, UpToDate, Available }
    data class State(val kind: Kind, val offer: Offer? = null)

    private val NAME = Regex("[0-9A-Za-z._+-]{1,24}")

    /** Once a day; a clock that went backwards counts as due. */
    fun due(lastMs: Long, now: Long): Boolean = lastMs <= 0 || now < lastMs || now - lastMs >= INTERVAL_MS

    /** A provider row turned into an offer, only if it is plausible and newer than [currentCode]. Free text is cleaned and capped. */
    fun offerFrom(code: Int?, name: String?, notes: String?, currentCode: Int): Offer? {
        if (code == null || code <= currentCode || name == null || !NAME.matches(name.trim())) return null
        val n = (notes ?: "").filter { !it.isISOControl() }.trim().take(120)
        return Offer(code, name.trim(), n)
    }

    /** Provider owner must be UrTV home with UrTV's pinned certificate. */
    fun trusted(ctx: Context): Boolean = runCatching {
        val owner = ctx.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName
        owner == UPDATE_PACKAGE && Integrity.callerAllowed(owner, Integrity.certDigestsOf(ctx, owner))
    }.getOrDefault(false)

    /** Asks UrTV (blocking, call off the main thread). [trust] is injectable for tests. */
    fun read(ctx: Context, currentCode: Int, trust: (Context) -> Boolean = ::trusted): State {
        if (!trust(ctx)) return State(Kind.NoUrtv)
        return runCatching {
            ctx.contentResolver.query(Uri.parse(URI), arrayOf("version_code", "version_name", "notes"), null, null, null)?.use { c ->
                if (!c.moveToFirst()) State(Kind.UpToDate)
                else offerFrom(c.getInt(0), c.getString(1), c.getString(2), currentCode)?.let { State(Kind.Available, it) } ?: State(Kind.UpToDate)
            } ?: State(Kind.NoUrtv)
        }.getOrDefault(State(Kind.NoUrtv))
    }

    // ---- what was last learned, so Home and About can show it without asking again
    fun load(sp: SharedPreferences, currentCode: Int): State = when (sp.getString("upd_kind", null)) {
        "available" -> offerFrom(sp.getInt("upd_code", 0), sp.getString("upd_name", null), sp.getString("upd_notes", null), currentCode)?.let { State(Kind.Available, it) } ?: State(Kind.UpToDate)   // installed since: no longer newer
        "nourtv" -> State(Kind.NoUrtv)
        "uptodate" -> State(Kind.UpToDate)
        else -> State(Kind.UpToDate)
    }

    fun save(sp: SharedPreferences, s: State, now: Long) {
        sp.edit().putLong("upd_last", now).putString("upd_kind", when (s.kind) { Kind.Available -> "available"; Kind.NoUrtv -> "nourtv"; Kind.UpToDate -> "uptodate" })
            .apply { s.offer?.let { putInt("upd_code", it.code).putString("upd_name", it.name).putString("upd_notes", it.notes) } }.apply()
    }
}
