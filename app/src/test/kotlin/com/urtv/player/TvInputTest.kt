package com.urtv.player

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.ComponentActivity
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Before
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.urtv.player.player.CachingDns
import com.urtv.player.player.LoadTrace
import com.urtv.player.ui.FocusField
import com.urtv.player.ui.UrtvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UrtvApp::class)
class TvInputTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<UrtvApp>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
    }
    @After fun tearDown() { scenario?.close() }

    @Test fun aTextFieldIsOnlyEditableAfterOkNotJustFocused() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { sc -> sc.onActivity { a -> a.setContent { androidx.compose.runtime.CompositionLocalProvider(com.urtv.player.ui.LocalAutoLeaveEdit provides false) { UrtvTheme(com.urtv.player.data.Appearance(), 100) { var v by remember { mutableStateOf("abc") }; FocusField(v, { v = it }, "label") } } } } }
        rule.mainClock.autoAdvance = false       // the focus animations never settle in the test environment: drive the clock by hand
        fun settle(ms: Long = 300) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); rule.mainClock.advanceTimeBy(ms); Thread.sleep(40) }
        settle(); settle()
        // nothing here waits for Compose to be "idle" (it never is with the clock paused, and a slow machine decides how long that hang lasts): nodes are read directly
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("abc")).fetchSemanticsNodes().isNotEmpty(), "the field is on screen")
        assertEquals(0, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size, "no editor (and so no keyboard) before OK")
        val ok = rule.onAllNodes(androidx.compose.ui.test.hasText("abc") and androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().first().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action   // OK / D-pad centre
        val focus = rule.onAllNodes(androidx.compose.ui.test.hasText("abc") and androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().first().config[androidx.compose.ui.semantics.SemanticsActions.RequestFocus].action
        scenario!!.onActivity { focus?.invoke() }; settle(); settle()
        scenario!!.onActivity { ok?.invoke() }
        settle(); settle(); settle()
        assertEquals(1, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size, "OK opens the editor")
    }

    @Test fun dnsAnswersAreKeptForTheTtlOnly() {
        var clock = 0L; var calls = 0
        val upstream = object : okhttp3.Dns { override fun lookup(hostname: String): List<InetAddress> { calls++; return listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))) } }
        val d = CachingDns(ttlMs = 1000, delegate = upstream, now = { clock })
        d.lookup("a.example"); d.lookup("a.example"); d.lookup("b.example")
        assertEquals(2, calls, "second lookup of the same host is cached")
        clock = 2000; d.lookup("a.example"); assertEquals(3, calls, "expired after the ttl")
    }

    @Test fun loadBreakdownSaysWhereTheTimeWent() {
        val a = LoadTrace.Req().apply { start = 1000; dns = 40; connect = 120; headersAt = 1500; endAt = 1700 }      // manifest
        val b = LoadTrace.Req().apply { start = 1700; headersAt = 2600; endAt = 3000 }                                // first media bytes
        val r = LoadTrace.compute(listOf(a, b), t0 = 900, now = 4000)
        assertEquals(40, r.dnsMs); assertEquals(120, r.connectMs); assertEquals(700, r.manifestMs); assertEquals(900, r.firstBytesMs)
        assertEquals(1400, r.decodeMs, "after the last first byte until the picture"); assertEquals(3100, r.totalMs); assertEquals(2, r.requests)
        assertTrue(r.line.contains("= 3100 ms"))
    }
}
