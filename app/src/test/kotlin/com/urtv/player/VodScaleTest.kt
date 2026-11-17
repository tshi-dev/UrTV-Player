package com.urtv.player

import androidx.test.core.app.ApplicationProvider
import com.urtv.player.core.SyntheticAccount
import com.urtv.player.data.PlaylistDef
import com.urtv.player.data.VodQuery
import com.urtv.player.data.VodRow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A made-up provider with 150 000 films + 20 000 series on a local port, read through the real Vod refresh into the disk index. Prints the numbers a TV would care
 * about (first load, a refresh with nothing new, a refresh with a few new films, memory kept and peak, a page, a search, the category list) and asserts generous budgets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UrtvApp::class)
class VodScaleTest {
    private val app get() = ApplicationProvider.getApplicationContext<UrtvApp>()
    private fun ms(f: () -> Unit): Long { val t = System.nanoTime(); f(); return (System.nanoTime() - t) / 1_000_000 }
    private fun used(): Long { val r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) / 1_048_576 }
    private fun gcUsed(): Long { repeat(3) { System.gc() }; return used() }

    /** A tiny HTTP server on a local port (the JDK one is not on the test classpath): answers the few Xtream calls with the made-up data. */
    private fun serve(perCategory: Boolean): Triple<ServerSocket, Int, () -> Unit> {
        val n = SyntheticAccount.CATS
        fun films(c: Int) = (1..SyntheticAccount.VOD).filter { it % n + 1 == c }.joinToString(",", "[", "]") { i -> SyntheticAccount.vod(1, n, i).removePrefix("[").removeSuffix("]") }     // made when asked, never kept
        val series = SyntheticAccount.series(); val cats = SyntheticAccount.cats(); val sc = SyntheticAccount.cats(60)
        fun all() = SyntheticAccount.vod()
        val srv = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!srv.isClosed) {
                val c = try { srv.accept() } catch (_: Exception) { break }
                Thread {
                    c.use { sock ->
                        val line = sock.getInputStream().bufferedReader().let { r -> val l = r.readLine().orEmpty(); while (r.readLine().orEmpty().isNotEmpty()) { }; l }
                        val q = line.substringAfter('?', "").substringBefore(' ').split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
                        val body = when (q["action"]) {
                            "get_vod_categories", "get_live_categories" -> cats; "get_live_streams" -> SyntheticAccount.live(); "get_series_categories" -> sc; "get_series" -> series
                            "get_vod_streams" -> if (perCategory && q["category_id"] != null) films(q["category_id"]!!.toInt()) else all()
                            else -> "[]"
                        }.toByteArray()
                        sock.getOutputStream().let { o -> o.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); o.write(body); o.flush() }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        return Triple(srv, srv.localPort) { srv.close() }
    }

    private fun wait(cond: () -> Boolean, ms: Long = 120_000) { val end = System.currentTimeMillis() + ms; while (!cond() && System.currentTimeMillis() < end) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(25) } }

    @Test fun fiftyFiveThousandChannelsComeFromTheCacheAtOnceAndTheProgressMoves() {
        val (_, port, stop) = serve(true)
        try {
            val def = PlaylistDef("live", "Live", "xtream", server = "http://127.0.0.1:$port", user = "u", pass = "p")
            app.store.addPlaylist(def)
            var maxProgress = 0
            val first = ms { app.catalog.open("live", force = true); wait({ maxProgress = maxOf(maxProgress, app.catalog.state.value.progress); app.catalog.state.value.channels.size >= SyntheticAccount.LIVE }) }
            println("SCALE live: first load of ${app.catalog.state.value.channels.size} channels from the provider: $first ms, progress reached $maxProgress")
            assertTrue(maxProgress > 0 || first < 3_000, "the count moved while loading")
            wait({ app.catalog.timings.cacheWriteMs > 0 || app.catalog.timings.channels > 0 })
            var n = 0
            val read = ms { n = app.catalog.cachedChannels("live").size }
            assertEquals(SyntheticAccount.LIVE, n)
            println("SCALE live: cold start from the cache (read + parse of the cached list): $read ms")
            assertTrue(read < 15_000, "cache read $read ms")
        } finally { stop() }
    }

    @Test fun aBigAccountLoadsIntoTheIndexAndReadsFastWithLittleMemory() {
        val (_, port, stop) = serve(true)
        try {
            val def = PlaylistDef("big", "Big", "xtream", server = "http://127.0.0.1:$port", user = "u", pass = "p")
            app.store.addPlaylist(def)
            val base = gcUsed(); var peak = base
            val sampler = Thread { while (!Thread.currentThread().isInterrupted) { peak = maxOf(peak, used()); try { Thread.sleep(20) } catch (_: InterruptedException) { return@Thread } } }.apply { isDaemon = true; start() }
            val first = ms { app.vod.open(def, force = true); wait({ app.vod.state.value.movieCount >= SyntheticAccount.VOD && app.vod.state.value.seriesCount >= SyntheticAccount.SERIES && !app.vod.state.value.loading }) }
            sampler.interrupt()
            val st = app.vod.state.value
            println("SCALE after (heap limit ${Runtime.getRuntime().maxMemory() / 1_048_576} MB): first load of ${st.movieCount} films + ${st.seriesCount} series (per category) into the disk index: $first ms, memory kept ${gcUsed() - base} MB, peak above start ${peak - base} MB")
            assertEquals(SyntheticAccount.VOD, st.movieCount); assertEquals(0, st.fresh, "the first load marks nothing as new")
            val db = app.vod.db
            println("SCALE after: category list (${SyntheticAccount.CATS} cats) ${ms { db.cats("big", 0) }} ms; first page of a category ${ms { db.page(VodQuery("big", 0, cat = "فئة 7 | Category 7"), 0, 60) }} ms; deep page (offset 100 000, all films) ${ms { db.page(VodQuery("big", 0), 100_000, 60) }} ms")
            println("SCALE after: search over 150k films: ${ms { db.page(VodQuery("big", 0, text = "number 1234"), 0, 60) }} ms; «recently added» ${ms { db.newest("big", 24) }} ms; 40 trending titles ${ms { db.byTitles("big", (1..40).map { "film $it" }) }} ms; one film by id ${ms { db.get("big", 0, "4242") }} ms")
            val before = app.vod.db.meta("big", "checked"); Thread.sleep(5)
            val again = ms { app.vod.open(def, force = true); wait({ app.vod.db.meta("big", "checked") != before && !app.vod.state.value.loading }, 60_000) }
            println("SCALE after: refresh with nothing new (diff, no rewrite of the list): $again ms, fresh=${app.vod.state.value.fresh}")
            assertEquals(SyntheticAccount.VOD, app.vod.state.value.movieCount); assertEquals(0, app.vod.state.value.fresh)
            assertTrue(first < 120_000, "first load $first ms")
            println("SCALE after: memory kept by the app after the loads: ${gcUsed() - base} MB (printed, not asserted: it includes whatever the test runner itself loaded)")
            // what must hold on any machine: the state the screens observe holds counts, never the lists
            val listFields = com.urtv.player.data.VodState::class.java.declaredFields.filter { java.util.Collection::class.java.isAssignableFrom(it.type) || it.type.isArray }
            assertTrue(listFields.isEmpty(), "VodState keeps no list: ${listFields.map { it.name }}")
        } finally { stop() }
    }
}
