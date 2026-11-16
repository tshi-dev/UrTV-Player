package com.urtv.player

import androidx.test.core.app.ApplicationProvider
import com.urtv.player.data.VodDb
import com.urtv.player.data.VodQuery
import com.urtv.player.data.VodRow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The disk index: paging in the provider's order, search, diffs (new / changed / removed), «new» marks, and no web address or login ever stored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VodDbTest {
    private fun db() = VodDb(ApplicationProvider.getApplicationContext(), "t${System.nanoTime()}.db")
    private fun film(i: Int, cat: String = "c${i % 3}", name: String = "فيلم $i Movie $i") = VodRow(0, "$i", name, cat, "http://img/$i.jpg", "7.$i", null, 1000L + i, ext = "mkv")

    @Test fun categoriesPageInOrderAndSearchFindsArabicAndLatin() {
        val d = db()
        d.apply("p", 0, null, (1..100).asSequence().map { film(it) }, 0, initial = true)
        assertEquals(100, d.count("p")); assertEquals(0, d.count("other"))
        assertEquals(listOf("c1", "c2", "c0"), d.cats("p", 0).map { it.first }, "first appearance = provider order")
        assertEquals(34, d.size(VodQuery("p", 0, cat = "c1")))
        assertEquals(listOf("1", "4", "7"), d.page(VodQuery("p", 0, cat = "c1"), 0, 3).map { it.id })
        assertEquals(listOf("10", "13"), d.page(VodQuery("p", 0, cat = "c1"), 3, 2).map { it.id }, "offsets continue where the page before ended")
        assertEquals(listOf("50"), d.page(VodQuery("p", 0, text = "movie 50"), 0, 10).map { it.id })
        assertEquals(listOf("50"), d.page(VodQuery("p", 0, text = "فيلم 50"), 0, 10).map { it.id })
        assertEquals(listOf("100", "99", "98"), d.page(VodQuery("p", 0, newest = true), 0, 3).map { it.id })
        assertEquals(2, d.size(VodQuery("p", 0, ids = setOf("5", "6", "nope"))))
        assertEquals("mkv", d.get("p", 0, "7")?.ext); assertNull(d.get("p", 1, "7"))
    }

    @Test fun aSecondAnswerIsAppliedAsADiffAndNewItemsAreMarkedButTheFirstLoadIsNot() {
        val d = db()
        d.apply("p", 0, "c1", (1..10).asSequence().map { film(it, "c1") }, 0, initial = true)
        assertEquals(0, d.fresh("p"), "the very first load marks nothing as new")
        val diff = d.apply("p", 0, "c1", ((3..10) + (11..12)).asSequence().map { film(it, "c1", if (it == 4) "Renamed" else "فيلم $it Movie $it") }, 0, initial = false)
        assertEquals(10, diff.seen); assertEquals(2, diff.added); assertEquals(2, diff.removed)
        assertEquals(10, d.count("p")); assertNull(d.get("p", 0, "1")); assertEquals("Renamed", d.get("p", 0, "4")?.name)
        assertEquals(2, d.fresh("p")); assertTrue(d.get("p", 0, "11")!!.fresh()); assertTrue(!d.get("p", 0, "5")!!.fresh())
        // a cut-short or empty answer never wipes a category
        d.apply("p", 0, "c1", emptySequence(), 0, initial = false)
        d.apply("p", 0, "c1", (3..4).asSequence().map { film(it, "c1") }, 0, initial = false, complete = { false })
        assertEquals(10, d.count("p"))
    }

    @Test fun titlesMatchForTrendingAndNothingStoredCarriesAnAddressOrLogin() {
        val d = db()
        d.apply("p", 0, null, sequenceOf(film(1, name = "Inception (2010) [4K]"), film(2, name = "Heat")), 0, initial = true)
        assertEquals(listOf("1"), d.byTitles("p", listOf("inception", "other")).map { it.id })
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val all = ctx.getDatabasePath(d.databaseName).let { f -> listOf(f, java.io.File(f.path + "-wal")).filter { it.exists() }.joinToString { String(it.readBytes(), Charsets.ISO_8859_1) } }
        assertTrue("http://host" !in all && "password" !in all, "no stream address in the index")
    }
}
