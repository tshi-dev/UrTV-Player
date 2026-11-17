package com.urtv.player.core

/**
 * What a beta build measured on the device (numbers only, never a name, address or login) and what those numbers say: each area rated good / fair /
 * poor against fixed thresholds. Pure logic, unit-tested; the screen and the report both read it.
 */
object Assessment {
    data class Snapshot(
        val coldStartMs: Long = -1, val tabOpenMs: List<Long> = emptyList(), val playlistLoadMs: Long = -1, val playlistChannels: Int = 0, val epgLoadMs: Long = -1,
        val zapMs: List<Long> = emptyList(), val playSeconds: Long = 0, val bufferingSeconds: Long = 0, val rebuffers: Int = 0, val dropped: Long = 0, val decoderErrors: Int = 0,
        val failures: Map<String, Int> = emptyMap(), val plays: Int = 0,
        val heapPeakMb: Int = 0, val heapLimitMb: Int = 0, val pssPeakMb: Int = 0,
        val crashes: Int = 0, val anrs: Int = 0, val lowMemoryKills: Int = 0, val lastCrash: String = "",
    )

    enum class Area { Startup, TabOpen, PlaylistLoad, EpgLoad, Zap, Buffering, Dropped, Decoder, Failures, Memory, Stability }
    enum class Rating { NoData, Good, Fair, Poor }
    /** [value] is a number already formatted for display (with its unit key in the screen), [detail] a second number for context. */
    data class Finding(val area: Area, val rating: Rating, val value: Long = -1, val detail: Long = -1)

    fun percentile(l: List<Long>, p: Int): Long { if (l.isEmpty()) return -1; val s = l.sorted(); return s[((s.size - 1) * p / 100).coerceIn(0, s.size - 1)] }

    /** Lower is better: good up to [good], fair up to [fair], else poor. */
    private fun lower(v: Long, good: Long, fair: Long) = if (v < 0) Rating.NoData else if (v <= good) Rating.Good else if (v <= fair) Rating.Fair else Rating.Poor

    fun rate(s: Snapshot): List<Finding> {
        val tab = percentile(s.tabOpenMs, 90); val zap = percentile(s.zapMs, 90)
        val failTotal = s.failures.values.sum()
        val failPer100 = if (s.plays > 0) failTotal * 100L / s.plays else -1L
        val rebufPerHour = if (s.playSeconds >= 120) s.rebuffers * 3600L / s.playSeconds else -1L
        val bufShare = if (s.playSeconds >= 120) s.bufferingSeconds * 1000L / s.playSeconds else -1L           // per mille of the time spent waiting
        val droppedPerMin = if (s.playSeconds >= 120) s.dropped * 60L / s.playSeconds else -1L
        val buffering = if (rebufPerHour < 0) Rating.NoData else worst(lower(rebufPerHour, 2, 8), lower(bufShare, 10, 40))
        val memRatio = if (s.heapLimitMb > 0 && s.heapPeakMb > 0) s.heapPeakMb * 100L / s.heapLimitMb else -1L
        val stability = if (s.crashes + s.anrs + s.lowMemoryKills == 0) Rating.Good else if (s.crashes + s.anrs + s.lowMemoryKills == 1) Rating.Fair else Rating.Poor
        return listOf(
            Finding(Area.Startup, lower(s.coldStartMs, 1500, 3000), s.coldStartMs),
            Finding(Area.TabOpen, lower(tab, 150, 400), tab),
            Finding(Area.PlaylistLoad, if (s.playlistLoadMs < 0) Rating.NoData else lower(s.playlistLoadMs, 6000 + s.playlistChannels / 10L, 15000 + s.playlistChannels / 5L), s.playlistLoadMs, s.playlistChannels.toLong()),
            Finding(Area.EpgLoad, lower(s.epgLoadMs, 8000, 20000), s.epgLoadMs),
            Finding(Area.Zap, lower(zap, 1500, 3000), zap, percentile(s.zapMs, 50)),
            Finding(Area.Buffering, buffering, rebufPerHour, bufShare),
            Finding(Area.Dropped, lower(droppedPerMin, 5, 30), droppedPerMin),
            Finding(Area.Decoder, if (s.plays == 0 && s.decoderErrors == 0) Rating.NoData else lower(s.decoderErrors.toLong(), 0, 2), s.decoderErrors.toLong()),
            Finding(Area.Failures, lower(failPer100, 2, 8), failPer100, failTotal.toLong()),
            Finding(Area.Memory, lower(memRatio, 60, 80), memRatio, s.pssPeakMb.toLong()),
            Finding(Area.Stability, stability, (s.crashes + s.anrs + s.lowMemoryKills).toLong(), s.anrs.toLong()),
        )
    }

    private fun worst(a: Rating, b: Rating) = if (a == Rating.NoData) b else if (b == Rating.NoData) a else maxOf(a, b)

    /** One word for the whole: poor if anything important is poor, fair if anything is fair, good if there is data and nothing wrong. */
    fun overall(f: List<Finding>): Rating {
        val r = f.map { it.rating }.filter { it != Rating.NoData }
        return if (r.isEmpty()) Rating.NoData else if (Rating.Poor in r) Rating.Poor else if (Rating.Fair in r) Rating.Fair else Rating.Good
    }
}
