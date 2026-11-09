package com.urtv.player.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.urtv.player.R
import com.urtv.player.ui.C
import com.urtv.player.ui.ProgressBar
import com.urtv.player.ui.Rd
import com.urtv.player.ui.SectionLabel
import com.urtv.player.ui.Spinner
import com.urtv.player.ui.T
import com.urtv.player.ui.Tv
import com.urtv.player.ui.TvButton
import com.urtv.player.ui.mutedInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun clockText(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** Movie, show or collection page. The show title appears once, at the top; episodes carry only their own label. */
@Composable
fun DetailScreen(backend: MediaBackend, id: String, onPlay: (MediaItem, Long, List<MediaItem>) -> Unit, onOpen: (MediaItem) -> Unit) {
    var version by remember { mutableStateOf(0) }
    val res by load(Triple(backend.config.id, id, version)) { backend.item(id) ?: throw ServerException(ServerException.Reason.Refused, "missing") }
    when (val r = res) {
        is Res.Loading -> Centered { Spinner() }
        is Res.Fail -> Centered { Text(errorText(r.e), style = T.title) }
        is Res.Ok -> when (r.v.type) {
            ItemType.Series -> ShowPage(backend, r.v, version, { version++ }, onPlay)
            ItemType.BoxSet -> CollectionPage(backend, r.v, onOpen)
            else -> MoviePage(backend, r.v, { version++ }, onPlay, onOpen)
        }
    }
}

@Composable
private fun Backdrop(item: MediaItem, backend: MediaBackend) {
    val headers = remember(backend) { backend.authHeaders() }
    Box(Modifier.fillMaxSize()) {
        val kind = if (Img.Backdrop in item.images) Img.Backdrop else Img.Poster
        Art(backend.imageUrl(item, kind, 1280), headers, Modifier.fillMaxSize().padding(start = 360.dp))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to C.bg, 0.5f to C.bg.copy(alpha = 0.93f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to C.bg)))
    }
}

@Composable
private fun MoviePage(backend: MediaBackend, item: MediaItem, onChanged: () -> Unit, onPlay: (MediaItem, Long, List<MediaItem>) -> Unit, onOpen: (MediaItem) -> Unit) {
    val scope = rememberCoroutineScope()
    val similar by load(item.id) { backend.similar(item.id) }
    val first = remember { FocusRequester() }
    LaunchedEffect(item.id) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        Backdrop(item, backend)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Column(Modifier.widthIn(max = 820.dp).padding(top = 24.dp, start = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.name, style = T.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.tagline.isNotBlank()) Text(item.tagline, style = T.body.copy(color = mutedInk()), maxLines = 1)
                    MetaRow(item)
                    if (item.genres.isNotEmpty()) Text(item.genres.take(4).joinToString("   "), style = T.small)
                    if (item.overview.isNotBlank()) Text(item.overview, style = T.body.copy(color = C.text.copy(alpha = 0.85f)), maxLines = 5, overflow = TextOverflow.Ellipsis)
                    if (item.resumeMs > 0 && item.runtimeMs > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProgressBar(item.progress, Modifier.width(260.dp)); Text(stringResource(R.string.srv_left_min, ((item.runtimeMs - item.resumeMs) / 60_000L).toInt().coerceAtLeast(1)), style = T.small)
                    }
                    ActionRow(backend, item, onChanged, onPlay, first)
                }
            }
            if (item.people.isNotEmpty()) item { CastRow(item) }
            val sim = (similar as? Res.Ok)?.v.orEmpty()
            if (sim.isNotEmpty()) item { ItemRow(stringResource(R.string.srv_similar), sim, backend, wide = false, onFocus = {}, onClick = onOpen, onLong = null) }
        }
    }
}

@Composable
private fun ActionRow(backend: MediaBackend, item: MediaItem, onChanged: () -> Unit, onPlay: (MediaItem, Long, List<MediaItem>) -> Unit, first: FocusRequester) {
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
        if (item.resumeMs > 0) {
            TvButton(stringResource(R.string.srv_resume, clockText(item.resumeMs)), { onPlay(item, item.resumeMs, emptyList()) }, primary = true, focusRequester = first)
            TvButton(stringResource(R.string.srv_from_start), { onPlay(item, 0, emptyList()) })
        } else TvButton(stringResource(R.string.srv_play), { onPlay(item, 0, emptyList()) }, primary = true, focusRequester = first)
        TvButton(stringResource(if (item.played) R.string.srv_mark_unwatched else R.string.srv_mark_watched), {
            scope.launch { withContext(Dispatchers.IO) { runCatching { backend.setPlayed(item, !item.played) } }; onChanged() }
        })
        if (item.trailers.isNotEmpty()) TvButton(stringResource(R.string.srv_trailer), {
            scope.launch { val t = withContext(Dispatchers.IO) { runCatching { backend.trailerItems(item).firstOrNull() }.getOrNull() }; if (t != null) onPlay(t, 0, emptyList()) }
        })
    }
}

