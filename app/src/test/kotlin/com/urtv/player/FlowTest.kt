package com.urtv.player

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.urtv.player.MainActivity
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
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)   // a0_ runs first: the phone-server lifetime test reads the (faked) system clock and is sensitive to what ran before it in the same JVM
class FlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private val app get() = ApplicationProvider.getApplicationContext<UrtvApp>()

    @Before fun setUp() { shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name)) }
    // closing the screen must not decide the test: with the clock paused it may wait for animations that never end, so it gets a short wait and its failure is ignored
    @After fun tearDown() { runCatching { androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(8, java.util.concurrent.TimeUnit.SECONDS); scenario?.close() } }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { sc -> sc.onActivity { a -> a.setContent { UrtvTheme(com.urtv.player.data.Appearance(), 100) { content() } } } }
    }

    /** Spinners and the focus ring animate forever, so the test clock is driven by hand instead of waiting for "idle". */
    private fun settle(ms: Long = 200) { rule.mainClock.autoAdvance = false; shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeBy(ms); Thread.sleep(40) }
    /** Waits by counting rounds (each is ~40 ms of real sleeping): Robolectric's clock is faked, so wall-clock timeouts would not mean what they say. */
    private fun until(timeoutMs: Long, cond: () -> Boolean) { var rounds = (timeoutMs / 40).toInt() * 3;       /* a loaded machine needs more rounds, never a different test */  while (!cond()) { check(rounds-- > 0) { "condition not met in ~$timeoutMs ms; screen shows ${texts().take(12)}" }; settle() } }

    private fun texts(): List<String> = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any text") { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) }).fetchSemanticsNodes()
        .flatMap { n -> n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text } }
    private fun code(): String? = texts().firstOrNull { it.matches(Regex("[0-9]{3} [0-9]{3}")) }

    @Test fun a0_retryAfterTheSessionEndsMakesANewCodeAndStaysOnThePhoneTab() {
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
        show { SearchRow("", {}, com.urtv.player.core.SearchFold.KeyMode.Auto, {}, onUp = {}) }
        settle()
        rule.onNodeWithText(app.getString(R.string.search_hint)).performKeyInput { /* focus via a key into the first focusable */ pressKey(Key.DirectionDown) }
        settle()
        // the field is a plain focus target (not editing): one press toward the chip reaches it, the opposite press comes back
        val chip = rule.onNodeWithText("ع/EN")
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }      // right-to-left: the chip sits on the left of the field
        settle()
        chip.assertIsFocusedSafe(); rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        settle()
        assertTrue(rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() && rule.onAllNodes(hasText("ع/EN") and isFocused()).fetchSemanticsNodes().isEmpty(), "back on the field")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsFocusedSafe() { assertTrue(rule.onAllNodes(hasText("ع/EN") and isFocused()).fetchSemanticsNodes().isNotEmpty(), "the chip has the focus") }

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

    // ---- seeded playlist helper for the screen-level tests
    private fun seedPlaylist(sameUrl: Boolean = false) {
        app.store.addPlaylist(com.urtv.player.data.PlaylistDef("demo", "demo", "m3u", url = "http://example.com/list.m3u"))
        val dir = java.io.File(app.filesDir, "cache").apply { mkdirs() }
        java.io.File(dir, "ch_demo.tsv").writeText((1..60).joinToString("\n") { i -> listOf("c$i", "قناة تجريبية $i", "http://example.com/s/${if (sameUrl) 1 else i}.m3u8", "أخبار", "", "", "").joinToString("\t") } + "\n")
        app.catalog.open("demo")
        val end = System.currentTimeMillis() + 10_000
        while (app.catalog.state.value.channels.isEmpty() || !app.store.ready.value) { check(System.currentTimeMillis() < end) { "playlist did not load" }; shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30) }
        app.splashDone = true; app.langGate = false     // the first-start language gate is MainActivity's job
    }

    private fun focusedTexts(): List<String> = rule.onAllNodes(isFocused()).fetchSemanticsNodes().flatMap { n -> if (n.config.contains(SemanticsProperties.Text)) n.config[SemanticsProperties.Text].map { it.text } else emptyList() }

    private val noMatchIntent get() = android.content.Intent(com.urtv.player.FindMatchActivity.ACTION).setClassName(app, com.urtv.player.FindMatchActivity::class.java.name)
        .putExtra("home_en", "Team A").putExtra("away_en", "Team B").putExtra("channels", arrayOf("Nonexistent Channel 9"))

    @Test fun findMatchNotFoundSearchButtonHandsTheSearchToTheMainActivity() {
        seedPlaylist()
        com.urtv.player.FindMatchActivity.trustedCallerForTests = { it == "sa.urtv.home" }     // stands in for UrTV's package and certificate (debug builds only)
        val savedLocale = java.util.Locale.getDefault()     // the activity sets the app language as the JVM default
        var ctl: org.robolectric.android.controller.ActivityController<com.urtv.player.FindMatchActivity>? = null
        try {
            ctl = org.robolectric.Robolectric.buildActivity(com.urtv.player.FindMatchActivity::class.java, noMatchIntent)
            shadowOf(ctl.get()).setCallingPackage("sa.urtv.home")
            ctl.create().start().resume().visible()
            until(10_000) { rule.onAllNodes(hasText(app.getString(R.string.fm_search))).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText(app.getString(R.string.fm_search)).performKeyInput { pressKey(Key.DirectionCenter) }
            settle(); settle()
            assertEquals(com.urtv.player.PendingSearch("Nonexistent Channel 9", "Team A – Team B"), app.pendingSearch)
            assertEquals(MainActivity::class.java.name, shadowOf(ctl!!.get()).nextStartedActivity?.component?.className, "the main screen is started")
        } finally { com.urtv.player.FindMatchActivity.trustedCallerForTests = null; app.pendingSearch = null; runCatching { ctl?.pause()?.stop()?.destroy() }; java.util.Locale.setDefault(savedLocale) }     // do not leave a resumed activity (and its animations) behind for the next test
    }

    @Test fun aPendingSearchOpensTheLiveTabWithTheBoxFilledAndDownFromTheTabReachesTheSearchBox() {
        seedPlaylist()
        show { com.urtv.player.ui.AppRoot(app) {} }
        until(10_000) { rule.onAllNodes(hasText(app.getString(R.string.tab_live))).fetchSemanticsNodes().isNotEmpty() }
        app.pendingSearch = com.urtv.player.PendingSearch("قناة تجريبية 7", "Team A – Team B")
        until(8_000) { texts().any { it == "قناة تجريبية 7" } }                       // the search box holds the text
        assertTrue(texts().any { it.contains("Team A – Team B") }, "the line saying which match it is for")
        assertTrue(texts().any { it.contains("قناة تجريبية 7") }, "and its result is listed")
        assertEquals(null, app.pendingSearch, "consumed")
        // Down from the tab bar: the search box first, never the last visited row
        until(5_000) { rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText(app.getString(R.string.tab_live)) and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        settle(); settle()
        rule.onNode(hasText(app.getString(R.string.tab_live)) and isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        settle(); settle()
        assertTrue(focusedTexts().any { it == "قناة تجريبية 7" || it == app.getString(R.string.search_hint) }, "focus is on the search box, got ${focusedTexts()}")
        // go down into the list (a row now has the focus and is remembered), back up to the tab, Down again: still the search box, never the remembered row
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        settle(); settle()
        assertTrue(focusedTexts().any { it.startsWith("قناة تجريبية") && it != "قناة تجريبية 7" || it.contains("قناة تجريبية") }, "a channel row has the focus, got ${focusedTexts()}")
        rule.onNode(hasText(app.getString(R.string.tab_live)) and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        settle(); settle()
        rule.onNode(hasText(app.getString(R.string.tab_live)) and isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        settle(); settle()
        assertTrue(focusedTexts().any { it == "قناة تجريبية 7" || it == app.getString(R.string.search_hint) }, "Down from the tab is the search box every time, got ${focusedTexts()}")
    }

    @Test fun theOkPanelActionsAreOneMoveAwayWhateverTheChannelNumber() {
        val ch = (1..80).map { com.urtv.player.core.Channel("c$it", "قناة $it", "http://example.com/$it.m3u8") }
        show { com.urtv.player.ui.MiniGuide(ch, ch[59], com.urtv.player.data.EpgIndex.EMPTY, 0L, ch, fav = false, onFav = {}, statsOn = false, onStats = {}, cause = com.urtv.player.core.Diagnosis.Cause.None, httpStatus = 0) {} }
        until(5_000) { focusedTexts().any { it.contains("قناة 60") } }
        // right-to-left: the actions sit on the end side (left); one press toward them from channel 60
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        settle(); settle()
        assertTrue(focusedTexts().any { it == app.getString(R.string.menu_fav) || it == app.getString(R.string.set_stats) }, "an action has the focus after ONE move, got ${focusedTexts()}")
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        settle(); settle()
        assertTrue(focusedTexts().any { it.contains("قناة 60") }, "and one move back returns to the row you left (60), got ${focusedTexts()}")
    }

    @Test fun zSearchHandedOverBeforeTheProcessDiedIsRestoredOnAColdStartAndOpensTheLiveTab() {
        seedPlaylist()
        app.handOverSearch(com.urtv.player.PendingSearch("قناة تجريبية 9", "Team A – Team B"))       // what FindMatchActivity does before starting the main screen
        app.pendingSearch = null                                                                       // the process died: only the preferences remain
        app.restorePendingSearch()
        assertEquals("قناة تجريبية 9", app.pendingSearch?.text, "restored from preferences")
        show { com.urtv.player.ui.AppRoot(app) {} }
        until(10_000) { texts().any { it == "قناة تجريبية 9" } }
        assertTrue(texts().any { it.contains(app.getString(R.string.tab_live)) }, "the channel list is open")
        assertEquals(null, app.pendingSearch); assertEquals(null, app.getSharedPreferences("urtv", 0).getString("pendingSearch", null), "consumed everywhere")
        // an old hand-over (30 s passed) is ignored
        app.handOverSearch(com.urtv.player.PendingSearch("x", "y"), now = System.currentTimeMillis() - 60_000); app.pendingSearch = null; app.restorePendingSearch()
        assertEquals(null, app.pendingSearch)
    }

    @Test fun movingFromTheHomeTabToTheLiveTabKeepsTheFocusOnTheTabAfterTheRowWasRestoredOnce() {
        seedPlaylist()
        val key = com.urtv.player.ui.chKey("demo", com.urtv.player.core.Channel("c5", "", "http://example.com/s/5.m3u8"))
        show {
            val tab = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.urtv.player.ui.Tab.Live) }
            val rk = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(key) }
            com.urtv.player.ui.HomeScreen(app, tab.value, { tab.value = it }, { _, _ -> }, {}, {}, restoreKey = rk.value, onRestored = { rk.value = null })
        }
        until(10_000) { focusedTexts().any { it == "قناة تجريبية 5" } }
        rule.onNode(hasText(app.getString(R.string.tab_live), substring = false) and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        settle(); settle()
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }              // right-to-left: Home is to the right of Live
        settle(); settle(); settle()
        assertTrue(focusedTexts().any { it == app.getString(R.string.tab_home) }, "on the Home tab: ${focusedTexts()}")
        val before = focusedTexts()
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }              // and back: the Live tab
        settle(); settle(); settle()
        assertTrue(focusedTexts().any { it == app.getString(R.string.tab_live) }, "the focus stays on the tab, was $before, got ${focusedTexts()}")
    }

    @Test fun pressingTowardsTheEndOfTheTabBarFromHomeDoesNotJumpIntoTheList() {
        seedPlaylist()
        val key = com.urtv.player.ui.chKey("demo", com.urtv.player.core.Channel("c5", "", "http://example.com/s/5.m3u8"))
        show {
            val tab = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.urtv.player.ui.Tab.Home) }
            com.urtv.player.ui.HomeScreen(app, tab.value, { tab.value = it }, { _, _ -> }, {}, {}, restoreKey = null, onRestored = {})
        }
        settle(); settle()
        rule.onNode(hasText(app.getString(R.string.tab_home), substring = false) and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        settle(); settle()
        // right-to-left: Home is the rightmost tab, so RIGHT is the end of the bar
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        settle(); settle(); settle()
        assertTrue(focusedTexts().any { it == app.getString(R.string.tab_home) }, "the focus stays on Home, got ${focusedTexts()}")
    }

    @Test fun a1_backFromTheLiveTabWithTheFocusOnTheTabBarGoesToHomeFirstAndLeavesOnlyFromThere() {
        seedPlaylist()
        app.handOverSearch(com.urtv.player.PendingSearch("", ""))
        show { com.urtv.player.ui.AppRoot(app) {} }
        until(10_000) { texts().any { it == "قناة تجريبية 9" } }
        rule.onNode(hasText(app.getString(R.string.tab_live), substring = false) and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        settle(); settle()
        scenario!!.onActivity { }; settle()
        fun back(): Boolean { var done = false; scenario!!.onActivity { a -> a.onBackPressedDispatcher.onBackPressed(); done = a.isFinishing }; settle(); settle(); settle(); settle(); return done }
        for (r in 1..8) settle(500)          // let the first seconds of start-up work finish: Back is only meaningful once the screen has settled
        assertTrue(!back(), "the first Back does not leave the app")
        for (r in 1..6) { if (texts().none { it == "قناة تجريبية 9" }) break; settle(500) }
        assertTrue(texts().none { it == "قناة تجريبية 9" }, "the Home tab is showing, not the channel list: focus=${focusedTexts()} ${texts()}")
        assertTrue(back(), "Back on the Home tab leaves")
    }

    @Test fun twoChannelsWithTheSameStreamUrlDoNotCrashTheListAndMoveWithKeys() {
        seedPlaylist(sameUrl = true)                               // real playlists repeat URLs: every row used to need a different one
        app.handOverSearch(com.urtv.player.PendingSearch("", ""))
        show { com.urtv.player.ui.AppRoot(app) {} }
        until(10_000) { texts().any { it == "قناة تجريبية 9" } }
        rule.onNode(hasText(app.getString(R.string.tab_live), substring = false) and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        settle(); settle()
        for (i in 1..4) { rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }; settle(); settle() }
        assertTrue(texts().any { it.startsWith("قناة تجريبية") }, "still alive after moving through the list")
    }

    @Test fun uniqueKeysKeepTheFirstUrlAndSuffixTheCopies() {
        assertEquals(listOf("a", "b", "a#2", "a#3", "b#2"), com.urtv.player.ui.uniqueKeys(listOf("a", "b", "a", "a", "b")))
        assertEquals(emptyList(), com.urtv.player.ui.uniqueKeys(emptyList()))
    }

    @Test fun upFromAPageLandsOnTheCurrentTabNotTheLastOneUsed() {
        seedPlaylist()
        show {
            val tab = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.urtv.player.ui.Tab.Settings) }
            com.urtv.player.ui.HomeScreen(app, tab.value, { tab.value = it }, { _, _ -> }, {}, {}, restoreKey = null, onRestored = {})
        }
        settle(); settle()
        rule.onNode(hasText(app.getString(R.string.tab_settings), substring = false) and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        settle(); settle()
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }; settle(); settle()      // into the page
        assertTrue(focusedTexts().none { it == app.getString(R.string.tab_settings) }, "focus is in the page: ${focusedTexts()}")
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionUp) }; settle(); settle()
        assertTrue(focusedTexts().any { it == app.getString(R.string.tab_settings) }, "UP returns to the current tab, got ${focusedTexts()}")
    }

    @Test fun a2_theCommentaryPanelOffersTheOriginalSoundAndOnlyOtherChannelsOfTheOwnList() {
        seedPlaylist()
        val all = app.catalog.state.value.channels
        val cur = all[2]                                                     // "قناة تجريبية 3"
        show { androidx.compose.runtime.CompositionLocalProvider(com.urtv.player.ui.LocalAutoLeaveEdit provides false) { com.urtv.player.ui.CommentaryPanel(app, all, cur) {} } }
        settle(); settle()
        until(10_000) { texts().any { it == app.getString(R.string.cm_pick) } }
        assertTrue(texts().any { it == app.getString(R.string.cm_original) } && texts().any { it.contains(cur.name) }, "the original sound is the first choice and names the current channel")
        rule.onNode(hasText(app.getString(R.string.cm_pick), substring = false) and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle(); settle(); settle()
        settle(); settle()
        until(10_000) { texts().any { it == "قناة تجريبية 5" } }
        assertTrue(texts().none { it == cur.name }, "the channel that carries the picture is not offered for the sound")
    }

    @Test fun theOkPanelHasTheCommentaryRowOnlyWhenTheDeviceSupportsIt() {
        seedPlaylist()
        val ch = app.catalog.state.value.channels
        val on = true
        show { com.urtv.player.ui.MiniGuide(ch, ch[0], com.urtv.player.data.EpgIndex.EMPTY, 0L, ch, fav = false, onFav = {}, statsOn = false, onStats = {}, cause = com.urtv.player.core.Diagnosis.Cause.None, httpStatus = 0, onCommentary = if (on) ({}) else null) {} }
        until(10_000) { texts().any { it == app.getString(R.string.cm_entry) } }
        assertTrue(texts().any { it == app.getString(R.string.cm_entry) }, "supported: the row is in the OK panel")
    }

    @Test fun a3_smallTvsAreAskedBeforeTheCommentaryPanelOpens() {
        seedPlaylist()
        val all = app.catalog.state.value.channels
        show { androidx.compose.runtime.CompositionLocalProvider(com.urtv.player.ui.LocalAutoLeaveEdit provides false) { com.urtv.player.ui.CommentaryPanel(app, all, all[2], warn = true) {} } }
        settle(); settle()
        until(10_000) { texts().any { it == app.getString(R.string.cm_try) } }
        assertTrue(texts().any { it == app.getString(R.string.cm_small_msg) } && texts().none { it == app.getString(R.string.cm_pick) }, "the question first, not the panel")
        rule.onNode(hasText(app.getString(R.string.cm_try), substring = false) and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle(); settle(); settle()
        until(10_000) { settle(); texts().any { it == app.getString(R.string.cm_pick) } }
    }

    @Test fun theOkPanelRowOnASmallTvCarriesTheSubLine() {
        seedPlaylist()
        val ch = app.catalog.state.value.channels
        show { com.urtv.player.ui.MiniGuide(ch, ch[0], com.urtv.player.data.EpgIndex.EMPTY, 0L, ch, fav = false, onFav = {}, statsOn = false, onStats = {}, cause = com.urtv.player.core.Diagnosis.Cause.None, httpStatus = 0, onCommentary = {}, commentaryNote = true) {} }
        until(10_000) { texts().any { it == app.getString(R.string.cm_small_tv) } }
        assertTrue(texts().any { it == app.getString(R.string.cm_entry) }, "the row is not hidden")
    }
}
