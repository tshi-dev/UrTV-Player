package com.urtv.player.core

/**
 * «ليش يقطّع؟»: names the LIKELY cause of choppy playback from what the player can measure. Pure logic (unit-tested with scripted
 * traces). It never names a provider and never claims more than "likely".
 */
object Diagnosis {
    enum class Cause { None, Source, HomeNetwork, Decoder, DeadChannel, Unknown }
    enum class ErrorKind { None, Http, Timeout, Connection, Format, Decoder }

    data class Sample(
        val buffering: Boolean = false,
        val stallsInWindow: Int = 0,          // rebuffers after playback had started, last 30 s
        val stallMsInWindow: Long = 0,
        val bufferedMs: Long = 0,
        val droppedPerSec: Float = 0f,        // decoder dropped frames per second, last few seconds
        val receivedKbps: Int = 0,            // what the connection is delivering now
        val streamKbps: Int = 0,              // what this stream needs (0 = unknown)
        val linkKbps: Int = 0,                // the TV's own link estimate (0 = unknown)
        val signalDbm: Int? = null,           // Wi-Fi signal (null = not Wi-Fi / unknown)
        val stalledChannels: Int = 0,         // distinct channels that stalled in the last 10 min
        val error: ErrorKind = ErrorKind.None,
        val httpStatus: Int = 0,
        val attempts: Int = 0,
    )

    const val WEAK_SIGNAL_DBM = -75
    const val DROPS_PER_SEC = 3f

    fun classify(s: Sample): Cause {
        when (s.error) {
            ErrorKind.Http -> return if (s.httpStatus in 400..499) Cause.DeadChannel else if (s.httpStatus in 500..599) Cause.Source else Cause.Unknown
            ErrorKind.Format -> return Cause.DeadChannel
            ErrorKind.Decoder -> return Cause.Decoder
            ErrorKind.Timeout, ErrorKind.Connection -> return if (weakLink(s) || s.stalledChannels >= 3) Cause.HomeNetwork else if (s.attempts >= 2) Cause.DeadChannel else Cause.Source
            ErrorKind.None -> Unit
        }
        // the TV cannot keep up: frames are dropped although the buffer is full and nothing stalls
        if (s.droppedPerSec >= DROPS_PER_SEC && s.bufferedMs >= 2000 && s.stallsInWindow == 0) return Cause.Decoder
        val trouble = s.stallsInWindow >= 2 || (s.buffering && s.stallMsInWindow >= 4000)
        if (!trouble) return Cause.None
        if (s.stalledChannels >= 3 || weakLink(s)) return Cause.HomeNetwork
        if (s.streamKbps > 0 && s.receivedKbps > 0 && s.receivedKbps < s.streamKbps) return Cause.Source
        return Cause.Unknown
    }

    private fun weakLink(s: Sample) = (s.signalDbm != null && s.signalDbm <= WEAK_SIGNAL_DBM) ||
        (s.linkKbps > 0 && s.streamKbps > 0 && s.linkKbps < s.streamKbps * 2)

    /** Remembers when and where playback stalled; answers the window questions [Sample] needs. */
    class Tracker(private val windowMs: Long = 30_000, private val channelWindowMs: Long = 600_000) {
        private val stalls = ArrayDeque<Long>()
        private val stallSeconds = ArrayDeque<Long>()
        private val channels = HashMap<String, Long>()
        fun stall(now: Long, channel: String) { stalls.addLast(now); channels[channel] = now; trim(now) }
        /** One call per second while buffering after playback had started. */
        fun bufferingSecond(now: Long) { stallSeconds.addLast(now); trim(now) }
        fun stallsIn(now: Long): Int { trim(now); return stalls.size }
        fun stallMs(now: Long): Long { trim(now); return stallSeconds.size * 1000L }
        fun stalledChannels(now: Long): Int { trim(now); return channels.size }
        fun clear() { stalls.clear(); stallSeconds.clear(); channels.clear() }
        private fun trim(now: Long) {
            while (stalls.isNotEmpty() && now - stalls.first() > windowMs) stalls.removeFirst()
            while (stallSeconds.isNotEmpty() && now - stallSeconds.first() > windowMs) stallSeconds.removeFirst()
            channels.entries.removeAll { now - it.value > channelWindowMs }
        }
    }
}
