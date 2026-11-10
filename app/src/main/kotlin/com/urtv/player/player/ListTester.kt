package com.urtv.player.player

import com.urtv.player.core.Channel
import com.urtv.player.core.ListProbe
import com.urtv.player.core.ListProbe.Fail
import com.urtv.player.core.ListProbe.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * «اختبار القائمة»: asks a small spread sample of channels for their first bytes (never the whole stream), a few at a time with a
 * pause between starts, short timeouts, so a subscription can be judged without hammering the provider. Nothing is logged or kept;
 * the screen shows percentages and times only, never a URL.
 */
object ListTester {
    const val PARALLEL = 3
    const val SPACING_MS = 200L
    const val FIRST_BYTES = 2048L

    private val client by lazy { Net.client.newBuilder().callTimeout(10, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).retryOnConnectionFailure(false).build() }

    /** One real probe: time until the first bytes arrive. Blocking, call on IO. */
    fun probeOnce(url: String): Result {
        val t0 = System.nanoTime()
        return try {
            client.newCall(Request.Builder().url(url).header("User-Agent", com.urtv.player.data.Http.UA).header("Range", "bytes=0-${FIRST_BYTES - 1}").build()).execute().use { r ->
                val ms = { (System.nanoTime() - t0) / 1_000_000 }
                when {
                    r.code in 400..499 -> Result(false, 0, Fail.Http4xx)
                    r.code in 500..599 -> Result(false, 0, Fail.Http5xx)
                    else -> {
                        val got = r.body?.source()?.let { s -> runCatching { s.request(1) }.getOrDefault(false) } ?: false     // the first byte
                        if (got) Result(true, ms()) else Result(false, 0, Fail.Connection)
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) { Result(false, 0, Fail.Timeout) }
        catch (e: java.io.InterruptedIOException) { Result(false, 0, Fail.Timeout) }
        catch (e: Exception) { Result(false, 0, Fail.Connection) }
    }

    /**
     * Runs [probe] over the sample with at most [PARALLEL] at once and [SPACING_MS] between starts. [onProgress] gets (done, total).
     * Cancelling the coroutine stops the test. [probe] is injectable so the pacing is tested without a network.
     */
    suspend fun run(channels: List<Channel>, n: Int = ListProbe.DEFAULT_SAMPLE, spacingMs: Long = SPACING_MS, parallel: Int = PARALLEL,
                    onProgress: (Int, Int) -> Unit = { _, _ -> }, probe: (String) -> Result = ::probeOnce): List<Result> {
        val sample = ListProbe.sample(channels, n)
        val gate = Semaphore(parallel); var done = 0
        return coroutineScope {
            sample.mapIndexed { i, c ->
                async(Dispatchers.IO) {
                    delay(i * spacingMs / parallel.coerceAtLeast(1))        // staggered starts
                    gate.withPermit { probe(c.url).also { synchronized(this@ListTester) { done++; onProgress(done, sample.size) } } }
                }
            }.awaitAll()
        }
    }
}
