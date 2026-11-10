package com.urtv.player.player

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
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
            .dns(dns).eventListenerFactory(LoadTrace.listenerFactory)
            .followRedirects(true).followSslRedirects(true)
            .build()
    }

    private val warmClient by lazy { client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build() }

    /**
     * Fetches only the small HLS manifest of a neighbouring channel so DNS, TCP and TLS are ready when the user zaps.
     * Never touches non-HLS URLs (those would open a media stream and could hit a provider's connection limit).
     */
    fun warmManifest(url: String) {
        if (!url.contains(".m3u8", true) || !(url.startsWith("http://", true) || url.startsWith("https://", true))) return
        Thread({ runCatching { warmClient.newCall(Request.Builder().url(url).header("User-Agent", com.urtv.player.data.Http.UA).build()).execute().use { r -> r.body?.source()?.request(64 * 1024) } } }, "warm").apply { isDaemon = true }.start()
    }
}
