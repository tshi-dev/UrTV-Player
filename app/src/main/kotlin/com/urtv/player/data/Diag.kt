package com.urtv.player.data

import java.util.ArrayDeque

/**
 * A tiny in-memory log of WHAT failed, never why in words: exception class names and the player's error-code name. Messages,
 * URLs and stack traces are never stored, and nothing is written to disk or sent anywhere by the app itself.
 */
object Diag {
    private val ring = ArrayDeque<String>()
    @Volatile var lastPlayerError: String = ""; private set
    /** Set while a stream has a video track this TV cannot show (codec, size, number of decoders); empty otherwise. Report line only. */
    @Volatile var videoBlock: String = ""

    private val CLASS = Regex("[A-Za-z][A-Za-z0-9_]{0,48}")

    /** Last segment of a class name, only if it looks like an identifier (anything else is dropped). */
    fun simpleName(c: Class<*>?): String? = c?.simpleName?.takeIf { CLASS.matches(it) }

    fun record(t: Throwable?) {
        var x = t; var n = 0
        while (x != null && n < 3) { simpleName(x.javaClass)?.let { add(it) }; x = x.cause; n++ }
    }

    /** The player's own error-code name (a constant such as ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) plus the cause classes. */
    fun playerError(codeName: String, t: Throwable?) {
        lastPlayerError = codeName.takeIf { Regex("[A-Z][A-Z0-9_]{0,60}").matches(it) } ?: ""
        record(t)
    }

    private fun add(n: String) = synchronized(ring) { ring.addLast(n); while (ring.size > 20) ring.removeFirst() }

    /** Up to [max] latest distinct class names, newest first. */
    fun recent(max: Int = 5): List<String> = synchronized(ring) { ring.toList().asReversed().distinct().take(max) }

    fun clearForTest() = synchronized(ring) { ring.clear(); lastPlayerError = "" }
}
