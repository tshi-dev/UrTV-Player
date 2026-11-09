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
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
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

/** Tinted artwork placeholder: a stable hue per title with its initial, like the main UI's monograms. */
@Composable
fun ArtPlaceholder(title: String, modifier: Modifier = Modifier, monogram: Boolean = true) {
    val hue = ((title.hashCode() % 360) + 360) % 360
    val top = Color.hsv(hue.toFloat(), 0.32f, 0.34f); val bottom = Color.hsv(((hue + 30) % 360).toFloat(), 0.4f, 0.17f)
    Box(modifier.background(Brush.verticalGradient(listOf(top, bottom))), contentAlignment = Alignment.Center) {
        if (monogram && title.isNotBlank()) Text(title.trim().take(1).uppercase(), style = T.display.copy(color = Color.White.copy(alpha = 0.5f), fontSize = 56.sp))
    }
}

/** Image from the server. The token travels in a header (never in the URL) and only to the server itself. The placeholder shows until (or instead of) the picture. */
@Composable
fun Art(url: String?, headers: Map<String, String>, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, title: String = "", monogram: Boolean = true) {
    Box(modifier) {
        ArtPlaceholder(title, Modifier.fillMaxSize(), monogram)
        if (url != null) {
            val ctx = LocalContext.current
            val req = remember(url, headers) {
                ImageRequest.Builder(ctx).data(url).httpHeaders(NetworkHeaders.Builder().also { b -> headers.forEach { (k, v) -> b[k] = v } }.build()).build()
            }
            AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = scale)
        }
    }
}

/** The focused/open title, drawn full-bleed behind the whole server UI (set by the screens, drawn once by ServerRoot). */
object ServerBackdrop {
    var item by mutableStateOf<MediaItem?>(null)
    var backend by mutableStateOf<MediaBackend?>(null)
}

@Composable
fun BackdropLayer() {
    val it = ServerBackdrop.item; val b = ServerBackdrop.backend
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxSize()) {
        if (it != null && b != null) {
            val kind = when { Img.Backdrop in it.images -> Img.Backdrop; Img.Thumb in it.images -> Img.Thumb; else -> Img.Poster }
            Art(b.imageUrl(it, kind, 1920), b.authHeaders(), Modifier.fillMaxSize(), title = it.cardTitle(), monogram = false)
        }
        // start-side scrim (mirrored in RTL) keeps text readable; a bottom fade settles the rows
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = if (rtl) -1f else 1f }.background(Brush.horizontalGradient(0f to C.bg.copy(alpha = 0.97f), 0.5f to C.bg.copy(alpha = 0.78f), 1f to C.bg.copy(alpha = 0.18f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to C.bg.copy(alpha = 0.95f))))
    }
}

/** Small tick in a circle for watched items (theme accent, subtle). */
@Composable
fun WatchedMark(modifier: Modifier = Modifier, text: String = "✓") {
    Box(modifier.size(28.dp).background(C.bg.copy(alpha = 0.78f), CircleShape).border(1.5.dp, C.accent, CircleShape), contentAlignment = Alignment.Center) {
        Text(text, style = T.small.copy(color = C.accent, fontSize = 16.sp, lineHeight = 18.sp))
    }
}

fun runtimeText(ms: Long, h: String, m: String): String {
    val min = (ms / 60_000L).toInt(); if (min <= 0) return ""
    return if (min >= 60) "${min / 60}$h ${min % 60}$m" else "$min$m"
}

@Composable
fun Badge(text: String) {
    Box(Modifier.border(1.5.dp, C.text.copy(alpha = 0.5f), Rd(8)).padding(horizontal = 11.dp, vertical = 3.dp)) {
        Text(text, style = T.small.copy(color = C.text.copy(alpha = 0.9f), fontSize = 18.sp, lineHeight = 24.sp), maxLines = 1)
    }
}

@Composable
fun metaStyle() = T.small.copy(fontSize = 21.sp, lineHeight = 30.sp, color = C.text.copy(alpha = 0.78f))

/** Year, certificate, runtime, rating: spaced apart, never joined with separator characters. */
@Composable
fun MetaRow(item: MediaItem) {
    val h = stringResource(R.string.srv_h); val m = stringResource(R.string.srv_m)
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        item.year?.let { Text("$it", style = metaStyle()) }
        item.officialRating?.takeIf { it.isNotBlank() }?.let { Badge(it) }
        runtimeText(item.runtimeMs, h, m).takeIf { it.isNotEmpty() }?.let { Text(it, style = metaStyle()) }
        item.rating?.let { Text("★ " + "%.1f".format(java.util.Locale.US, it), style = metaStyle()) }
    }
}

