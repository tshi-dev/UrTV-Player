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
import com.urtv.player.data.VodQuery
import com.urtv.player.data.VodRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One movie or series in a poster grid. */
data class Poster(val id: String, val title: String, val image: String?, val category: String, val meta: String?, val added: Long = 0, val fresh: Boolean = false)

/** What the VOD player plays: [key] is where the resume position is stored; [kind] non-empty feeds "Continue watching". */
data class VodPlay(
    val title: String, val url: String, val key: String,
    val pid: String = "", val kind: String = "", val image: String? = null,
    val seriesId: String? = null, val season: Int = 0, val episode: Int = 0, val episodeTitle: String = "",
)

private const val ALL = "\u0000all"
private const val LATEST = "\u0000latest"
private const val FAVS = "\u0000favs"
fun vodKey(pid: String, id: String) = "vod|$pid|$id"

/** A long list read from the disk index a page at a time: only the pages near the screen are held (and a few behind), the rest is asked for as it is scrolled to. */
class PagedPosters(val count: Int, private val scope: kotlinx.coroutines.CoroutineScope, private val load: suspend (Int, Int) -> List<Poster>) {
    private val pages = HashMap<Int, List<Poster>>(); private val asked = HashSet<Int>()
    private var tick by androidx.compose.runtime.mutableIntStateOf(0)
    fun at(i: Int): Poster? {
        tick                                                    // read: the card recomposes when its page arrives
        val p = i / PAGE
        pages[p]?.let { return it.getOrNull(i % PAGE) }
        if (asked.add(p)) scope.launch {
            val l = load(p * PAGE, PAGE)
            pages[p] = l
            if (pages.size > KEEP) pages.keys.sortedByDescending { kotlin.math.abs(it - p) }.take(pages.size - KEEP).forEach { pages.remove(it); asked.remove(it) }
            tick++
        }
        return null
    }
    companion object { const val PAGE = 60; const val KEEP = 14 }
}

@Composable
fun PosterBrowse(app: UrtvApp, movies: Boolean, onOpen: (String) -> Unit) {
    val vod by app.vod.state.collectAsState()
    val pid = vod.playlistId.orEmpty(); val kind = if (movies) VodRow.MOVIE else VodRow.SERIES
    val total = if (movies) vod.movieCount else vod.seriesCount
    val favs by app.store.favs.collectAsState()
    var cat by remember(movies) { mutableStateOf(LATEST) }
    var query by remember(movies) { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // the provider's category order with the number of items in each: one grouped read of the index, off the main thread
    val cats by produceState(emptyList<Pair<String, Int>>(), vod.rev, pid, kind) { value = withContext(Dispatchers.IO) { app.vod.db.cats(pid, kind).filter { it.first.isNotBlank() } } }
    val favIds = remember(favs, pid) { favs.filter { it.startsWith("vod|$pid|") }.map { it.removePrefix("vod|$pid|") }.toSet() }
    val favCount by produceState(0, favIds, vod.rev, pid, kind) { value = withContext(Dispatchers.IO) { if (favIds.isEmpty()) 0 else app.vod.db.size(VodQuery(pid, kind, ids = favIds)) } }
    val vq = remember(pid, kind, cat, query, favIds) { VodQuery(pid, kind, cat = cat.takeIf { it != ALL && it != LATEST && it != FAVS }, text = query, ids = if (cat == FAVS) favIds else null, newest = cat == LATEST, limit = if (cat == LATEST) 200 else Int.MAX_VALUE) }
    val list by produceState<PagedPosters?>(null, vq, vod.rev) {
        if (vq.text.isNotBlank()) kotlinx.coroutines.delay(150)      // one search per pause in typing, not per key
        val n = withContext(Dispatchers.IO) { app.vod.db.size(vq) }
        value = PagedPosters(n, scope) { o, l -> withContext(Dispatchers.IO) { app.vod.db.page(vq, o, l).map { Poster(it.id, it.name, it.poster, it.cat, it.rating, it.added, it.fresh()) } } }
    }
    val shown = list?.count ?: 0
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(listOf(LATEST, FAVS, ALL) + cats.map { it.first }, key = { it }) { c ->
                Tv({ cat = c }, Modifier.fillMaxWidth().height(52.dp), selected = c == cat, onFocus = { if (it) cat = c }) {
                    Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (c == ALL) stringResource(R.string.all_categories) else if (c == LATEST) stringResource(R.string.vod_latest) else if (c == FAVS) stringResource(R.string.tab_favs) else c, style = if (c == cat) T.bodyBold else T.body.copy(color = C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${if (c == ALL) total else if (c == LATEST) minOf(total, 200) else if (c == FAVS) favCount else cats.firstOrNull { it.first == c }?.second ?: 0}", style = T.tiny)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // an M3U list: said once that the Xtream login gives posters, plots and episodes
            val hint = remember { app.store.playlists.value.firstOrNull { it.id == vod.playlistId }?.isXtream == false && !app.getSharedPreferences("urtv", 0).getBoolean("xtreamHint", false) }
            if (hint) {
                LaunchedEffect(Unit) { app.getSharedPreferences("urtv", 0).edit().putBoolean("xtreamHint", true).apply() }
                Text(stringResource(R.string.xtream_hint), style = T.small, modifier = Modifier.padding(bottom = 12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusField(query, { query = it }, stringResource(R.string.search_hint), Modifier.weight(1f), leading = { Icon(Glyph.Search, C.faint, 24) }, imeAction = androidx.compose.ui.text.input.ImeAction.Search, compact = true)
                if (vod.loading) TvButton(stringResource(R.string.vod_skip), { app.vod.skip() })
            }
            if (vod.loading) VodProgress(vod)
            else if (vod.fresh > 0) Text(stringResource(R.string.vod_fresh_n, vod.fresh), style = T.tiny.copy(color = C.accent), modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxSize()) {
                when {
                    list == null || (shown == 0 && vod.loading) -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), userScrollEnabled = false) { items(10) { SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(0.68f), 14) } }
                    shown == 0 && vod.failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.err_network), style = T.title); Spacer(Modifier.height(16.dp))
                        TvButton(stringResource(R.string.retry), { app.vod.open(app.store.playlists.value.firstOrNull { it.id == vod.playlistId }, true) }, primary = true) }
                    shown == 0 -> Text(stringResource(if (query.isBlank()) R.string.empty_group else R.string.no_results), style = T.body.copy(color = C.muted), modifier = Modifier.align(Alignment.Center))
                    else -> LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        val l = list!!
                        items(l.count, key = { it }) { i -> val p = l.at(i); if (p != null) PosterCard(p, Modifier.fillMaxWidth()) { onOpen(p.id) } else SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(0.68f), 14) }
                    }
                }
            }
        }
    }
}

