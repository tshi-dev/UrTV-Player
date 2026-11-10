package com.urtv.player.core

/** UI smoothness from frame-to-frame times (ms): the numbers a TV test reports per screen. Pure, unit-tested. */
object FrameStats {
    /** A frame is janky when it took longer than 1.5 display intervals (25 ms at 60 Hz). */
    const val JANK_MS = 25.0

    data class Result(val frames: Int, val jankyPct: Double, val p50: Double, val p90: Double, val p99: Double) {
        val line get() = "ui $frames frames: janky ${"%.1f".format(java.util.Locale.US, jankyPct)} %  p50 ${p50.toInt()} / p90 ${p90.toInt()} / p99 ${p99.toInt()} ms"
    }

    fun of(intervalsMs: List<Double>): Result {
        if (intervalsMs.isEmpty()) return Result(0, 0.0, 0.0, 0.0, 0.0)
        val s = intervalsMs.sorted()
        fun pct(p: Double) = s[((s.size - 1) * p).toInt()]
        return Result(s.size, intervalsMs.count { it > JANK_MS } * 100.0 / s.size, pct(0.5), pct(0.9), pct(0.99))
    }

    /** Frames per second from a counter that grew by [frames] over [ms]; 0 when there is not enough to say. */
    fun fps(frames: Long, ms: Long): Float = if (frames <= 0 || ms < 500) 0f else (frames * 1000f / ms)
}
