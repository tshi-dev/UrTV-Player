package com.urtv.player.core

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.Reader

/** Streaming XMLTV parser (XmlPullParser: built into Android, so no extra APK weight). Malformed documents yield what was read so far. */
object XmltvParser {
    const val MAX_PROGRAMMES = 1_500_000
    /**
     * [fromMs]..[toMs] keep only programmes that overlap that window and [perChannelMax] caps how many each channel keeps: a provider guide of a
     * week for tens of thousands of channels is millions of entries, and holding them all is what ran small TVs out of memory while the person
     * only sat on the home screen. The defaults keep everything (small guides, tests).
     */
    fun parse(input: InputStream, keepChannels: Set<String>? = null, fromMs: Long = Long.MIN_VALUE, toMs: Long = Long.MAX_VALUE, perChannelMax: Int = Int.MAX_VALUE): List<Programme> =
        run({ it.setInput(input, null) }, fromMs, toMs, perChannelMax)(keepChannels)
    fun parse(reader: Reader, keepChannels: Set<String>? = null): List<Programme> = run({ it.setInput(reader) }, Long.MIN_VALUE, Long.MAX_VALUE, Int.MAX_VALUE)(keepChannels)
    fun parse(text: String, keepChannels: Set<String>? = null) = parse(java.io.StringReader(text.removePrefix("﻿").trimStart()), keepChannels)

    private fun run(init: (XmlPullParser) -> Unit, fromMs: Long, toMs: Long, perChannelMax: Int): (Set<String>?) -> List<Programme> = { keep ->
        val out = ArrayList<Programme>(); val perChannel = HashMap<String, Int>()
        try {
            val f = XmlPullParserFactory.newInstance()
            f.isNamespaceAware = false
            // No DTD / external entity processing: a hostile guide cannot read files or call out.
            runCatching { f.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            val p = f.newPullParser(); init(p)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "programme") readProgramme(p, keep)?.let { pr ->
                    if (pr.stopMs >= fromMs && pr.startMs <= toMs && out.size < MAX_PROGRAMMES) {
                        val n = perChannel[pr.channelId] ?: 0
                        if (n < perChannelMax) { perChannel[pr.channelId] = n + 1; out.add(pr) }
                    }
                }
                ev = p.next()
            }
        } catch (_: Exception) { /* keep partial result */ }
        out
    }

    private fun readProgramme(p: XmlPullParser, keep: Set<String>?): Programme? {
        val ch = p.getAttributeValue(null, "channel") ?: return null
        val start = parseTime(p.getAttributeValue(null, "start")); val stop = parseTime(p.getAttributeValue(null, "stop"))
        var title = ""; var desc = ""
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_TAG && p.name == "programme") break
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG) when (p.name) {
                "title" -> { val t = p.nextText(); if (title.isEmpty()) title = t.trim() }
                "desc" -> { val t = p.nextText(); if (desc.isEmpty()) desc = t.trim() }
            }
        }
        if (start == null || stop == null || stop <= start) return null
        if (keep != null && ch !in keep) return null
        return Programme(ch.take(200), start, stop, title.take(200), desc.take(400))
    }

    /** "20240131203000 +0300", "20240131203000", "202401312030 -0100" -> epoch millis (UTC). */
    private val TIME = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4})?$""")     // compiled once: it used to be rebuilt twice per programme

    fun parseTime(s: String?): Long? {
        if (s == null) return null
        val t = s.trim()
        val m = TIME.find(t) ?: return null
        val g = m.groupValues
        val days = daysFromCivil(g[1].toInt(), g[2].toInt(), g[3].toInt())
        var sec = days * 86400L + g[4].toInt() * 3600 + g[5].toInt() * 60 + (g[6].toIntOrNull() ?: 0)
        g[7].takeIf { it.isNotEmpty() }?.let {
            val sign = if (it[0] == '-') -1 else 1
            sec -= sign * (it.substring(1, 3).toInt() * 3600 + it.substring(3, 5).toInt() * 60)
        }
        return sec * 1000
    }

    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }
}
