package com.urtv.player.data

import com.urtv.player.core.Programme

/** In-memory programme index, sorted by start time per channel id. Pure logic (unit-tested). */
class EpgIndex(private val byChannel: Map<String, List<Programme>>) {
    val isEmpty get() = byChannel.isEmpty()

    fun forChannel(epgId: String?): List<Programme> {
        if (epgId == null) return emptyList()
        return byChannel[epgId] ?: byChannel[epgId.lowercase()] ?: emptyList()
    }

    /** Programme running at [t] and the one after it. */
    fun nowNext(epgId: String?, t: Long): Pair<Programme?, Programme?> {
        val l = forChannel(epgId)
        if (l.isEmpty()) return null to null
        var lo = 0; var hi = l.size - 1; var idx = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (l[m].startMs <= t) { idx = m; lo = m + 1 } else hi = m - 1 }
        val now = l.getOrNull(idx)?.takeIf { it.stopMs > t }
        val next = if (now != null) l.getOrNull(idx + 1) else l.getOrNull(idx + 1)
        return now to next
    }

    companion object {
        val EMPTY = EpgIndex(emptyMap())

        /** Keeps programmes inside [fromMs, toMs], grouped and sorted. Channel ids match case-insensitively on lookup. */
        fun build(all: List<Programme>, fromMs: Long, toMs: Long): EpgIndex {
            val m = HashMap<String, MutableList<Programme>>()
            for (p in all) if (p.stopMs >= fromMs && p.startMs <= toMs) m.getOrPut(p.channelId) { ArrayList() }.add(p)
            for (v in m.values) v.sortBy { it.startMs }
            return EpgIndex(m)
        }
    }
}
