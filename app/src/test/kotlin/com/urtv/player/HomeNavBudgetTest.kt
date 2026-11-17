package com.urtv.player

import com.urtv.player.core.Channel
import com.urtv.player.data.Browse
import com.urtv.player.data.ChannelIndex
import com.urtv.player.ui.chKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The home screen's channel rows on a big list (55 000 channels, 30 group rows, 200 favourites): what the rows did on EVERY recomposition before
 * (a pass over all channels per row, hashing every channel for the favourites) against reading the index that is built once off the main thread.
 */
class HomeNavBudgetTest {
    private val n = 55_000
    private val list = (1..n).map { Channel("c$it", "قناة $it HD", "http://h.example:8000/live/u/p/$it.m3u8", "Group ${it % 60};Extra ${it % 7}", "http://l.example/$it.png", "id$it", it) }
    private fun ms(f: () -> Unit): Long { val t = System.nanoTime(); f(); return (System.nanoTime() - t) / 1_000_000 }

    @Test fun homeRowsReadTheIndexInsteadOfScanningTheList() {
        val idx = ChannelIndex(list, "p")
        val favs = list.filterIndexed { i, _ -> i % 275 == 0 }.map { chKey("p", it) }.toSet()
        val groups = idx.groups.take(30)
        // before: per recomposition
        lateinit var oldRows: List<List<Channel>>; lateinit var oldFavs: List<Channel>
        val before = ms { oldRows = groups.map { g -> list.filter { g in Browse.groupsOf(it) }.take(24) }; oldFavs = list.filter { chKey("p", it) in favs } }
        // after
        lateinit var newRows: List<List<Channel>>; lateinit var newFavs: List<Channel>
        val after = ms { newRows = groups.map { g -> idx.head(g, 24) }; newFavs = idx.favourites(favs) }
        println("BUDGET home rows over $n channels, ${groups.size} group rows + favourites, per recomposition: before $before ms, after $after ms")
        assertEquals(oldRows, newRows, "same rows as before"); assertEquals(oldFavs, newFavs, "same favourites as before")
        assertTrue(after < 50, "the rows took $after ms")
        assertTrue(after * 10 <= maxOf(before, 10), "at least ten times cheaper (before $before ms, after $after ms)")
    }
}
