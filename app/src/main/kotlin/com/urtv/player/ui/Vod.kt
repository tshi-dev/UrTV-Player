package com.urtv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.Episode
import com.urtv.player.core.Movie
import com.urtv.player.core.Series
import com.urtv.player.core.VodInfo
import com.urtv.player.data.PlaylistDef

/** One movie or series in the poster grid. */
data class Poster(val id: String, val title: String, val image: String?, val category: String, val meta: String?)

/** What the VOD player plays: [key] is where the resume position is stored. */
data class VodPlay(
    val title: String, val url: String, val key: String,
    /** Set for movies/episodes so progress feeds "Continue watching" (empty for catch-up). */
    val pid: String = "", val kind: String = "", val subtitle: String = "", val image: String? = null,
)

private const val ALL = "\u0000all"
fun vodKey(pid: String, id: String) = "vod|$pid|$id"

@Composable
fun PosterBrowse(app: UrtvApp, movies: Boolean, onOpen: (String) -> Unit) {
    val vod by app.vod.state.collectAsState()
    val items = remember(vod.movies, vod.series, movies) {
        if (movies) vod.movies.map { Poster(it.id, it.name, it.poster, it.category.ifBlank { "" }, it.rating) }
        else vod.series.map { Poster(it.id, it.name, it.poster, it.category.ifBlank { "" }, it.rating) }
    }
    val cats = remember(items) { items.map { it.category }.distinct().filter { it.isNotBlank() } }
    var cat by remember(movies) { mutableStateOf(ALL) }
    var query by remember(movies) { mutableStateOf("") }
    val list = remember(items, cat, query) {
        items.filter { (cat == ALL || it.category == cat) && (query.isBlank() || it.title.contains(query.trim(), true)) }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        LazyColumn(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(8.dp)) {
            items(listOf(ALL) + cats, key = { it }) { c ->
                Tv({ cat = c }, Modifier.fillMaxWidth().height(56.dp), RoundedCornerShape(14.dp), selected = c == cat, onFocus = { if (it) cat = c }) {
                    Text(if (c == ALL) stringResource(R.string.all_categories) else c, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            FocusField(query, { query = it }, stringResource(R.string.search_hint), Modifier.fillMaxWidth(), leading = { Icon(Glyph.Search, C.muted, 26) },
                imeAction = androidx.compose.ui.text.input.ImeAction.Search)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxSize()) {
                when {
                    list.isEmpty() && vod.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Spinner(); Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.loading), style = T.body.copy(color = C.muted)) }
                    list.isEmpty() && vod.failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.err_network), style = T.title); Spacer(Modifier.height(16.dp))
                        TvButton(stringResource(R.string.retry), { app.vod.open(app.store.playlists.value.firstOrNull { it.id == vod.playlistId }, true) }, primary = true) }
                    list.isEmpty() -> Text(stringResource(if (query.isBlank()) R.string.empty_group else R.string.no_results), style = T.body.copy(color = C.muted), modifier = Modifier.align(Alignment.Center))
                    else -> LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        itemsIndexed(list, key = { _, p -> p.id }) { _, p -> PosterCard(p) { onOpen(p.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(p: Poster, onClick: () -> Unit) {
    Tv(onClick, Modifier.fillMaxWidth().aspectRatio(0.68f), RoundedCornerShape(16.dp)) {
        Placeholder(p.title)
        if (!p.image.isNullOrBlank()) SubcomposeAsyncImage(p.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, loading = {}, error = {})
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xEE05070B))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(p.title, style = T.small.copy(color = C.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!p.meta.isNullOrBlank() && p.meta != "0") Text("★ ${p.meta}", style = T.tiny.copy(color = C.accent2))
        }
    }
}

/** Calm tinted tile shown until (or instead of) a poster; the tint is derived from the title so it is stable. */
@Composable
private fun Placeholder(title: String) {
    val h = (title.hashCode().toUInt() % 360u).toFloat()
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.hsv(h, 0.35f, 0.32f), Color.hsv((h + 40) % 360, 0.45f, 0.18f)))), contentAlignment = Alignment.Center) {
        Text(title.trim().take(1), style = T.display.copy(color = Color.White.copy(alpha = 0.35f)))
    }
}

@Composable
private fun DetailFrame(poster: String?, title: String, meta: String, plot: String, extra: String, buttons: @Composable () -> Unit, below: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 64.dp, vertical = 40.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Box(Modifier.width(240.dp).aspectRatio(0.68f).clip(RoundedCornerShape(20.dp))) {
                Placeholder(title)
                if (!poster.isNullOrBlank()) SubcomposeAsyncImage(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, loading = {}, error = {})
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = T.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (meta.isNotBlank()) Text(meta, style = T.body.copy(color = C.accent2))
                Spacer(Modifier.height(12.dp))
                if (plot.isNotBlank()) Text(plot, style = T.body, maxLines = 5, overflow = TextOverflow.Ellipsis)
                if (extra.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(extra, style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(24.dp))
                buttons()
            }
        }
        Spacer(Modifier.height(20.dp))
        below()
    }
}

