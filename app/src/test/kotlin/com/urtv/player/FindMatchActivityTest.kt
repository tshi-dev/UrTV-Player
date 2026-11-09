package com.urtv.player

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FindMatchActivityTest {
    private fun intent(action: String? = FindMatchActivity.ACTION) =
        Intent(ApplicationProvider.getApplicationContext(), FindMatchActivity::class.java).setAction(action).putExtra("home_en", "A").putExtra("away_en", "B")

    @Test fun ignoredWithoutAVerifiableCaller() {
        // started like any other app would: no calling package that can be verified, so it closes at once
        val c = Robolectric.buildActivity(FindMatchActivity::class.java, intent()).create()
        assertTrue(c.get().isFinishing)
    }

    @Test fun ignoredForOtherActions() {
        val c = Robolectric.buildActivity(FindMatchActivity::class.java, intent("android.intent.action.VIEW")).create()
        assertTrue(c.get().isFinishing)
    }

    @Test fun extrasAreReadDefensivelyAndCapped() {
        val i = intent().putExtra("kickoff", "not a long").putExtra("channels", arrayOf("a", null, "x".repeat(500)) + Array(50) { "c$it" })
            .putExtra("home_ar", "ن".repeat(1000)).putExtra("away_en", 42)
        val r = FindMatchActivity.readRequest(i, 1_700_000_000_000L)
        assertEquals(80, r.homeAr.length); assertEquals("", r.awayEn)
        assertEquals(12, r.channels.size); assertTrue(r.channels.all { it.length <= 80 })
        assertEquals(1_700_000_000_000L, r.kickoffMs)
    }
}
