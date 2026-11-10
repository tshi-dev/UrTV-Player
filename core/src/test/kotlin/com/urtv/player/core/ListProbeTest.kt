package com.urtv.player.core

import com.urtv.player.core.ListProbe.Fail
import com.urtv.player.core.ListProbe.Result
import com.urtv.player.core.ListProbe.Verdict
import kotlin.test.*

class ListProbeTest {
    private fun ch(i: Int, url: String = "http://s.example/$i") = Channel("$i", "C$i", url)

    @Test fun sampleSpreadsOverTheWholeListAndSkipsNonWebAndDuplicates() {
        val all = (0 until 1000).map { ch(it) } + ch(2000, "rtsp://x/1") + ch(2001, "http://s.example/5")
        val s = ListProbe.sample(all, 30)
        assertEquals(30, s.size); assertEquals(30, s.map { it.url }.toSet().size)
        assertTrue(s.first().id == "0" && s.last().id.toInt() > 900, "covers the start and the end")
        assertTrue(s.none { it.url.startsWith("rtsp") })
        assertEquals(5, ListProbe.sample((0 until 5).map { ch(it) }, 30).size)
        assertTrue(ListProbe.sample(emptyList()).isEmpty())
    }

    @Test fun summaryNumbersAndVerdicts() {
        val good = List(30) { Result(true, 300L + it * 10) }
        val s = ListProbe.summarize(good)
        assertEquals(100, s.reachablePct); assertEquals(Verdict.Good, s.verdict); assertEquals(30, s.tested)
        assertEquals(450L, s.medianMs)
        val mixed = List(20) { Result(true, 800) } + List(6) { Result(false, 0, Fail.Timeout) } + List(4) { Result(false, 0, Fail.Http4xx) }
        val m = ListProbe.summarize(mixed)
        assertEquals(66, m.reachablePct); assertEquals(Verdict.Weak, m.verdict); assertEquals(6, m.fails[Fail.Timeout]); assertEquals(4, m.fails[Fail.Http4xx])
        assertEquals(Verdict.Fair, ListProbe.summarize(List(8) { Result(true, 2000) } + List(2) { Result(false, 0, Fail.Connection) }).verdict)
    }

    @Test fun skippedAreNotCountedAndEmptyIsNoData() {
        assertEquals(Verdict.NoData, ListProbe.summarize(emptyList()).verdict)
        assertEquals(Verdict.NoData, ListProbe.summarize(listOf(Result(false, 0, Fail.Skipped))).verdict)
        assertEquals(1, ListProbe.summarize(listOf(Result(true, 100), Result(false, 0, Fail.Skipped))).tested)
    }
}
