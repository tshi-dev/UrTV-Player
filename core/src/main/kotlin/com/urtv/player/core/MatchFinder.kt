package com.urtv.player.core

/**
 * Finds, among the user's OWN channels, the ones showing a given match. Pure logic (unit-tested). Nothing here knows any
 * channel or stream: it only compares text from the request with names and programme titles already in the user's playlists.
 * Every request field is untrusted: [Request.sanitized] caps lengths and counts and strips control characters.
 */
object MatchFinder {
    data class Request(
        val homeAr: String = "", val homeEn: String = "", val awayAr: String = "", val awayEn: String = "",
        val compAr: String = "", val compEn: String = "", val kickoffMs: Long = 0, val channels: List<String> = emptyList(),
    ) {
        /** Same request with every string cleaned and capped, and the kickoff within a week of [nowMs] (else [nowMs]). */
        fun sanitized(nowMs: Long): Request {
            fun c(s: String) = s.filter { !it.isISOControl() }.trim().take(MAX_LEN)
            val week = 7L * 24 * 3_600_000
            return Request(c(homeAr), c(homeEn), c(awayAr), c(awayEn), c(compAr), c(compEn),
                if (kickoffMs in (nowMs - week)..(nowMs + week)) kickoffMs else nowMs,
                channels.take(MAX_CHANNELS).map { c(it) }.filter { it.isNotEmpty() })
        }
        val hasTeams get() = (homeAr.isNotBlank() || homeEn.isNotBlank()) && (awayAr.isNotBlank() || awayEn.isNotBlank())
        /** Key under which the user's pick is remembered. */
        val competitionKey get() = norm(compEn.ifBlank { compAr })
    }

    enum class Why { Guide, Named, Group }
    data class Hit(val channel: Channel, val why: Why, val score: Int, val quality: Int, val programme: Programme? = null)

    const val MAX_LEN = 80
    const val MAX_CHANNELS = 12

