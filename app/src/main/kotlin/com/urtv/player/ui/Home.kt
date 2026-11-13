package com.urtv.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import com.urtv.player.data.FocusStyle
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import androidx.compose.foundation.focusGroup
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One unique key per list entry even when a playlist repeats a stream URL (a LazyList crashes on a repeated key): the first keeps the URL, later copies get a suffix. */
fun uniqueKeys(urls: List<String>): List<String> {
    val seen = HashMap<String, Int>(urls.size * 2)
    return urls.map { u -> val n = (seen[u] ?: 0) + 1; seen[u] = n; if (n == 1) u else "$u#$n" }
}

enum class Tab { Home, Live, Movies, Series, Guide, Settings }

/** The focus requester of the tab you are in, so Up from the first field of a page lands on it. */
val LocalTabFocus = androidx.compose.runtime.staticCompositionLocalOf<FocusRequester?> { null }

/** The first thing the current tab wants focus on when the person presses Down from the tab bar (the search box on the channel list), not the last row they visited. */
val LocalContentEntry = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<FocusRequester?>?> { null }

private val keyMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
/** Favourite/recent key: playlist id + a hash of the stream URL, so credentials in URLs never land in preferences. */
fun chKey(pid: String, c: Channel): String = keyMemo.getOrPut(c.url) {
    val d = java.security.MessageDigest.getInstance("SHA-256").digest(c.url.toByteArray())
    val hex = "0123456789abcdef"; val sb = StringBuilder(24)     // the first 12 bytes as lower-case hex (a format() call per byte was most of the cost on 50 000 channels)
    for (i in 0 until 12) { val b = d[i].toInt() and 0xff; sb.append(hex[b ushr 4]).append(hex[b and 15]) }
    sb.toString()
}.let { "$pid|$it" }
fun groupKey(pid: String, g: String) = "$pid|$g"
fun fmtTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) { while (true) { delay(20_000); value = System.currentTimeMillis() } }

