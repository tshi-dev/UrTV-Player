package com.urtv.player.data

import com.urtv.player.core.Channel
import com.urtv.player.core.SearchFold

/**
 * Everything the Channels tab needs to answer in less than a frame, built once when a list is loaded (on a background thread):
 * the distinct groups, the channels of each group, the channel numbers and the prepared names for search. The screens only read it.
 */
class ChannelIndex(val channels: List<Channel>, pid: String = "") {
    /** The favourites key of every channel (playlist id + hash of the URL), computed once here instead of on the main thread. */
    val keys: Array<String> = Array(channels.size) { com.urtv.player.ui.chKey(pid, channels[it]) }
    val groups: List<String>
    val byGroup: Map<String, List<Channel>>
    val numbers: HashMap<String, Int>
    val names = SearchFold.Index(channels.map { it.name })

    init {
        val g = LinkedHashMap<String, ArrayList<Channel>>()
        val nums = HashMap<String, Int>(channels.size * 2)
        channels.forEachIndexed { i, c ->
            for (name in Browse.groupsOf(c)) g.getOrPut(name) { ArrayList() } += c
            nums[c.url] = c.number ?: (i + 1)
        }
        groups = g.keys.toList(); byGroup = g; numbers = nums
    }

    /** The first [n] channels of a group: a lookup, not a pass over the whole list (the home rows asked this for every group on every recomposition). */
    fun head(group: String, n: Int): List<Channel> = byGroup[group]?.let { if (it.size <= n) it else it.subList(0, n) } ?: emptyList()

    /** The favourites among the channels, from the keys computed once (no per-channel hashing on the main thread). */
    fun favourites(favs: Set<String>): List<Channel> = if (favs.isEmpty()) emptyList() else channels.filterIndexed { i, _ -> keys[i] in favs }

    /** Channels whose name matches [query] (Arabic/Latin, keyboard-layout slips), in list order. */
    fun search(query: String): List<Channel> = if (query.isBlank()) channels else SearchFold.search(names, query).map { channels[it] }

    companion object { val EMPTY = ChannelIndex(emptyList()) }
}
