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
        val st = app.vod.state.value
        assertEquals(listOf("Film A"), st.movies.map { it.name })
        assertEquals(2, st.series.size, "one show card for the three episodes, one flat entry for the one without a pattern")
        val show = st.series.first { it.name == "Show X" }
        val eps = runBlocking { app.vod.episodes(def, show.id) }
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), eps.map { it.season to it.number }, "sorted by season then number")
        // after a restart the cached copy is what comes back
        app.vod.open(def, fetch = false)
        val end = System.currentTimeMillis() + 5000
        while (app.vod.state.value.series.size != 2 && System.currentTimeMillis() < end) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(30) }
        assertEquals(2, app.vod.state.value.series.size)
        assertEquals(3, runBlocking { app.vod.episodes(def, show.id) }.size)
    }
}
