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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.Episode
import com.urtv.player.core.Movie
import com.urtv.player.core.Series
import com.urtv.player.core.VodInfo
import com.urtv.player.data.PlaylistDef

/** One movie or series in a poster grid. */
data class Poster(val id: String, val title: String, val image: String?, val category: String, val meta: String?)

/** What the VOD player plays: [key] is where the resume position is stored; [kind] non-empty feeds "Continue watching". */
data class VodPlay(
    val title: String, val url: String, val key: String,
    val pid: String = "", val kind: String = "", val image: String? = null,
    val seriesId: String? = null, val season: Int = 0, val episode: Int = 0, val episodeTitle: String = "",
)

private const val ALL = "\u0000all"
fun vodKey(pid: String, id: String) = "vod|$pid|$id"

@Composable
fun PosterBrowse(app: UrtvApp, movies: Boolean, onOpen: (String) -> Unit) {
    val vod by app.vod.state.collectAsState()
    val items = remember(vod.movies, vod.series, movies) {
        if (movies) vod.movies.map { Poster(it.id, it.name, it.poster, it.category, it.rating) } else vod.series.map { Poster(it.id, it.name, it.poster, it.category, it.rating) }
    }
    val cats = remember(items) { items.map { it.category }.distinct().filter { it.isNotBlank() } }
    val counts = remember(items) { items.groupingBy { it.category }.eachCount() }
    var cat by remember(movies) { mutableStateOf(ALL) }
    var query by remember(movies) { mutableStateOf("") }
    val list = remember(items, cat, query) { items.filter { (cat == ALL || it.category == cat) && (query.isBlank() || it.title.contains(query.trim(), true)) } }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(listOf(ALL) + cats, key = { it }) { c ->
                Tv({ cat = c }, Modifier.fillMaxWidth().height(52.dp), selected = c == cat, onFocus = { if (it) cat = c }) {
                    Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (c == ALL) stringResource(R.string.all_categories) else c, style = if (c == cat) T.bodyBold else T.body.copy(color = C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${if (c == ALL) items.size else counts[c] ?: 0}", style = T.tiny)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            FocusField(query, { query = it }, stringResource(R.string.search_hint), Modifier.fillMaxWidth(), leading = { Icon(Glyph.Search, C.faint, 24) }, imeAction = androidx.compose.ui.text.input.ImeAction.Search, compact = true)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxSize()) {
                when {
                    list.isEmpty() && vod.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Spinner(); Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.loading), style = T.body.copy(color = C.muted)) }
                    list.isEmpty() && vod.failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.err_network), style = T.title); Spacer(Modifier.height(16.dp))
                        TvButton(stringResource(R.string.retry), { app.vod.open(app.store.playlists.value.firstOrNull { it.id == vod.playlistId }, true) }, primary = true) }
                    list.isEmpty() -> Text(stringResource(if (query.isBlank()) R.string.empty_group else R.string.no_results), style = T.body.copy(color = C.muted), modifier = Modifier.align(Alignment.Center))
                    else -> LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        itemsIndexed(list, key = { _, p -> p.id }) { _, p -> PosterCard(p, Modifier.fillMaxWidth()) { onOpen(p.id) } }
                    }
                }
            }
        }
    }
}

@Composable
fun PosterCard(p: Poster, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Tv(onClick, modifier.aspectRatio(0.68f), Rd(14), ring = true, lift = 1.05f, restColor = C.surface, focusRequester = focusRequester, onFocus = { if (it) Backdrop.url = p.image }) {
        PosterArt(p.image, p.title)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xE6000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(p.title, style = T.small.copy(color = Color.White, fontSize = 18.sp, lineHeight = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!p.meta.isNullOrBlank() && p.meta != "0") Text("★ ${p.meta}", style = T.tiny.copy(color = Color(0xFFB9D9FF)))
        }
    }
}

private fun hms(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** Poster on the start side; title, fact chips, plot and actions beside it. Facts are chips, never "a · b · c". */
@Composable
private fun DetailHeader(poster: String?, title: String, chips: List<String>, plot: String, credits: String, buttons: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Box(Modifier.width(220.dp).aspectRatio(0.68f).clip(Rd(16))) { PosterArt(poster, title) }
        Column(Modifier.weight(1f)) {
            Text(title, style = T.display.copy(fontSize = 46.sp, lineHeight = 60.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (chips.isNotEmpty()) Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { chips.forEach { Chip(it) } }
            if (plot.isNotBlank()) Text(plot, style = T.body.copy(color = C.muted), maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(900.dp))
            if (credits.isNotBlank()) Text(credits, style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).width(900.dp))
            Spacer(Modifier.height(22.dp))
            buttons()
        }
    }
}

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
    val chips = listOfNotNull(info.genre.takeIf { it.isNotBlank() }, m.rating?.takeIf { it != "0" }?.let { "★ $it" }, info.durationSec.takeIf { it > 0 }?.let { stringResource(R.string.min_n, it / 60) })
    val play = VodPlay(m.name, m.url, key, def.id, "movie", m.poster)
    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 72.dp, vertical = 56.dp)) {
        DetailHeader(m.poster, m.name, chips, info.plot, listOf(info.director, info.cast).filter { it.isNotBlank() }.joinToString("\n"), buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (pos > 0) TvButton(stringResource(R.string.resume_from, hms(pos)), { onPlay(play, pos) }, primary = true, focusRequester = fr)
                TvButton(stringResource(if (pos > 0) R.string.from_start else R.string.play), { onPlay(play, 0) }, primary = pos == 0L, focusRequester = if (pos > 0) null else fr)
                TvButton(stringResource(R.string.back), onBack)
            }
        })
    }
}