    private val noise = setOf("fc", "sc", "cf", "afc", "fk", "club", "al", "the", "de", "nadi", "نادي", "نادى")
    private val qualityWords = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "1080p", "720p", "1080", "720", "backup", "raw", "multi")
    private val sportsWords = setOf("sport", "sports", "sporty", "football", "soccer", "match", "ssc", "سبورت", "سبورتس", "رياضه", "رياضيه", "رياضي", "كوره", "ماتش")

    /** Groups of spellings that mean the same club (already normalised form is applied to them at load). */
    private val aliasGroups: List<Set<String>> = listOf(
        listOf("manchester united", "man united", "man utd", "manchester utd", "مانشستر يونايتد", "مان يونايتد"),
        listOf("manchester city", "man city", "مانشستر سيتي", "مان سيتي"),
        listOf("barcelona", "barca", "fc barcelona", "برشلونه"),
        listOf("real madrid", "ريال مدريد", "ريال"),
        listOf("atletico madrid", "atletico", "اتلتيكو مدريد", "اتلتيكو"),
        listOf("bayern munich", "bayern", "بايرن ميونخ", "بايرن"),
        listOf("paris saint germain", "paris sg", "psg", "باريس سان جيرمان", "باريس"),
        listOf("inter milan", "internazionale", "inter", "انتر ميلان", "انتر"),
        listOf("ac milan", "milan", "ميلان"),
        listOf("juventus", "juve", "يوفنتوس"),
        listOf("tottenham hotspur", "tottenham", "spurs", "توتنهام"),
        listOf("liverpool", "ليفربول"),
        listOf("chelsea", "تشيلسي"),
        listOf("arsenal", "ارسنال", "ارسنل"),
        listOf("borussia dortmund", "dortmund", "دورتموند", "بروسيا دورتموند"),
        listOf("saudi arabia", "saudi", "السعوديه", "المنتخب السعودي"),
    ).map { g -> g.map { norm(it) }.toSet() }

    /** Lower-case, no diacritics/tatweel, folded Arabic letter variants, no punctuation, no leading "ال", no club noise words. */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            val c = when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ة' -> 'ه'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                else -> ch
            }
            when {
                c == 'ـ' || c in 'ً'..'ٟ' || c == 'ٰ' -> {}
                c.isLetterOrDigit() -> sb.append(c)
                else -> sb.append(' ')
            }
        }
        return sb.split(' ').filter { it.isNotEmpty() }
            .map { w -> if (w.length > 3 && w.startsWith("ال")) w.substring(2) else w }
            .filter { it !in noise }.joinToString(" ")
    }

    private fun tokens(s: String): List<String> = norm(s).split(' ').filter { it.isNotEmpty() }

    private fun names(ar: String, en: String): Set<String> {
        val base = listOf(ar, en).map { norm(it) }.filter { it.isNotEmpty() }.toMutableSet()
        for (g in aliasGroups) if (base.any { it in g }) base += g
        return base
    }

    /** [text] (already normalised) contains one of [names] as whole words. */
    private fun mentions(text: String, names: Set<String>): Boolean {
        val padded = " $text "
        return names.any { it.isNotEmpty() && padded.contains(" $it ") }
    }

    /** 4 = 4K/UHD, 3 = FHD, 2 = HD, 1 = unmarked, 0 = SD. */
    fun quality(name: String): Int {
        val t = name.lowercase().split(Regex("[^a-z0-9]+")).toSet()
        return when { "4k" in t || "uhd" in t || "8k" in t -> 4; "fhd" in t || "1080p" in t || "1080" in t -> 3; "hd" in t || "720p" in t || "720" in t -> 2; "sd" in t -> 0; else -> 1 }
    }

    private fun channelTokens(name: String) = tokens(name).filter { it !in qualityWords }

    private fun isSports(c: Channel): Boolean = (channelTokens(c.name) + tokens(c.group)).any { it in sportsWords }

    /**
     * Best channels first. [programmes] gives the guide for a channel's epg id; [recentRank] is the position of a channel in
     * the user's recently played list (0 = latest, large = never), used only to break ties.
     */
    fun find(
        req: Request, channels: List<Channel>, recentRank: (Channel) -> Int = { Int.MAX_VALUE },
        programmes: (String?) -> List<Programme>,
    ): List<Hit> {
        if (channels.isEmpty()) return emptyList()
        val hits = LinkedHashMap<String, Hit>()
        fun add(h: Hit) { val old = hits[h.channel.url]; if (old == null || h.score > old.score) hits[h.channel.url] = h }

        // 1) guide: a programme around kickoff that names both teams
        if (req.hasTeams) {
            val home = names(req.homeAr, req.homeEn); val away = names(req.awayAr, req.awayEn)
            val from = req.kickoffMs - 45 * 60_000L; val to = req.kickoffMs + 150 * 60_000L
            for (c in channels) {
                val p = programmes(c.epgId).firstOrNull { p ->
                    p.stopMs > from && p.startMs < to && norm(p.title + " " + p.description).let { t -> mentions(t, home) && mentions(t, away) }
                } ?: continue
                add(Hit(c, Why.Guide, 100, quality(c.name), p))
            }
        }
        // 2) channel names the caller listed
        val wanted = req.channels.map { channelTokens(it) }.filter { it.isNotEmpty() }
        for (c in channels) {
            val t = channelTokens(c.name).toSet()
            if (wanted.any { w -> t.containsAll(w) }) add(Hit(c, Why.Named, 80, quality(c.name)))
        }
        // 3) fallback: sports channels of the same broadcaster (first word of a listed channel) or the competition's own group
        if (hits.isEmpty()) {
            val brands = wanted.mapNotNull { w -> w.firstOrNull { it.length >= 2 && it.any { ch -> !ch.isDigit() } && it !in sportsWords } }.toSet()
            val comp = tokens(req.compEn.ifBlank { req.compAr })
            for (c in channels) {
                val t = channelTokens(c.name)
                val byBrand = brands.isNotEmpty() && t.any { it in brands } && isSports(c)
                val byComp = comp.isNotEmpty() && (norm(c.group) + " " + t.joinToString(" ")).let { s -> comp.all { it in s.split(' ') } }
                if (byBrand || byComp) add(Hit(c, Why.Group, if (byBrand) 40 else 30, quality(c.name)))
            }
        }
        return hits.values.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.quality }.thenBy { recentRank(it.channel) }.thenBy { it.channel.name })
    }
}