/** Technical labels (4K, HDR10, Dolby Digital+, 5.1) as one neat row. */
@Composable
fun BadgeRow(item: MediaItem) {
    val list = Badges.of(item)
    if (list.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { list.forEach { Badge(it) } }
}

@Composable
fun episodeLabel(season: Int?, ep: Int?): String = when {
    season != null && ep != null -> stringResource(R.string.srv_ep_label, "$season", "$ep")
    ep != null -> stringResource(R.string.srv_ep_only, "$ep")
    else -> ""
}

/** Shown name of a card: the series for an episode (the episode label goes on the second line). */
fun MediaItem.cardTitle(): String = (if (type == ItemType.Episode) seriesName?.takeIf { it.isNotBlank() } else null) ?: name

@Composable
fun PosterCard(item: MediaItem, backend: MediaBackend, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 190.dp, onFocus: (Boolean) -> Unit = {}, onLongClick: (() -> Unit)? = null) {
    val headers = remember(backend) { backend.authHeaders() }
    val kind = if (item.type == ItemType.Episode && Img.Poster !in item.images) Img.Thumb else Img.Poster
    Column(modifier.width(width)) {
        Tv(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), Rd(12), onFocus = onFocus, onLongClick = onLongClick) {
            Art(backend.imageUrl(item, kind, 400), headers, Modifier.fillMaxSize(), title = item.cardTitle())
            if (item.played) WatchedMark(Modifier.align(Alignment.TopEnd).padding(8.dp))
            else if (item.unplayed > 0 && item.type == ItemType.Series) WatchedMark(Modifier.align(Alignment.TopEnd).padding(8.dp), "${item.unplayed}")
            if (item.progress > 0f) ProgressBar(item.progress, Modifier.align(Alignment.BottomCenter).padding(10.dp))
        }
        Text(item.cardTitle(), style = T.body.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 2.dp))
        item.year?.let { Text("$it", style = T.small, modifier = Modifier.padding(start = 2.dp)) }
    }
}

/** 16:9 card for Continue watching and Next up. */
@Composable
fun WideCard(item: MediaItem, backend: MediaBackend, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit = {}, onLongClick: (() -> Unit)? = null) {
    val headers = remember(backend) { backend.authHeaders() }
    val kind = when { Img.Thumb in item.images -> Img.Thumb; Img.Backdrop in item.images -> Img.Backdrop; else -> Img.Poster }
    Column(modifier.width(360.dp)) {
        Tv(onClick, Modifier.fillMaxWidth().aspectRatio(16f / 9f), Rd(12), onFocus = onFocus, onLongClick = onLongClick) {
            Art(backend.imageUrl(item, kind, 720), headers, Modifier.fillMaxSize(), title = item.cardTitle())
            if (item.progress > 0f) ProgressBar(item.progress, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
        Text(item.cardTitle(), style = T.body.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 2.dp))
        if (item.type == ItemType.Episode) Text(episodeLabel(item.seasonNumber, item.episodeNumber) + "  " + item.name, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
    }
}

/** Title, facts, plot and the action of OK for the focused item; the picture is the full-bleed [BackdropLayer] behind it. */
@Composable
fun Hero(item: MediaItem?, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height)) {
        if (item != null) Column(Modifier.align(Alignment.CenterStart).widthIn(max = 880.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.cardTitle(), style = T.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.type == ItemType.Episode) Text(episodeLabel(item.seasonNumber, item.episodeNumber) + "  " + item.name, style = T.title.copy(color = mutedInk()), maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaRow(item)
            if (item.overview.isNotBlank()) Text(item.overview, style = T.body.copy(fontSize = 24.sp, lineHeight = 34.sp, color = C.text.copy(alpha = 0.85f)), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp).background(C.accent, Rd(24)).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.urtv.player.ui.Icon(com.urtv.player.ui.Glyph.Play, C.onAccent, 22)
                Text(if (item.resumeMs > 0) stringResource(R.string.srv_resume, clockOf(item.resumeMs)) else stringResource(R.string.srv_play), style = T.bodyBold.copy(color = C.onAccent))
            }
        }
    }
}

fun clockOf(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(java.util.Locale.US, s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(java.util.Locale.US, s / 60, s % 60) }

@Composable
fun Centered(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