/** "24 min left" for a programme at [nowMs]. */
@Composable
fun leftText(p: Programme, nowMs: Long) = stringResource(R.string.min_left, ((p.stopMs - nowMs) / 60_000L).toInt().coerceAtLeast(1))

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun HomeScreen(
    app: UrtvApp, tab: Tab, onTab: (Tab) -> Unit,
    onPlay: (List<Channel>, Channel) -> Unit, onAdd: () -> Unit, onLanguage: () -> Unit,
    restoreKey: String?, onRestored: () -> Unit = {},
    onManageGroups: () -> Unit = {}, onManageHome: () -> Unit = {}, onBackup: (Boolean) -> Unit = {}, onOpenMovie: (String) -> Unit = {}, onOpenSeries: (String) -> Unit = {},
    onCatchup: (Channel, Programme) -> Unit = { _, _ -> }, onResume: (WatchItem) -> Unit = {}, onServer: () -> Unit = {},
) {
    val store = app.store
    val cat by app.catalog.state.collectAsState()
    val playlists by store.playlists.collectAsState()
    val active = playlists.firstOrNull { it.id == cat.playlistId }
    val tabFocus = remember { FocusRequester() }
    val nowMs by rememberNow()
    val contentEntry = remember { mutableStateOf<FocusRequester?>(null) }
    val barFocused = remember { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab == Tab.Home && barFocused.value) runCatching { tabFocus.requestFocus() } }     // Back from a tab: the focus follows the selection to «الرئيسية»
    androidx.compose.runtime.CompositionLocalProvider(LocalTabFocus provides tabFocus, LocalContentEntry provides contentEntry) {
    LaunchedEffect(Unit) { if (restoreKey == null && playlists.isNotEmpty()) runCatching { tabFocus.requestFocus() } }

    LaunchedEffect(tab) { Backdrop.url = null }
    LaunchedEffect(tab) { Perf.afterTwoFrames("tab_open_${tab.name}", "to the second frame after the switch") }
    LaunchedEffect(tab) { Bisect.refresh() }
    Box(Modifier.fillMaxSize().background(if (Bisect.on("nobackdrop")) androidx.compose.ui.graphics.SolidColor(C.bg) else C.bgBrush)) {
    if (!Bisect.on("nobackdrop")) PosterBackdrop()
    Column(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 36.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(260.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_mark), null, Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp)); Text("UrTV", style = T.title.copy(fontSize = 28.sp))
                if (app.integrity == com.urtv.player.data.Integrity.Status.Debug) { Spacer(Modifier.width(10.dp)); Chip(stringResource(R.string.debug_label)) }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.focusProperties { enter = { tabFocus } }.focusGroup().clip(RoundedCornerShape(50)).background(C.glass).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {     // UP from any page lands on the CURRENT tab, whichever pill was used last
                val vodHere by app.vod.state.collectAsState()
                for (t in Tab.entries.filter { (it != Tab.Movies && it != Tab.Series) || active?.isXtream == true || vodHere.movies.isNotEmpty() || vodHere.series.isNotEmpty() }) {
                    val label = stringResource(when (t) { Tab.Home -> R.string.tab_home; Tab.Live -> R.string.tab_live; Tab.Movies -> R.string.tab_movies; Tab.Series -> R.string.tab_series; Tab.Guide -> R.string.tab_guide; Tab.Settings -> R.string.tab_settings })
                    Tv({ onTab(t) }, Modifier.height(48.dp).focusProperties {
                        down = if (t == Tab.Live) (contentEntry.value ?: FocusRequester.Default) else FocusRequester.Default
                        if (t == Tab.Home) start = FocusRequester.Cancel     // the end of the bar: the beam search must not jump into the list
                    }, RoundedCornerShape(50), selected = t == tab, onFocus = { barFocused.value = it; if (it) onTab(t) }, focusRequester = if (t == tab) tabFocus else null, lift = 1f) {
                        Text(label, style = if (t == tab) T.bodyBold else T.body.copy(color = C.muted), maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp))
                    }
                }
                // Home media server (Jellyfin, Emby, Plex): opens its own screen.
                Tv({ onServer() }, Modifier.height(48.dp).focusProperties { end = FocusRequester.Cancel }, RoundedCornerShape(50), lift = 1f) {
                    Text(stringResource(R.string.srv_tab_server), style = T.body.copy(color = C.muted), maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp))
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
                Tab.Live -> LiveTab(app, cat, nowMs, onPlay, restoreKey, onRestored)
                Tab.Movies -> PosterBrowse(app, true, onOpenMovie)
                Tab.Series -> PosterBrowse(app, false, onOpenSeries)
                Tab.Guide -> GuideScreen(app, cat, onPlay, onCatchup)
                Tab.Settings -> SettingsScreen(app, onAdd, onLanguage, onManageGroups, onManageHome, onBackup)
            }
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
    val byKeyL = remember(cat.channels) { lazy { cat.channels.associateBy { chKey(pid, it) } } }
    val first = remember { FocusRequester() }
    var firstTaken = false

    @Composable
    fun channelRow(titleRes: Int, list: List<Channel>) {
        if (list.isEmpty()) return
        val fr = if (!firstTaken) { firstTaken = true; first } else null
        RowTitle(stringResource(titleRes))
        val keys = remember(list) { uniqueKeys(list.map { it.url }) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
            itemsIndexed(list, key = { i, _ -> keys[i] }) { i, c ->
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
    if (shown.isEmpty() && (!cat.settled || cat.loading)) {
        Column(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            repeat(3) { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { SkeletonBlock(Modifier.size(220.dp, 26.dp), 6); SkeletonCards(5) } }
        }
        return
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
                    id == "recent" -> channelRow(R.string.recent_channels, recents.mapNotNull { byKeyL.value[it] }.take(20))
                    id == "movies" -> posterRow(R.string.tab_movies, vod.movies.take(24).map { Poster(it.id, it.name, it.poster, it.category, it.rating) }, onOpenMovie)
                    id == "series" -> posterRow(R.string.tab_series, vod.series.take(24).map { Poster(it.id, it.name, it.poster, it.category, it.rating) }, onOpenSeries)
                    id.startsWith("group:") -> {
                        val g = id.removePrefix("group:")
                        val gl = cat.channels.filter { g in Browse.groupsOf(it) }.take(24)
                        if (gl.isNotEmpty()) {
                            val fr = if (!firstTaken) { firstTaken = true; first } else null
                            Text(if (g == Browse.OTHER) stringResource(R.string.group_other) else g, style = T.title.copy(fontSize = 26.sp), modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
                            val keys = remember(gl) { uniqueKeys(gl.map { it.url }) }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                                itemsIndexed(gl, key = { i, _ -> keys[i] }) { i, c ->
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
private fun LiveTab(app: UrtvApp, cat: CatalogState, nowMs: Long, onPlay: (List<Channel>, Channel) -> Unit, restoreKey: String?, onRestored: () -> Unit = {}) {
    val store = app.store
    val pid = cat.playlistId.orEmpty()
    val order by store.groupOrder.collectAsState()
    val hidden by store.hiddenGroups.collectAsState()
    val favs by store.favs.collectAsState()
    val recents by store.recents.collectAsState()
    val lists by store.customLists.collectAsState()
    val epg by app.catalog.epg.collectAsState()
    val index = cat.index.takeIf { it.channels === cat.channels } ?: remember(cat.channels) { com.urtv.player.data.ChannelIndex(cat.channels, pid) }     // normally built when the list was loaded, off the main thread
    val allGroups = index.groups
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
    val hiddenCh by store.hiddenChannels.collectAsState()
    val nothingHidden = groups.size == allGroups.size && hiddenCh.isEmpty()
    val visibleChannels = remember(cat.channels, groups, hiddenCh) { if (nothingHidden) cat.channels else { val g = groups.toSet(); cat.channels.filterIndexed { i, c -> Browse.groupsOf(c).any { it in g } && (hiddenCh.isEmpty() || index.keys[i] !in hiddenCh) } } }     // nothing hidden (the usual case): no work at all; keys are only computed for hidden channels
    val numbers = index.numbers
    val byKeyL = remember(cat.channels) { lazy { HashMap<String, Channel>(cat.channels.size * 2).also { m -> cat.channels.forEachIndexed { i, c -> m[index.keys[i]] = c } } } }     // only the «recent» group needs it
    val searched = remember(pid) { mutableStateOf<List<Channel>?>(null) }
    // typing: filter 50k names off the main thread, a short debounce, the list shows the previous result meanwhile
    LaunchedEffect(visibleChannels, query) {
        if (query.isBlank()) { searched.value = null; return@LaunchedEffect }
        delay(140)
        searched.value = withContext(kotlinx.coroutines.Dispatchers.Default) { val t0 = System.nanoTime(); val hits = index.search(query); val r = if (nothingHidden) hits else { val vis = visibleChannels.toHashSet(); hits.filter { it in vis } }; Perf.log("search", (System.nanoTime() - t0) / 1_000_000, "${index.channels.size} names, ${r.size} hits"); r }
    }
    val list = remember(cat.channels, visibleChannels, group, query, favs, recents, searched.value) { Perf.time("list_build", "${cat.channels.size} channels") {
        when {
            query.isNotBlank() -> searched.value ?: visibleChannels
            group == Browse.ALL -> visibleChannels
            group == Browse.FAV -> if (favs.isEmpty()) emptyList() else cat.channels.filterIndexed { i, _ -> index.keys[i] in favs }
            group == Browse.RECENT -> recents.mapNotNull { byKeyL.value[it] }
            group.startsWith(Browse.LIST) -> (lists["$pid|${group.removePrefix(Browse.LIST)}"] ?: emptySet()).let { ks -> if (ks.isEmpty()) emptyList() else cat.channels.filterIndexed { i, _ -> index.keys[i] in ks } }
            else -> if (nothingHidden) (index.byGroup[group] ?: emptyList()) else Browse.filter(visibleChannels, group, "")
        }
    } }
    val counts = remember(cat.channels) { HashMap<String, Int>().also { m -> for (c in cat.channels) for (g in Browse.groupsOf(c)) m[g] = (m[g] ?: 0) + 1 } }
    val arabicUi = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar"

    // focus bookkeeping: remember the focused row; put focus on the nearest row when it disappears or after the player
    val listState = rememberLazyListState()
    var lastIdx by remember { mutableStateOf(-1) }
    var focusIdx by remember(restoreKey) { mutableStateOf(if (restoreKey == null) -1 else list.indexOfFirst { chKey(pid, it) == restoreKey }) }
    var prevSize by remember { mutableStateOf(list.size) }
    LaunchedEffect(list.size) { if (list.size < prevSize && lastIdx >= 0 && list.isNotEmpty()) focusIdx = minOf(lastIdx, list.size - 1); prevSize = list.size }
    val fr = remember { FocusRequester() }
    LaunchedEffect(focusIdx, list.size) { if (focusIdx in list.indices) { listState.scrollToItem(focusIdx); runCatching { fr.requestFocus() }; lastIdx = focusIdx; focusIdx = -1; onRestored() } }     // the row you left is restored ONCE (coming back from the player), not every time you return to this tab

    val focusedState = remember(pid) { mutableStateOf<Channel?>(null) }     // read only inside the preview pane, so moving the focus does not recompose the whole tab
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    var keyMode by remember { mutableStateOf(com.urtv.player.core.SearchFold.KeyMode.Auto) }
    val entry = LocalContentEntry.current
    val searchFr = remember { FocusRequester() }
    androidx.compose.runtime.DisposableEffect(Unit) { entry?.value = searchFr; onDispose { if (entry?.value === searchFr) entry.value = null } }
    val tabFocusRq = LocalTabFocus.current
    val showPreview by store.livePreview.collectAsState()
    // fill the image cache a screen ahead, off the UI thread, at the exact size the rows use (debounced: nothing while keys repeat)
    val logoCtx = androidx.compose.ui.platform.LocalContext.current
    val logoPx = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.roundToPx() }
    LaunchedEffect(listState.firstVisibleItemIndex, list) {
        delay(200)
        val from = listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size
        for (i in from until minOf(from + 14, list.size)) list[i].logo?.takeIf { it.isNotBlank() }?.let { LogoCache.load(logoCtx, it, logoPx) }
    }
    var matchHint by remember { mutableStateOf("") }
    LaunchedEffect(app.pendingSearch) { if (app.pendingSearch != null) app.consumePendingSearch()?.let { query = it.text; matchHint = it.hint; group = Browse.ALL } }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        // ---- groups rail: plain text rows with counts
        LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            val myLists = lists.keys.filter { it.startsWith("$pid|") }.map { Browse.LIST + it.substringAfter('|') }
            val entries = listOf(Browse.ALL, Browse.FAV, Browse.RECENT) + myLists + groups
            items(entries, key = { it }) { g ->
                val gk = groupKey(pid, g)
                val label = when (g) { Browse.ALL -> stringResource(R.string.all_channels); Browse.FAV -> stringResource(R.string.tab_favs); Browse.RECENT -> stringResource(R.string.tab_recents); Browse.OTHER -> stringResource(R.string.group_other); else -> if (g.startsWith(Browse.LIST)) g.removePrefix(Browse.LIST) else com.urtv.player.core.GroupNames.display(g, arabicUi) }
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
            if (matchHint.isNotBlank() && query.isNotBlank()) Text(stringResource(R.string.fm_for_match, matchHint), style = T.small.copy(color = C.accent), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
            SearchRow(query, { query = it }, keyMode, { keyMode = it }, onUp = { tabFocusRq?.let { runCatching { it.requestFocus() } } }, focusRequester = searchFr)
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
                    list.isEmpty() && (!cat.settled || (cat.loading && cat.channels.isEmpty())) -> Box(Modifier.fillMaxSize()) {
                        SkeletonRows(10)
                        if (cat.progress > 0) Text(stringResource(R.string.loading_n, cat.progress), style = T.bodyBold, modifier = Modifier.align(Alignment.TopCenter).background(C.surfaceHi, Rd(12)).padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                    list.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (false) { }
                        else if (cat.error != null && cat.channels.isEmpty()) {
                            Text(stringResource(when (cat.error) { LoadError.Network -> R.string.err_network; LoadError.Format -> R.string.err_format; else -> R.string.err_empty }), style = T.title)
                            Spacer(Modifier.height(20.dp)); TvButton(stringResource(R.string.retry), { app.catalog.refreshActive() }, primary = true)
                        } else Text(stringResource(when { query.isNotBlank() -> R.string.no_results; group == Browse.FAV -> R.string.empty_favs; group == Browse.RECENT -> R.string.empty_recents; else -> R.string.empty_group }), style = T.body.copy(color = C.muted), modifier = Modifier.width(520.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    else -> androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides (if (Bisect.on("springscroll")) SpringBringIntoView else InstantBringIntoView)) {
                      val rowKeys = remember(list) { uniqueKeys(list.map { it.url }) }
                      LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        itemsIndexed(list, key = { i, _ -> rowKeys[i] }) { i, ch ->
                            val k = chKey(pid, ch)
                            ChannelRow(ch, numbers[ch.url] ?: (i + 1), epg.nowNext(ch.epgId, nowMs).first, nowMs, k in favs,
                                onClick = { onPlay(list, ch) }, onLong = { menuFor = ch },
                                focusRequester = if (i == focusIdx) fr else null, onFocus = { if (it) { lastIdx = i; focusedState.value = ch; com.urtv.player.player.Net.preResolve(ch.url) } })
                        }
                      }
                    }
                }
            }
        }
        // ---- preview pane
        if (Bisect.on("nopreview")) Spacer(Modifier.width(520.dp)) else PreviewPane(app, focusedState, list.firstOrNull(), epg, nowMs, showPreview && !isLocked, Modifier.width(520.dp).fillMaxHeight())
    }
    menuFor?.let { c -> ChannelMenu(app, pid, c, numbers[c.url] ?: 0, epg.nowNext(c.epgId, nowMs).first) { menuFor = null; focusIdx = lastIdx.coerceAtLeast(0) } }
    PinHost(pinReq, store) { pinReq = null }
    deleteList?.let { n -> ConfirmDialog(stringResource(R.string.delete_list_confirm, n), onYes = { store.deleteList(pid, n); group = Browse.ALL; deleteList = null }, onNo = { deleteList = null }) }
}

/** D-pad list scrolling jumps straight to the focused row instead of animating a spring that lands between frames (flag `springscroll` restores the spring). */
@OptIn(ExperimentalFoundationApi::class)
private class ListScrollSpec(override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float>) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f && trailing > containerSize -> 0f
            kotlin.math.abs(offset) < kotlin.math.abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}
@OptIn(ExperimentalFoundationApi::class) private val InstantBringIntoView = ListScrollSpec(androidx.compose.animation.core.snap())
@OptIn(ExperimentalFoundationApi::class) private val SpringBringIntoView = ListScrollSpec(androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))

/** The search box and its keyboard-language chip: D-pad Left/Right move between them (the chip is not inside the text field). */
@Composable
fun SearchRow(query: String, onQuery: (String) -> Unit, mode: com.urtv.player.core.SearchFold.KeyMode, onMode: (com.urtv.player.core.SearchFold.KeyMode) -> Unit, onUp: () -> Unit, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // what is typed is converted live to the script the chip says (the TV keyboard may ignore the language hint)
        FocusField(query, { onQuery(com.urtv.player.core.SearchFold.applyMode(it, mode)) }, stringResource(R.string.search_hint), Modifier.weight(1f), leading = { Icon(Glyph.Search, C.faint, 24) }, imeAction = androidx.compose.ui.text.input.ImeAction.Search, compact = true,
            onUp = onUp, hintLocale = when (mode) { com.urtv.player.core.SearchFold.KeyMode.Latin -> "en"; com.urtv.player.core.SearchFold.KeyMode.Arabic -> "ar"; else -> null }, focusRequester = focusRequester)
        // one press switches the keyboard to Latin / back, for channel names typed in the other script
        val next = com.urtv.player.core.SearchFold.KeyMode.entries[(mode.ordinal + 1) % com.urtv.player.core.SearchFold.KeyMode.entries.size]
        Tv({ onMode(next) }, Modifier.height(52.dp), Rd(12), selected = mode != com.urtv.player.core.SearchFold.KeyMode.Auto, lift = 1f) { Text(when (mode) { com.urtv.player.core.SearchFold.KeyMode.Latin -> "EN"; com.urtv.player.core.SearchFold.KeyMode.Arabic -> "ع"; else -> "ع/EN" }, style = T.bodyBold, modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp)) }
    }
}

/**
 * One dense row: number, logo, name over programme (time left at the end) and a thin progress bar.
 *
 * Built for scrolling on a weak TV: the whole row is ONE node that draws itself (text layouts measured once and cached, colours chosen at draw
 * time), so moving the focus changes only the draw of the two rows involved: no recomposition, no child nodes to re-record, no state read in
 * composition. The focus highlight is the same as [Tv]'s (solid fill, ring or glow, outline at rest) without its animations.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(ch: Channel, number: Int, now: Programme?, nowMs: Long, fav: Boolean, onClick: () -> Unit, onLong: () -> Unit, focusRequester: FocusRequester?, onFocus: (Boolean) -> Unit) {
    val th = LocalTheme.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(256)
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val density = androidx.compose.ui.platform.LocalDensity.current
    val logoPx = with(density) { 44.dp.roundToPx() }
    val logo = rememberLogo(ch.logo, logoPx)
    val noLogo = Bisect.on("nologos")
    val focused = remember { mutableStateOf(false) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // text styles without a colour (the colour is picked per focus state while drawing)
    val nameStyle = T.row.copy(fontSize = 25.sp, lineHeight = 32.sp, color = Color.Unspecified)
    val subStyle = T.small.copy(fontSize = 20.sp, lineHeight = 26.sp, color = Color.Unspecified)
    val tinyStyle = T.tiny.copy(fontSize = 17.sp, color = Color.Unspecified)
    val numStyle = T.small.copy(color = Color.Unspecified)
    val letterStyle = T.bodyBold.copy(color = Color.White, fontSize = (44 * 0.42f).sp)
    val title = now?.title
    val leftStr = if (now != null) leftText(now, nowMs) else null
    val progress = now?.let { ((nowMs - it.startMs).toFloat() / (it.stopMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
    val letter = remember(ch.name) { Regex("""\d{1,2}$""").find(ch.name.trim())?.value ?: (ch.name.trim().firstOrNull() ?: '?').toString() }.uppercase()
    val hue = remember(ch.name) { (ch.name.trim().fold(0) { a, c -> (a * 31 + c.code) and 0xFFFF } % 360).toFloat() }

    Box(
        Modifier.fillMaxWidth().height(76.dp)
            .onKeyEvent { e -> if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (e.key == androidx.compose.ui.input.key.Key.Menu || e.key == androidx.compose.ui.input.key.Key.Info)) { onLong(); true } else false }
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused.value = it.isFocused; onFocus(it.isFocused) }
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLong)
            .semantics(mergeDescendants = true) { text = androidx.compose.ui.text.AnnotatedString(ch.name) }
            .drawWithCache {
                val w = size.width; val h = size.height
                val pad = 16.dp.toPx(); val numW = 52.dp.toPx(); val lg = 44.dp.toPx(); val gap = 16.dp.toPx()
                val favW = if (fav) 32.dp.toPx() else 0f
                val textX = pad + numW + lg + gap                 // distance from the start edge
                val textW = (w - textX - pad - favW).coerceAtLeast(1f)
                val ld = if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
                fun fit(t: String, st: androidx.compose.ui.text.TextStyle, maxW: Float) = measurer.measure(androidx.compose.ui.text.AnnotatedString(t), st, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxW.toInt().coerceAtLeast(1)), layoutDirection = ld)
                val leftL = leftStr?.let { fit(it, tinyStyle, textW / 3) }
                val nameL = fit(ch.name, nameStyle, textW)
                val subL = title?.let { fit(it, subStyle, textW - (leftL?.size?.width ?: 0) - 12.dp.toPx()) }
                val numL = fit("$number", numStyle, numW)
                val letterL = if (logo == null && !noLogo) measurer.measure(androidx.compose.ui.text.AnnotatedString(letter), letterStyle, layoutDirection = ld) else null
                val contentH = nameL.size.height + (if (subL != null) subL.size.height + 6.dp.toPx() else 0f)
                val top = (h - contentH) / 2f
                val r = 12.dp.toPx() * th.cornerScale
                fun x(fromStart: Float, width: Float) = if (rtl) w - fromStart - width else fromStart      // mirror in right-to-left
                onDrawBehind {
                    val f = focused.value
                    val solid = f && th.solidFocus
                    val ink = if (solid) th.onFocus else th.text
                    val muted = ink.copy(alpha = th.mutedAlpha)
                    val faint = if (solid) th.onFocus.copy(alpha = 0.5f) else C.faint
                    val tiny = (if (solid) th.onFocus else th.text).copy(alpha = th.mutedAlpha * 0.64f / 0.7f)
                    // highlight
                    if (f) drawRoundRect(if (solid) th.focusFill else th.focusSoft, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                    when {
                        f && (th.focus == FocusStyle.Ring) -> drawRoundRect(th.ring, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                        f && th.focus == FocusStyle.Glow -> drawRoundRect(th.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                        f && th.outlineAtRest -> drawRoundRect(th.ring, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                        th.outlineAtRest -> drawRoundRect(th.text.copy(alpha = 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                    }
                    // number
                    drawText(numL, color = faint, topLeft = Offset(x(pad, numL.size.width.toFloat()), (h - numL.size.height) / 2f))
                    // logo
                    val lx = x(pad + numW, lg); val ly = (h - lg) / 2f
                    if (!noLogo) {
                        if (logo != null) {
                            val sc = minOf(lg / logo.width, lg / logo.height)
                            val dw = (logo.width * sc).toInt().coerceAtLeast(1); val dh = (logo.height * sc).toInt().coerceAtLeast(1)
                            drawImage(logo, dstOffset = androidx.compose.ui.unit.IntOffset((lx + (lg - dw) / 2f).toInt(), (ly + (lg - dh) / 2f).toInt()), dstSize = androidx.compose.ui.unit.IntSize(dw, dh))
                        } else {
                            drawRoundRect(Brush.linearGradient(listOf(Color.hsv(hue, 0.38f, 0.40f), Color.hsv((hue + 30) % 360, 0.42f, 0.26f)), Offset(lx, ly), Offset(lx + lg, ly + lg)), Offset(lx, ly), androidx.compose.ui.geometry.Size(lg, lg), androidx.compose.ui.geometry.CornerRadius(lg / 4.5f))
                            letterL?.let { drawText(it, topLeft = Offset(lx + (lg - it.size.width) / 2f, ly + (lg - it.size.height) / 2f)) }
                        }
                    }
                    // name, programme, time left, progress
                    val tx = textX
                    drawText(nameL, color = ink, topLeft = Offset(if (rtl) w - tx - nameL.size.width else tx, top))
                    if (subL != null) {
                        val sy = top + nameL.size.height + 6.dp.toPx() - 6.dp.toPx() + 0f
                        drawText(subL, color = muted, topLeft = Offset(if (rtl) w - tx - subL.size.width else tx, sy))
                        leftL?.let { drawText(it, color = tiny, topLeft = Offset(if (rtl) pad + favW else w - pad - favW - it.size.width, sy + (subL.size.height - it.size.height) / 2f)) }
                        val by = sy + subL.size.height + 3.dp.toPx()
                        val bw = 220.dp.toPx(); val bh = 3.dp.toPx(); val bx = if (rtl) w - tx - bw else tx
                        drawRoundRect(if (solid) th.onFocus.copy(alpha = 0.15f) else th.text.copy(alpha = 0.16f), Offset(bx, by), androidx.compose.ui.geometry.Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(bh / 2))
                        if (progress > 0f) drawRoundRect(th.accent, Offset(if (rtl) bx + bw * (1 - progress) else bx, by), androidx.compose.ui.geometry.Size(bw * progress, bh), androidx.compose.ui.geometry.CornerRadius(bh / 2))
                    }
                    // favourite heart
                    if (fav) {
                        val hs = 20.dp.toPx(); val hx = if (rtl) pad else w - pad - hs; val hy = (h - hs) / 2f
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(hx + hs / 2, hy + hs * 0.92f)
                            cubicTo(hx - hs * 0.15f, hy + hs * 0.55f, hx + hs * 0.05f, hy - hs * 0.05f, hx + hs / 2, hy + hs * 0.30f)
                            cubicTo(hx + hs * 0.95f, hy - hs * 0.05f, hx + hs * 1.15f, hy + hs * 0.55f, hx + hs / 2, hy + hs * 0.92f)
                            close()
                        }
                        drawPath(path, if (solid) th.onFocus else th.accent)
                    }
                }
            },
    )
}

/** Starts the preview only when the focus has RESTED on a channel (not while a key repeats); stops decoding the moment it moves again. */
const val PREVIEW_REST_MS = 500L

@Composable
private fun PreviewPane(app: UrtvApp, focusedState: State<Channel?>, first: Channel?, epg: com.urtv.player.data.EpgIndex, nowMs: Long, enabled: Boolean, modifier: Modifier) {
    val ctrl = app.player
    // The pane follows the focus ~90 ms behind it, so a key press costs one light frame (focus + scroll), and the heavier pane update lands in the next one.
    val followed by produceState<Channel?>(null, focusedState) { snapshotFlow { focusedState.value }.collectLatest { c -> if (value != null) delay(90); value = c } }
    val ch = followed ?: first
    var shownUrl by remember { mutableStateOf<String?>(null) }
    var surfaceUp by remember { mutableStateOf(false) }     // the video surface is created once and kept while previews are on: creating and destroying it on every key were the two big frames
    val pst by ctrl.status.collectAsState()
    val firstFrame by ctrl.firstFrame.collectAsState()
    LaunchedEffect(enabled) { if (!enabled) { surfaceUp = false; shownUrl = null } }
    LaunchedEffect(ch?.url, enabled) {
        if (ch == null || !enabled) { return@LaunchedEffect }
        if (shownUrl != null && shownUrl != ch.url) { shownUrl = null; ctrl.idlePreview() }   // focus moved: stop decoding the old one at once (keeps the player)
        delay(PREVIEW_REST_MS)                                                                 // a key held down moves faster than this, so nothing starts
        surfaceUp = true; shownUrl = ch.url; ctrl.playPreview(ch)
    }
    DisposableEffect(Unit) { onDispose { if (ctrl.isPreview) ctrl.stop() } }   // never kill a full-screen channel that just started
    Column(modifier.clip(Rd(18)).background(C.surface).padding(16.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Rd(12)).background(if (enabled && ch != null && shownUrl == ch.url) Color.Black else C.surfaceHi)) {
            // the video surface exists only while a preview is actually running: scrolling past channels composes no surface at all
            if (enabled && surfaceUp) AndroidView({ android.view.SurfaceView(it).also(ctrl::attach) }, Modifier.fillMaxSize(), onRelease = { ctrl.detach(it) })
            if (enabled && surfaceUp && !(ch != null && shownUrl == ch.url && firstFrame)) Box(Modifier.fillMaxSize().background(if (ch != null && shownUrl == ch.url) Color.Black else C.surfaceHi))     // covers the surface until this channel's own first frame
            if (ch != null) Box(Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(50)).background(Color(0x80000000)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(C.accent)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.live), style = T.tiny.copy(color = Color.White, fontSize = 15.sp)) }
            }
            if (!enabled || shownUrl != ch?.url) Box(Modifier.align(Alignment.Center)) { if (ch != null) Logo(ch, 132) }
            val noPic by ctrl.videoBlock.collectAsState()
            // the logo alone must never look like a broken preview: say why it is a logo
            if (!enabled) Text(stringResource(R.string.preview_off_hint), style = T.tiny.copy(color = Color.White), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).background(Color(0x99000000), Rd(8)).padding(horizontal = 10.dp, vertical = 4.dp))
            else if (noPic.isNotEmpty() && shownUrl == ch?.url) Text(stringResource(R.string.preview_no_picture, noPic), style = T.tiny.copy(color = Color.White), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).background(Color(0x99000000), Rd(8)).padding(horizontal = 10.dp, vertical = 4.dp))
            if (enabled && ch != null && shownUrl == ch.url && pst == com.urtv.player.player.PlayStatus.Failed) Text(stringResource(R.string.preview_dead), style = T.bodyBold.copy(color = Color.White), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000), Rd(10)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (ch != null) {
            // Fixed-size blocks: a key press changes the text inside them, never the size of the pane, so nothing around the pane is measured again.
            val (now, _) = remember(ch.epgId, epg, nowMs / 60_000) { epg.nowNext(ch.epgId, nowMs) }
            Box(Modifier.fillMaxWidth().height(84.dp).padding(top = 16.dp, start = 4.dp, end = 4.dp)) {
                Text(ch.name, style = T.title.copy(fontSize = 30.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, top = 4.dp)) {
                if (now != null) Text(now.title, style = T.row.copy(fontSize = 22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().height(34.dp).padding(start = 4.dp, bottom = 8.dp)) {
                if (now != null) Text("${fmtTime(now.startMs)} – ${fmtTime(now.stopMs)}", style = T.small)
            }
            Box(Modifier.fillMaxWidth().height(10.dp).padding(horizontal = 4.dp)) {
                if (now != null) ProgressBar((nowMs - now.startMs).toFloat() / (now.stopMs - now.startMs).coerceAtLeast(1), Modifier)
            }
            Box(Modifier.fillMaxWidth().height(84.dp).padding(start = 4.dp, top = 12.dp, end = 4.dp)) {
                if (now != null && now.description.isNotBlank()) Text(now.description, style = T.small, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            val upcoming = remember(ch.epgId, epg, now?.stopMs) { epg.forChannel(ch.epgId).filter { it.startMs >= (now?.stopMs ?: nowMs) }.take(3) }
            Column(Modifier.fillMaxWidth().height(132.dp).padding(start = 4.dp, top = 18.dp, end = 4.dp)) {
                if (upcoming.isNotEmpty()) {
                    Text(stringResource(R.string.next), style = T.tiny)
                    for (u in upcoming) Row(Modifier.padding(top = 6.dp)) {
                        Text(fmtTime(u.startMs), style = T.small, modifier = Modifier.width(72.dp))
                        Text(u.title, style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Long OK / Menu / Info on a channel: favourite, hide, information. Back or No closes it. */
@Composable
fun ChannelMenu(app: UrtvApp, pid: String, ch: Channel, number: Int, now: Programme?, onClose: () -> Unit) {
    val store = app.store
    val favs by store.favs.collectAsState()
    val key = chKey(pid, ch)
    var info by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { if (info) info = false else onClose() }
    val fr = remember { FocusRequester() }
    LaunchedEffect(info) { runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(C.scrim), contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp).background(C.surface, Rd(22)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ch.name, style = T.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!info) {
                TvButton(stringResource(if (key in favs) R.string.menu_unfav else R.string.menu_fav), { store.toggleFav(key); onClose() }, primary = false, focusRequester = fr, modifier = Modifier.fillMaxWidth())
                TvButton(stringResource(R.string.menu_hide), { store.toggleHiddenChannel(key); onClose() }, modifier = Modifier.fillMaxWidth())
                TvButton(stringResource(R.string.menu_info), { info = true }, modifier = Modifier.fillMaxWidth())
            } else {
                val q = com.urtv.player.core.MatchFinder.quality(ch.name)
                Text("# $number", style = T.body)
                Text(com.urtv.player.core.GroupNames.split(ch.group).joinToString(" · ").ifBlank { stringResource(R.string.group_other) }, style = T.body.copy(color = C.muted))
                if (q != 1) Text(when (q) { 4 -> "4K"; 3 -> "FHD"; 2 -> "HD"; else -> "SD" }, style = T.body.copy(color = C.muted))
                Text(stringResource(if (ch.epgId != null) R.string.menu_has_guide else R.string.menu_no_guide), style = T.body.copy(color = C.muted))
                if (now != null) Text(now.title, style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TvButton(stringResource(R.string.fm_back), { info = false }, primary = false, focusRequester = fr, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Channel logo: from the in-memory [LogoCache] at its fixed on-screen size (one plain Image when cached), the monogram until it is there. */
@Composable
fun Logo(ch: Channel, size: Int) {
    if (Bisect.on("nologos")) { Box(Modifier.size(size.dp)); return }
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.dp.roundToPx() }
    val bmp = rememberLogo(ch.logo, px)
    if (bmp == null) Monogram(ch.name, size)
    else androidx.compose.foundation.Image(bmp, null, Modifier.size(size.dp), contentScale = ContentScale.Fit)
}

/** Poster/still with a stable tinted placeholder underneath (no flash while loading, nothing broken when it fails). */
@Composable
fun PosterArt(image: String?, title: String) {
    val h = (title.fold(0) { a, c -> (a * 31 + c.code) and 0xFFFF } % 360).toFloat()
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.hsv(h, 0.30f, 0.30f), Color.hsv((h + 40) % 360, 0.40f, 0.17f)))))
    if (!image.isNullOrBlank()) SubcomposeAsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, loading = {}, error = {})
}
