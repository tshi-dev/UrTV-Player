package com.urtv.player.data

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Tiny logo loader: bounded concurrency, downsampled decode, small LRU. No image library needed. */
object Logos {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, v: ImageBitmap) = v.width * v.height * 4
    }
    private val failed = HashSet<String>()
    private val gate = Semaphore(4)

    suspend fun load(url: String, target: Int = 160): ImageBitmap? {
        cache.get(url)?.let { return it }
        if (synchronized(failed) { url in failed }) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                runCatching {
                    val bytes = Http.open(url, 8000).use { it.readBytes() }
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var s = 1; while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                    bmp?.asImageBitmap()?.also { cache.put(url, it) }
                }.getOrNull().also { if (it == null) synchronized(failed) { failed += url } }
            }
        }
    }
}

@Composable
fun rememberLogo(url: String?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, url) { value = if (url.isNullOrBlank()) null else Logos.load(url) }
