package com.urtv.player.server

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.urtv.player.UrtvApp
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

/** Placeholder library: no real titles, no artwork (cards show the flat placeholder). */
private class FakeBackend(lang: String) : MediaBackend {
    private val ar = lang == "ar"
    override val kind = ServerKind.Jellyfin
    override val config = ServerConfig("x", ServerKind.Jellyfin, if (ar) "خادم المنزل" else "Home server", "http://192.0.2.10:8096", "u", "user", "token")
    private val films = Library("L1", if (ar) "أفلام" else "Films", LibraryKind.Movies)
    private val shows = Library("L2", if (ar) "مسلسلات" else "Shows", LibraryKind.Shows)
    private val lorem = if (ar) "وصف قصير تجريبي للعنوان، بلا محتوى حقيقي." else "A short placeholder description with no real content."
    private fun movie(i: Int) = MediaItem("m$i", ItemType.Movie, (if (ar) "فيلم تجريبي " else "Example Film ") + i, year = 2015 + i % 9, overview = lorem, rating = 6.0 + (i % 4), runtimeMs = (90 + i * 7) * 60_000L,
        officialRating = "PG-13", genres = listOf(if (ar) "دراما" else "Drama"), resumeMs = if (i % 3 == 0) 40 * 60_000L else 0, played = i % 4 == 1,
        streams = listOf(StreamInfo(0, type = StreamType.Video, codec = "hevc", width = 3840, height = 2160, videoRange = "HDR10"),
            StreamInfo(1, type = StreamType.Audio, codec = "eac3", channels = 6, language = "eng", isDefault = true),
            StreamInfo(2, type = StreamType.Subtitle, codec = "subrip", language = "ara")),
        people = listOf(Person(if (ar) "ممثل تجريبي" else "Example Actor", if (ar) "الدور الأول" else "Lead", "Actor")))
    private fun show(i: Int) = MediaItem("s$i", ItemType.Series, (if (ar) "مسلسل تجريبي " else "Example Show ") + i, year = 2019 + i % 5, overview = lorem, rating = 7.5, unplayed = i % 3, childCount = 3,
        genres = listOf(if (ar) "كوميديا" else "Comedy"))
    private fun ep(n: Int) = MediaItem("e$n", ItemType.Episode, (if (ar) "حلقة تجريبية " else "Example Episode ") + n, seriesId = "s1", seriesName = (if (ar) "مسلسل تجريبي " else "Example Show ") + 1,
        seasonId = "se1", seasonNumber = 1, episodeNumber = n, overview = lorem, runtimeMs = 44 * 60_000L, resumeMs = if (n == 2) 20 * 60_000L else 0, played = n == 1)
    override fun libraries() = listOf(films, shows)
    override fun continueWatching(limit: Int) = listOf(movie(3), ep(2), movie(6))
    override fun nextUp(limit: Int) = listOf(ep(3))
    override fun latest(lib: Library, limit: Int) = if (lib.id == "L1") (1..10).map(::movie) else (1..10).map(::show)
    override fun browse(lib: Library, q: Query) = Page(latest(lib, 30), 30)
    override fun genres(lib: Library) = listOf("Drama", "Comedy")
    override fun item(id: String): MediaItem? = when { id.startsWith("m") -> movie(id.drop(1).toInt()); id.startsWith("s") -> show(id.drop(1).toInt()); else -> ep(1) }
    override fun seasons(seriesId: String) = listOf(MediaItem("se1", ItemType.Season, "", seasonNumber = 1, unplayed = 2), MediaItem("se2", ItemType.Season, "", seasonNumber = 2, unplayed = 3))
    override fun episodes(seriesId: String, seasonId: String) = (1..6).map(::ep)
    override fun children(id: String) = (1..4).map(::movie)
    override fun similar(id: String, limit: Int) = (4..9).map(::movie)
    override fun search(text: String, limit: Int) = emptyList<MediaItem>()
    override fun imageUrl(item: MediaItem, kind: Img, width: Int): String? = null
    override fun authHeaders() = emptyMap<String, String>()
    override fun playback(item: MediaItem, caps: DeviceCaps, opts: PlayOptions): PlaybackPlan = throw ServerException(ServerException.Reason.Refused, "n/a")
    override fun markers(item: MediaItem) = emptyList<Marker>()
    override fun subtitleUrl(plan: PlaybackPlan, s: StreamInfo): String? = null
    override fun reportStart(plan: PlaybackPlan, positionMs: Long) {}
    override fun reportProgress(plan: PlaybackPlan, positionMs: Long, paused: Boolean) {}
    override fun reportStop(plan: PlaybackPlan, positionMs: Long) {}
    override fun setPlayed(item: MediaItem, played: Boolean) {}
    override fun searchSubtitles(item: MediaItem, language: String) = emptyList<RemoteSubtitle>()
    override fun downloadSubtitle(item: MediaItem, sub: RemoteSubtitle) {}
}

/** Smoke-renders the home-server screens with placeholder data; UPDATE_SHOTS=1 writes docs/shots/server-*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar-ldrtl-w960dp-h540dp-xhdpi", application = UrtvApp::class)
class ServerShotsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var app: UrtvApp

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        val sc = ActivityScenario.launch(ComponentActivity::class.java)
        sc.onActivity { it.setContent { UrtvTheme { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) { content() } } } }
        repeat(30) { rule.waitForIdle(); shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(80) }
        var bmp: Bitmap? = null
        sc.onActivity { a ->
            val v = a.window.decorView
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 1920, 1080)
            bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        rule.waitForIdle()
        if (System.getenv("UPDATE_SHOTS") == "1") {
            val f = File("../docs/shots/$name.png"); f.parentFile?.mkdirs()
            f.outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        sc.close()
    }

    private val ar = FakeBackend("ar")
    private val en = FakeBackend("en")
    private val sample = listOf(Cue(0, 1, "مرحبًا بالعالم! Hello world"))

    @Test fun homeAr() = shot("server-home-ar") { ServerHome(ar, {}, {}) }
    @Test fun libraryAr() = shot("server-library-ar") { LibraryScreen(ar, ar.libraries()[0], {}) }
    @Test fun movieAr() = shot("server-movie-ar") { DetailScreen(ar, "m3", { _, _, _ -> }, {}) }
    @Test fun showAr() = shot("server-show-ar") { DetailScreen(ar, "s1", { _, _, _ -> }, {}) }
    @Test fun subtitlesAr() = shot("server-subtitles-ar") { SubtitleOverlay(sample, SubtitleStyle()) }
    @Test @Config(qualifiers = "en-w960dp-h540dp-xhdpi") fun homeEn() = shot("server-home-en") { ServerHome(en, {}, {}) }
    @Test @Config(qualifiers = "en-w960dp-h540dp-xhdpi") fun libraryEn() = shot("server-library-en") { LibraryScreen(en, en.libraries()[0], {}) }
    @Test @Config(qualifiers = "en-w960dp-h540dp-xhdpi") fun movieEn() = shot("server-movie-en") { DetailScreen(en, "m3", { _, _, _ -> }, {}) }
    @Test @Config(qualifiers = "en-w960dp-h540dp-xhdpi") fun showEn() = shot("server-show-en") { DetailScreen(en, "s1", { _, _, _ -> }, {}) }
    @Test @Config(qualifiers = "en-w960dp-h540dp-xhdpi") fun subtitlesEn() = shot("server-subtitles-en") { SubtitleOverlay(sample, SubtitleStyle(background = 0x99000000)) }
}