@Composable
fun SeriesDetail(app: UrtvApp, def: PlaylistDef, id: String, onPlay: (List<VodPlay>, Int, Long) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val s: Series? = remember(id) { app.vod.state.value.series.firstOrNull { it.id == id } }
    if (s == null) { onBack(); return }
    val eps by produceState<List<Episode>?>(null) { value = app.vod.episodes(def, id) }
    val seasons = remember(eps) { eps.orEmpty().map { it.season }.distinct() }
    var season by remember(seasons) { mutableStateOf(seasons.firstOrNull() ?: 1) }
    val all = eps.orEmpty()
    val list = remember(eps, season) { all.filter { it.season == season } }
    val fr = remember { FocusRequester() }
    val epKey = { e: Episode -> vodKey(def.id, e.id) }
    // the episode you were in the middle of, else the first one you have not finished, else the first
    val target = remember(list) { list.indexOfFirst { app.store.position(epKey(it)) > 0 }.takeIf { it >= 0 } ?: list.indexOfFirst { !app.store.isWatched(epKey(it)) }.coerceAtLeast(0) }
    LaunchedEffect(season, eps) { if (eps != null) runCatching { fr.requestFocus() } }
    fun playlist(from: List<Episode>) = from.map { VodPlay(s.name, it.url, epKey(it), def.id, "episode", it.image ?: s.poster, id, it.season, it.number, it.title) }

    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 72.dp, vertical = 44.dp)) {
        Text(s.name, style = T.display.copy(fontSize = 46.sp, lineHeight = 60.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            s.year?.let { Chip(it) }; s.rating?.takeIf { it != "0" }?.let { Chip("★ $it") }
            if (all.isNotEmpty()) Chip(stringResource(R.string.episodes_n, all.size))
        }
        if (s.plot.isNotBlank()) Text(s.plot, style = T.body.copy(color = C.muted), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(1000.dp))
        Spacer(Modifier.height(16.dp))
        when {
            eps == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { Spinner() }
            all.isEmpty() -> Column { Text(stringResource(R.string.err_empty), style = T.body.copy(color = C.muted)); Spacer(Modifier.height(16.dp)); TvButton(stringResource(R.string.back), onBack, focusRequester = fr) }
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(seasons) { n ->
                        Tv({ season = n }, Modifier.height(48.dp), Rd(10), selected = n == season, onFocus = { if (it) season = n }, lift = 1f) {
                            Text(stringResource(R.string.season_n, n), style = if (n == season) T.bodyBold else T.body.copy(color = C.muted), modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp))
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(list.size) { i ->
                        val e = list[i]; val k = epKey(e); val pos = app.store.position(k); val done = app.store.isWatched(k)
                        val dur = e.durationSec * 1000L
                        Tv({ onPlay(playlist(list.drop(i)), 0, pos) }, Modifier.fillMaxWidth().height(96.dp), Rd(12), focusRequester = if (i == target) fr else null) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(150.dp).height(84.dp).clip(Rd(8))) { PosterArt(e.image, e.title.ifBlank { s.name }) }
                                Text("${e.number}", style = T.title.copy(color = C.faint, fontSize = 26.sp), modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Column(Modifier.weight(1f)) {
                                    Text(e.title.ifBlank { stringResource(R.string.episode_n, e.number) }, style = T.row.copy(fontSize = 23.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (e.plot.isNotBlank()) Text(e.plot, style = T.small.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (pos > 0 && dur > 0) { Spacer(Modifier.height(5.dp)); ProgressBar(pos / dur.toFloat(), Modifier.width(240.dp)) }
                                }
                                when {
                                    done -> Icon(Glyph.Check, C.faint, 26)
                                    pos > 0 && dur > 0 -> Text(stringResource(R.string.min_left, ((dur - pos) / 60_000L).toInt().coerceAtLeast(1)), style = T.small)
                                    e.durationSec > 0 -> Text(stringResource(R.string.min_n, e.durationSec / 60), style = T.small)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
