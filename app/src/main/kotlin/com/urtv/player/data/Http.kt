package com.urtv.player.data

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Minimal HTTP(S) client: follows http<->https redirects, transparently gunzips .gz bodies. */
object Http {
    const val UA = "UrTV/0.1 (Android TV)"

    fun open(url: String, timeoutMs: Int = 20000): InputStream {
        var u = URL(url)
        repeat(8) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs * 2
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without location")
                c.disconnect(); u = URL(u, loc); return@repeat
            }
            if (code !in 200..299) { c.disconnect(); throw IOException("HTTP $code") }
            val raw = BufferedInputStream(c.inputStream, 64 * 1024)
            raw.mark(2)
            val b0 = raw.read(); val b1 = raw.read(); raw.reset()
            return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
        }
        throw IOException("too many redirects")
    }

    fun text(url: String): String = open(url).use { it.readBytes().toString(Charsets.UTF_8) }
}
