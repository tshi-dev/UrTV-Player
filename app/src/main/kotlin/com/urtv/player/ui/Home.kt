package com.urtv.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.SubcomposeAsyncImage
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.Channel
import com.urtv.player.core.Programme
import com.urtv.player.data.Browse
import com.urtv.player.data.CatalogState
import com.urtv.player.data.LoadError
import com.urtv.player.data.WatchItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab { Home, Live, Movies, Series, Guide, Settings }

private val keyMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
/** Favourite/recent key: playlist id + a hash of the stream URL, so credentials in URLs never land in preferences. */
fun chKey(pid: String, c: Channel): String = keyMemo.getOrPut(c.url) {
    java.security.MessageDigest.getInstance("SHA-256").digest(c.url.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}.let { "$pid|$it" }
fun groupKey(pid: String, g: String) = "$pid|$g"
fun fmtTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) { while (true) { delay(20_000); value = System.currentTimeMillis() } }

/** "24 min left" for a programme at [nowMs]. */
@Composable
fun leftText(p: Programme, nowMs: Long) = stringResource(R.string.min_left, ((p.stopMs - nowMs) / 60_000L).toInt().coerceAtLeast(1))

@Composable
fun HomeScreen(
    app: UrtvApp, tab: Tab, onTab: (Tab) -> Unit,
    onPlay: (List<Channel>, Channel) -> Unit, onAdd: () -> Unit, onLanguage: () -> Unit,
    restoreKey: String?,
    onManageGroups: () -> Unit = {}, onManageHome: () -> Unit = {}, onBackup: (Boolean) -> Unit = {}, onOpenMovie: (String) -> Unit = {}, onOpenSeries: (String) -> Unit = {},
    onCatchup: (Channel, Programme) -> Unit = { _, _ -> }, onResume: (WatchItem) -> Unit = {},
) {
    val store = app.store
    val cat by app.catalog.state.collectAsState()
    val playlists by store.playlists.collectAsState()
    val active = playlists.firstOrNull { it.id == cat.playlistId }
    val tabFocus = remember { FocusRequester() }
    val nowMs by rememberNow()
    LaunchedEffect(Unit) { if (restoreKey == null && playlists.isNotEmpty()) runCatching { tabFocus.requestFocus() } }

    LaunchedEffect(tab) { Backdrop.url = null }
    Box(Modifier.fillMaxSize().background(C.bgBrush)) {
    PosterBackdrop()
    Column(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 36.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(260.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_mark), null, Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp)); Text("UrTV", style = T.title.copy(fontSize = 28.sp))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(50)).background(C.glass).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (t in Tab.entries.filter { (it != Tab.Movies && it != Tab.Series) || active?.isXtream == true }) {
                    val label = stringResource(when (t) { Tab.Home -> R.string.tab_home; Tab.Live -> R.string.tab_live; Tab.Movies -> R.string.tab_movies; Tab.Series -> R.string.tab_series; Tab.Guide -> R.string.tab_guide; Tab.Settings -> R.string.tab_settings })
                    Tv({ onTab(t) }, Modifier.height(48.dp), RoundedCornerShape(50), selected = t == tab, onFocus = { if (it) onTab(t) }, focusRequester = if (t == tab) tabFocus else null, lift = 1f) {
                        Text(label, style = if (t == tab) T.bodyBold else T.body.copy(color = C.muted), maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(fmtTime(nowMs), style = T.row, modifier = Modifier.width(260.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (playlists.isEmpty() && tab != Tab.Settings) EmptyPlaylists(onAdd)
            else when (tab) {
                Tab.Home -> HomeRows(app, cat, nowMs, onPlay, onOpenMovie, onOpenSeries, onResume, onManageHome)
                Tab.Live -> LiveTab(app, cat, nowMs, onPlay, restoreKey)
                Tab.Movies -> PosterBrowse(app, true, onOpenMovie)
                Tab.Series -> PosterBrowse(app, false, onOpenSeries)
                Tab.Guide -> GuideScreen(app, cat, onPlay, onCatchup)
                Tab.Settings -> SettingsScreen(app, onAdd, onLanguage, onManageGroups, onManageHome, onBackup)
            }
        }
    }
    }
}

@Composable
fun EmptyPlaylists(onAdd: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_mark), null, Modifier.size(104.dp))
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.no_playlists_title), style = T.display)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.no_playlists_body), style = T.body.copy(color = C.muted), modifier = Modifier.width(760.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        TvButton(stringResource(R.string.add_playlist), onAdd, primary = true, focusRequester = fr)
    }
}

