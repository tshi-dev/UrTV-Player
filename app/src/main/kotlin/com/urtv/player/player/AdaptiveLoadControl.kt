package com.urtv.player.player

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * ExoPlayer's own load control with one addition: after stalls ([PlaybackTuning.BufferGrowth]) it asks for a deeper cushion, both when it restarts after
 * a stall and while it keeps loading, without rebuilding the player (so a stutter never costs a re-zap). Everything else is the default behaviour.
 */
class AdaptiveLoadControl(
    private val base: DefaultLoadControl, private val growth: PlaybackTuning.BufferGrowth, private val minMs: Int, private val maxMs: Int, private val rebufMs: Int,
    private val now: () -> Long = System::currentTimeMillis,
) : LoadControl by base {
    // Kotlin's `by` hands on only the abstract member: the interface's other methods are explicit here (the default ones would answer «not implemented»)
    override fun onPrepared(playerId: androidx.media3.exoplayer.analytics.PlayerId) = base.onPrepared(playerId)
    override fun onTracksSelected(p: LoadControl.Parameters, groups: androidx.media3.exoplayer.source.TrackGroupArray, selections: Array<androidx.media3.exoplayer.trackselection.ExoTrackSelection>) = base.onTracksSelected(p, groups, selections)
    override fun onStopped(playerId: androidx.media3.exoplayer.analytics.PlayerId) = base.onStopped(playerId)
    override fun onReleased(playerId: androidx.media3.exoplayer.analytics.PlayerId) = base.onReleased(playerId)
    override fun getBackBufferDurationUs(playerId: androidx.media3.exoplayer.analytics.PlayerId): Long = base.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: androidx.media3.exoplayer.analytics.PlayerId): Boolean = base.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinuePreloading(timeline: androidx.media3.common.Timeline, id: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean = base.shouldContinuePreloading(timeline, id, bufferedDurationUs)

    private fun extraUs(): Long = growth.extraMs(now()).coerceAtMost((maxMs - minMs - 2_000).coerceAtLeast(0).toLong()) * 1000

    override fun shouldStartPlayback(p: LoadControl.Parameters): Boolean {
        val e = extraUs()
        if (e > 0 && p.rebuffering && p.bufferedDurationUs < (rebufMs * 1000L + e)) return false      // restart after a stall only with the deeper cushion
        return base.shouldStartPlayback(p)
    }

    override fun shouldContinueLoading(p: LoadControl.Parameters): Boolean {
        val e = extraUs()
        if (e > 0 && p.bufferedDurationUs < minMs * 1000L + e) return true                              // keep filling until the deeper floor is reached
        return base.shouldContinueLoading(p)
    }
}
