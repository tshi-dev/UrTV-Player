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