// ------------------------------------------------------------------ Home rows (configurable)

@Composable
private fun HomeRows(
    app: UrtvApp, cat: CatalogState, nowMs: Long, onPlay: (List<Channel>, Channel) -> Unit,
    onOpenMovie: (String) -> Unit, onOpenSeries: (String) -> Unit, onResume: (WatchItem) -> Unit, onManageHome: () -> Unit,
) {
    val store = app.store
    val pid = cat.playlistId.orEmpty()
    val rows by store.homeRows.collectAsState()
    val watching by store.watching.collectAsState()
    val favs by store.favs.collectAsState()
    val recents by store.recents.collectAsState()
    val vod by app.vod.state.collectAsState()
    val epg by app.catalog.epg.collectAsState()
    val byKey = remember(cat.channels) { cat.channels.associateBy { chKey(pid, it) } }
    val first = remember { FocusRequester() }
    var firstTaken = false
    LaunchedEffect(rows.size, watching.size) { runCatching { first.requestFocus() } }

    @Composable
    fun channelRow(titleRes: Int, list: List<Channel>) {
        if (list.isEmpty()) return
        val fr = if (!firstTaken) { firstTaken = true; first } else null
        RowTitle(stringResource(titleRes))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
            itemsIndexed(list, key = { _, c -> c.url }) { i, c ->
                val (now, _) = epg.nowNext(c.epgId, nowMs)
                Tv({ onPlay(list, c) }, Modifier.width(300.dp).height(88.dp), Rd(14), restColor = C.surface, focusRequester = if (i == 0) fr else null) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Logo(c, 52); Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = T.row.copy(fontSize = 21.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (now != null) Text(now.title, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun posterRow(titleRes: Int, items: List<Poster>, onOpen: (String) -> Unit) {
        if (items.isEmpty()) return
        val fr = if (!firstTaken) { firstTaken = true; first } else null
        RowTitle(stringResource(titleRes))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
            itemsIndexed(items, key = { _, p -> p.id }) { i, p -> PosterCard(p, Modifier.width(160.dp), focusRequester = if (i == 0) fr else null) { onOpen(p.id) } }
        }
    }

    val shown = rows.filter { id ->
        when {
            id == "continue" -> watching.any { it.pid == pid }
            id == "favs" -> favs.any { it.startsWith("$pid|") }
            id == "recent" -> recents.any { it.startsWith("$pid|") }
            id == "movies" -> vod.movies.isNotEmpty()
            id == "series" -> vod.series.isNotEmpty()
            else -> true
        }
    }
    if (shown.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.home_empty), style = T.title)
            Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.home_empty_hint), style = T.body.copy(color = C.muted))
            Spacer(Modifier.height(28.dp)); TvButton(stringResource(R.string.home_customize), onManageHome, focusRequester = first)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)) {
        for (id in shown) item(key = id) {
            Column(Modifier.padding(bottom = 22.dp)) {
                when {
                    id == "continue" -> {
                        val list = watching.filter { it.pid == pid }
                        val fr = if (!firstTaken) { firstTaken = true; first } else null
                        RowTitle(stringResource(R.string.cont_watching))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
                            itemsIndexed(list, key = { _, w -> w.key }) { i, w -> ContinueCard(w, onClick = { onResume(w) }, onLong = { store.removeWatching(w.key) }, focusRequester = if (i == 0) fr else null) }
                        }
                        Text(stringResource(R.string.cont_hint), style = T.tiny, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    id == "favs" -> channelRow(R.string.tab_favs, cat.channels.filter { chKey(pid, it) in favs })
                    id == "recent" -> channelRow(R.string.recent_channels, recents.mapNotNull { byKey[it] }.take(20))
                    id == "movies" -> posterRow(R.string.tab_movies, vod.movies.take(24).map { Poster(it.id, it.name, it.poster, it.category, it.rating) }, onOpenMovie)
                    id == "series" -> posterRow(R.string.tab_series, vod.series.take(24).map { Poster(it.id, it.name, it.poster, it.category, it.rating) }, onOpenSeries)
                    id.startsWith("group:") -> {
                        val g = id.removePrefix("group:")
                        val gl = cat.channels.filter { Browse.groupOf(it) == g }.take(24)
                        if (gl.isNotEmpty()) {
                            val fr = if (!firstTaken) { firstTaken = true; first } else null
                            Text(if (g == Browse.OTHER) stringResource(R.string.group_other) else g, style = T.title.copy(fontSize = 26.sp), modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                                itemsIndexed(gl, key = { _, c -> c.url }) { i, c ->
                                    val (now, _) = epg.nowNext(c.epgId, nowMs)
                                    Tv({ onPlay(gl, c) }, Modifier.width(300.dp).height(88.dp), Rd(14), restColor = C.surface, focusRequester = if (i == 0) fr else null) {
                                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Logo(c, 52); Spacer(Modifier.width(14.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(c.name, style = T.row.copy(fontSize = 21.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (now != null) Text(now.title, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowTitle(text: String) = Text(text, style = T.title.copy(fontSize = 26.sp), modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))

/** Wide 16:9 card: poster or episode still, thin progress, title, then the S/E badge with time left. */
@Composable
private fun ContinueCard(w: WatchItem, onClick: () -> Unit, onLong: () -> Unit, focusRequester: FocusRequester?) {
    Column(Modifier.width(360.dp)) {
        Tv(onClick, Modifier.fillMaxWidth().aspectRatio(16f / 9f), Rd(14), ring = true, lift = 1.04f, restColor = C.surface, focusRequester = focusRequester, onLongClick = onLong, onFocus = { if (it) Backdrop.url = w.image }) {
            PosterArt(w.image, w.title)
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC000000))))
            ProgressBar(w.fraction, Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 12.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(w.title, style = T.row.copy(fontSize = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (w.kind == "episode" && w.season > 0) Chip(stringResource(R.string.se_badge, w.season, w.episode))
            if (w.durMs > 0) Text(stringResource(R.string.min_left, w.leftMin), style = T.small)
            else if (w.kind == "episode") Text(stringResource(R.string.up_next), style = T.small)
        }
    }
}

// ------------------------------------------------------------------ Live: groups | dense list | preview

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveTab(app: UrtvApp, cat: CatalogState, nowMs: Long, onPlay: (List<Channel>, Channel) -> Unit, restoreKey: String?) {
    val store = app.store
    val pid = cat.playlistId.orEmpty()
    val order by store.groupOrder.collectAsState()
    val hidden by store.hiddenGroups.collectAsState()
    val favs by store.favs.collectAsState()
    val recents by store.recents.collectAsState()
    val lists by store.customLists.collectAsState()
    val epg by app.catalog.epg.collectAsState()
    val allGroups = remember(cat.channels) { Browse.groups(cat.channels) }
    val groups = remember(allGroups, order, hidden) { Browse.arrange(allGroups, order[pid].orEmpty(), hidden.filter { it.startsWith("$pid|") }.map { it.substringAfter('|') }.toSet()) }
    var group by remember(pid) { mutableStateOf(Browse.ALL) }
    var query by remember(pid) { mutableStateOf("") }
    val locked by store.lockedGroups.collectAsState()
    val hasPin by store.hasPin.collectAsState()
    var pinReq by remember { mutableStateOf<PinRequest?>(null) }
    var deleteList by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(groups) { if (group !in groups && group != Browse.ALL && group != Browse.FAV && group != Browse.RECENT && !group.startsWith(Browse.LIST)) group = Browse.ALL }
    val gKey = groupKey(pid, group)
    val isLocked = group != Browse.ALL && gKey in locked && gKey !in app.unlocked
    val visibleChannels = remember(cat.channels, groups) { val g = groups.toSet(); cat.channels.filter { Browse.groupOf(it) in g } }
    val byKey = remember(cat.channels) { cat.channels.associateBy { chKey(pid, it) } }
    val list = remember(cat.channels, visibleChannels, group, query, favs, recents) {
        when {
            query.isNotBlank() -> Browse.filter(visibleChannels, Browse.ALL, query)
            group == Browse.ALL -> visibleChannels
            group == Browse.FAV -> cat.channels.filter { chKey(pid, it) in favs }
            group == Browse.RECENT -> recents.mapNotNull { byKey[it] }
            group.startsWith(Browse.LIST) -> (lists["$pid|${group.removePrefix(Browse.LIST)}"] ?: emptySet()).let { ks -> cat.channels.filter { chKey(pid, it) in ks } }
            else -> Browse.filter(cat.channels, group, "")
        }
    }
    val counts = remember(cat.channels) { cat.channels.groupingBy { Browse.groupOf(it) }.eachCount() }

    // focus bookkeeping: remember the focused row; put focus on the nearest row when it disappears or after the player
    val listState = rememberLazyListState()
    var lastIdx by remember { mutableStateOf(-1) }
    var focusIdx by remember(restoreKey) { mutableStateOf(if (restoreKey == null) -1 else list.indexOfFirst { chKey(pid, it) == restoreKey }) }
    var prevSize by remember { mutableStateOf(list.size) }
    LaunchedEffect(list.size) { if (list.size < prevSize && lastIdx >= 0 && list.isNotEmpty()) focusIdx = minOf(lastIdx, list.size - 1); prevSize = list.size }
    val fr = remember { FocusRequester() }
    LaunchedEffect(focusIdx, list.size) { if (focusIdx in list.indices) { listState.scrollToItem(focusIdx); runCatching { fr.requestFocus() }; lastIdx = focusIdx; focusIdx = -1 } }

    var focused by remember(pid) { mutableStateOf<Channel?>(null) }
    val showPreview by store.livePreview.collectAsState()

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        // ---- groups rail: plain text rows with counts
        LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            val myLists = lists.keys.filter { it.startsWith("$pid|") }.map { Browse.LIST + it.substringAfter('|') }
            val entries = listOf(Browse.ALL, Browse.FAV, Browse.RECENT) + myLists + groups
            items(entries, key = { it }) { g ->
                val gk = groupKey(pid, g)
                val label = when (g) { Browse.ALL -> stringResource(R.string.all_channels); Browse.FAV -> stringResource(R.string.tab_favs); Browse.RECENT -> stringResource(R.string.tab_recents); Browse.OTHER -> stringResource(R.string.group_other); else -> if (g.startsWith(Browse.LIST)) g.removePrefix(Browse.LIST) else g }
                val n = when (g) { Browse.ALL -> cat.channels.size; Browse.FAV -> favs.count { it.startsWith("$pid|") }; Browse.RECENT -> recents.count { it.startsWith("$pid|") }; else -> if (g.startsWith(Browse.LIST)) (lists["$pid|${g.removePrefix(Browse.LIST)}"]?.size ?: 0) else counts[g] ?: 0 }
                val special = g == Browse.ALL || g == Browse.FAV || g == Browse.RECENT
                val isList = g.startsWith(Browse.LIST)
                Tv({ group = g }, Modifier.fillMaxWidth().height(52.dp), selected = g == group, onFocus = { if (it) group = g },
                    onLongClick = if (special) null else if (isList) { { deleteList = g.removePrefix(Browse.LIST) } } else { { pinReq = if (hasPin) PinRequest.Verify { store.toggleLockedGroup(gk) } else PinRequest.SetNew { store.setPin(it); store.toggleLockedGroup(gk) } } }) {
                    Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = if (g == group) T.bodyBold else T.body.copy(color = C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (gk in locked) { Icon(Glyph.Lock, C.faint, 18); Spacer(Modifier.width(8.dp)) }
                        Text("$n", style = T.tiny)
                    }
                }
            }
        }
        // ---- dense channel list
        Column(Modifier.weight(1f).fillMaxHeight()) {
            FocusField(query, { query = it }, stringResource(R.string.search_hint), Modifier.fillMaxWidth(), leading = { Icon(Glyph.Search, C.faint, 24) }, imeAction = androidx.compose.ui.text.input.ImeAction.Search, compact = true)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxSize()) {
                when {
                    isLocked -> {
                        val ufr = remember { FocusRequester() }
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Glyph.Lock, C.faint, 56); Spacer(Modifier.height(14.dp))
                            Text(stringResource(R.string.group_locked), style = T.title); Spacer(Modifier.height(22.dp))
                            TvButton(stringResource(R.string.unlock), { pinReq = PinRequest.Verify { app.unlocked = app.unlocked + gKey } }, primary = true, focusRequester = ufr)
                        }
                    }
                    list.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (cat.loading && cat.channels.isEmpty()) { Spinner(); Spacer(Modifier.height(14.dp)); Text(stringResource(R.string.loading), style = T.body.copy(color = C.muted)) }
                        else if (cat.error != null && cat.channels.isEmpty()) {
                            Text(stringResource(when (cat.error) { LoadError.Network -> R.string.err_network; LoadError.Format -> R.string.err_format; else -> R.string.err_empty }), style = T.title)
                            Spacer(Modifier.height(20.dp)); TvButton(stringResource(R.string.retry), { app.catalog.refreshActive() }, primary = true)
                        } else Text(stringResource(when { query.isNotBlank() -> R.string.no_results; group == Browse.FAV -> R.string.empty_favs; group == Browse.RECENT -> R.string.empty_recents; else -> R.string.empty_group }), style = T.body.copy(color = C.muted), modifier = Modifier.width(520.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        itemsIndexed(list, key = { _, c -> c.url }) { i, ch ->
                            val k = chKey(pid, ch)
                            ChannelRow(ch, Browse.numberOf(cat.channels, ch), epg.nowNext(ch.epgId, nowMs).first, nowMs, k in favs,
                                onClick = { onPlay(list, ch) }, onLong = { store.toggleFav(k) },
                                focusRequester = if (i == focusIdx) fr else null, onFocus = { if (it) { lastIdx = i; focused = ch } })
                        }
                    }
                }
            }
        }
        // ---- preview pane
        PreviewPane(app, focused ?: list.firstOrNull(), epg, nowMs, showPreview && !isLocked, Modifier.width(520.dp).fillMaxHeight())
    }
    PinHost(pinReq, store) { pinReq = null }
    deleteList?.let { n -> ConfirmDialog(stringResource(R.string.delete_list_confirm, n), onYes = { store.deleteList(pid, n); group = Browse.ALL; deleteList = null }, onNo = { deleteList = null }) }
}

/** One dense row: number, logo, name over programme (with time left at the end) and a thin progress bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(ch: Channel, number: Int, now: Programme?, nowMs: Long, fav: Boolean, onClick: () -> Unit, onLong: () -> Unit, focusRequester: FocusRequester?, onFocus: (Boolean) -> Unit) {
    var f by remember { mutableStateOf(false) }
    Tv(onClick, Modifier.fillMaxWidth().height(76.dp), Rd(12), focusRequester = focusRequester, onFocus = { f = it; onFocus(it) }, onLongClick = onLong, lift = 1.02f) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$number", style = T.small.copy(color = C.faint), modifier = Modifier.width(52.dp))
            Logo(ch, 44); Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(ch.name, style = T.row.copy(fontSize = 25.sp, lineHeight = 32.sp), maxLines = 1, softWrap = false, overflow = if (f) TextOverflow.Clip else TextOverflow.Ellipsis, modifier = if (f) Modifier.basicMarquee() else Modifier)
                if (now != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(now.title, style = T.small.copy(fontSize = 20.sp, lineHeight = 26.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp)); Text(leftText(now, nowMs), style = T.tiny.copy(fontSize = 17.sp))
                    }
                    Spacer(Modifier.height(3.dp)); ProgressBar((nowMs - now.startMs).toFloat() / (now.stopMs - now.startMs).coerceAtLeast(1), Modifier.width(220.dp))
                }
            }
            if (fav) { Spacer(Modifier.width(12.dp)); Icon(Glyph.Heart, if (f) C.onFocus else C.accent, 20, filled = true) }
        }
    }
}

@Composable
private fun PreviewPane(app: UrtvApp, ch: Channel?, epg: com.urtv.player.data.EpgIndex, nowMs: Long, enabled: Boolean, modifier: Modifier) {
    val ctrl = app.player
    var shownUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ch?.url, enabled) {
        if (ch == null || !enabled) { return@LaunchedEffect }
        delay(1200)                                  // only start the stream once the user rests on a channel
        shownUrl = ch.url; ctrl.playPreview(ch)
    }
    DisposableEffect(Unit) { onDispose { if (ctrl.isPreview) ctrl.stop() } }   // never kill a full-screen channel that just started
    Column(modifier.clip(Rd(18)).background(C.surface).padding(16.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Rd(12)).background(if (enabled && shownUrl == ch?.url) Color.Black else C.surfaceHi)) {
            if (enabled) AndroidView({ android.view.SurfaceView(it).also(ctrl::attach) }, Modifier.fillMaxSize(), onRelease = { ctrl.detach(it) })
            if (ch != null) Box(Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(50)).background(Color(0x80000000)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(C.accent)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.live), style = T.tiny.copy(color = Color.White, fontSize = 15.sp)) }
            }
            if (!enabled || shownUrl != ch?.url) Box(Modifier.align(Alignment.Center)) { if (ch != null) Logo(ch, 132) }
        }
        if (ch != null) {
            val (now, _) = epg.nowNext(ch.epgId, nowMs)
            Text(ch.name, style = T.title.copy(fontSize = 30.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp))
            if (now != null) {
                Text(now.title, style = T.row.copy(fontSize = 22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                Text("${fmtTime(now.startMs)} – ${fmtTime(now.stopMs)}", style = T.small, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                ProgressBar((nowMs - now.startMs).toFloat() / (now.stopMs - now.startMs).coerceAtLeast(1), Modifier.padding(horizontal = 4.dp))
                if (now.description.isNotBlank()) Text(now.description, style = T.small, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 12.dp, end = 4.dp))
            }
            val upcoming = epg.forChannel(ch.epgId).filter { it.startMs >= (now?.stopMs ?: nowMs) }.take(3)
            if (upcoming.isNotEmpty()) Column(Modifier.padding(start = 4.dp, top = 18.dp, end = 4.dp)) {
                Text(stringResource(R.string.next), style = T.tiny)
                for (u in upcoming) Row(Modifier.padding(top = 6.dp)) {
                    Text(fmtTime(u.startMs), style = T.small, modifier = Modifier.width(72.dp))
                    Text(u.title, style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun Logo(ch: Channel, size: Int) {
    val url = ch.logo
    if (url.isNullOrBlank()) Monogram(ch.name, size)
    else SubcomposeAsyncImage(
        model = url, contentDescription = null, modifier = Modifier.size(size.dp), contentScale = ContentScale.Fit,
        error = { Monogram(ch.name, size) }, loading = { Monogram(ch.name, size) },
    )
}

/** Poster/still with a stable tinted placeholder underneath (no flash while loading, nothing broken when it fails). */
@Composable
fun PosterArt(image: String?, title: String) {
    val h = (title.fold(0) { a, c -> (a * 31 + c.code) and 0xFFFF } % 360).toFloat()
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.hsv(h, 0.30f, 0.30f), Color.hsv((h + 40) % 360, 0.40f, 0.17f)))))
    if (!image.isNullOrBlank()) SubcomposeAsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, loading = {}, error = {})
}
