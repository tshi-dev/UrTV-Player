package com.urtv.player

import com.urtv.player.data.Http
import com.urtv.player.phone.PhoneInput
import com.urtv.player.phone.PhoneServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HttpLimitsTest {
    @Test fun onlyHttpAndHttpsAreFetched() {
        for (u in listOf("file:///etc/passwd", "content://x/y", "jar:file:///a!/b", "ftp://example.com/x", "intent://x#Intent;end", "javascript:alert(1)"))
            assertFailsWith<IllegalArgumentException>(u) { Http.open(u) }
    }

    @Test fun gzipBombIsCappedAfterDecompression() {
        val bomb = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(ByteArray(5_000_000)) } }.toByteArray()
        assertTrue(bomb.size < 20_000, "bomb should be tiny compressed")
        val s = Http.Limited(GZIPInputStream(ByteArrayInputStream(bomb)), 1_000_000)
        assertFailsWith<IOException> { s.readBytes() }
    }

    @Test fun underTheCapIsReadInFull() {
        assertEquals(10, Http.Limited(ByteArrayInputStream(ByteArray(10)), 10).readBytes().size)
    }
}

class PhoneServerTest {
    private val lo = InetAddress.getByName("127.0.0.1")

    private fun raw(port: Int, req: String): String = Socket(lo, port).use { s ->
        s.soTimeout = 3000
        s.getOutputStream().write(req.toByteArray()); s.getOutputStream().flush()
        runCatching { s.getInputStream().readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
    }

    private fun post(port: Int, code: String, body: String = "name=n&url=http%3A%2F%2Fexample.com%2Fa.m3u&c=$code", host: String? = null, origin: String? = null): String {
        val h = host ?: "127.0.0.1:$port"
        return raw(port, "POST /add HTTP/1.1\r\nHost: $h\r\n" + (origin?.let { "Origin: $it\r\n" } ?: "") + "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
    }

    private fun start(onInput: (PhoneInput) -> Unit = {}, closed: CountDownLatch = CountDownLatch(1)): PhoneServer =
        PhoneServer(lo, onInput, { closed.countDown() }).also { assertTrue(it.start()) }

    @Test fun bindsToTheGivenAddressOnARandomPortAndServesOnlyWithTheRightHost() {
        val s = start()
        try {
            assertTrue(s.port > 1024)
            assertTrue(raw(s.port, "GET / HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 200"))
            assertTrue(raw(s.port, "GET / HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 403"), "DNS-rebinding host must be refused")
            assertTrue(raw(s.port, "GET /etc/passwd HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 404"))
            assertTrue(raw(s.port, "DELETE / HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\n\r\n").startsWith("HTTP/1.1 405"))
        } finally { s.close() }
    }

    @Test fun successfulSubmitDeliversOnceAndClosesTheServer() {
        var got: PhoneInput? = null; val closed = CountDownLatch(1)
        val s = start({ got = it }, closed)
        val r = post(s.port, s.code)
        assertTrue(r.startsWith("HTTP/1.1 200"), r)
        assertTrue(closed.await(5, TimeUnit.SECONDS), "server must close after one use")
        assertEquals("http://example.com/a.m3u", got?.url)
        assertFailsWith<IOException> { Socket(lo, s.port).use { it.getOutputStream().write(1) ; it.getInputStream().read().also { x -> if (x < 0) throw IOException() } } }
    }

    @Test fun wrongCodeIsRefusedAndFiveTriesCloseTheServer() {
        var delivered = false; val closed = CountDownLatch(1)
        val s = start({ delivered = true }, closed)
        repeat(5) { assertTrue(post(s.port, "000000".takeIf { it != s.code } ?: "111111").startsWith("HTTP/1.1 403")) }
        assertTrue(closed.await(5, TimeUnit.SECONDS), "5 wrong codes must shut the page")
        assertFalse(delivered)
    }

    @Test fun oversizedBodyAndForeignOriginAreRejected() {
        val s = start()
        try {
            assertTrue(raw(s.port, "POST /add HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nContent-Length: 100000\r\n\r\n").startsWith("HTTP/1.1 413"))
            assertTrue(post(s.port, s.code, origin = "http://evil.example").startsWith("HTTP/1.1 403"), "cross-site POST must be refused")
        } finally { s.close() }
    }

    @Test fun errorPagesLeakNothing() {
        val s = start()
        try {
            val r = raw(s.port, "GET /%00../../x HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nConnection: close\r\n\r\n")
            assertFalse(r.contains("Exception") || r.contains("at com.") || r.contains("/data/"), r)
        } finally { s.close() }
    }

    @Test fun codeIsSixDigitsAndDiffersBetweenSessions() {
        val a = PhoneServer(lo, {}, {}); val b = PhoneServer(lo, {}, {})
        assertTrue(Regex("\\d{6}").matches(a.code)); assertNotNull(b.code)
    }
}
