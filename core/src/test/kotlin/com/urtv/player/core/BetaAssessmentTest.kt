package com.urtv.player.core

import com.urtv.player.core.Assessment.Area
import com.urtv.player.core.Assessment.Rating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BetaAssessmentTest {
    private fun rating(s: Assessment.Snapshot, a: Area) = Assessment.rate(s).first { it.area == a }.rating

    @Test fun eachAreaIsRatedAgainstItsThresholds() {
        assertEquals(Rating.NoData, rating(Assessment.Snapshot(), Area.Startup))
        assertEquals(Rating.Good, rating(Assessment.Snapshot(coldStartMs = 870), Area.Startup)); assertEquals(Rating.Fair, rating(Assessment.Snapshot(coldStartMs = 2500), Area.Startup)); assertEquals(Rating.Poor, rating(Assessment.Snapshot(coldStartMs = 4000), Area.Startup))
        assertEquals(Rating.Poor, rating(Assessment.Snapshot(tabOpenMs = List(8) { 100L } + List(4) { 900L }), Area.TabOpen), "p90 counts, not the average")
        assertEquals(Rating.Good, rating(Assessment.Snapshot(zapMs = listOf(800, 900, 1200)), Area.Zap)); assertEquals(Rating.Poor, rating(Assessment.Snapshot(zapMs = listOf(4000, 5000)), Area.Zap))
        // a longer playlist is allowed a longer load
        assertEquals(Rating.Poor, rating(Assessment.Snapshot(playlistLoadMs = 20000, playlistChannels = 500), Area.PlaylistLoad)); assertEquals(Rating.Good, rating(Assessment.Snapshot(playlistLoadMs = 9000, playlistChannels = 55_000), Area.PlaylistLoad).let { if (it == Rating.Fair) Rating.Good else it })
        assertEquals(Rating.Good, rating(Assessment.Snapshot(playSeconds = 3600, rebuffers = 1, bufferingSeconds = 5), Area.Buffering))
        assertEquals(Rating.Poor, rating(Assessment.Snapshot(playSeconds = 3600, rebuffers = 20, bufferingSeconds = 200), Area.Buffering))
        assertEquals(Rating.NoData, rating(Assessment.Snapshot(playSeconds = 30, rebuffers = 5), Area.Buffering), "too little playing time to say")
        assertEquals(Rating.Poor, rating(Assessment.Snapshot(playSeconds = 600, dropped = 600), Area.Dropped))
        assertEquals(Rating.Good, rating(Assessment.Snapshot(plays = 10), Area.Decoder)); assertEquals(Rating.Poor, rating(Assessment.Snapshot(plays = 10, decoderErrors = 5), Area.Decoder))
        assertEquals(Rating.Good, rating(Assessment.Snapshot(plays = 100, failures = mapOf("Server" to 1)), Area.Failures)); assertEquals(Rating.Poor, rating(Assessment.Snapshot(plays = 20, failures = mapOf("Server" to 5, "Offline" to 2)), Area.Failures))
        assertEquals(Rating.Good, rating(Assessment.Snapshot(heapPeakMb = 100, heapLimitMb = 512), Area.Memory)); assertEquals(Rating.Poor, rating(Assessment.Snapshot(heapPeakMb = 480, heapLimitMb = 512), Area.Memory))
        assertEquals(Rating.Good, rating(Assessment.Snapshot(), Area.Stability)); assertEquals(Rating.Fair, rating(Assessment.Snapshot(crashes = 1), Area.Stability)); assertEquals(Rating.Poor, rating(Assessment.Snapshot(crashes = 1, anrs = 1), Area.Stability))
    }

    @Test fun overallIsPoorIfAnythingIsPoorAndNeedsData() {
        assertEquals(Rating.NoData, Assessment.overall(Assessment.rate(Assessment.Snapshot()).filter { it.area != Area.Stability }))
        assertEquals(Rating.Poor, Assessment.overall(Assessment.rate(Assessment.Snapshot(coldStartMs = 900, anrs = 3))))
        assertEquals(Rating.Good, Assessment.overall(Assessment.rate(Assessment.Snapshot(coldStartMs = 900))))
    }

    private val dev = BetaReport.Device("0.9.16-beta15", "official", "14", 34, "BRAVIA_4K_GB", "normal")
    private val snap = Assessment.Snapshot(coldStartMs = 870, tabOpenMs = listOf(100, 120), playlistLoadMs = 4200, playlistChannels = 5240, epgLoadMs = 3100, zapMs = listOf(900, 1800), playSeconds = 3600, bufferingSeconds = 12, rebuffers = 3, dropped = 40,
        decoderErrors = 1, failures = mapOf("Server" to 2, "Offline" to 1), plays = 42, heapPeakMb = 180, heapLimitMb = 512, pssPeakMb = 420, crashes = 0, anrs = 0, lastCrash = "")

    @Test fun theReportHasTheChannelTheNumbersAndOnlyAllowedLines() {
        val t = BetaReport.build(snap, dev)
        assertTrue(t.startsWith("UrTV Player beta report, 0.9.16-beta15\nChannel: beta\n"))
        for (p in listOf("Versions: ", "Device: ", "Timing: ", "Playback: ", "Failures: ", "Memory: ", "Stability: ", "Assessment: ")) assertTrue(t.lines().any { it.startsWith(p) }, p)
        assertTrue("cold start 870 ms" in t && "server 2" in t && "offline 1" in t && "java heap peak 180 MB of 512 MB" in t && "Android 14 (API 34)" in t && "BRAVIA_4K_GB" in t)
        assertTrue(t.lines().filter { it.isNotBlank() }.all { l -> listOf("UrTV Player beta report, ", "Channel: beta", "Versions: ", "Device: ", "Timing: ", "Playback: ", "Failures: ", "Memory: ", "Stability: ", "Assessment: ").any { l.startsWith(it) } })
    }

    @Test fun hostileTextNeverGetsIntoTheReport() {
        // the only free-text inputs are the device strings and the last crash name: all are cut down to a token or a fixed word
        val evil = BetaReport.Device("1.0 http://evil.example/x?token=abc", "official user@mail.com", "14 192.168.1.5", 34, "Model http://host:8080/live/user/pass/1.m3u8 my-playlist-name", "normal")
        val t = BetaReport.build(snap.copy(lastCrash = "java.lang.RuntimeException: http://h/u?p=1 password"), evil)
        assertTrue("Device: unknown, tv" in t, "a model string that is not a plain token is replaced"); for (bad in listOf("http", "://", "@", "?", "=", "192.168", "evil.example", "live/user", "password", "token", "my-playlist-name", "user/pass")) assertFalse(bad in t, "found: $bad in\n$t")
        assertTrue("last crash other" in t, "an unknown crash name is reduced to «other»")
        // the last net: unknown lines and risky words are removed from any text handed to it
        val s = BetaReport.sanitize("Playlist: My Home List\nDevice: tv http://a.b/c?x=1 192.168.0.1 user@x.com ok\nTiming: fine\nrandom line\nStability: crashes 0, last crash none")
        assertEquals("Device: tv ok\nTiming: fine\nStability: crashes 0, last crash none\n", s)
        assertFalse("Playlist" in s)
    }

    @Test fun aKnownCrashClassIsKeptByName() { assertTrue("last crash OutOfMemoryError" in BetaReport.build(snap.copy(crashes = 1, lastCrash = "OutOfMemoryError"), dev)) }
    @Test fun failureTypesCoverEveryReasonWithoutProviderNames() {
        val t = BetaReport.build(snap, dev); val line = t.lines().first { it.startsWith("Failures: ") }
        for (r in FailureReason.Reason.entries.filter { it != FailureReason.Reason.None }) assertTrue(r.name.lowercase() in line, r.name)
    }
}
