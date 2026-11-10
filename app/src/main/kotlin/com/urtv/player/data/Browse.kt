package com.urtv.player.data

import com.urtv.player.core.Channel

/** Pure helpers for grouping, searching and number zapping (unit-tested). */
object Browse {
    const val ALL = "\u0000all"
    const val OTHER = "\u0000other"
    const val FAV = "\u0000fav"
    const val RECENT = "\u0000recent"
    const val LIST = "\u0000list:"

    fun groupOf(c: Channel) = groupsOf(c).first()

    /** «Documentary;News» is two groups; a channel without any group is in OTHER. */
    fun groupsOf(c: Channel): List<String> = com.urtv.player.core.GroupNames.split(c.group).ifEmpty { listOf(OTHER) }

    /** Distinct groups in first-seen order. */
    fun groups(channels: List<Channel>): List<String> {
        val s = LinkedHashSet<String>()
        for (c in channels) s += groupsOf(c)
        return s.toList()
    }

    /** Groups in the user's saved order (unknown ones keep first-seen order at the end), without hidden ones. */
    fun arrange(groups: List<String>, order: List<String>, hidden: Set<String>, includeHidden: Boolean = false): List<String> {
        val rank = order.withIndex().associate { it.value to it.index }
        val sorted = groups.withIndex().sortedWith(compareBy({ rank[it.value] ?: (order.size + it.index) }, { it.index })).map { it.value }
        return if (includeHidden) sorted else sorted.filter { it !in hidden }
    }

    fun filter(channels: List<Channel>, group: String, query: String): List<Channel> {
        val q = query.trim()
        var l = if (group == ALL) channels else channels.filter { group in groupsOf(it) }
        if (q.isNotEmpty()) { val m = com.urtv.player.core.SearchFold.Query(q); l = l.filter { m.matches(it.name) } }     // Arabic/Latin names and keyboard-layout slips
        return l
    }

    /** Channel for a typed number: matches an explicit channel number first, else the 1-based position in [all]. */
    fun byNumber(all: List<Channel>, n: Int): Channel? =
        all.firstOrNull { it.number == n } ?: all.getOrNull(n - 1)

    fun numberOf(all: List<Channel>, c: Channel): Int = c.number ?: (all.indexOf(c) + 1)
}
