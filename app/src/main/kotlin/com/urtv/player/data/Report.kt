package com.urtv.player.data

import java.util.Base64
import java.util.zip.Deflater

/**
 * «Send a report» / «Ask for a feature»: builds a short plain-text summary that the TV shows as a QR code. The QR opens UrTV's
 * public share page with the summary after the #; the phone shows it, the person adds their words and presses send there (the
 * page posts to the owner's form). The Player makes no network call. Every line is built from fixed vocabulary or from values
 * checked against a strict pattern, and [sanitize] is a last net: no playlist name, URL, host, IP, user name or token can get in.
 */
object Report {
    /** Same page and format as UrTV's own report: "z." + raw deflate (level 9) in base64url without padding. */
    const val PAGE = "https://tshi-dev.github.io/UrTV/share/"

    enum class Kind { Problem, Request }
    val TOPICS = listOf("playback", "lists", "guide", "home server", "interface", "other")

    data class Info(
        val version: String, val official: String,            // official | unofficial | debug
        val androidRelease: String, val sdk: Int, val brand: String, val ram: String,   // ram: low | normal
        val language: String, val theme: String, val accent: String,
        val playlists: List<PlaylistDef>, val homeServers: Int,
        val codec: String, val lastPlayerError: String, val recentErrors: List<String>, val videoBlock: String = "",
    )

    /** ASCII letters, digits and ._- only, capped: whatever else is in [s] is dropped. */
    private fun tok(s: String, max: Int = 32): String = s.filter { it.code < 128 && (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') }.take(max)

    private val IDENT = Regex("[A-Za-z][A-Za-z0-9_]{0,48}")
    private val CODE = Regex("[A-Z][A-Z0-9_]{0,60}")
    /** A class or error-code name, only if the whole string is one identifier; anything else is dropped, not cleaned. */
    private fun ident(s: String): String = s.substringAfterLast('.').takeIf { IDENT.matches(it) && !s.contains(' ') && !s.contains(':') } ?: ""

    fun build(kind: Kind, topic: String, i: Info): String {
        val t = if (topic in TOPICS) topic else "other"
        val m3u = i.playlists.count { !it.isXtream }; val xt = i.playlists.count { it.isXtream }   // counts only: names, links, hosts and logins never leave the Playlist objects
        val lines = buildList {
            add(if (kind == Kind.Problem) "UrTV Player problem report, ${tok(i.version)}, topic: $t" else "UrTV Player feature request, ${tok(i.version)}")
            add("Versions: Player ${tok(i.version)} ${tok(i.official, 12)}, Android ${tok(i.androidRelease, 10)} (API ${i.sdk.coerceIn(0, 99)})")
            add("Device: ${tok(i.brand, 20)}, tv, ram ${tok(i.ram, 8)}")
            add("Extras: language ${tok(i.language, 3)}, theme ${tok(i.theme)}, accent ${tok(i.accent)}")
            add("Extras: playlists ${i.playlists.size} (m3u $m3u, xtream $xt), home servers ${i.homeServers.coerceIn(0, 99)}")
            if (kind == Kind.Problem) {
                add("Extras: codec ${tok(i.codec, 24).ifEmpty { "none" }}, last player error ${i.lastPlayerError.takeIf { CODE.matches(it) } ?: "none"}, picture ${tok(i.videoBlock, 60).ifEmpty { "ok" }}")
                val errs = i.recentErrors.map { ident(it) }.filter { it.isNotEmpty() }.take(5)
                add("Extras: recent errors ${if (errs.isEmpty()) "none" else errs.joinToString(", ")}")
            }
        }
        return sanitize(lines.joinToString("\n") + "\n")
    }

    private val ALLOWED = Regex("UrTV Player (problem report|feature request), |Versions:|Device:|Extras:")
    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    /** The last net (the same allowlist idea as UrTV's own share): drops unknown lines, drops any word that looks like a link, address or credential. */
    fun sanitize(text: String): String = text.lines().filter { it.isNotBlank() && ALLOWED.containsMatchIn(it.take(40)) && ALLOWED.find(it)?.range?.first == 0 }
        .joinToString("\n") { l -> l.take(400).split(' ').filterNot { w -> w.contains("://") || w.contains('@') || w.contains('?') || w.contains('=') || w.contains('/') || IPV4.containsMatchIn(w) || w.length > 60 }.joinToString(" ") } + "\n"

    fun pack(text: String): String {
        val d = Deflater(9, true); val bytes = text.toByteArray(Charsets.UTF_8)
        d.setInput(bytes); d.finish()
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(512)
        while (!d.finished()) { val n = d.deflate(buf); if (n <= 0) break; out.write(buf, 0, n) }
        d.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun link(text: String) = "$PAGE#z.${pack(text)}"
}
