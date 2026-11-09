package com.urtv.player

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
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

    @Test fun home() { seed(); shot("01-channels-ar") { HomeScreen(app, Tab.Live, {}, { _, _ -> }, {}, {}, null) } }
    @Test fun guide() { seed(); shot("02-guide-ar") { HomeScreen(app, Tab.Guide, {}, { _, _ -> }, {}, {}, null) } }
    @Test fun settings() { seed(); shot("03-settings-ar") { HomeScreen(app, Tab.Settings, {}, { _, _ -> }, {}, {}, null) } }
    @Test fun empty() { shot("04-empty-ar") { HomeScreen(app, Tab.Live, {}, { _, _ -> }, {}, {}, null) } }
    @Test fun addPhone() { shot("05-add-from-phone-ar") { AddPlaylistScreen(app) {} } }
    @Test fun pin() { shot("06-pin-ar") { PinPad("أدخل الرمز السري", null, 0, {}) {} } }
    @Test fun player() { seed(); val ch = app.catalog.state.value.channels[2]
        shot("07-player-ar") { PlayerScreen(app, app.catalog.state.value.channels, ch) {} } }
}
