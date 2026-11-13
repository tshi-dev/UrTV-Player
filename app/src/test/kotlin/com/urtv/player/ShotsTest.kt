package com.urtv.player

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.urtv.player.data.PlaylistDef
import com.urtv.player.ui.AddPlaylistScreen
import com.urtv.player.ui.EmptyPlaylists
import com.urtv.player.ui.HomeScreen
import com.urtv.player.ui.PinPad
import com.urtv.player.ui.PlayerScreen
import com.urtv.player.ui.Tab
import com.urtv.player.ui.UrtvTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/**
 * Renders the real screens with placeholder data ("Example", "تجريبي") on the JVM.
 * Set UPDATE_SHOTS=1 to write the PNGs into docs/shots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar-ldrtl-w960dp-h540dp-xhdpi", application = UrtvApp::class)
class ShotsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var app: UrtvApp

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
    }

    private fun seed() {
        val now = System.currentTimeMillis()
        app.store.addPlaylist(PlaylistDef("demo", "قائمة تجريبية", "m3u", url = "http://example.com/list.m3u"))
        val dir = File(app.filesDir, "cache").apply { mkdirs() }
        val groups = listOf("أخبار", "رياضة", "أطفال", "أفلام", "وثائقيات")
        File(dir, "ch_demo.tsv").writeText((1..24).joinToString("\n") { i ->
            listOf("c$i", "قناة تجريبية $i", "http://example.com/s/$i.m3u8", groups[i % groups.size], "", "ex$i", "").joinToString("\t")
        } + "\n")
        fun t(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US).format(java.util.Date(ms))
        val h = 3_600_000L; val base = (now / h) * h - h
        File(dir, "epg_demo.xml").writeText("<tv>" + (1..24).joinToString("") { i ->
            (0 until 8).joinToString("") { k ->
                val len = if ((i + k) % 3 == 0) h else if ((i + k) % 3 == 1) h * 3 / 2 else h / 2
                val s = base + (0 until k).sumOf { j -> if ((i + j) % 3 == 0) h else if ((i + j) % 3 == 1) h * 3 / 2 else h / 2 }
                "<programme start=\"${t(s)}\" stop=\"${t(s + len)}\" channel=\"ex$i\"><title>برنامج تجريبي ${k + 1}</title><desc>وصف قصير للبرنامج.</desc></programme>"
            }
        } + "</tv>")
        app.catalog.open("demo")
        repeat(300) { if (!app.catalog.epg.value.isEmpty) return@repeat; shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) }
        check(!app.catalog.epg.value.isEmpty) { "epg not loaded: ${app.catalog.state.value}" }
    }

    private fun seedHome() {
        val ch = app.catalog.state.value.channels
        listOf(0, 1, 2, 5, 7, 9).forEach { app.store.toggleFav(com.urtv.player.ui.chKey("demo", ch[it])) }
        listOf(3, 4, 6, 8, 10, 11).forEach { app.store.pushRecent(com.urtv.player.ui.chKey("demo", ch[it])) }
        fun w(k: String, kind: String, t: String, se: Int, ep: Int, pos: Long, dur: Long) =
            com.urtv.player.data.WatchItem(k, "demo", kind, t, "", null, "http://example.com/$k.mp4", pos, dur, System.currentTimeMillis(), null, se, ep)
        listOf(w("a", "episode", "مسلسل تجريبي 1", 2, 4, 1_000_000, 2_800_000), w("b", "movie", "فيلم تجريبي 2", 0, 0, 1_500_000, 5_000_000),
            w("c", "episode", "مسلسل تجريبي 3", 1, 7, 900_000, 3_300_000), w("d", "episode", "مسلسل تجريبي 4", 3, 2, 2_000_000, 2_700_000))
            .reversed().forEach { app.store.updateWatching(it) }
    }

    private fun seedXtream() {
        app.store.addPlaylist(PlaylistDef("xt", "Example Xtream", "xtream", server = "http://example.com", user = "u", pass = "p"))
        val dir = File(app.filesDir, "cache").apply { mkdirs() }
        val cats = listOf("أكشن", "دراما", "كوميديا", "وثائقي")
        File(dir, "movies_xt.tsv").writeText((1..30).joinToString("\n") { i ->
            listOf("m$i", "فيلم تجريبي $i", "http://example.com/movie/u/p/$i.mp4", cats[i % cats.size], "", "%.1f".format(java.util.Locale.US, 5 + (i % 5) * 0.9), i.toString()).joinToString("\t")
        } + "\n")
        File(dir, "series_xt.tsv").writeText((1..12).joinToString("\n") { i ->
            listOf("s$i", "مسلسل تجريبي $i", cats[i % cats.size], "", "7.5", "2022", i.toString(), "وصف قصير للمسلسل.").joinToString("\t")
        } + "\n")
        app.catalog.open("xt"); app.vod.open(app.store.playlists.value.first { it.id == "xt" })
        repeat(100) { if (app.vod.state.value.movies.isNotEmpty()) return@repeat; shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) }
        check(app.vod.state.value.movies.isNotEmpty())
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        val sc = ActivityScenario.launch(ComponentActivity::class.java)
        sc.onActivity { it.setContent { UrtvTheme { content() } } }
        rule.waitForIdle(); Thread.sleep(400); rule.waitForIdle()
        var bmp: Bitmap? = null
        sc.onActivity { a ->
            val v = a.window.decorView
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 1920, 1080)
            bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        rule.waitForIdle()
        val b = bmp!!
        if (System.getenv("UPDATE_SHOTS") == "1") {
            val f = File("../docs/shots/$name.png"); f.parentFile?.mkdirs()
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        sc.close()
    }

    @Test fun home() { seed(); seedHome(); shot("01-home-ar") { HomeScreen(app, Tab.Home, {}, { _, _ -> }, {}, {}, null) } }
    @Test fun live() { seed(); shot("02-live-ar") { HomeScreen(app, Tab.Live, {}, { _, _ -> }, {}, {}, null) } }
    @Test fun guide() { seed(); shot("03-guide-ar") { HomeScreen(app, Tab.Guide, {}, { _, _ -> }, {}, {}, null) } }
    @Test fun settings() { seed(); shot("09-settings-ar") { HomeScreen(app, Tab.Settings, {}, { _, _ -> }, {}, {}, null) } }
    @Test fun empty() { shot("10-empty-ar") { HomeScreen(app, Tab.Live, {}, { _, _ -> }, {}, {}, null) } }
    @Test fun addPhone() { shot("08-add-from-phone-ar") { AddPlaylistScreen(app, Triple("http://192.0.2.10:49152/?c=482915", "482915", "192.0.2.10:49152")) {} } }
    @Test fun backup() { shot("12-backup-passphrase-ar") { com.urtv.player.ui.BackupScreen(app, false) {} } }
    @Test fun restore() { shot("13-restore-from-phone-ar") { com.urtv.player.ui.BackupScreen(app, true, Triple("http://192.0.2.10:49153/?c=730164", "730164", "192.0.2.10:49153")) {} } }
    @Test fun loadingSkeleton() {
        shot("14-loading-skeleton-ar") { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(com.urtv.player.ui.C.bgBrush).padding(horizontal = 72.dp, vertical = 130.dp)) { com.urtv.player.ui.SkeletonRows(9) } }
    }
    @Test fun pin() { shot("11-pin-ar") { PinPad("أدخل الرمز السري", null, 0, {}) {} } }
    @Test fun player() { seed(); val ch = app.catalog.state.value.channels[2]
        shot("04-player-ar") { PlayerScreen(app, app.catalog.state.value.channels, ch) {} } }

    @Test fun playerPanel() { seed(); val ch = app.catalog.state.value.channels[2]
        shot("15-player-panel-ar") { com.urtv.player.ui.OptionsPanel(app, ch.name, false, {}, {}, {}, {}, sleepMin = 30, onLists = {}) } }

    @Test fun movies() { seedXtream(); shot("05-movies-ar") { HomeScreen(app, Tab.Movies, {}, { _, _ -> }, {}, {}, null) } }
    @Test fun series() { seedXtream(); shot("06-series-ar") { HomeScreen(app, Tab.Series, {}, { _, _ -> }, {}, {}, null) } }
    @Test @Config(qualifiers = "en-w960dp-h540dp-xhdpi") fun homeEn() { seed(); seedHome(); shot("01-home-en") { HomeScreen(app, Tab.Home, {}, { _, _ -> }, {}, {}, null) } }
    @Test @Config(qualifiers = "en-w960dp-h540dp-xhdpi") fun liveEn() { seed(); shot("02-live-en") { HomeScreen(app, Tab.Live, {}, { _, _ -> }, {}, {}, null) } }
    @Test @Config(qualifiers = "en-w960dp-h540dp-xhdpi") fun settingsEn() { seed(); shot("09-settings-en") { HomeScreen(app, Tab.Settings, {}, { _, _ -> }, {}, {}, null) } }
}
