package com.urtv.player.core

import java.util.Locale

/**
 * «دمج التعليق»: the picture of one channel (muted) with the sound of a second channel of the person's own list.
 * Pure rules only (delay maths, who is held back, why a second channel failed); the players live in the app module.
 */
object Commentary {
    const val MAX_MS = 10_000L
    /** The buttons of the delay row, in order: 0 resets. */
    val STEPS = listOf(-5000L, -1000L, -500L, 0L, 500L, 1000L, 5000L)
    /** Under this much RAM the feature is hidden: two decoders and two connections are too much. */
    const val MIN_RAM_MB = 2000L

    fun supported(totalRamMb: Long, lowRamDevice: Boolean) = !lowRamDevice && totalRamMb >= MIN_RAM_MB

    fun clamp(ms: Long) = ms.coerceIn(-MAX_MS, MAX_MS)

    /** One press of the delay row: «0» resets, the others add, always within ±10 s. Positive = the sound comes later than the picture. */
    fun press(current: Long, step: Long): Long = if (step == 0L) 0L else clamp(current + step)

    /** How far each stream is held back behind live for a delay: the one that is ahead waits. */
    data class Lag(val audioMs: Long, val videoMs: Long)
    fun lagFor(delayMs: Long) = Lag(audioMs = maxOf(clamp(delayMs), 0L), videoMs = maxOf(-clamp(delayMs), 0L))

    /** Seek moves for a change of delay; positive = move further back (hold back more), negative = move toward live. */
    data class Moves(val audioBackMs: Long, val videoBackMs: Long)
    fun plan(oldDelayMs: Long, newDelayMs: Long): Moves {
        val a = lagFor(oldDelayMs); val b = lagFor(newDelayMs)
        return Moves(b.audioMs - a.audioMs, b.videoMs - a.videoMs)
    }

    /** «+1.5» / «−0.5» / «0» (Western digits, a real minus sign). */
    fun label(ms: Long): String {
        val v = clamp(ms); if (v == 0L) return "0"
        val s = String.format(Locale.US, "%.1f", Math.abs(v) / 1000.0).removeSuffix(".0")
        return (if (v > 0) "+" else "−") + s
    }

    enum class Failure { OneConnection, Unreachable, NotSeekable }

    /** The provider answers like this when the account's single connection is already in use. */
    private val ONE_CONNECTION_HTTP = setOf(401, 403, 423, 429, 458, 509)

    /**
     * Why the second channel could not play. [httpStatus] is the server's answer (0 = none), [originalFailedSoon] is true when the
     * first channel itself dropped right after the second one started (a provider that allows one connection cuts the first).
     */
    fun failureFor(httpStatus: Int, originalFailedSoon: Boolean): Failure = when {
        originalFailedSoon -> Failure.OneConnection
        httpStatus in ONE_CONNECTION_HTTP -> Failure.OneConnection
        else -> Failure.Unreachable
    }
}
