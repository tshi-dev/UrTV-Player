package com.urtv.player.player

import okhttp3.Dns
import okhttp3.EventListener
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Host name answers kept for ten minutes: a zap to another channel of the same provider skips DNS entirely. */
class CachingDns(private val ttlMs: Long = 10 * 60_000L, private val delegate: Dns = Dns.SYSTEM, private val now: () -> Long = System::currentTimeMillis) : Dns {
    private class Hit(val at: Long, val addrs: List<InetAddress>)
    private val cache = ConcurrentHashMap<String, Hit>()
    override fun lookup(hostname: String): List<InetAddress> {
        val h = cache[hostname]
        if (h != null && now() - h.at < ttlMs) return h.addrs
        return delegate.lookup(hostname).also { if (it.isNotEmpty()) cache[hostname] = Hit(now(), it) }
    }
    fun forget() = cache.clear()
}

/**
 * Where the time goes between asking for a channel and its first picture: DNS, connect (TCP + TLS), the first request (manifest), the
 * first media bytes, and what is left (decoder start and render). Fed by an OkHttp EventListener on the shared media client.
 */
object LoadTrace {
    class Req { var start = 0L; var dns = 0L; var connect = 0L; var headersAt = 0L; var endAt = 0L; var dnsStart = 0L; var connStart = 0L }
    data class Breakdown(val dnsMs: Long, val connectMs: Long, val manifestMs: Long, val firstBytesMs: Long, val decodeMs: Long, val totalMs: Long, val requests: Int) {
        val line get() = "dns $dnsMs  connect $connectMs  manifest $manifestMs  media $firstBytesMs  decode $decodeMs  = $totalMs ms ($requests req)"
    }

    @Volatile private var t0 = 0L
    private val calls = CopyOnWriteArrayList<Req>()

    fun begin(now: Long = System.currentTimeMillis()) { calls.clear(); t0 = now }
    fun begun() = t0 > 0

    /** Called when the first frame is drawn at [now]. Pure arithmetic over what was recorded (tested). */
    fun finish(now: Long = System.currentTimeMillis()): Breakdown? {
        if (t0 == 0L) return null
        val list = calls.toList(); val total = now - t0
        t0 = 0
        if (list.isEmpty()) return Breakdown(0, 0, 0, 0, total, total, 0)
        return compute(list, t0 = now - total, now = now)
    }

    internal fun compute(list: List<Req>, t0: Long, now: Long): Breakdown {
        val first = list[0]
        val manifest = if (first.endAt > 0) first.endAt - first.start else first.headersAt - first.start
        val media = list.getOrNull(1) ?: first
        val mediaBytes = if (list.size > 1) (media.headersAt - media.start).coerceAtLeast(0) else 0L
        val lastBytesAt = (list.drop(1).maxOfOrNull { it.headersAt } ?: first.headersAt).takeIf { it > 0 } ?: first.endAt
        val decode = if (lastBytesAt > 0) (now - lastBytesAt).coerceAtLeast(0) else 0L
        return Breakdown(first.dns, first.connect, manifest.coerceAtLeast(0), mediaBytes, decode, now - t0, list.size)
    }

    val listenerFactory = EventListener.Factory { _: okhttp3.Call ->
        object : EventListener() {
            private val c = Req()
            private fun active() = t0 > 0 && calls.size < 8
            override fun callStart(call: okhttp3.Call) { if (active()) { c.start = System.currentTimeMillis(); calls.add(c) } }
            override fun dnsStart(call: okhttp3.Call, domainName: String) { c.dnsStart = System.currentTimeMillis() }
            override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<InetAddress>) { if (c.dnsStart > 0) c.dns = System.currentTimeMillis() - c.dnsStart }
            override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { c.connStart = System.currentTimeMillis() }
            override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) { if (c.connStart > 0 && c.connect == 0L) c.connect = System.currentTimeMillis() - c.connStart }
            override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) { c.headersAt = System.currentTimeMillis() }
            override fun callEnd(call: okhttp3.Call) { c.endAt = System.currentTimeMillis() }
            override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { c.endAt = System.currentTimeMillis() }
        }
    }
}
