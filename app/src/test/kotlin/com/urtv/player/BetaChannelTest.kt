package com.urtv.player

import androidx.test.core.app.ApplicationProvider
import com.urtv.player.core.SelfUpdate
import com.urtv.player.data.Metrics
import com.urtv.player.data.SelfDownloader
import com.urtv.player.data.SelfUpdater
import com.urtv.player.data.Update
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The beta channel's mechanism: the check against a scripted server, the decision with UrTV installed, and the on-device measurements. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BetaChannelTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var srv: ServerSocket? = null
    @After fun stop() { srv?.close(); Metrics.enabled = false; Metrics.reset(ctx) }

    private fun serve(files: Map<String, String>): String {
        val s = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1")).also { srv = it }
        Thread { while (!s.isClosed) runCatching { s.accept().use { c ->
            val path = c.getInputStream().bufferedReader().readLine().orEmpty().split(' ').getOrElse(1) { "/" }
            val body = files[path]
            val o = c.getOutputStream()
            if (body == null) o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            else { val b = body.toByteArray(); o.write("HTTP/1.1 200 OK\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); o.write(b) }
            o.flush() } } }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:${s.localPort}"
    }
    private val localDl = SelfDownloader(allow = { u -> u.startsWith("http://127.0.0.1:") })

    @Test fun theCheckReadsTheStableAndTheBetaAssetAndSaysWhyWhenThereIsNothing() {
        val base = serve(mapOf("/stable" to "150 0.9.15\n", "/beta" to "173 0.9.16-beta16\n", "/oldbeta" to "160 0.9.16-beta9"))
        // beta on: the beta is newer than the stable and than the installed 0.9.15
        val on = SelfUpdater.check(150, "0.9.15", true, localDl, "$base/stable", "$base/beta")
        assertEquals(173L, on.release?.code); assertEquals(SelfUpdate.Reason.Newer, on.reason)
        // beta off: stable only, nothing newer, and the reason says so
        val off = SelfUpdater.check(150, "0.9.15", false, localDl, "$base/stable", "$base/beta")
        assertNull(off.release); assertEquals(SelfUpdate.Reason.UpToDate, off.reason)
        // an installed beta with the switch OFF is never moved down to the older stable
        val keep = SelfUpdater.check(172, "0.9.16-beta15", false, localDl, "$base/stable", "$base/beta")
        assertNull(keep.release); assertEquals(SelfUpdate.Reason.NoDowngrade, keep.reason)
        // an older beta asset than the installed build is not offered either
        assertNull(SelfUpdater.check(172, "0.9.16-beta15", true, localDl, "$base/stable", "$base/oldbeta").release)
        // no beta published: said so; server unreachable: said so
        assertEquals(SelfUpdate.Reason.NoBetaYet, SelfUpdater.check(150, "0.9.15", true, localDl, "$base/stable", "$base/missing").reason)
        srv?.close()
        assertEquals(SelfUpdate.Reason.Network, SelfUpdater.check(150, "0.9.15", true, localDl, "$base/stable", "$base/beta").reason)
    }

    @Test fun withTheBetaSwitchOnTheBetaIsOfferedEvenWhenUrtvIsInstalledAndOffIsUnchanged() {
        val urtv = Update.State(Update.Kind.UpToDate)
        val rel = SelfUpdate.Release("0.9.16-beta16", 173, SelfUpdate.BETA_APK_URL, SelfUpdate.BETA_SHA_URL)
        val on = Update.decide(urtv, true, beta = true) { rel }
        assertEquals(Update.Kind.Available, on.kind); assertEquals(Update.Via.Self, on.offer?.via); assertEquals(SelfUpdate.BETA_APK_URL, on.offer?.apkUrl)
        assertEquals(urtv, Update.decide(urtv, true, beta = false) { rel }, "switch off: UrTV stays the authority and the beta lookup is never used")
        var asked = false
        assertEquals(Update.Kind.UpToDate, Update.decide(Update.State(Update.Kind.NoUrtv), false, beta = true) { asked = true; rel }.kind); assertTrue(!asked, "a build that may not self-update never looks")
        assertEquals(Update.Kind.UpToDate, Update.decide(urtv, true, beta = true) { null }.kind, "nothing newer: whatever UrTV said")
    }

    @Test fun stableBuildsMeasureNothingAndBetaBuildsKeepOnlyNumbers() {
        Metrics.enabled = false; Metrics.reset(); Metrics.zap(900); Metrics.crash(ctx, IllegalStateException("x"))
        assertEquals(0, Metrics.snapshot().zapMs.size + Metrics.snapshot().crashes, "off in a stable build")
        Metrics.enabled = true
        Metrics.timing("cold_start_first_frame", 870); Metrics.timing("tab_open_Live", 120); Metrics.timing("search", 30); Metrics.zap(900); Metrics.zap(1800); Metrics.playlist(4200, 5240); Metrics.epg(3100)
        repeat(100) { Metrics.second(false, 1) }; repeat(5) { Metrics.second(true, 0) }; Metrics.rebuffer(); Metrics.decoderError(); Metrics.play(); Metrics.failure("Server"); Metrics.failure("Server"); Metrics.memory(180, 512, 420)
        Metrics.crash(ctx, OutOfMemoryError("secret http://h/u?p=1"))
        val s = Metrics.snapshot()
        assertEquals(870, s.coldStartMs); assertEquals(listOf(120L), s.tabOpenMs, "only the tab opens, not every timing"); assertEquals(listOf(900L, 1800L), s.zapMs)
        assertEquals(100, s.playSeconds); assertEquals(5, s.bufferingSeconds); assertEquals(100, s.dropped); assertEquals(mapOf("Server" to 2), s.failures)
        assertEquals(1, s.crashes); assertEquals("OutOfMemoryError", s.lastCrash, "the class name only, never the message")
        // it survives a restart of the process (the file is read back)
        val before = Metrics.snapshot(); Metrics.save(ctx, force = true, sync = true)
        Metrics.reset(); assertEquals(0, Metrics.snapshot().crashes)
        Metrics.load(ctx); assertEquals(before, Metrics.snapshot())
        // and the stored file holds numbers and the allowed words only
        val stored = ctx.getSharedPreferences("urtv_metrics", android.content.Context.MODE_PRIVATE).getString("m", "")!!
        for (bad in listOf("http", "secret", "://", "@")) assertTrue(bad !in stored, "found $bad")
    }

    @Test fun theSettingsAboutPageHasTheBetaSwitchTheCheckRowAndTheAssessmentEntry() {
        val src = File("src/main/kotlin/com/urtv/player/ui/Settings.kt").readText()
        val about = src.substringAfter("Section.About -> item {")
        assertTrue("BetaRows(app)" in about && "assess_title" in about)
        assertTrue("setBetaChannel(!on)" in src && "upd_check" in src)
        assertTrue(!ctx.getSharedPreferences("urtv", android.content.Context.MODE_PRIVATE).getBoolean("betaChannel", false), "off by default")
    }
}
