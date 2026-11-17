package com.urtv.player.core

/**
 * The beta build's «send a report» text. It is assembled ONLY from numbers and fixed words (the ratings, the failure types of [FailureReason], the
 * channel «beta»), never from anything the person typed or the provider sent, and [sanitize] is a last net with the same allow-list idea as UrTV's own
 * reports: unknown lines are dropped, and any word that looks like a link, an address or a credential is removed. No URL, playlist name, host, IP,
 * login or account data can get in. The Player itself sends nothing: the text is shown as a QR code for the phone.
 */
object BetaReport {
    data class Device(val version: String, val official: String, val androidRelease: String, val sdk: Int, val model: String, val ram: String)

    private val KNOWN_CRASHES = setOf("OutOfMemoryError", "NullPointerException", "IllegalStateException", "IllegalArgumentException", "IndexOutOfBoundsException", "ClassCastException", "ConcurrentModificationException", "NumberFormatException", "SecurityException", "UnsupportedOperationException", "StackOverflowError", "SQLiteException", "IOException", "RuntimeException")
    /** A device or version string only if it is already a plain token (letters, digits and ._- , spaces become _); anything else, a link or an address included, becomes «unknown» instead of being cleaned into something half-readable. */
    private fun tok(s: String, max: Int = 32): String { val c = s.trim().replace(' ', '_'); return if (c.length in 1..max && c.all { it.code < 128 && (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') }) c else "unknown" }
    private fun word(r: Assessment.Rating) = when (r) { Assessment.Rating.Good -> "good"; Assessment.Rating.Fair -> "fair"; Assessment.Rating.Poor -> "poor"; Assessment.Rating.NoData -> "nodata" }
    private fun n(v: Long) = if (v < 0) "n/a" else v.coerceAtMost(9_999_999).toString()

    fun build(s: Assessment.Snapshot, d: Device): String {
        val f = Assessment.rate(s)
        val fail = FailureReason.Reason.entries.filter { it != FailureReason.Reason.None }.joinToString(", ") { "${it.name.lowercase()} ${(s.failures[it.name] ?: 0).coerceIn(0, 99999)}" }
        val lines = listOf(
            "UrTV Player beta report, ${tok(d.version)}",
            "Channel: beta",
            "Versions: Player ${tok(d.version)} ${tok(d.official, 12)}, Android ${tok(d.androidRelease, 10)} (API ${d.sdk.coerceIn(0, 99)})",
            "Device: ${tok(d.model, 24)}, tv, ram ${tok(d.ram, 8)}",
            "Timing: cold start ${n(s.coldStartMs)} ms, tab open p90 ${n(Assessment.percentile(s.tabOpenMs, 90))} ms, playlist load ${n(s.playlistLoadMs)} ms (${s.playlistChannels.coerceIn(0, 9_999_999)} channels), epg load ${n(s.epgLoadMs)} ms",
            "Playback: plays ${s.plays.coerceIn(0, 9_999_999)}, zap first frame p50 ${n(Assessment.percentile(s.zapMs, 50))} ms p90 ${n(Assessment.percentile(s.zapMs, 90))} ms, played ${n(s.playSeconds)} s, buffering ${n(s.bufferingSeconds)} s in ${s.rebuffers.coerceIn(0, 9_999_999)} stalls, dropped frames ${n(s.dropped)}, decoder errors ${s.decoderErrors.coerceIn(0, 9_999_999)}",
            "Failures: $fail",
            "Memory: java heap peak ${s.heapPeakMb.coerceIn(0, 99_999)} MB of ${s.heapLimitMb.coerceIn(0, 99_999)} MB, pss peak ${s.pssPeakMb.coerceIn(0, 99_999)} MB",
            "Stability: crashes ${s.crashes.coerceIn(0, 99_999)}, anr ${s.anrs.coerceIn(0, 99_999)}, low memory kills ${s.lowMemoryKills.coerceIn(0, 99_999)}, last crash ${if (s.lastCrash in KNOWN_CRASHES) s.lastCrash else if (s.lastCrash.isEmpty()) "none" else "other"}",
            "Assessment: overall ${word(Assessment.overall(f))}, " + f.joinToString(", ") { "${it.area.name.lowercase()} ${word(it.rating)}" },
        )
        return sanitize(lines.joinToString("\n") + "\n")
    }

    private val ALLOWED = listOf("UrTV Player beta report, ", "Channel: beta", "Versions: ", "Device: ", "Timing: ", "Playback: ", "Failures: ", "Memory: ", "Stability: ", "Assessment: ")
    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    /** Only the known lines, each at most 500 characters, without any word that looks like a link, an address or a credential. */
    fun sanitize(text: String): String = text.lines().filter { l -> l.isNotBlank() && ALLOWED.any { l.startsWith(it) } }
        .joinToString("\n") { l -> l.take(500).split(' ').filterNot { w -> w.contains("://") || w.contains('@') || w.contains('?') || w.contains('=') || w.contains('\\') || (w.contains('/') && w != "n/a") || IPV4.containsMatchIn(w) || w.length > 60 }.joinToString(" ") } + "\n"
}
