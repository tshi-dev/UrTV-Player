package com.urtv.player.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap

/**
 * Decoded channel logos at their fixed on-screen size, kept in memory. A list row whose logo is here costs one plain Image when it is composed
 * (no image-request machinery on the key press); a missing one is requested in the background and the row redraws when it arrives.
 */
object LogoCache {
    private val cache = object : LruCache<String, ImageBitmap>(240) {}
    private fun key(url: String, px: Int) = "$px|$url"
    fun get(url: String, px: Int): ImageBitmap? = cache.get(key(url, px))

    /** Fetches [url] at [px] x [px] through Coil (disk cache, network) and keeps the result; [done] runs on the main thread when it is in. */
    fun load(ctx: Context, url: String, px: Int, done: () -> Unit = {}) {
        if (get(url, px) != null) return
        val req = ImageRequest.Builder(ctx).data(url).size(px, px).target(onSuccess = { img ->
            runCatching { img.toBitmap().asImageBitmap() }.getOrNull()?.let { cache.put(key(url, px), it); done() }
        }).build()
        SingletonImageLoader.get(ctx).enqueue(req)
    }
}

/** The logo bitmap for [url] if known; otherwise null now and the caller recomposes when it has loaded. */
@Composable
fun rememberLogo(url: String?, px: Int): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    val ctx = LocalContext.current
    var tick by remember(url, px) { mutableIntStateOf(0) }
    val have = LogoCache.get(url, px)
    if (have == null) LaunchedEffect(url, px) { LogoCache.load(ctx, url, px) { tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    return have ?: LogoCache.get(url, px)
}
