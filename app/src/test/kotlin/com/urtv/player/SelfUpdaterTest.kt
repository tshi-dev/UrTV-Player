package com.urtv.player

import com.urtv.player.data.SelfDownloader
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The download path against a local scripted server (hosts are pinned to GitHub in production; here only this server is allowed). */
class SelfUpdaterTest {
    private val payload = ByteArray(300_000) { (it * 31).toByte() }
    private lateinit var srv: ServerSocket
    private val tmp = kotlin.io.path.createTempDirectory("selfupd").toFile()

    private fun start(): String {
        srv = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        Thread { while (!srv.isClosed) runCatching { srv.accept().use { s ->
            val path = s.getInputStream().bufferedReader().readLine().orEmpty().split(' ').getOrElse(1) { "/" }
            val o = s.getOutputStream()
            fun head(code: String, extra: String) = o.write("HTTP/1.1 $code\r\n$extra\r\nConnection: close\r\n\r\n".toByteArray())
            when (path) {
                "/apk" -> { head("200 OK", "Content-Length: ${payload.size}"); o.write(payload) }
                "/redirect" -> head("302 Found", "Location: /apk\r\nContent-Length: 0")
                "/redirect-evil" -> head("302 Found", "Location: http://evil.example/apk\r\nContent-Length: 0")
                "/loop" -> head("302 Found", "Location: /loop\r\nContent-Length: 0")
                "/declared-big" -> { head("200 OK", "Content-Length: 99999999"); o.write(ByteArray(1000)) }
                "/endless" -> { head("200 OK", ""); runCatching { val b = ByteArray(65536); while (true) o.write(b) } }
                "/short" -> { head("200 OK", "Content-Length: ${payload.size}"); o.write(payload, 0, 1000) }
                "/404" -> head("404 Not Found", "Content-Length: 0")
                "/json" -> { val b = "[{\"tag_name\":\"player-0.9.9\"}]".toByteArray(); head("200 OK", "Content-Length: ${b.size}"); o.write(b) }
                else -> head("404 Not Found", "Content-Length: 0")
            }
            o.flush()
        } } }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:${srv.localPort}"
    }

    private fun dl(base: String) = SelfDownloader(allow = { it.startsWith(base) })
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun completeDownloadIsHashedAndRenamedOnlyWhenWhole() {
        val base = start(); val f = File(tmp, "a.apk"); val progress = ArrayList<Long>()
        val h = dl(base).file("$base/apk", f, 1_000_000, { d, _ -> progress += d })
        assertEquals(sha(payload), h); assertTrue(f.exists() && f.length() == payload.size.toLong()); assertFalse(File(f.path + ".part").exists())
        assertTrue(progress.isNotEmpty() && progress.last() == payload.size.toLong())
        assertEquals(sha(payload), dl(base).file("$base/redirect", f, 1_000_000), "a redirect inside the pinned host is followed")
    }

    @Test fun redirectsToOtherHostsAndLoopsAreRefused() {
        val base = start(); val f = File(tmp, "b.apk")
        assertFailsWith<SelfDownloader.Refused> { dl(base).file("$base/redirect-evil", f, 1_000_000) }
        assertFailsWith<SelfDownloader.Refused> { dl(base).file("$base/loop", f, 1_000_000) }
        assertFailsWith<SelfDownloader.Refused> { dl(base).file("http://evil.example/apk", f, 1_000_000) }
        assertFalse(f.exists())
    }

    @Test fun productionPolicyRefusesPlainHttpAndOtherHosts() {
        val d = SelfDownloader()   // the real pinned-host policy
        assertFailsWith<SelfDownloader.Refused> { d.text("http://github.com/x", 1000) }
        assertFailsWith<SelfDownloader.Refused> { d.file("https://evil.example/urtv-player.apk", File(tmp, "c.apk"), 1000) }
    }

    @Test fun oversizeIsStoppedWhetherDeclaredOrStreamed() {
        val base = start(); val f = File(tmp, "d.apk")
        assertFailsWith<SelfDownloader.Refused> { dl(base).file("$base/declared-big", f, 1_000_000) }
        assertFailsWith<SelfDownloader.Refused> { dl(base).file("$base/endless", f, 500_000) }
        assertFalse(f.exists()); assertFalse(File(f.path + ".part").exists())
        assertFailsWith<SelfDownloader.Refused> { dl(base).text("$base/endless", 2000) }
    }

    @Test fun interruptedHttpErrorAndDiskFullLeaveNothingBehind() {
        val base = start(); val f = File(tmp, "e.apk")
        assertFailsWith<IOException> { dl(base).file("$base/short", f, 1_000_000) }
        assertFailsWith<IOException> { dl(base).file("$base/404", f, 1_000_000) }
        var writes = 0
        assertFailsWith<IOException> { dl(base).file("$base/apk", f, 1_000_000, sink = { p -> object : OutputStream() {
            val real = p.outputStream()
            override fun write(b: Int) = real.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) { if (++writes > 2) throw IOException("No space left on device"); real.write(b, off, len) }
            override fun close() = real.close()
        } }) }
        assertFalse(f.exists()); assertFalse(File(f.path + ".part").exists())
    }

    @Test fun staleFilesFromEarlierAttemptsAreReplaced() {
        val base = start(); val f = File(tmp, "g.apk"); f.writeText("old"); File(f.path + ".part").writeText("half")
        dl(base).file("$base/apk", f, 1_000_000)
        assertEquals(payload.size.toLong(), f.length())
    }

    @Test fun textDocumentsAreReadWithinTheirCap() {
        val base = start()
        assertTrue(dl(base).text("$base/json", 1000).contains("player-0.9.9"))
        assertFailsWith<SelfDownloader.Refused> { dl(base).text("$base/json", 5) }
    }
}
