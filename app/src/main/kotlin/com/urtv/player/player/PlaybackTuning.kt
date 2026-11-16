package com.urtv.player.player

import com.urtv.player.data.BufferMode

/** Pure playback rules (unit-tested): buffer sizing, resolution caps, bandwidth seed and the "too many rebuffers" trigger. */
object PlaybackTuning {
    data class Buffers(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufMs: Int, val targetBytes: Int)

    /**
     * Live starts playing after ~1 s of media (fast zap) but asks for a larger buffer after a stall, so a flaky connection
     * recovers into a safe cushion. On-demand needs a bigger start buffer. Low-RAM boxes get a byte cap.
     */
    fun buffers(mode: BufferMode, live: Boolean, lowRam: Boolean, steady: Boolean = false): Buffers {
        val b0 = if (live) when (mode) {
            BufferMode.Low -> Buffers(2_000, 8_000, 800, 2_000, -1)
            BufferMode.Normal -> Buffers(8_000, 30_000, 1_000, 3_500, 48 * 1024 * 1024)     // a picture after 1 s, then a real cushion (the 4/15 s of beta3 stuttered on jittery providers), bounded memory
            BufferMode.High -> Buffers(15_000, 50_000, 1_500, 5_000, -1)
        } else Buffers(15_000, 50_000, 2_500, 5_000, -1)
        // «جودة أثبت»: a deeper cushion before and after a stall, still a picture after ~1.5 s
        val b = if (steady && live) b0.copy(minMs = b0.minMs + 4_000, maxMs = b0.maxMs + 10_000, startMs = maxOf(b0.startMs, 1_500), rebufMs = b0.rebufMs + 2_500, targetBytes = if (b0.targetBytes > 0) b0.targetBytes + 24 * 1024 * 1024 else -1) else b0
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

    /** With «جودة أثبت» on: never above 720p, and quality changes slower and only to what half of the measured bandwidth can carry (the default asks 0.7 and switches up after 10 s). */
    const val STEADY_MAX_W = 1280; const val STEADY_MAX_H = 720
    const val STEADY_BANDWIDTH_FRACTION = 0.5f
    const val STEADY_UP_AFTER_MS = 25_000

    /**
     * How much deeper the live cushion is asked to be after stalls: each stall adds a step (3 s) up to 9 s, and the extra goes away step by step when
     * five minutes pass without one. A flaky provider settles into a cushion that holds; a good one never pays for it.
     */
    class BufferGrowth(private val stepMs: Long = 3_000, private val maxMs: Long = 9_000, private val decayAfterMs: Long = 5 * 60_000) {
        @Volatile private var extra = 0L
        private var lastChange = 0L
        fun onStall(now: Long): Long { extra = (extra + stepMs).coerceAtMost(maxMs); lastChange = now; return extra }
        fun extraMs(now: Long): Long { if (extra > 0 && now - lastChange > decayAfterMs) { extra = (extra - stepMs).coerceAtLeast(0); lastChange = now }; return extra }
        fun reset() { extra = 0 }
    }

    /** Lower cap after repeated rebuffers: 70 % of the current video bitrate, never below 500 kbit/s. */
    fun downgradeCap(currentBitrate: Long): Long = ((currentBitrate.coerceAtLeast(0) * 0.7).toLong()).coerceAtLeast(500_000L)

    /** True when [limit] rebuffers happened within [windowMs]: time to switch to a lower variant. */
    class RebufferTracker(private val windowMs: Long = 60_000, private val limit: Int = 3) {
        private val times = ArrayDeque<Long>()
        fun record(now: Long): Boolean { times.addLast(now); while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst(); return times.size >= limit }
        fun reset() = times.clear()
    }

    /** Live playback that has drifted this far behind the live edge (after a stall) is brought back to it. */
    const val LIVE_BEHIND_MAX_MS = 60_000L     // a jump to the live edge empties the buffer and stutters again: only when really far behind
    fun shouldSeekToLive(offsetMs: Long, sinceLastSeekMs: Long): Boolean = offsetMs > LIVE_BEHIND_MAX_MS && sinceLastSeekMs > 15_000L
}
