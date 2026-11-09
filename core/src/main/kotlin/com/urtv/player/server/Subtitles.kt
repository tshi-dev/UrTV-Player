package com.urtv.player.server

/** Languages are compared as 2-letter codes; servers send 2-letter, ISO 639-2/T or /B codes. */
object Lang {
    private val map = mapOf(
        "ara" to "ar", "arb" to "ar", "eng" to "en", "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de", "spa" to "es",
        "ita" to "it", "tur" to "tr", "rus" to "ru", "por" to "pt", "zho" to "zh", "chi" to "zh", "jpn" to "ja", "kor" to "ko",
        "hin" to "hi", "fas" to "fa", "per" to "fa", "heb" to "he", "urd" to "ur", "nld" to "nl", "dut" to "nl", "pol" to "pl",
        "swe" to "sv", "ell" to "el", "gre" to "el",
    )
    private val three = map.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.first() }
    fun norm(s: String?): String? {
        val t = s?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
        if (t.isEmpty() || t == "und" || t == "zxx" || t == "mis" || t == "-1") return null
        return when (t.length) { 2 -> t; 3 -> map[t] ?: t; else -> if (t.startsWith("arab")) "ar" else if (t.startsWith("engl")) "en" else null }
    }
    /** The 3-letter code servers expect for subtitle search. */
    fun toIso3(s: String): String = three[norm(s) ?: s] ?: s
    fun isRtl(s: String?) = norm(s) in setOf("ar", "fa", "he", "ur")
}

data class LangPrefs(val audio: List<String> = listOf("ar", "en"), val subtitle: List<String> = listOf("ar", "en"))
enum class SubtitleMode { Auto, Always, Off }

object SubtitleChoice {
    private fun isCommentary(s: StreamInfo) = s.title?.contains("commentary", true) == true
    private val imageCodecs = setOf("pgs", "pgssub", "hdmv_pgs_subtitle", "dvdsub", "dvd_subtitle", "vobsub", "dvbsub", "dvb_subtitle", "xsub")
    fun isImage(s: StreamInfo) = s.codec?.lowercase() in imageCodecs
    /**
     * Picture subtitles (PGS, VobSub, DVB) are burned in by the server's transcoder: that works on every TV and keeps the
     * bitmap in sync. Text subtitles (SRT/ASS/SSA/VTT) are fetched as text and drawn by UrTV itself.
     */
    fun needsBurnIn(s: StreamInfo) = isImage(s)

    fun pickAudio(audio: List<StreamInfo>, prefs: LangPrefs): StreamInfo? {
        if (audio.isEmpty()) return null
        val usable = audio.filter { !isCommentary(it) }.ifEmpty { audio }
        for (l in prefs.audio) usable.filter { Lang.norm(it.language) == Lang.norm(l) }
            .maxWithOrNull(compareBy<StreamInfo>({ it.isDefault }, { it.channels ?: 0 }))?.let { return it }
        return usable.firstOrNull { it.isDefault } ?: usable.first()
    }

    /**
     * Infuse-style: subtitles follow the language of the sound.
     * - Audio already in the user's primary language: only a forced track (signs, foreign dialogue), else none.
     * - Foreign audio: a full track in the preferred order; a forced track only as a last resort.
     * - [SubtitleMode.Always] always wants a full track; [SubtitleMode.Off] none.
     */
    fun pickSubtitle(subs: List<StreamInfo>, audio: StreamInfo?, prefs: LangPrefs, mode: SubtitleMode = SubtitleMode.Auto): StreamInfo? {
        if (mode == SubtitleMode.Off || subs.isEmpty()) return null
        val primary = Lang.norm(prefs.subtitle.firstOrNull())
        val audioLang = Lang.norm(audio?.language)
        val nativeAudio = mode == SubtitleMode.Auto && audioLang != null && audioLang == primary
        fun forAll(l: String, forced: Boolean) = subs.filter { Lang.norm(it.language) == Lang.norm(l) && it.isForced == forced }
            .sortedWith(compareBy<StreamInfo>({ it.isHearingImpaired }, { isImage(it) }, { !it.isDefault }, { !it.isExternal }))
        val forcedPick = prefs.subtitle.asSequence().mapNotNull { forAll(it, true).firstOrNull() }.firstOrNull()
        if (nativeAudio) return forcedPick
        for (l in prefs.subtitle) forAll(l, false).firstOrNull()?.let { return it }
        return forcedPick
    }
}

enum class Edge { None, Outline, Shadow, Raised, Depressed }

/** User-controlled look of subtitles; rendered by UrTV itself so it never depends on the TV's accessibility menu. */
data class SubtitleStyle(
    val size: Float = 0.058f,            // fraction of the video height
    val color: Long = 0xFFFFFFFF,
    val background: Long = 0x00000000,
    val edge: Edge = Edge.Outline,
    val edgeColor: Long = 0xFF000000,
    val font: String = "sans",           // sans | serif | mono
    val bold: Boolean = false,
    val bottom: Float = 0.07f,           // distance from the bottom edge, fraction of the height
) {
    fun coerced() = copy(size = size.coerceIn(0.03f, 0.12f), bottom = bottom.coerceIn(0.02f, 0.30f), font = if (font in setOf("sans", "serif", "mono")) font else "sans")
    companion object { val Presets = listOf(
        "white" to 0xFFFFFFFFL, "yellow" to 0xFFFFE066L, "cyan" to 0xFF7FE9FFL, "green" to 0xFF9CFF9CL)
    }
}

