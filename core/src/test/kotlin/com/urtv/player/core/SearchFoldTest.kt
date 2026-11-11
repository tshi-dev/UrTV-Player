package com.urtv.player.core

import kotlin.test.*

class SearchFoldTest {
    private fun m(name: String, q: String) = SearchFold.matches(name, q)

    @Test fun plainSearchIgnoresCaseDiacriticsAndArticles() {
        assertTrue(m("beIN SPORTS 1 HD", "sports 1")); assertTrue(m("قناة القرآن الكريم", "قران")); assertTrue(m("الجزيرة مباشر", "جزيره")); assertTrue(m("MBC 4", "mbc"))
        assertFalse(m("Cartoon Network", "quran")); assertTrue(m("anything", "  "))
    }

    @Test fun arabicKeyboardTypedForLatinKeysStillFindsTheChannel() {
        assertEquals("quran", SearchFold.layoutToLatin("ضعقشى"))                   // the case from the TV test
        assertTrue(m("Quran TV", "ضعقشى")); assertTrue(m("Saudi Quran Channel", "ضعقشى"))
        assertEquals("news", SearchFold.layoutToLatin("ىثصس")); assertTrue(m("Sky News Arabia", "ىثصس"))
        assertNull(SearchFold.layoutToLatin("quran"), "Latin input is left alone")
    }

    @Test fun arabicAndLatinNamesMatchThroughAliases() {
        assertTrue(m("Quran Radio", "قرآن")); assertTrue(m("قناة القرآن", "quran")); assertTrue(m("Saudi TV", "السعودية")); assertTrue(m("القناة السعودية", "saudi"))
        assertTrue(m("Al Jazeera English", "الجزيرة")); assertTrue(m("الجزيرة", "aljazeera")); assertTrue(m("Kids Zone", "أطفال")); assertTrue(m("قناة أطفال", "kids"))
        assertTrue(m("Documentary World", "وثائقي"))
    }

    @Test fun looseTransliterationFindsMisspelledLatin() {
        assertTrue(m("Quran Kareem", "qran")); assertTrue(m("قناة القران الكريم", "quran")); assertTrue(m("Saudi 1", "saudiya"))
        assertFalse(m("Movie Channel", "ab"), "too short for the loose rule")
        assertFalse(m("Fashion TV", "quran"))
    }

    @Test fun groupSplitAndArabicNames() {
        assertEquals(listOf("Documentary", "News"), GroupNames.split("Documentary;News")); assertEquals(listOf("News", "Sports"), GroupNames.split(" News | Sports ;; News"))
        assertEquals(emptyList(), GroupNames.split("")); assertEquals(listOf("أخبار"), GroupNames.split("أخبار"))
        assertEquals("أخبار", GroupNames.display("News", true)); assertEquals("وثائقي", GroupNames.display("documentary", true)); assertEquals("News", GroupNames.display("News", false))
        assertEquals("My Own Group", GroupNames.display("My Own Group", true))
        for (g in listOf("News", "Sports", "Kids", "Religious", "Movies", "Documentary", "Cooking", "General", "Music")) assertNotEquals(g, GroupNames.display(g, true), g)
    }
}

class FrameStatsTest {
    @Test fun jankShareAndPercentiles() {
        val smooth = List(100) { 16.6 }
        assertEquals(0.0, FrameStats.of(smooth).jankyPct)
        val mixed = List(80) { 16.6 } + List(20) { 48.0 }
        val r = FrameStats.of(mixed)
        assertEquals(20.0, r.jankyPct, 0.001); assertEquals(16.6, r.p50, 0.001); assertEquals(48.0, r.p90, 0.001); assertEquals(100, r.frames)
        assertEquals(0, FrameStats.of(emptyList<Double>()).frames)
        assertTrue(r.line.contains("janky 20.0 %"))
    }

    @Test fun fpsFromRenderedFrameCounts() {
        assertEquals(25f, FrameStats.fps(25, 1000), 0.01f); assertEquals(50f, FrameStats.fps(100, 2000), 0.01f)
        assertEquals(0f, FrameStats.fps(0, 1000)); assertEquals(0f, FrameStats.fps(10, 100), "too short a window to say")
    }

    @Test fun moreArabicGroupNames() {
        assertEquals("عائلية", GroupNames.display("Family", true)); assertEquals("Family", GroupNames.display("Family", false))
    }
}

class KeyModeTest {
    @Test fun latinModeTurnsArabicSlipsIntoLatinAsTyped() {
        assertEquals("quran", SearchFold.applyMode("ضعقشى", SearchFold.KeyMode.Latin))
        assertEquals("quran 1", SearchFold.applyMode("ضعقشى 1", SearchFold.KeyMode.Latin), "digits and spaces stay")
        assertEquals("bein", SearchFold.applyMode("bein", SearchFold.KeyMode.Latin), "already Latin: unchanged (idempotent while typing)")
        assertEquals("quran", SearchFold.applyMode(SearchFold.applyMode("ضعقشى", SearchFold.KeyMode.Latin), SearchFold.KeyMode.Latin))
    }
    @Test fun arabicModeTurnsLatinSlipsIntoArabic() {
        assertEquals("ضعقشى", SearchFold.applyMode("quran", SearchFold.KeyMode.Arabic)); assertEquals("ضعقشى 7", SearchFold.applyMode("Quran 7", SearchFold.KeyMode.Arabic))
        assertEquals("قناة", SearchFold.applyMode("قناة", SearchFold.KeyMode.Arabic), "already Arabic: unchanged")
    }
    @Test fun autoLeavesTextAlone() { assertEquals("ضعقشى quran", SearchFold.applyMode("ضعقشى quran", SearchFold.KeyMode.Auto)) }
}

class FrameRuleTest {
    @Test fun jankIsMissingTheDeadlineNotJustTakingLongerThanOneInterval() {
        // 100 frames of 34 ms that all met their (two-interval) deadline are not janky; 10 that missed it are
        val ok = List(90) { FrameStats.Frame(34.0, false) } + List(10) { FrameStats.Frame(60.0, true) }
        val r = FrameStats.of(ok)
        assertEquals(10.0, r.jankyPct, 0.001); assertEquals(34.0, r.p50, 0.001); assertEquals(100, r.frames)
        assertEquals(0, FrameStats.of(emptyList<FrameStats.Frame>()).frames)
    }
}
