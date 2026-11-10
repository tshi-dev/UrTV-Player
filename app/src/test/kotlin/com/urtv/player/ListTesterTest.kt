package com.urtv.player

import com.urtv.player.core.Channel
import com.urtv.player.core.ListProbe.Fail
import com.urtv.player.core.ListProbe.Result
import com.urtv.player.player.ListTester
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListTesterTest {
    private val chans = (0 until 200).map { Channel("$it", "C$it", "http://s.example/$it") }

    @Test fun neverMoreThanTheParallelLimitAndOnlyTheSampleIsProbed() = runBlocking {
        val now = AtomicInteger(); val max = AtomicInteger(); val calls = AtomicInteger(); val progress = ArrayList<Int>()
        val r = ListTester.run(chans, n = 30, spacingMs = 5, onProgress = { d, _ -> synchronized(progress) { progress += d } }) {
            val c = now.incrementAndGet(); max.updateAndGet { m -> maxOf(m, c) }; calls.incrementAndGet(); Thread.sleep(20); now.decrementAndGet()
            Result(true, 100)
        }
        assertEquals(30, r.size); assertEquals(30, calls.get()); assertTrue(max.get() <= ListTester.PARALLEL, "parallel ${max.get()}")
        assertEquals((1..30).toList(), progress.sorted())
    }

    @Test fun failuresComeThroughAsResults() = runBlocking {
        val r = ListTester.run(chans, n = 6, spacingMs = 0) { u -> if (u.endsWith("0")) Result(false, 0, Fail.Timeout) else Result(true, 50) }
        assertEquals(6, r.size); assertTrue(r.any { it.fail == Fail.Timeout })
    }

    @Test fun aLocalServerIsProbedForItsFirstBytesOnly() {
        // a real socket: answers 200 with an endless-looking body (we must not read it all), another path 404, another is dead
        val srv = java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val t = Thread { while (!srv.isClosed) runCatching { srv.accept().use { s ->
            val line = s.getInputStream().bufferedReader().readLine().orEmpty()
            val out = s.getOutputStream()
            if (line.contains("/ok")) { out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray()); out.write(ByteArray(8192)); out.flush() }
            else out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } } }.apply { isDaemon = true; start() }
        try {
            val base = "http://127.0.0.1:${srv.localPort}"
            val ok = ListTester.probeOnce("$base/ok"); assertTrue(ok.ok && ok.ttfbMs < 3000, ok.toString())
            assertEquals(Fail.Http4xx, ListTester.probeOnce("$base/nope").fail)
        } finally { srv.close() }
        assertEquals(Fail.Connection, ListTester.probeOnce("http://127.0.0.1:1/x").fail)
    }
}
