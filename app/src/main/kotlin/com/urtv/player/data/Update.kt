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

    enum class Via { Urtv, Self }
    /** [code] is the announced versionCode (also checked against the downloaded APK). */
    data class Offer(val code: Int, val name: String, val notes: String, val via: Via = Via.Urtv, val apkUrl: String = "", val shaUrl: String = "")
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

    /**
     * UrTV is the authority when it is installed and answers. Only when it is NOT there does the Player look for its own update, and
     * only if this build may self-update (official, release-signed). [lookup] is never called otherwise. Pure, unit-tested.
     */
    fun decide(urtv: State, selfAllowed: Boolean, lookup: () -> com.urtv.player.core.SelfUpdate.Release?): State {
        if (urtv.kind != Kind.NoUrtv) return urtv
        if (!selfAllowed) return State(Kind.UpToDate)
        val rel = lookup() ?: return State(Kind.UpToDate)
        return State(Kind.Available, Offer(rel.code.toInt(), rel.version, "", Via.Self, rel.apkUrl, rel.shaUrl))
    }

    // ---- what was last learned, so Home and About can show it without asking again
    fun load(sp: SharedPreferences, currentCode: Int, currentName: String = ""): State = when (sp.getString("upd_kind", null)) {
        "self" -> {
            val name = sp.getString("upd_name", null).orEmpty(); val apk = sp.getString("upd_apk", null).orEmpty(); val sha = sp.getString("upd_sha", null).orEmpty()
            // re-validated on every load: only a plausible, still newer version with pinned-host links is ever offered
            if (name.isNotEmpty() && com.urtv.player.core.SelfUpdate.isNewer(name, currentName) && com.urtv.player.core.SelfUpdate.hostAllowed(apk) && com.urtv.player.core.SelfUpdate.hostAllowed(sha))
                State(Kind.Available, Offer(sp.getInt("upd_code", 0), name, "", Via.Self, apk, sha)) else State(Kind.UpToDate)
        }
        "available" -> offerFrom(sp.getInt("upd_code", 0), sp.getString("upd_name", null), sp.getString("upd_notes", null), currentCode)?.let { State(Kind.Available, it) } ?: State(Kind.UpToDate)   // installed since: no longer newer
        "nourtv" -> State(Kind.NoUrtv)
        "uptodate" -> State(Kind.UpToDate)
        else -> State(Kind.UpToDate)
    }

    fun save(sp: SharedPreferences, s: State, now: Long) {
        val self = s.kind == Kind.Available && s.offer?.via == Via.Self
        sp.edit().putLong("upd_last", now).putString("upd_kind", when { self -> "self"; s.kind == Kind.Available -> "available"; s.kind == Kind.NoUrtv -> "nourtv"; else -> "uptodate" })
            .apply { s.offer?.let { putInt("upd_code", it.code).putString("upd_name", it.name).putString("upd_notes", it.notes).putString("upd_apk", it.apkUrl).putString("upd_sha", it.shaUrl) } }.apply()
    }
}
