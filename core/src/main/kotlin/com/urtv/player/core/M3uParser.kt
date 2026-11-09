package com.urtv.player.core

/** Tolerant M3U/M3U8 playlist parser. Never throws on malformed input; bad entries are counted in [ParsedPlaylist.skipped]. */
object M3uParser {
    private val ATTR = Regex("""([A-Za-z0-9_\-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,"']+))""")
    private val SCHEMES = listOf("http://", "https://", "rtmp://", "rtsp://", "udp://", "rtp://") // never file:// content:// intent://

    fun parse(text: String): ParsedPlaylist = parse(text.removePrefix("\uFEFF").lineSequence())

    /** Streaming variant: reads one line at a time, so very large playlists never sit in memory as one String. */
    const val MAX_CHANNELS = 200_000
    const val MAX_LINE = 8192

    fun parse(lines: Sequence<String>): ParsedPlaylist {
        val channels = ArrayList<Channel>()
        val epg = LinkedHashSet<String>()
        var skipped = 0
        var pending: Pending? = null
        var group = ""

        for (raw in lines) {
            if (raw.length > MAX_LINE) { skipped++; pending = null; continue } // hostile / garbage line
            if (channels.size >= MAX_CHANNELS) { skipped++; continue }
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", true) -> {
                    val a = attrs(line)
                    (a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"])?.split(',')
                        ?.map { it.trim() }?.filter { it.startsWith("http") }?.let { epg.addAll(it) }
                }
                line.startsWith("#EXTINF", true) -> {
                    if (pending != null) skipped++ // EXTINF with no URL
                    pending = parseInf(line)
                }
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit // other directives (#EXTVLCOPT, comments)
                else -> {
                    val p = pending
                    if (SCHEMES.none { line.startsWith(it, true) }) {
                        if (p != null) skipped++
                    } else {
                        val name = p?.name?.ifBlank { null } ?: line.substringAfterLast('/').substringBefore('?')
                        val g = p?.group?.ifBlank { null } ?: group
                        channels += Channel(
                            id = p?.epgId?.ifBlank { null } ?: line,
                            name = name.take(300), url = line, group = g.take(120),
                            logo = p?.logo?.ifBlank { null }, epgId = p?.epgId?.ifBlank { null },
                            number = p?.number,
                        )
                    }
                    pending = null; group = ""
                }
            }
        }
        if (pending != null) skipped++
        return ParsedPlaylist(channels, epg.toList(), skipped)
    }

    private class Pending(val name: String, val group: String, val logo: String?, val epgId: String?, val number: Int?)

    private fun attrs(s: String): Map<String, String> =
        ATTR.findAll(s).associate { it.groupValues[1].lowercase() to (it.groups[2]?.value ?: it.groups[3]?.value ?: it.groups[4]?.value ?: "") }

    private fun parseInf(line: String): Pending {
        // The title starts at the first comma outside quotes (attributes come before it; the title may itself contain commas).
        var inQ = false; var q = 0.toChar(); var cut = -1
        for (i in line.indices) {
            val c = line[i]
            if (inQ) { if (c == q) inQ = false } else if (c == '"' || c == '\'') { if (line.indexOf(c, i + 1) > 0) { inQ = true; q = c } } else if (c == ',') { cut = i; break }
        }
        val title = if (cut >= 0) line.substring(cut + 1).trim() else ""
        val head = if (cut >= 0) line.substring(0, cut) else line
        val a = attrs(head)
        return Pending(
            name = title.ifBlank { a["tvg-name"] ?: "" },
            group = a["group-title"] ?: "",
            logo = a["tvg-logo"],
            epgId = a["tvg-id"],
            number = (a["tvg-chno"] ?: a["channel-number"])?.toIntOrNull(),
        )
    }
}