@Composable
private fun CastRow(item: MediaItem) {
    Column {
        SectionLabel(stringResource(R.string.srv_cast))
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(item.people.filter { it.kind == null || it.kind == "Actor" || it.kind == "GuestStar" }.take(20)) { p ->
                Column(Modifier.width(210.dp).background(C.surface, Rd(12)).padding(14.dp)) {
                    Text(p.name, style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.role ?: " ", style = T.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CollectionPage(backend: MediaBackend, item: MediaItem, onOpen: (MediaItem) -> Unit) {
    val kids by load(item.id) { backend.children(item.id) }
    Column(Modifier.fillMaxSize()) {
        Text(item.name, style = T.display, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
        when (val r = kids) {
            is Res.Loading -> Centered { Spinner() }
            is Res.Fail -> Centered { Text(errorText(r.e), style = T.title) }
            is Res.Ok -> LazyVerticalGrid(GridCells.Adaptive(168.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                gridItems(r.v, key = { it.id }) { PosterCard(it, backend, { onOpen(it) }) }
            }
        }
    }
}

@Composable
private fun ShowPage(backend: MediaBackend, show: MediaItem, version: Int, onChanged: () -> Unit, onPlay: (MediaItem, Long, List<MediaItem>) -> Unit) {
    val scope = rememberCoroutineScope()
    val seasonsRes by load(Pair(show.id, version)) { backend.seasons(show.id) }
    val seasons = (seasonsRes as? Res.Ok)?.v.orEmpty()
    var seasonId by remember(show.id) { mutableStateOf<String?>(null) }
    // Open on the first season that still has unwatched episodes.
    val chosen = seasonId ?: (seasons.firstOrNull { it.unplayed > 0 } ?: seasons.firstOrNull())?.id
    val epsRes by load(Triple(show.id, chosen, version)) { if (chosen == null) emptyList() else backend.episodes(show.id, chosen) }
    val eps = (epsRes as? Res.Ok)?.v.orEmpty()
    val playTarget = eps.firstOrNull { it.resumeMs > 0 } ?: eps.firstOrNull { !it.played } ?: eps.firstOrNull()
    val first = remember { FocusRequester() }
    LaunchedEffect(playTarget?.id) { if (playTarget != null) runCatching { first.requestFocus() } }
    val headers = remember(backend) { backend.authHeaders() }

    Box(Modifier.fillMaxSize()) {
        Backdrop(show, backend)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(Modifier.widthIn(max = 820.dp).padding(top = 24.dp, start = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(show.name, style = T.display, maxLines = 2, overflow = TextOverflow.Ellipsis)          // the series name: once
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        show.year?.let { Text("$it", style = T.small) }
                        show.officialRating?.takeIf { it.isNotBlank() }?.let { Badge(it) }
                        if (seasons.isNotEmpty()) Text(stringResource(R.string.srv_seasons_n, seasons.size), style = T.small)
                        show.rating?.let { Text("★ " + "%.1f".format(java.util.Locale.US, it), style = T.small) }
                    }
                    if (show.genres.isNotEmpty()) Text(show.genres.take(4).joinToString("   "), style = T.small)
                    if (show.overview.isNotBlank()) Text(show.overview, style = T.body.copy(color = C.text.copy(alpha = 0.85f)), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (playTarget != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                        val label = episodeLabel(playTarget.seasonNumber, playTarget.episodeNumber)
                        TvButton(stringResource(if (playTarget.resumeMs > 0) R.string.srv_resume_ep else R.string.srv_play_ep, label), { onPlay(playTarget, playTarget.resumeMs, eps.dropWhile { it.id != playTarget.id }.drop(1)) }, primary = true, focusRequester = first)
                    }
                }
            }
            if (seasons.size > 1) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(seasons, key = { it.id }) { s ->
                        Tv({ seasonId = s.id }, Modifier.height(48.dp), Rd(24), selected = s.id == chosen) {
                            Text(if (s.seasonNumber != null && s.seasonNumber == 0) stringResource(R.string.srv_specials) else if (s.seasonNumber != null) stringResource(R.string.srv_season_n, s.seasonNumber!!) else s.name,
                                style = T.body, modifier = Modifier.align(Alignment.Center).padding(horizontal = 20.dp), maxLines = 1)
                        }
                    }
                }
            }
            when (val r = epsRes) {
                is Res.Loading -> item { Centered { Spinner() } }
                is Res.Fail -> item { Text(errorText(r.e), style = T.title) }
                is Res.Ok -> items(r.v, key = { it.id }) { e ->
                    Tv({ onPlay(e, e.resumeMs, r.v.dropWhile { it.id != e.id }.drop(1)) }, Modifier.fillMaxWidth().height(124.dp), Rd(14),
                        onLongClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { backend.setPlayed(e, !e.played) } }; onChanged() } }) {
                        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(0.34f).aspectRatio(16f / 9f)) {
                                Art(backend.imageUrl(e, Img.Thumb, 480), headers, Modifier.fillMaxSize().background(C.surfaceHi, Rd(10)))
                                if (e.progress > 0f) ProgressBar(e.progress, Modifier.align(Alignment.BottomCenter).padding(8.dp))
                            }
                            Column(Modifier.weight(0.66f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(e.episodeNumber?.let { "$it" } ?: "", style = T.bodyBold.copy(color = com.urtv.player.ui.accentInk()))
                                    Text(e.name, style = T.bodyBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (e.played) Text("✓", style = T.small.copy(color = com.urtv.player.ui.accentInk()))
                                    val rt = runtimeText(e.runtimeMs, stringResource(R.string.srv_h), stringResource(R.string.srv_m)); if (rt.isNotEmpty()) Text(rt, style = T.tiny)
                                }
                                if (e.overview.isNotBlank()) Text(e.overview, style = T.tiny, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
