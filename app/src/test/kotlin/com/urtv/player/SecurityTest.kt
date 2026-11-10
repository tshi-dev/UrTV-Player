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

    // ---- shaped like a real phone browser (Chrome): idle pre-opened sockets, keep-alive, Origin: null from a no-referrer page

    private fun browserPost(port: Int, body: String, origin: String?, extra: String = "", closeAfter: Boolean = false): String = Socket(lo, port).use { s ->
        s.soTimeout = 4000
        val bytes = body.toByteArray()
        val head = "POST /add HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: keep-alive\r\nContent-Length: ${bytes.size}\r\nCache-Control: max-age=0\r\n" +
            (origin?.let { "Origin: $it\r\n" } ?: "") + "Upgrade-Insecure-Requests: 1\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
            "User-Agent: Mozilla/5.0 (Linux; Android 14) Chrome/130\r\nAccept: text/html\r\nAccept-Encoding: gzip, deflate\r\n$extra\r\n"
        s.getOutputStream().write(head.toByteArray()); s.getOutputStream().flush()
        if (extra.contains("Expect")) { Thread.sleep(50) }
        s.getOutputStream().write(bytes); s.getOutputStream().flush()
        // read just the response (Content-Length framed), as a keep-alive client does, not until close
        val inp = s.getInputStream(); val sb = StringBuilder(); val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 4000) {
            val b = inp.read(); if (b < 0) break; sb.append(b.toChar())
            if (sb.endsWith("</html>") || sb.endsWith("forbidden") || sb.endsWith("not found")) break
        }
        sb.toString()
    }

    @Test fun formPostSucceedsWhileBrowserIdleSocketsAreOpen() {
        var got: PhoneInput? = null; val ran = CountDownLatch(1)
        val s = start({ got = it; ran.countDown() })
        // Chrome opens extra connections ahead of time and leaves them silent
        val idle = List(3) { Socket(lo, s.port) }
        try {
            val t0 = System.currentTimeMillis()
            val r = browserPost(s.port, "c=${s.code}&name=Mine&url=http%3A%2F%2Fexample.com%2Fl.m3u&server=&user=&pass=&epg=", origin = "http://127.0.0.1:${s.port}")
            assertTrue(r.startsWith("HTTP/1.1 200"), r)
            assertTrue(System.currentTimeMillis() - t0 < 2500, "idle sockets must not delay a real request")
            assertTrue(ran.await(3, TimeUnit.SECONDS), "the playlist job must start")
            assertEquals("http://example.com/l.m3u", got?.url); assertEquals("Mine", got?.name)
        } finally { idle.forEach { runCatching { it.close() } }; s.close() }
    }

    @Test fun formPostWithOriginNullAndExpectContinueIsAccepted() {
        var got: PhoneInput? = null; val ran = CountDownLatch(1)
        val s = start({ got = it; ran.countDown() })
        try {
            val r = browserPost(s.port, "c=${s.code}&url=https%3A%2F%2Fx.example%2Fa.m3u", origin = "null", extra = "Expect: 100-continue\r\n")
            assertTrue(r.contains("HTTP/1.1 200"), r)
            assertTrue(ran.await(3, TimeUnit.SECONDS)); assertEquals("https://x.example/a.m3u", got?.url)
        } finally { s.close() }
    }

    @Test fun foreignOriginStillRefusedAndMalformedEscapesGetAnAnswer() {
        val s = start()
        try {
            assertTrue(browserPost(s.port, "c=${s.code}&url=http%3A%2F%2Fa", origin = "http://evil.example").startsWith("HTTP/1.1 403"))
            val bad = browserPost(s.port, "c=${s.code}&url=%ZZ%", origin = "null")
            assertTrue(bad.startsWith("HTTP/1.1 403") || bad.startsWith("HTTP/1.1 400"), "a malformed escape is answered, not dropped: $bad")
        } finally { s.close() }
    }

    @Test fun concurrentSubmitsDeliverOnce() {
        val n = java.util.concurrent.atomic.AtomicInteger(); val s = start({ n.incrementAndGet() })
        val ts = List(4) { Thread { browserPost(s.port, "c=${s.code}&url=http%3A%2F%2Fa.example%2Fl", origin = "null") }.also { it.start() } }
        ts.forEach { it.join(6000) }; Thread.sleep(200)
        assertEquals(1, n.get()); s.close()
    }

    @Test fun logsOneLinePerRequestWithoutUrlsOrCodes() {
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        PhoneServer.logger = { lines += it }
        val s = start()
        try {
            browserPost(s.port, "c=${s.code}&url=http%3A%2F%2Fsecret.example%2Fl", origin = "null"); Thread.sleep(300)
            assertTrue(lines.any { it.contains("POST /add -> 200") }, lines.toString())
            assertTrue(lines.none { it.contains("secret.example") || it.contains(s.code) }, "no URLs or codes in the log")
        } finally { PhoneServer.logger = {}; s.close() }
    }
}
