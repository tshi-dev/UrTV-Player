package com.urtv.player.player

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.concurrent.TimeUnit

/** One shared OkHttp client for media: HTTP/2 where offered, pooled connections, no gzip on media (identity), tuned timeouts. */
object Net {
    val dns = CachingDns()
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(dns).eventListenerFactory(LoadTrace.listenerFactory).addInterceptor(ManifestCache.interceptor)
            .followRedirects(true).followSslRedirects(true)
            .build()
    }

    private val warmClient by lazy { client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build() }

    /** Starts resolving a stream's host name in the background (cached ten minutes), so a later zap or preview skips DNS. Never touches the stream. */
    fun preResolve(url: String) {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        if (!(url.startsWith("http://", true) || url.startsWith("https://", true))) return
        Thread({ runCatching { dns.lookup(host) } }, "dns-pre").apply { isDaemon = true }.start()
    }

    /**
     * Fetches the small HLS manifest of a neighbouring channel ahead of a zap and keeps it for 20 s ([ManifestCache]); the first real
     * request for the same URL is answered from memory. Only .m3u8 URLs, at most 256 KB, never a media stream.
     */
    fun prefetchManifest(url: String) {
        if (!url.contains(".m3u8", true) || !(url.startsWith("http://", true) || url.startsWith("https://", true))) return
        Thread({ runCatching {
            warmClient.newCall(Request.Builder().url(url).header("User-Agent", com.urtv.player.data.Http.UA).build()).execute().use { r ->
                if (r.code == 200) {
                    val src = r.body?.source() ?: return@use
                    src.request(ManifestCache.MAX_BYTES + 1L)
                    if (src.buffer.size <= ManifestCache.MAX_BYTES) ManifestCache.put(url, src.buffer.readByteArray(), r.header("Content-Type"))
                }
            }
        } }, "manifest-pre").apply { isDaemon = true }.start()
    }
}

/** Manifests fetched ahead of a zap, answered once from memory (see [Net.prefetchManifest]). Small, short-lived, bounded. */
object ManifestCache {
    const val TTL_MS = 20_000L
    const val MAX_BYTES = 256 * 1024
    const val MAX_ENTRIES = 4
    private class Entry(val at: Long, val bytes: ByteArray, val type: String?)
    private val map = LinkedHashMap<String, Entry>()

    @Synchronized fun put(url: String, bytes: ByteArray, type: String?, now: Long = System.currentTimeMillis()) {
        if (bytes.size > MAX_BYTES || bytes.isEmpty()) return
        map[url] = Entry(now, bytes, type)
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
    }

    /** The prefetched body if it is still fresh; it is removed (a live playlist must be re-fetched by the player afterwards). */
    @Synchronized fun take(url: String, now: Long = System.currentTimeMillis()): Pair<ByteArray, String?>? {
        val e = map.remove(url) ?: return null
        return if (now - e.at <= TTL_MS) e.bytes to e.type else null
    }

    @Synchronized fun clear() = map.clear()

    val interceptor = okhttp3.Interceptor { chain ->
        val req = chain.request()
        val hit = if (req.method == "GET") take(req.url.toString()) else null
        if (hit == null) chain.proceed(req)
        else okhttp3.Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", hit.second ?: "application/vnd.apple.mpegurl")
            .body(hit.first.toResponseBody(hit.second?.toMediaTypeOrNull())).build()
    }
}
