package com.urtv.player

import androidx.test.core.app.ApplicationProvider
import com.urtv.player.core.Channel
import com.urtv.player.core.M3uVod
import com.urtv.player.data.PlaylistDef
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UrtvApp::class)
class M3uVodTest {
    private val app get() = ApplicationProvider.getApplicationContext<UrtvApp>()

    @Test fun moviesAndSeriesSplitOutOfAnM3uListAreKeptAndEpisodesAreGroupedByShow() {
        val def = PlaylistDef("m1", "x", "m3u", url = "http://example.com/l.m3u")
        val c = { n: String, u: String, g: String -> Channel(n, n, u, g, "http://example.com/$n.png") }
        val split = M3uVod.split(listOf(
            c("Live 1", "http://h/live/u/p/1.m3u8", "News"), c("Film A", "http://h/movie/u/p/2.mp4", "Action"),
            c("Show X S01 E02", "http://h/series/u/p/4.mkv", "Drama"), c("Show X S01 E01", "http://h/series/u/p/3.mkv", "Drama"), c("Show X S02 E01", "http://h/series/u/p/5.mkv", "Drama"),
            c("Lonely episode", "http://h/series/u/p/6.mkv", "Drama")))
        app.vod.setFromM3u(def, split)
        val db = app.vod.db
        assertEquals(listOf("Film A"), db.page(com.urtv.player.data.VodQuery("m1", 0), 0, 50).map { it.name })
        assertEquals(2, db.count("m1", 1), "one show card for the three episodes, one flat entry for the one without a pattern")
        val show = db.page(com.urtv.player.data.VodQuery("m1", 1), 0, 50).first { it.name == "Show X" }
        val eps = runBlocking { app.vod.episodes(def, show.id) }
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), eps.map { it.season to it.number }, "sorted by season then number")
        app.vod.open(def, fetch = false)          // the start-up cache read must not wipe what is already showing
        assertEquals(2, app.vod.db.count("m1", 1)); assertEquals(1, app.vod.db.count("m1", 0))
        // after a restart the cached copy is what comes back
        app.vod.open(def, fetch = false)
        val end = System.currentTimeMillis() + 5000
        while (app.vod.state.value.seriesCount != 2 && System.currentTimeMillis() < end) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(30) }
        assertEquals(2, app.vod.state.value.seriesCount)
        assertEquals(1, app.vod.movie(def, "m3u:0")?.let { 1 }, "the stream address of an M3U film comes from the encrypted file")
        assertEquals(3, runBlocking { app.vod.episodes(def, show.id) }.size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UrtvApp::class)
class TrackPrefTest {
    private val app get() = ApplicationProvider.getApplicationContext<UrtvApp>()

    @Test fun theChoicesForAChannelOrFilmAreKeptAndTheSpeedCyclesAndIsRemembered() {
        val c = app.player
        c.trackKey("vod|p|1")
        assertEquals(1f, c.speed.value)
        c.cycleSpeed(); assertEquals(1.25f, c.speed.value); c.cycleSpeed(); assertEquals(1.5f, c.speed.value)
        assertEquals(1.5f, app.store.trackPref("vod|p|1")?.speed, "remembered for this film")
        c.trackKey("vod|p|2"); assertEquals(1f, c.speed.value, "another film starts normal")
        c.trackKey("vod|p|1"); assertEquals(1.5f, c.speed.value, "and the first one comes back at its speed")
        app.store.setTrackPref("ch|a", com.urtv.player.data.Store.TrackPref("English", "", 1f))
        assertEquals("English", app.store.trackPref("ch|a")?.audio); assertEquals(null, app.store.trackPref("ch|unknown"))
    }

    @Test fun watchCountsFeedMostWatchedAndTheNewHomeRowsStartOnTopOnce() {
        val st = app.store
        st.bumpViews("vod|p|1"); st.bumpViews("vod|p|1"); st.bumpViews("vod|p|2")
        assertEquals(2, st.views.value["vod|p|1"]); assertEquals(1, st.views.value["vod|p|2"])
        assertEquals(listOf("added", "viewed", "trending"), st.homeRows.value.take(3), "new installs and upgrades get the new rows first")
    }

    @Test fun serverHiccupsAreRetriedButAWrongLoginIsFinal() {
        var calls = 0
        val r = com.urtv.player.data.Http.retrying { calls++; if (calls < 3) throw java.io.IOException("HTTP 503") else "ok" }
        assertEquals("ok", r); assertEquals(3, calls)
        calls = 0
        try { com.urtv.player.data.Http.retrying { calls++; throw java.io.IOException("HTTP 401") }; error("should have thrown") } catch (e: java.io.IOException) { assertEquals(1, calls, "no retry on a 4xx") }
    }
}
