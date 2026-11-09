package com.urtv.player.data

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Minimal HTTP(S) client for untrusted servers: only http/https (never file:, content:, jar:, ftp:), at most 5 redirects,
 * timeouts, and a hard cap on the number of bytes read AFTER decompression (so a gzip bomb cannot exhaust memory or disk).
 */
object Http {
    const val UA = "UrTV/0.1 (Android TV)"
    const val MAX_TEXT = 64L * 1024 * 1024        // playlist / Xtream JSON
    const val MAX_GUIDE = 300L * 1024 * 1024      // XMLTV after gunzip
    private const val MAX_REDIRECTS = 5

    fun isWebUrl(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)

    fun open(url: String, timeoutMs: Int = 20000, maxBytes: Long = MAX_TEXT): InputStream {
        require(isWebUrl(url.trim())) { "unsupported scheme" }
        var u = URL(url.trim())
        repeat(MAX_REDIRECTS + 1) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs * 2
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without location")
                c.disconnect()
                val next = URL(u, loc)
                if (next.protocol != "http" && next.protocol != "https") throw IOException("redirect to unsupported scheme")
                u = next; return@repeat
            }
            if (code !in 200..299) { c.disconnect(); throw IOException("HTTP $code") }
            if (c.contentLengthLong > maxBytes && c.getHeaderField("Content-Encoding") == null) { c.disconnect(); throw IOException("too large") }
            val raw = BufferedInputStream(c.inputStream, 64 * 1024)
            raw.mark(2)
            val b0 = raw.read(); val b1 = raw.read(); raw.reset()
            val body = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
            return Limited(body, maxBytes)
        }
        throw IOException("too many redirects")
    }

    fun text(url: String, maxBytes: Long = MAX_TEXT): String = open(url, maxBytes = maxBytes).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Throws once more than [max] bytes have been produced. */
    class Limited(i: InputStream, private val max: Long) : FilterInputStream(i) {
        private var n = 0L
        private fun add(k: Int) { if (k > 0) { n += k; if (n > max) throw IOException("response too large") } }
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it) }
    }
}