/** «جارٍ التحديث… 12 من 300 — الفئة» with a bar that moves; the lists already on disk stay usable underneath. */
@Composable
private fun VodProgress(v: com.urtv.player.data.VodState) {
    val frac = if (v.totalCats > 0) (v.doneCats.toFloat() / v.totalCats).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(top = 8.dp)) {
        Text(if (v.totalCats > 1) stringResource(R.string.vod_updating, v.doneCats, v.totalCats) + if (v.current.isNotBlank()) " — ${v.current}" else "" else stringResource(R.string.vod_updating_rows, v.rows), style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.surface)) { Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(C.accent)) }
    }
}

@Composable
fun PosterCard(p: Poster, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Tv(onClick, modifier.aspectRatio(0.68f), Rd(14), ring = true, lift = 1.05f, restColor = C.surface, focusRequester = focusRequester, onFocus = { if (it) Backdrop.url = p.image }) {
        PosterArt(p.image, p.title)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xE6000000))))
        if (p.fresh) Text(stringResource(R.string.vod_new), style = T.tiny.copy(color = Color.White), modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(C.accent, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(p.title, style = T.small.copy(color = Color.White, fontSize = 18.sp, lineHeight = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!p.meta.isNullOrBlank() && p.meta != "0") Text("★ ${p.meta}", style = T.tiny.copy(color = Color(0xFFB9D9FF)))
        }
    }
}