private fun hms(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

@Composable
fun MovieDetail(app: UrtvApp, def: PlaylistDef, id: String, onPlay: (VodPlay, Long) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val m: Movie? = remember(id) { app.vod.state.value.movies.firstOrNull { it.id == id } }
    if (m == null) { onBack(); return }
    val info by produceState(VodInfo()) { value = app.vod.movieInfo(def, id) }
    val key = vodKey(def.id, id)
    val pos = remember { app.store.position(key) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val meta = listOfNotNull(info.genre.ifBlank { null }, m.rating?.takeIf { it != "0" }?.let { "★ $it" }, info.durationSec.takeIf { it > 0 }?.let { "${it / 60} ${"min"}" }).joinToString("  ·  ")
    DetailFrame(m.poster, m.name, meta, info.plot, listOf(info.director.takeIf { it.isNotBlank() }, info.cast.takeIf { it.isNotBlank() }).filterNotNull().joinToString("  ·  "), buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (pos > 0) TvButton(stringResource(R.string.resume_from, hms(pos)), { onPlay(VodPlay(m.name, m.url, key, def.id, "movie", "", m.poster), pos) }, primary = true, focusRequester = fr)
            TvButton(stringResource(if (pos > 0) R.string.from_start else R.string.play), { onPlay(VodPlay(m.name, m.url, key, def.id, "movie", "", m.poster), 0) }, primary = pos == 0L, focusRequester = if (pos > 0) null else fr)
            TvButton(stringResource(R.string.back), onBack)
        }
    })
}

@Composable
fun SeriesDetail(app: UrtvApp, def: PlaylistDef, id: String, onPlay: (List<VodPlay>, Int, Long) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val s: Series? = remember(id) { app.vod.state.value.series.firstOrNull { it.id == id } }
    if (s == null) { onBack(); return }
    val eps by produceState<List<Episode>?>(null) { value = app.vod.episodes(def, id) }
    val seasons = remember(eps) { eps.orEmpty().map { it.season }.distinct() }
    var season by remember(seasons) { mutableStateOf(seasons.firstOrNull() ?: 1) }
    val list = remember(eps, season) { eps.orEmpty().filter { it.season == season } }
    DetailFrame(s.poster, s.name, listOfNotNull(s.year, s.rating?.takeIf { it != "0" }?.let { "★ $it" }).joinToString("  ·  "), s.plot, "", buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { TvButton(stringResource(R.string.back), onBack) }
    }, below = {
        when {
            eps == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Spinner() }
            eps!!.isEmpty() -> Text(stringResource(R.string.err_empty), style = T.body.copy(color = C.muted))
            else -> {
                val fr = remember { FocusRequester() }
                LaunchedEffect(season, eps) { runCatching { fr.requestFocus() } }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
                    items(seasons) { n -> Tv({ season = n }, Modifier.height(48.dp), RoundedCornerShape(24.dp), selected = n == season, onFocus = { if (it) season = n }) {
                        Text(stringResource(R.string.season_n, n), style = if (n == season) T.bodyBold else T.body.copy(color = C.muted), modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp)) } }
                }
                // The episode you were in the middle of (else the first) gets focus.
                val firstOpen = list.indexOfFirst { app.store.position(vodKey(def.id, it.id)) > 0 }.coerceAtLeast(0)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list.size) { i ->
                        val e = list[i]; val k = vodKey(def.id, e.id); val pos = app.store.position(k)
                        Tv({ onPlay(list.map { VodPlay(s.name, it.url, vodKey(def.id, it.id), def.id, "episode", "S${it.season}·E${it.number} · ${it.title}", it.image ?: s.poster) }, i, pos) }, Modifier.fillMaxWidth().height(80.dp),
                            focusRequester = if (i == firstOpen) fr else null) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${e.number}", style = T.title.copy(color = C.accent2), modifier = Modifier.width(56.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, style = T.bodyBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (e.plot.isNotBlank()) Text(e.plot, style = T.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (pos > 0 && e.durationSec > 0) ProgressBar(pos / (e.durationSec * 1000f), Modifier.fillMaxWidth(0.4f))
                                }
                                if (e.durationSec > 0) Text("${e.durationSec / 60} min", style = T.small)
                            }
                        }
                    }
                }
            }
        }
    })
}
