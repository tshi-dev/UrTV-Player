package com.urtv.player.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import com.urtv.player.R
import com.urtv.player.ui.C
import com.urtv.player.ui.ProgressBar
import com.urtv.player.ui.Rd
import com.urtv.player.ui.T
import com.urtv.player.ui.Tv
import com.urtv.player.ui.mutedInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of a network call that is shown to the user instead of crashing. */
sealed interface Res<out T> {
    data object Loading : Res<Nothing>
    data class Ok<T>(val v: T) : Res<T>
    data class Fail(val e: ServerException) : Res<Nothing>
}

@Composable
fun <T> load(key: Any?, block: () -> T): State<Res<T>> = produceState<Res<T>>(Res.Loading, key) {
    value = Res.Loading
    value = withContext(Dispatchers.IO) {
        try { Res.Ok(block()) }
        catch (e: ServerException) { Res.Fail(e) }
        catch (e: Exception) { Res.Fail(ServerException(ServerException.Reason.Network, "Unexpected error")) }
    }
}

@Composable
fun errorText(e: ServerException): String = stringResource(when (e.reason) {
    ServerException.Reason.Auth -> R.string.srv_expired
    ServerException.Reason.Certificate -> R.string.srv_cert_title
    else -> R.string.srv_unreachable
})

/** Image from the server. The token travels in a header (never in the URL) and only to the server itself. */
@Composable
fun Art(url: String?, headers: Map<String, String>, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    if (url == null) { Box(modifier.background(C.surfaceHi)); return }
    val ctx = LocalContext.current
    val req = remember(url, headers) {
        ImageRequest.Builder(ctx).data(url).httpHeaders(NetworkHeaders.Builder().also { b -> headers.forEach { (k, v) -> b[k] = v } }.build()).build()
    }
    AsyncImage(req, null, modifier, contentScale = scale)
}

fun runtimeText(ms: Long, h: String, m: String): String {
    val min = (ms / 60_000L).toInt(); if (min <= 0) return ""
    return if (min >= 60) "${min / 60}$h ${min % 60}$m" else "$min$m"
}

@Composable
fun Badge(text: String) {
    Box(Modifier.border(1.dp, C.text.copy(alpha = 0.45f), Rd(6)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = T.tiny.copy(color = C.text.copy(alpha = 0.85f), fontSize = 14.sp), maxLines = 1)
    }
}

/** Year, certificate, runtime, rating: spaced apart, never joined with separator characters. */
@Composable
fun MetaRow(item: MediaItem, badges: Boolean = true) {
    val h = stringResource(R.string.srv_h); val m = stringResource(R.string.srv_m)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        item.year?.let { Text("$it", style = T.small) }
        item.officialRating?.takeIf { it.isNotBlank() }?.let { Badge(it) }
        runtimeText(item.runtimeMs, h, m).takeIf { it.isNotEmpty() }?.let { Text(it, style = T.small) }
        item.rating?.let { Text("★ " + "%.1f".format(java.util.Locale.US, it), style = T.small) }
        if (badges) Badges.of(item).forEach { Badge(it) }
    }
}

@Composable
fun episodeLabel(season: Int?, ep: Int?): String = when {
    season != null && ep != null -> stringResource(R.string.srv_ep_label, season, ep)
    ep != null -> stringResource(R.string.srv_ep_only, ep)
    else -> ""
}

/** Shown name of a card: the series for an episode (the episode label goes on the second line). */
fun MediaItem.cardTitle(): String = if (type == ItemType.Episode && !seriesName.isNullOrBlank()) seriesName else name

@Composable
fun PosterCard(item: MediaItem, backend: MediaBackend, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 168.dp, onFocus: (Boolean) -> Unit = {}, onLongClick: (() -> Unit)? = null) {
    val headers = remember(backend) { backend.authHeaders() }
    val kind = if (item.type == ItemType.Episode && Img.Poster !in item.images) Img.Thumb else Img.Poster
    Column(modifier.width(width)) {
        Tv(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), Rd(12), onFocus = onFocus, onLongClick = onLongClick) {
            Art(backend.imageUrl(item, kind, 360), headers, Modifier.fillMaxSize())
            if (item.played) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).background(C.accent, Rd(8)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("✓", style = T.tiny.copy(color = C.onAccent)) }
            else if (item.unplayed > 0 && item.type == ItemType.Series) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).background(C.accent, Rd(8)).padding(horizontal = 8.dp, vertical = 2.dp)) { Text("${item.unplayed}", style = T.tiny.copy(color = C.onAccent)) }
            if (item.progress > 0f) ProgressBar(item.progress, Modifier.align(Alignment.BottomCenter).padding(10.dp))
        }
        Text(item.cardTitle(), style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
    }
}

/** 16:9 card for Continue watching and Next up. */
@Composable
fun WideCard(item: MediaItem, backend: MediaBackend, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit = {}, onLongClick: (() -> Unit)? = null) {
    val headers = remember(backend) { backend.authHeaders() }
    val kind = when { Img.Thumb in item.images -> Img.Thumb; Img.Backdrop in item.images -> Img.Backdrop; else -> Img.Poster }
    Column(modifier.width(320.dp)) {
        Tv(onClick, Modifier.fillMaxWidth().aspectRatio(16f / 9f), Rd(12), onFocus = onFocus, onLongClick = onLongClick) {
            Art(backend.imageUrl(item, kind, 640), headers, Modifier.fillMaxSize())
            if (item.progress > 0f) ProgressBar(item.progress, Modifier.align(Alignment.BottomCenter).padding(10.dp))
        }
        Text(item.cardTitle(), style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
        if (item.type == ItemType.Episode) Text(episodeLabel(item.seasonNumber, item.episodeNumber) + "  " + item.name, style = T.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
    }
}

/** Backdrop of the focused item with its title and a short plot, fading into the page background. */
@Composable
fun Hero(item: MediaItem?, backend: MediaBackend, height: Dp, modifier: Modifier = Modifier) {
    val headers = remember(backend) { backend.authHeaders() }
    Box(modifier.fillMaxWidth().height(height)) {
        if (item != null) {
            val kind = if (Img.Backdrop in item.images) Img.Backdrop else if (Img.Thumb in item.images) Img.Thumb else Img.Poster
            Art(backend.imageUrl(item, kind, 1280), headers, Modifier.fillMaxSize().padding(start = 220.dp))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to C.bg, 0.45f to C.bg.copy(alpha = 0.92f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to C.bg)))
        if (item != null) Column(Modifier.align(Alignment.CenterStart).widthIn(max = 760.dp).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.cardTitle(), style = T.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.type == ItemType.Episode) Text(episodeLabel(item.seasonNumber, item.episodeNumber) + "  " + item.name, style = T.title.copy(color = mutedInk()), maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaRow(item, badges = false)
            if (item.overview.isNotBlank()) Text(item.overview, style = T.small.copy(color = C.text.copy(alpha = 0.8f)), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
