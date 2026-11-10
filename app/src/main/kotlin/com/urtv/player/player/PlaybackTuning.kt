package com.urtv.player.player

import com.urtv.player.data.BufferMode

/** Pure playback rules (unit-tested): buffer sizing, resolution caps, bandwidth seed and the "too many rebuffers" trigger. */
object PlaybackTuning {
    data class Buffers(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufMs: Int, val targetBytes: Int)

    /**
     * Live starts playing after ~1 s of media (fast zap) but asks for a larger buffer after a stall, so a flaky connection
     * recovers into a safe cushion. On-demand needs a bigger start buffer. Low-RAM boxes get a byte cap.
     */
    fun buffers(mode: BufferMode, live: Boolean, lowRam: Boolean): Buffers {
        val b = if (live) when (mode) {
            BufferMode.Low -> Buffers(2_000, 8_000, 800, 2_000, -1)
            BufferMode.Normal -> Buffers(5_000, 20_000, 1_000, 3_000, -1)
            BufferMode.High -> Buffers(15_000, 50_000, 1_500, 5_000, -1)
        } else Buffers(15_000, 50_000, 2_500, 5_000, -1)
        return if (lowRam) b.copy(maxMs = minOf(b.maxMs, 15_000), minMs = minOf(b.minMs, 10_000), targetBytes = 8 * 1024 * 1024) else b
    }

    /**
     * Server media (Jellyfin/Emby/Plex files, often tens of Mbit/s): start after 1.5 s of media, keep up to a minute ahead,
     * but bound by BYTES (time-over-size is switched off for this mode), smaller on low-RAM boxes.
     */
    fun serverBuffers(lowRam: Boolean): Buffers =
        if (lowRam) Buffers(10_000, 30_000, 1_500, 3_000, 24 * 1024 * 1024) else Buffers(20_000, 60_000, 1_500, 3_000, 96 * 1024 * 1024)

    /** Never ask for more pixels than the screen shows (no 4K stream on a 1080p TV); a 4K screen may use 4K. */
    fun maxVideoSize(displayW: Int, displayH: Int): Pair<Int, Int> {
        val w = maxOf(displayW, displayH); val h = minOf(displayW, displayH)
        return if (w <= 0 || h <= 0) 1920 to 1080 else maxOf(w, 1280) to maxOf(h, 720)
    }

    /** Starting bandwidth estimate: last session's, clamped to a sane range; a default for first launch. */
    fun seedBitrate(saved: Long, default: Long = 3_000_000L): Long = if (saved <= 0) default else saved.coerceIn(500_000L, 50_000_000L)

    /** Lower cap after repeated rebuffers: 70 % of the current video bitrate, never below 500 kbit/s. */
    fun downgradeCap(currentBitrate: Long): Long = ((currentBitrate.coerceAtLeast(0) * 0.7).toLong()).coerceAtLeast(500_000L)

    /** True when [limit] rebuffers happened within [windowMs]: time to switch to a lower variant. */
    class RebufferTracker(private val windowMs: Long = 60_000, private val limit: Int = 3) {
        private val times = ArrayDeque<Long>()
        fun record(now: Long): Boolean { times.addLast(now); while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst(); return times.size >= limit }
        fun reset() = times.clear()
    }

    /** Live playback that has drifted this far behind the live edge (after a stall) is brought back to it. */
    const val LIVE_BEHIND_MAX_MS = 25_000L
    fun shouldSeekToLive(offsetMs: Long, sinceLastSeekMs: Long): Boolean = offsetMs > LIVE_BEHIND_MAX_MS && sinceLastSeekMs > 15_000L
}
