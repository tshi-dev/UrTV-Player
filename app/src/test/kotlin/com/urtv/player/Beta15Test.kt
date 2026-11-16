package com.urtv.player

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import com.urtv.player.data.BufferMode
import com.urtv.player.player.AdaptiveLoadControl
import com.urtv.player.player.PlaybackTuning
import com.urtv.player.player.Zoom
import com.urtv.player.ui.SubStyle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Beta 15: a deeper live cushion after stalls, «steadier quality», subtitle sizes and the picture zoom of the sync card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Beta15Test {
    @Test fun theCushionGrowsAfterStallsIsCappedAndShrinksWhenTheLineIsQuiet() {
        val g = PlaybackTuning.BufferGrowth()
        assertEquals(0, g.extraMs(0))
        assertEquals(3_000, g.onStall(1_000)); assertEquals(6_000, g.onStall(2_000)); assertEquals(9_000, g.onStall(3_000)); assertEquals(9_000, g.onStall(4_000), "capped")
        assertEquals(9_000, g.extraMs(4_000 + 60_000), "a minute of quiet is not enough")
        assertEquals(6_000, g.extraMs(4_000 + 5 * 60_000 + 1), "five quiet minutes give one step back")
        assertEquals(3_000, g.extraMs(4_000 + 10 * 60_000 + 2)); assertEquals(0, g.extraMs(4_000 + 15 * 60_000 + 3))
    }

    @Test fun steadierQualityIsDeeperThanNormalAndOnlyForLive() {
        val n = PlaybackTuning.buffers(BufferMode.Normal, true, false); val s = PlaybackTuning.buffers(BufferMode.Normal, true, false, steady = true)
        assertTrue(s.minMs > n.minMs && s.maxMs > n.maxMs && s.rebufMs > n.rebufMs)
        assertTrue(s.startMs <= 2_000, "still a quick picture")
        assertEquals(PlaybackTuning.buffers(BufferMode.Normal, false, false), PlaybackTuning.buffers(BufferMode.Normal, false, false, steady = true), "films are untouched")
        assertTrue(PlaybackTuning.STEADY_MAX_H <= 720 && PlaybackTuning.STEADY_BANDWIDTH_FRACTION < 0.7f)
    }

    private fun params(buffered: Long, rebuffering: Boolean) = LoadControl.Parameters(PlayerId.UNSET, Timeline.EMPTY, LoadControl.EMPTY_MEDIA_PERIOD_ID, 0, buffered * 1000, 1f, true, rebuffering, 0)

    @Test fun afterAStallTheRestartWaitsForTheDeeperCushionAndLoadingKeepsFilling() {
        val b = PlaybackTuning.buffers(BufferMode.Normal, true, false)
        val base = DefaultLoadControl.Builder().setBufferDurationsMs(b.minMs, b.maxMs, b.startMs, b.rebufMs).build()
        val g = PlaybackTuning.BufferGrowth(); var now = 0L
        val lc = AdaptiveLoadControl(base, g, b.minMs, b.maxMs, b.rebufMs) { now }
        // no stall yet: the default rules (restart at the normal rebuffer cushion)
        assertTrue(lc.shouldStartPlayback(params(b.rebufMs.toLong(), true)))
        g.onStall(now); g.onStall(now)                                       // 6 s deeper
        assertFalse(lc.shouldStartPlayback(params(b.rebufMs + 3_000L, true)), "3.5 s + 3 s is not yet the cushion asked for (3.5 s + 6 s)")
        assertTrue(lc.shouldStartPlayback(params(b.rebufMs + 6_000L, true)))
        assertTrue(lc.shouldStartPlayback(params(b.startMs.toLong(), false)), "the first start of a channel is as quick as before")
        assertTrue(lc.shouldContinueLoading(params(b.minMs + 5_000L, false)), "keeps loading above the old floor until the deeper floor")
        now += 6 * 60_000; assertTrue(g.extraMs(now) < 6_000, "and relaxes again when the line stays quiet")
    }

    @Test fun everyLoadControlMethodThePlayerCallsAnswersLikeTheDefaultOne() {
        val base = DefaultLoadControl.Builder().build()
        val lc = AdaptiveLoadControl(base, PlaybackTuning.BufferGrowth(), 8_000, 30_000, 3_500)
        assertEquals(base.getBackBufferDurationUs(PlayerId.UNSET), lc.getBackBufferDurationUs(PlayerId.UNSET))
        assertEquals(base.retainBackBufferFromKeyframe(PlayerId.UNSET), lc.retainBackBufferFromKeyframe(PlayerId.UNSET))
        lc.onPrepared(PlayerId.UNSET); lc.onStopped(PlayerId.UNSET); lc.onReleased(PlayerId.UNSET)
        assertEquals(base.shouldContinuePreloading(Timeline.EMPTY, LoadControl.EMPTY_MEDIA_PERIOD_ID, 0), lc.shouldContinuePreloading(Timeline.EMPTY, LoadControl.EMPTY_MEDIA_PERIOD_ID, 0))
        assertTrue(lc.allocator === base.allocator)
    }

    @Test fun subtitlesAreSmallerThanTheOldTitleSizeFollowTheSettingAndTheTvsOwnCaptionScale() {
        assertTrue(SubStyle.sizeSp(1, 1f) < 34f, "normal is below the 34 sp title size that was too big")
        val sizes = (0..3).map { SubStyle.sizeSp(it, 1f) }
        assertEquals(sizes.sorted(), sizes, "bigger setting, bigger text"); assertTrue(sizes[0] < sizes[1] && sizes[2] < sizes[3])
        assertEquals(SubStyle.sizeSp(1, 1f) * 1.5f, SubStyle.sizeSp(1, 1.5f), 0.01f, "the TV's caption scale applies too")
        assertEquals(SubStyle.sizeSp(1, 2f), SubStyle.sizeSp(1, 9f), "clamped"); assertEquals(SubStyle.sizeSp(3, 1f), SubStyle.sizeSp(9, 1f), "preset clamped")
    }

    @Test fun theSyncCardZoomHasThreeLevelsAndSixCorners() {
        assertEquals(1f, Zoom().scale)
        assertEquals(listOf(0f to 0f, 0.5f to 0f, 1f to 0f, 0f to 1f, 0.5f to 1f, 1f to 1f), (0..5).map { Zoom(2, it).let { z -> z.originX to z.originY } })
        assertEquals(3f, Zoom(3).scale)
    }
}
