package com.urtv.player

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.urtv.player.ui.AddPlaylistScreen
import com.urtv.player.ui.SearchRow
import com.urtv.player.ui.UrtvTheme
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Real key-event paths through the screens that failed on the TV. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UrtvApp::class, qualifiers = "ar-ldrtl-w960dp-h540dp-xhdpi")   // the TV is Arabic: right-to-left changes where focus falls
class FlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private val app get() = ApplicationProvider.getApplicationContext<UrtvApp>()

    @Before fun setUp() { shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name)) }
    @After fun tearDown() { scenario?.close() }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { sc -> sc.onActivity { a -> a.setContent { UrtvTheme(com.urtv.player.data.Appearance(), 100) { content() } } } }
    }

    /** Spinners and the focus ring animate forever, so the test clock is driven by hand instead of waiting for "idle". */
    private fun settle(ms: Long = 200) { rule.mainClock.autoAdvance = false; rule.mainClock.advanceTimeBy(ms); Thread.sleep(40) }
    private fun until(timeoutMs: Long, cond: () -> Boolean) { val end = System.currentTimeMillis() + timeoutMs; while (!cond()) { check(System.currentTimeMillis() < end) { "condition not met in $timeoutMs ms" }; settle() } }

    private fun texts(): List<String> = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any text") { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) }).fetchSemanticsNodes()
        .flatMap { n -> n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text } }
    private fun code(): String? = texts().firstOrNull { it.matches(Regex("[0-9]{3} [0-9]{3}")) }

    @Test fun retryAfterTheSessionEndsMakesANewCodeAndStaysOnThePhoneTab() {
        // a real loopback server with a 1.2 s lifetime, keys sent through the focus system
        show { AddPlaylistScreen(app, lifetimeMs = 1200, address = { InetAddress.getByName("127.0.0.1") }) {} }
        until(5_000) { code() != null }
        val first = code()
        until(8_000) { rule.onAllNodes(hasText(app.getString(R.string.phone_closed))).fetchSemanticsNodes().isNotEmpty() }
        until(3_000) { rule.onAllNodes(hasText(app.getString(R.string.retry)) and isFocused()).fetchSemanticsNodes().isNotEmpty() }   // the retry button holds the focus
        rule.onNodeWithText(app.getString(R.string.retry)).performKeyInput { pressKey(Key.DirectionCenter) }
        until(5_000) { code() != null && code() != first }
        assertNotEquals(first, code(), "a new address and code")
        // the page did not switch to the Xtream form
        assertEquals(0, rule.onAllNodes(hasText(app.getString(R.string.f_server))).fetchSemanticsNodes().size, "still the phone page, not the Xtream form")
        until(3_000) { rule.onAllNodes(hasText(app.getString(R.string.add_phone)) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theSearchChipIsReachableFromTheFieldWithLeftRightAndBack() {
        show { SearchRow("", {}, latin = false, onLatin = {}, onUp = {}) }
        settle()
        rule.onNodeWithText(app.getString(R.string.search_hint)).performKeyInput { /* focus via a key into the first focusable */ pressKey(Key.DirectionDown) }
        settle()
        // the field is a plain focus target (not editing): one press toward the chip reaches it, the opposite press comes back
        val chip = rule.onNodeWithText("ع")
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }      // right-to-left: the chip sits on the left of the field
        settle()
        chip.assertIsFocusedSafe(); rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        settle()
        assertTrue(rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() && rule.onAllNodes(hasText("ع") and isFocused()).fetchSemanticsNodes().isEmpty(), "back on the field")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsFocusedSafe() { assertTrue(rule.onAllNodes(hasText("ع") and isFocused()).fetchSemanticsNodes().isNotEmpty(), "the chip has the focus") }

    @Test fun aPrefetchedManifestIsServedOnceFromMemoryAndExpires() {
        val srv = java.net.ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")); var hits = 0
        val t = Thread { while (!srv.isClosed) runCatching { srv.accept().use { s ->
            s.getInputStream().bufferedReader().readLine(); hits++
            val body = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
        } } }.apply { isDaemon = true; start() }
        try {
            val url = "http://127.0.0.1:${srv.localPort}/live/1.m3u8"
            val client = OkHttpClient.Builder().addInterceptor(com.urtv.player.player.ManifestCache.interceptor).build()
            com.urtv.player.player.ManifestCache.clear()
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(200, it.code) }; assertEquals(1, hits, "nothing stored: goes to the server")
            com.urtv.player.player.ManifestCache.put(url, "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray(), "application/vnd.apple.mpegurl")
            client.newCall(Request.Builder().url(url).build()).execute().use { assertTrue(it.body!!.string().startsWith("#EXTM3U")) }; assertEquals(1, hits, "served from memory")
            client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }; assertEquals(2, hits, "only once")
            com.urtv.player.player.ManifestCache.put(url, "x".toByteArray(), null, now = System.currentTimeMillis() - 60_000)
            assertEquals(null, com.urtv.player.player.ManifestCache.take(url), "expired after 20 s")
            com.urtv.player.player.ManifestCache.put(url, ByteArray(com.urtv.player.player.ManifestCache.MAX_BYTES + 1), null)
            assertEquals(null, com.urtv.player.player.ManifestCache.take(url), "too large is never kept")
        } finally { srv.close() }
    }
}
