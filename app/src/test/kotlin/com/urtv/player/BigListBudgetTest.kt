package com.urtv.player

import com.urtv.player.core.Channel
import com.urtv.player.data.ChannelIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Timing budgets on a 100 000-entry list. The budgets are about 10x what a development machine needs, so they only fail when someone
 * puts per-channel work (hashing, splitting, normalising names) back on a path the screens run on every open or keystroke.
 */
class BigListBudgetTest {
    private val n = 100_000
    private val list = (1..n).map { Channel("c$it", "قناة $it HD / Channel $it", "http://host.example:8000/live/user/pass/$it.m3u8", "Group ${it % 400};Sport", "http://logos.example/$it.png", "id$it", it) }

    private fun ms(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    @Test fun theIndexIsBuiltOnceOffTheMainThreadAndEverythingElseReadsIt() {
        var idx: ChannelIndex? = null
        val build = ms { idx = ChannelIndex(list, "p") }
        println("BUDGET index build for $n channels: $build ms")
        assertTrue(build < 8_000, "index build $build ms")
        val i = idx!!
        assertEquals(n, i.keys.size); assertEquals(401, i.groups.size, "400 groups + Sport")
        assertEquals(250, i.byGroup["Group 7"]!!.size)
        // a group switch, the numbers and the key of a channel are plain lookups
        val reads = ms { repeat(1000) { i.byGroup["Group ${it % 400}"]; i.numbers["http://host.example:8000/live/user/pass/${it + 1}.m3u8"]; i.keys[it] } }
        assertTrue(reads < 200, "1000 lookups took $reads ms")
    }

    @Test fun aKeystrokeOverTheWholeListStaysUnderTheBudgetOnceWarm() {
        val i = ChannelIndex(list, "p")
        i.search("channel 49")                                   // first call fills the lazily computed parts
        val t = ms { repeat(5) { i.search("channel 4999") } } / 5
        println("BUDGET search over $n names: $t ms")
        assertTrue(t < 600, "a search took $t ms")
        assertTrue(i.search("channel 99999").any { it.name.contains("Channel 99999") })
    }

    @Test fun nothingHiddenMeansNoPerChannelWorkForTheVisibleList() {
        // the usual case (no hidden group, no hidden channel): the list shown is the loaded list itself
        val i = ChannelIndex(list, "p")
        val groupsHidden = 0; val hiddenCh = emptySet<String>()
        val nothingHidden = groupsHidden == 0 && hiddenCh.isEmpty()
        val t = ms { val visible = if (nothingHidden) list else list.filterIndexed { k, _ -> i.keys[k] !in hiddenCh }; assertEquals(n, visible.size) }
        assertTrue(t < 50, "took $t ms")
    }
}
