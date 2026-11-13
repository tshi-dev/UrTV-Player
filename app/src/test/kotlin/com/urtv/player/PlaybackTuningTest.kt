package com.urtv.player

import com.urtv.player.data.BufferMode
import com.urtv.player.player.PlaybackTuning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackTuningTest {
    @Test fun liveStartsFastButRecoversIntoALargerCushion() {
        for (m in BufferMode.entries) { val b = PlaybackTuning.buffers(m, live = true, lowRam = false); assertTrue(b.startMs <= 1_500, "$m start"); assertTrue(b.rebufMs > b.startMs, "$m rebuffer > start") }
        assertEquals(1_000, PlaybackTuning.buffers(BufferMode.Normal, true, false).startMs)
    }

    @Test fun onDemandBuffersMoreThanLive() = assertTrue(PlaybackTuning.buffers(BufferMode.Low, false, false).startMs > PlaybackTuning.buffers(BufferMode.Low, true, false).startMs)

    @Test fun lowRamCapsBufferBytesAndDuration() {
        val b = PlaybackTuning.buffers(BufferMode.High, true, lowRam = true)
        assertTrue(b.maxMs <= 15_000 && b.targetBytes in 1..(16 * 1024 * 1024))
        assertEquals(-1, PlaybackTuning.buffers(BufferMode.High, true, false).targetBytes)
    }

    @Test fun neverAsksForMorePixelsThanTheScreenHas() {
        assertEquals(1920 to 1080, PlaybackTuning.maxVideoSize(1920, 1080))
        assertEquals(3840 to 2160, PlaybackTuning.maxVideoSize(3840, 2160))
        assertEquals(1280 to 720, PlaybackTuning.maxVideoSize(1280, 720))
        assertEquals(1920 to 1080, PlaybackTuning.maxVideoSize(1080, 1920), "orientation does not matter")
        assertEquals(1920 to 1080, PlaybackTuning.maxVideoSize(0, 0), "unknown display falls back to 1080p")
        assertEquals(1280 to 720, PlaybackTuning.maxVideoSize(800, 480), "small panels still get 720p")
    }

    @Test fun bandwidthSeedIsClamped() {
        assertEquals(3_000_000L, PlaybackTuning.seedBitrate(0)); assertEquals(500_000L, PlaybackTuning.seedBitrate(10)); assertEquals(50_000_000L, PlaybackTuning.seedBitrate(9_999_999_999)); assertEquals(8_000_000L, PlaybackTuning.seedBitrate(8_000_000))
    }

    @Test fun repeatedRebuffersTriggerADowngradeOnlyWithinTheWindow() {
        val t = PlaybackTuning.RebufferTracker(windowMs = 60_000, limit = 3)
        assertFalse(t.record(0)); assertFalse(t.record(20_000)); assertTrue(t.record(40_000), "3 stalls in 60 s")
        t.reset(); assertFalse(t.record(0)); assertFalse(t.record(50_000)); assertFalse(t.record(110_000), "old stalls fall out of the window")
        assertEquals(3_500_000L, PlaybackTuning.downgradeCap(5_000_000)); assertEquals(500_000L, PlaybackTuning.downgradeCap(100_000))
    }

    @Test fun liveEdgeCatchUpOnlyWhenFarBehindAndNotTooOften() {
        assertFalse(PlaybackTuning.shouldSeekToLive(8_000, 60_000)); assertFalse(PlaybackTuning.shouldSeekToLive(40_000, 5_000))
        assertTrue(PlaybackTuning.shouldSeekToLive(90_000, 60_000))
    }

    // ---- budgets that can be pinned without a TV (real first-frame and zap times need a device: docs/PERFORMANCE.md)
    @Test fun startAndRecoveryBudgetsStayTight() {
        val normalLive = PlaybackTuning.buffers(com.urtv.player.data.BufferMode.Normal, live = true, lowRam = false)
        assertTrue(normalLive.startMs <= 1500, "live start buffer ${normalLive.startMs} ms")
        assertTrue(PlaybackTuning.buffers(com.urtv.player.data.BufferMode.Low, true, false).startMs <= 1000)
        assertTrue(PlaybackTuning.buffers(com.urtv.player.data.BufferMode.Normal, true, true).maxMs <= 15_000, "low-RAM TVs keep at most 15 s")
        // worst case time before a dead channel is declared failed: stall limit + sum of back-off delays
        val c = com.urtv.player.player.PlayerController
        var wait = 0L; for (n in 1..c.MAX_ATTEMPTS) wait += minOf(600L shl (n - 1), 8_000L) + c.STALL_LIMIT_SEC * 1000L
        assertTrue(wait <= 200_000L, "give-up time $wait ms (about 3 min today)")
        assertTrue(c.STALL_LIMIT_SEC <= 15 && c.FROZEN_LIMIT_SEC <= 8, "a frozen picture is noticed within 8 s, a stall within 15 s")
    }
}
