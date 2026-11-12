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

    @Test fun onlyUrtvPackagesWithTheSingleReleaseCertAreAccepted() {
        val pin = "AA:BB:CC:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:00:11:22"
        val same = "aabbcc00112233445566778899aabbccddeeff00112233445566778899001122"
        val I = com.urtv.player.data.Integrity
        assertTrue(I.callerAllowed("sa.urtv.home", listOf(same), pin)); assertTrue(I.callerAllowed("sa.urtv.screensaver", listOf(same), pin))
        assertEquals(false, I.callerAllowed("com.evil", listOf(same), pin), "right cert, wrong package")
        assertEquals(false, I.callerAllowed("sa.urtv.home", listOf("00" + same.drop(2)), pin), "right package, another cert")
        assertEquals(false, I.callerAllowed("sa.urtv.home", listOf(same, same), pin), "must be a single signer")
        assertEquals(false, I.callerAllowed("sa.urtv.home", emptyList(), pin)); assertEquals(false, I.callerAllowed(null, listOf(same), pin))
        assertEquals(false, I.callerAllowed("sa.urtv.home", listOf(same), ""), "no pin configured: nobody accepted")
    }

    @Test fun realPinIsSetAndMatchesOnlyThatCertificate() {
        val I = com.urtv.player.data.Integrity
        val real = "bc71eeacc4614d3c165129898b8fea979c75f8918c355070e84436e3183dab2b"
        assertEquals(real, I.URTV_CERT_SHA256)
        assertTrue(I.callerAllowed("sa.urtv.home", listOf(real.uppercase())))
        assertEquals(false, I.callerAllowed("sa.urtv.home", listOf("13ade7d009a07f5ba5bab0b7734bd449df10dd47b53868dfa28e44cd8069dfea")), "the Player's own key is not UrTV's")
    }

    @Test fun theScreenUsesUrtvsLanguageEvenBeforeThePlayerWasEverOpened() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("urtv", 0).edit().clear().commit()          // nothing remembered about UrTV, language on Auto, the TV itself is English
        val L = com.urtv.player.data.Language
        try {
            L.readerForTests = { "ar" }
            assertEquals("en", localized(ctx, fresh = false).resources.configuration.locales[0].language, "(what the screen used to do: the TV's language)")
            assertEquals("ar", localizedForCaller(ctx).resources.configuration.locales[0].language, "UrTV is Arabic: so is this screen")
            L.readerForTests = { "en" }
            assertEquals("en", localizedForCaller(ctx).resources.configuration.locales[0].language)
            L.readerForTests = { null }
            assertEquals("en", localizedForCaller(ctx).resources.configuration.locales[0].language, "no answer: the remembered one or the TV's")
        } finally { L.readerForTests = null }
    }
}
