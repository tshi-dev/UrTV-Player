package com.urtv.player.core

import com.urtv.player.core.MatchFinder.Request
import com.urtv.player.core.MatchFinder.Why
import kotlin.test.*

class MatchFinderTest {
    private val kick = 1_700_000_000_000L
    private fun ch(id: String, name: String, group: String = "", epg: String? = id) = Channel(id, name, "http://x/$id", group, epgId = epg)
    private fun prog(id: String, title: String, start: Long = kick, desc: String = "") = Programme(id, start, start + 7_200_000, title, desc)
    private val none: (String?) -> List<Programme> = { emptyList() }

    @Test fun normFoldsArabicAndStripsNoise() {
        assertEquals(MatchFinder.norm("ريـال مدريد"), MatchFinder.norm("ريال  مَدريد"))
        assertEquals("هلال", MatchFinder.norm("الهلال"))
        assertEquals("barcelona", MatchFinder.norm("FC Barcelona"))
        assertEquals(MatchFinder.norm("اتلتيكو"), MatchFinder.norm("أتلتيكو"))
    }

    @Test fun guideMatchEnglishTitle() {
        val a = ch("a", "Sport 1 HD"); val b = ch("b", "Sport 2 HD")
        val g = mapOf("a" to listOf(prog("a", "Real Madrid vs FC Barcelona - La Liga")), "b" to listOf(prog("b", "Cooking")))
        val r = MatchFinder.find(Request(homeEn = "Real Madrid", awayEn = "Barcelona", kickoffMs = kick), listOf(a, b)) { g[it].orEmpty() }
        assertEquals(listOf("a"), r.map { it.channel.id }); assertEquals(Why.Guide, r.single().why)
    }

    @Test fun guideMatchArabicTitleWithAliasesAndMixedRequest() {
        val a = ch("a", "قناة 1")
        val g = mapOf("a" to listOf(prog("a", "مباراة: برشلونة × ريال مدريد", desc = "الدوري الإسباني")))
        // request in English only, guide in Arabic: aliases bridge it
        val r = MatchFinder.find(Request(homeEn = "Barcelona", awayEn = "Real Madrid", kickoffMs = kick), listOf(a)) { g[it].orEmpty() }
        assertEquals(1, r.size)
    }

    @Test fun guideNeedsBothTeamsAndTimeWindow() {
        val a = ch("a", "A"); val b = ch("b", "B")
        val g = mapOf("a" to listOf(prog("a", "Real Madrid highlights")), "b" to listOf(prog("b", "Real Madrid v Barcelona", start = kick + 24 * 3_600_000L)))
        assertTrue(MatchFinder.find(Request(homeEn = "Real Madrid", awayEn = "Barcelona", kickoffMs = kick), listOf(a, b)) { g[it].orEmpty() }.isEmpty())
    }

    @Test fun namedChannelsMatchIgnoringQualityAndDigitsMatter() {
        val all = listOf(ch("1", "beIN SPORTS 1 HD"), ch("2", "beIN Sports 10"), ch("3", "bein sports 1 FHD"), ch("4", "News"))
        val r = MatchFinder.find(Request(homeEn = "x", awayEn = "y", channels = listOf("beIN Sports 1")), all, programmes = none)
        assertEquals(listOf("3", "1"), r.map { it.channel.id })      // FHD before HD, "10" and News excluded
        assertTrue(r.all { it.why == Why.Named })
    }

    @Test fun recentBreaksTies() {
        val all = listOf(ch("1", "Arena 1 HD"), ch("2", "Arena 1 HD (b)"))
        val r = MatchFinder.find(Request(channels = listOf("Arena 1")), all, recentRank = { if (it.id == "2") 0 else 9 }, programmes = none)
        assertEquals("2", r.first().channel.id)
    }

    @Test fun broadcasterFallbackOnlyWhenNothingElse() {
        val all = listOf(ch("1", "beIN Sports 5 HD", "Sport"), ch("2", "beIN Movies HD", "Movies"), ch("3", "Other Sports"))
        val r = MatchFinder.find(Request(homeEn = "a", awayEn = "b", channels = listOf("beIN Sports 1")), all, programmes = none)
        assertEquals(listOf("1"), r.map { it.channel.id }); assertEquals(Why.Group, r.single().why)
    }

    @Test fun competitionGroupFallback() {
        val all = listOf(ch("1", "Match 1", "Premier League"), ch("2", "Soap", "Drama"))
        val r = MatchFinder.find(Request(compEn = "Premier League"), all, programmes = none)
        assertEquals(listOf("1"), r.map { it.channel.id })
    }

    @Test fun noHit() {
        assertTrue(MatchFinder.find(Request(homeEn = "A", awayEn = "B", channels = listOf("Zzz 9")), listOf(ch("1", "News 24")), programmes = none).isEmpty())
        assertTrue(MatchFinder.find(Request(), emptyList(), programmes = none).isEmpty())
    }

    @Test fun sanitizedCapsEverything() {
        val long = "x".repeat(500)
        val r = Request(homeEn = long + "\u0000\n", channels = List(100) { "c$it" }, kickoffMs = 5L).sanitized(kick)
        assertEquals(MatchFinder.MAX_LEN, r.homeEn.length); assertEquals(MatchFinder.MAX_CHANNELS, r.channels.size); assertEquals(kick, r.kickoffMs)
        assertEquals(kick + 1000, Request(kickoffMs = kick + 1000).sanitized(kick).kickoffMs)
    }
}