private fun hms(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** Poster on the start side; title, fact chips, plot and actions beside it. Facts are chips, never "a · b · c". */
@Composable
private fun DetailHeader(poster: String?, title: String, chips: List<String>, plot: String, credits: String, buttons: @Composable () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Box(Modifier.width(220.dp).aspectRatio(0.68f).clip(Rd(16))) { PosterArt(poster, title) }
        Column(Modifier.weight(1f)) {
            Text(title, style = T.display.copy(fontSize = 46.sp, lineHeight = 60.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (chips.isNotEmpty()) Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { chips.forEach { Chip(it) } }
            if (plot.isNotBlank()) Text(plot, style = T.body.copy(color = C.muted), maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(900.dp))
            if (credits.isNotBlank()) Text(credits, style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).width(900.dp))
            Spacer(Modifier.height(22.dp))
            buttons()
            extra()
        }
    }
}

private fun yearIn(name: String): String? = Regex("""\((\d{4})\)""").find(name)?.groupValues?.get(1)

private fun openUrl(ctx: android.content.Context, url: String) { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }

/** TMDB's required attribution, shown wherever its data is. */
@Composable
private fun TmdbCredit() { Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", style = T.tiny.copy(color = C.faint), modifier = Modifier.padding(top = 14.dp)) }

/** Cast with photos (TMDB) under the buttons of the details page. */
@Composable
private fun CastRow(t: com.urtv.player.core.Tmdb.Info) {
    if (t.cast.isEmpty()) return
    Text(stringResource(R.string.tmdb_cast), style = T.bodyBold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(t.cast.size) { i -> val p = t.cast[i]
            Column(Modifier.width(104.dp)) {
                Box(Modifier.width(104.dp).height(104.dp).clip(Rd(14))) { PosterArt(com.urtv.player.core.Tmdb.profile(p.photo), p.name) }
                Text(p.name, style = T.tiny, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
fun MovieDetail(app: UrtvApp, def: PlaylistDef, id: String, onPlay: (VodPlay, Long) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val m: Movie? = remember(id) { app.vod.movie(def, id) }
    if (m == null) { onBack(); return }
    val info by produceState(VodInfo()) { value = app.vod.movieInfo(def, id) }
    val key = vodKey(def.id, id)
    val pos = remember { app.store.position(key) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val tm by produceState<com.urtv.player.core.Tmdb.Info?>(null, m.name, info.tmdbId) { value = app.tmdb.info(m.name, yearIn(m.name), false, info.tmdbId) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val chips = listOfNotNull(tm?.year?.takeIf { it.isNotBlank() }, tm?.age?.takeIf { it.isNotBlank() }, (info.genre.takeIf { it.isNotBlank() } ?: tm?.genres?.joinToString("، ")?.takeIf { it.isNotBlank() }), m.rating?.takeIf { it != "0" }?.let { "★ $it" } ?: tm?.rating?.takeIf { it > 0 }?.let { "TMDB ★ %.1f".format(java.util.Locale.US, it) }, (info.durationSec.takeIf { it > 0 }?.let { it / 60 } ?: tm?.runtimeMin?.takeIf { it > 0 })?.let { stringResource(R.string.min_n, it) })
    val play = VodPlay(m.name, m.url, key, def.id, "movie", m.poster)
    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 72.dp, vertical = 56.dp)) {
        DetailHeader(m.poster ?: com.urtv.player.core.Tmdb.poster(tm?.poster), m.name, chips, listOf(info.plot, tm?.overview.orEmpty()).maxByOrNull { it.length }.orEmpty(), listOf(info.director, info.cast).filter { it.isNotBlank() }.joinToString("\n"), buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (pos > 0) TvButton(stringResource(R.string.resume_from, hms(pos)), { onPlay(play, pos) }, primary = true, focusRequester = fr)
                TvButton(stringResource(if (pos > 0) R.string.from_start else R.string.play), { onPlay(play, 0) }, primary = pos == 0L, focusRequester = if (pos > 0) null else fr)
                val favs by app.store.favs.collectAsState()
                TvButton(stringResource(if (key in favs) R.string.menu_unfav else R.string.menu_fav), { app.store.toggleFav(key) })
                tm?.trailerKey?.let { k -> TvButton(stringResource(R.string.tmdb_trailer), { openUrl(ctx, com.urtv.player.core.Tmdb.youtube(k)) }) }
                TvButton(stringResource(R.string.back), onBack)
            }
        }, extra = { tm?.let { CastRow(it) }; if (tm != null) TmdbCredit() })
    }
}

@Composable
fun SeriesDetail(app: UrtvApp, def: PlaylistDef, id: String, onPlay: (List<VodPlay>, Int, Long) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val s: Series? = remember(id) { app.vod.series(def.id, id) }
    if (s == null) { onBack(); return }
    val eps by produceState<List<Episode>?>(null) { value = app.vod.episodes(def, id) }
    val tmS by produceState<com.urtv.player.core.Tmdb.Info?>(null, s.name) { value = app.tmdb.info(s.name, s.year ?: yearIn(s.name), true) }
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
            (s.year ?: tmS?.year?.takeIf { it.isNotBlank() })?.let { Chip(it) }; tmS?.age?.takeIf { it.isNotBlank() }?.let { Chip(it) }; (s.rating?.takeIf { it != "0" }?.let { "★ $it" } ?: tmS?.rating?.takeIf { it > 0 }?.let { "TMDB ★ %.1f".format(java.util.Locale.US, it) })?.let { Chip(it) }
            if (all.isNotEmpty()) Chip(stringResource(R.string.episodes_n, all.size))
        }
        listOf(s.plot, tmS?.overview.orEmpty()).maxByOrNull { it.length }?.takeIf { it.isNotBlank() }?.let { Text(it, style = T.body.copy(color = C.muted), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(1000.dp)) }
        if (tmS != null) TmdbCredit()
        val sFavs by app.store.favs.collectAsState(); val sKey = vodKey(def.id, id)
        Spacer(Modifier.height(10.dp))
        TvButton(stringResource(if (sKey in sFavs) R.string.menu_unfav else R.string.menu_fav), { app.store.toggleFav(sKey) })
        Spacer(Modifier.height(10.dp))
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