data class Cue(val startMs: Long, val endMs: Long, val text: String, val top: Boolean = false)

/** Parses SRT, WebVTT and (as a fallback when a server hands over raw ASS/SSA) Dialogue lines. Never throws. */
object SubtitleParser {
    private const val MAX_CUES = 20_000
    private const val MAX_CHARS = 8 * 1024 * 1024
    private val time = Regex("""(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
    private val tags = Regex("""<[^>]{0,60}>|\{\\[^}]{0,120}}""")

    fun parse(raw: String): List<Cue> {
        val text = raw.take(MAX_CHARS).removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        return runCatching {
            when {
                text.contains("[Events]", true) && text.contains("Dialogue:", true) -> ass(text)
                else -> cues(text)
            }
        }.getOrDefault(emptyList()).sortedBy { it.startMs }
    }

    private fun ms(m: MatchResult): Long {
        val h = m.groupValues[1].ifEmpty { "0" }.toLong(); val mi = m.groupValues[2].toLong(); val s = m.groupValues[3].toLong()
        val frac = m.groupValues[4].padEnd(3, '0').take(3).toLong()
        return ((h * 60 + mi) * 60 + s) * 1000 + frac
    }

    private fun cues(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        for (block in text.split(Regex("\n{2,}"))) {
            val lines = block.split('\n')
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val parts = lines[ti].split("-->")
            if (parts.size < 2) continue
            val a = time.find(parts[0]) ?: continue
            val b = time.find(parts[1]) ?: continue
            val settings = parts[1].substringAfter(time.find(parts[1])!!.value, "")
            val body = lines.drop(ti + 1).joinToString("\n")
            val top = Regex("""\{\\an[789]}""").containsMatchIn(body) || Regex("""line:\s*(\d{1,2})(?!%)\b""").find(settings)?.groupValues?.get(1)?.toIntOrNull()?.let { it <= 2 } == true
            val clean = clean(body)
            if (clean.isNotEmpty() && ms(b) >= ms(a)) out += Cue(ms(a), ms(b), clean, top)
            if (out.size >= MAX_CUES) break
        }
        return out
    }

    private fun ass(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        var textIdx = 9; var startIdx = 1; var endIdx = 2; var n = 10
        for (line in text.lineSequence()) {
            if (line.startsWith("Format:", true) && line.contains("Start", true) && line.contains("Text", true)) {
                val f = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                n = f.size; startIdx = f.indexOf("start"); endIdx = f.indexOf("end"); textIdx = f.indexOf("text")
            } else if (line.startsWith("Dialogue:", true) && startIdx >= 0 && endIdx >= 0 && textIdx >= 0) {
                val f = line.substringAfter(':').split(',', limit = n)
                if (f.size <= textIdx) continue
                val a = time.find(f[startIdx].trim()) ?: continue; val b = time.find(f[endIdx].trim()) ?: continue
                val body = f[textIdx].replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                val clean = clean(body)
                if (clean.isNotEmpty()) out += Cue(ms(a), ms(b), clean, Regex("""\{\\an[789]}""").containsMatchIn(f[textIdx]))
                if (out.size >= MAX_CUES) break
            }
        }
        return out
    }

    private fun clean(s: String) = tags.replace(s, "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&lrm;", "‎").replace("&rlm;", "‏")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(600)
}

/** Cue lookup with a user sync offset (positive = subtitles appear later). */
class CueTrack(private val cues: List<Cue>) {
    val size get() = cues.size
    fun at(positionMs: Long, offsetMs: Long = 0): List<Cue> {
        val t = positionMs - offsetMs
        // Cues are sorted by start; scan a small window backwards from the first start > t.
        var lo = 0; var hi = cues.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (cues[mid].startMs <= t) lo = mid + 1 else hi = mid }
        val out = ArrayList<Cue>(2)
        var i = lo - 1
        while (i >= 0 && lo - i <= 8) { val c = cues[i]; if (t < c.endMs) out += c; i-- }
        return out.reversed()
    }
}

/** Arabic/Hebrew shaping is done by the Android text engine; we only fix the paragraph direction. */
object Bidi {
    private const val RLM = '‏'
    private const val LRM = '‎'
    private fun strong(c: Char): Int = when (Character.getDirectionality(c)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> 1
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> -1
        else -> 0
    }
    /** Forces each line's base direction from its first strong letter, so "!مرحبا" and "... كيف حالك؟" keep punctuation on the correct side. */
    fun fixLine(line: String): String {
        val first = line.firstOrNull { strong(it) != 0 } ?: return line
        val cleaned = line.trimStart(RLM, LRM)
        return (if (strong(first) > 0) RLM else LRM).toString() + cleaned
    }
    fun fix(text: String) = text.lines().joinToString("\n") { fixLine(it) }
}
