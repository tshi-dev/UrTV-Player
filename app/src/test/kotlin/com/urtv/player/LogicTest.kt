package com.urtv.player

import com.urtv.player.core.Channel
import com.urtv.player.core.Programme
import com.urtv.player.data.Browse
import com.urtv.player.data.EpgIndex
import com.urtv.player.phone.Page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogicTest {
    private fun ch(n: String, g: String = "", num: Int? = null) = Channel(n, n, "http://example.com/$n", g, number = num)

    @Test fun groupsKeepFirstSeenOrderAndBlankBecomesOther() {
        val l = listOf(ch("a", "News"), ch("b"), ch("c", "News"), ch("d", "Kids"))
        assertEquals(listOf("News", Browse.OTHER, "Kids"), Browse.groups(l))
    }

    @Test fun filterByGroupAndQuery() {
        val l = listOf(ch("Alpha One", "News"), ch("Beta", "News"), ch("Alpha Two", "Kids"))
        assertEquals(2, Browse.filter(l, "News", "").size)
        assertEquals(listOf("Alpha One"), Browse.filter(l, "News", "alpha").map { it.name })
        assertEquals(3, Browse.filter(l, Browse.ALL, "").size)
    }

    @Test fun numberEntryPrefersExplicitNumberThenPosition() {
        val l = listOf(ch("a"), ch("b", num = 7), ch("c"))
        assertEquals("b", Browse.byNumber(l, 7)?.name)
        assertEquals("c", Browse.byNumber(l, 3)?.name)
        assertNull(Browse.byNumber(l, 99))
    }

    @Test fun epgNowNextAndCaseInsensitiveLookup() {
        val p = { s: Long, e: Long, t: String -> Programme("ch.one", s, e, t) }
        val idx = EpgIndex.build(listOf(p(300, 400, "C"), p(0, 100, "A"), p(100, 300, "B")), 0, 1000)
        assertEquals("B" to "C", idx.nowNext("ch.one", 150).let { it.first?.title to it.second?.title })
        assertEquals("A", idx.nowNext("CH.ONE".lowercase(), 50).first?.title)
        assertNull(idx.nowNext("ch.one", 500).first)       // after the last programme
        assertNull(idx.nowNext("missing", 50).first)
    }

    @Test fun epgDropsProgrammesOutsideWindow() {
        val idx = EpgIndex.build(listOf(Programme("x", 0, 10, "old"), Programme("x", 500, 600, "ok")), 100, 1000)
        assertEquals(1, idx.forChannel("x").size)
    }

    @Test fun phonePageEscapesCodeAndShipsNoScript() {
        val html = Page.form("\"><script>alert(1)</script>")
        assertFalse(html.contains("<script"))
        assertTrue(html.contains("&quot;&gt;&lt;script&gt;"))
    }
}

class ArrangeTest {
    @Test fun savedOrderThenFirstSeenAndHiddenRemoved() {
        val g = listOf("A", "B", "C", "D")
        assertEquals(listOf("C", "A", "B", "D"), Browse.arrange(g, listOf("C", "A"), emptySet()))
        assertEquals(listOf("C", "B", "D"), Browse.arrange(g, listOf("C", "A"), setOf("A")))
        assertEquals(listOf("C", "A", "B", "D"), Browse.arrange(g, listOf("C", "A"), setOf("A"), includeHidden = true))
        assertEquals(g, Browse.arrange(g, listOf("gone"), emptySet()))
    }
}

class WatchItemTest {
    @Test fun fractionAndTimeLeft() {
        val w = com.urtv.player.data.WatchItem("k", "p", "movie", "t", url = "http://example.com/a", posMs = 30 * 60_000L, durMs = 120 * 60_000L)
        assertEquals(0.25f, w.fraction); assertEquals(90, w.leftMin)
        assertEquals(0f, w.copy(durMs = 0).fraction)
    }
}

class LanguageTest {
    private val r = com.urtv.player.data.Language::resolve
    @Test fun manualChoiceAlwaysWins() {
        assertEquals("en", r("en", "ar", "ar")); assertEquals("ar", r("ar", "en", "en"))
    }
    @Test fun autoFollowsUrtvThenSystemThenArabic() {
        assertEquals("en", r("auto", "en", "ar")); assertEquals("ar", r("auto", "ar", "en"))
        assertEquals("en", r("auto", null, "en"), "UrTV not installed: system language")
        assertEquals("ar", r("auto", null, "ar")); assertEquals("ar", r("auto", null, "fr"), "Arabic first for anything else")
        assertEquals("en", r("auto", "xx", "en"), "an unknown value from the provider is ignored")
    }
    @Test fun cycleAndNormalise() {
        val l = com.urtv.player.data.Language
        assertEquals(listOf("ar", "en", "auto"), listOf(l.next("auto"), l.next("ar"), l.next("en")))
        assertEquals("ar", l.normalize(" AR-SA ")); assertEquals("en", l.normalize("en")); assertEquals(null, l.normalize("fr")); assertEquals(null, l.normalize(null))
    }
}
