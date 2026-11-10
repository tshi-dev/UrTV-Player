package com.urtv.player.core

/** Backups for a dead channel: other entries of the user's own list with the same name (quality tags aside). Pure, tested. */
object Alternates {
    private val noise = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "1080p", "720p", "1080", "720", "backup", "raw", "multi", "low", "high")

    private fun key(name: String): List<String> = MatchFinder.norm(name).split(' ').filter { it.isNotEmpty() && it !in noise }

    /** Same-name channels other than [current] and [exclude] (urls), same quality tier first, then the rest; at most [max]. */
    fun find(current: Channel, all: List<Channel>, exclude: Set<String> = emptySet(), max: Int = 4): List<Channel> {
        val k = key(current.name); if (k.isEmpty()) return emptyList()
        val q = MatchFinder.quality(current.name)
        return all.asSequence().filter { it.url != current.url && it.url !in exclude && key(it.name) == k }
            .distinctBy { it.url }.sortedBy { if (MatchFinder.quality(it.name) == q) 0 else 1 }.take(max).toList()
    }
}
