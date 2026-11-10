package com.urtv.player

import com.urtv.player.core.M3uParser
import com.urtv.player.core.XmltvParser
import com.urtv.player.data.Browse
import com.urtv.player.data.EpgIndex
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Big-list budgets, measured on the JVM with generated data (no real playlist). The limits are deliberately loose (CI machines are
 * slower than a laptop and far faster than a low-end TV): they catch an accidental O(n^2), not a 20 % change. The numbers are
 * printed so docs/PERFORMANCE.md can quote them. What only a TV can show (frame time while scrolling, real memory pressure) is not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])   // Robolectric supplies the real Android XML pull parser the app uses
class ScaleTest {
    private fun used(): Long { repeat(3) { System.gc() }; return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } }
    private inline fun <T> timed(label: String, block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime(); val r = block(); val ms = (System.nanoTime() - t0) / 1_000_000
        println("SCALE $label: $ms ms"); return r to ms
    }

    private fun m3u(n: Int): String = buildString(n * 140) {
        append("#EXTM3U url-tvg=\"http://guide.example/epg.xml\"\n")
        for (i in 0 until n) append("#EXTINF:-1 tvg-id=\"ch$i.x\" tvg-name=\"Channel $i\" tvg-logo=\"http://logo.example/$i.png\" tvg-chno=\"${i + 1}\" group-title=\"Group ${i % 120}\",Channel $i ${if (i % 7 == 0) "HD" else ""}\nhttp://s.example:8080/live/u/p/$i.m3u8\n")
    }

    @Test fun twentyThousandChannelsParseGroupSearchAndNumber() {
        val text = m3u(20_000)
        val before = used()
        val (parsed, parseMs) = timed("parse 20k channels (${text.length / 1024} KB)") { M3uParser.parse(text) }
        val ch = parsed.channels
        assertEquals(20_000, ch.size)
        val heapMb = (used() - before) / (1024 * 1024)
        println("SCALE channels heap: $heapMb MB")
        assertTrue(parseMs < 4000, "parse $parseMs ms"); assertTrue(heapMb < 80, "heap $heapMb MB")

        val (groups, gMs) = timed("groups") { Browse.groups(ch) }
        assertEquals(120, groups.size); assertTrue(gMs < 300, "groups $gMs ms")
        val (hits, fMs) = timed("search 'channel 19'") { Browse.filter(ch, Browse.ALL, "channel 19") }
        assertTrue(hits.size > 1000); assertTrue(fMs < 300, "search $fMs ms")
        val (_, gf) = timed("filter one group") { Browse.filter(ch, "Group 7", "") }; assertTrue(gf < 300)
        // one screen of rows asks for each channel's number: must not scan the whole list per row
        val (_, nMs) = timed("40 visible row numbers (numberOf)") { for (i in 0 until 40) Browse.numberOf(ch, ch[19_900 + i].copy(number = null)) }
        assertTrue(nMs < 200, "numberOf $nMs ms")
    }

    @Test fun hundredThousandGuideRowsParseIndexAndLookUp() {
        val day = 24 * 3_600_000L; val t0 = (System.currentTimeMillis() / day) * day
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
        val xml = buildString(30_000_000) {
            append("<?xml version=\"1.0\"?><tv>")
            for (c in 0 until 2_000) for (k in 0 until 50) {
                val s = t0 + k * 3_600_000L; append("<programme start=\"").append(fmt.format(java.util.Date(s))).append("\" stop=\"").append(fmt.format(java.util.Date(s + 3_600_000L)))
                    .append("\" channel=\"ch$c.x\"><title lang=\"ar\">Programme $c-$k</title><desc>Description of programme $c-$k with some more words in it to be realistic.</desc></programme>")
            }
            append("</tv>")
        }
        val keep = (0 until 2_000).map { "ch$it.x" }.toSet()
        val before = used()
        val (list, pMs) = timed("parse 100k programmes (${xml.length / 1024 / 1024} MB)") { XmltvParser.parse(xml, keep) }
        assertEquals(100_000, list.size)
        val (idx, bMs) = timed("build index") { EpgIndex.build(list, t0 - 3 * 3_600_000L, t0 + 36 * 3_600_000L) }
        val heapMb = (used() - before) / (1024 * 1024)
        println("SCALE guide heap: $heapMb MB")
        val (_, lMs) = timed("nowNext for 2000 channels") { for (c in 0 until 2_000) idx.nowNext("ch$c.x", t0 + 5 * 3_600_000L + 1) }
        assertTrue(pMs < 15_000, "parse $pMs ms"); assertTrue(bMs < 1000, "index $bMs ms"); assertTrue(lMs < 200, "lookup $lMs ms"); assertTrue(heapMb < 160, "heap $heapMb MB")
        assertEquals("Programme 7-5", idx.nowNext("ch7.x", t0 + 5 * 3_600_000L + 1).first?.title)
    }
}
