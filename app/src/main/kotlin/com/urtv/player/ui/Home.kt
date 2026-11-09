package com.urtv.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.Channel
import com.urtv.player.core.Programme
import com.urtv.player.data.Browse
import com.urtv.player.data.CatalogState
import com.urtv.player.data.EpgIndex
import com.urtv.player.data.LoadError
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab { Live, Movies, Series, Favs, Recents, Guide, Settings }

fun chKey(pid: String, c: Channel) = "$pid|${c.url}"
fun groupKey(pid: String, g: String) = "$pid|$g"
fun fmtTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) { while (true) { delay(20_000); value = System.currentTimeMillis() } }

@Composable
fun HomeScreen(
    app: UrtvApp, tab: Tab, onTab: (Tab) -> Unit,
    onPlay: (List<Channel>, Channel) -> Unit, onAdd: () -> Unit, onLanguage: () -> Unit,
    restoreKey: String?,
    onOpenMovie: (String) -> Unit = {}, onOpenSeries: (String) -> Unit = {}, onCatchup: (Channel, Programme) -> Unit = { _, _ -> },
) {
    val store = app.store
    val cat by app.catalog.state.collectAsState()
    val playlists by store.playlists.collectAsState()
    val active = playlists.firstOrNull { it.id == cat.playlistId }
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (restoreKey == null && playlists.isNotEmpty()) runCatching { tabFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("UrTV", style = T.display.copy(fontSize = 36.sp), modifier = Modifier.padding(end = 32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in Tab.entries.filter { (it != Tab.Movies && it != Tab.Series) || active?.isXtream == true }) {
                    val label = stringResource(when (t) { Tab.Live -> R.string.tab_live; Tab.Movies -> R.string.tab_movies; Tab.Series -> R.string.tab_series; Tab.Favs -> R.string.tab_favs; Tab.Recents -> R.string.tab_recents; Tab.Guide -> R.string.tab_guide; Tab.Settings -> R.string.tab_settings })
                    Tv({ onTab(t) }, Modifier.height(52.dp), RoundedCornerShape(26.dp), selected = t == tab, onFocus = { if (it) onTab(t) },
                        focusRequester = if (t == tab) tabFocus else null) {
                        Text(label, style = if (t == tab) T.bodyBold else T.body.copy(color = C.muted), modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (active != null) Text(active.name, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp).padding(horizontal = 16.dp))
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (playlists.isEmpty() && tab != Tab.Settings) {
                EmptyPlaylists(onAdd)
            } else when (tab) {
                Tab.Live -> LiveTab(app, cat, onPlay, restoreKey)
                Tab.Movies -> PosterBrowse(app, true, onOpenMovie)
                Tab.Series -> PosterBrowse(app, false, onOpenSeries)
                Tab.Favs -> {
                    val favs by store.favs.collectAsState()
                    val pid = cat.playlistId.orEmpty()
                    val list = cat.channels.filter { chKey(pid, it) in favs }
                    ChannelGrid(app, cat, list, stringResource(R.string.empty_favs), onPlay, restoreKey)
                }
                Tab.Recents -> {
                    val recents by store.recents.collectAsState()
                    val pid = cat.playlistId.orEmpty()
                    val byKey = remember(cat.channels) { cat.channels.associateBy { chKey(pid, it) } }
                    val list = recents.mapNotNull { byKey[it] }
                    ChannelGrid(app, cat, list, stringResource(R.string.empty_recents), onPlay, restoreKey)
                }
                Tab.Guide -> GuideScreen(app, cat, onPlay, onCatchup)
                Tab.Settings -> SettingsScreen(app, onAdd, onLanguage)
            }
        }
    }
}

@Composable
fun EmptyPlaylists(onAdd: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.no_playlists_title), style = T.display)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.no_playlists_body), style = T.body.copy(color = C.muted))
        Spacer(Modifier.height(32.dp))
        TvButton(stringResource(R.string.add_playlist), onAdd, primary = true, focusRequester = fr)
    }
}

@Composable
private fun LiveTab(app: UrtvApp, cat: CatalogState, onPlay: (List<Channel>, Channel) -> Unit, restoreKey: String?) {
    val store = app.store
    val pid = cat.playlistId.orEmpty()
    val groups = remember(cat.channels) { Browse.groups(cat.channels) }
    var group by remember(pid) { mutableStateOf(Browse.ALL) }
    var query by remember(pid) { mutableStateOf("") }
    val locked by store.lockedGroups.collectAsState()
    val hasPin by store.hasPin.collectAsState()
    var pinReq by remember { mutableStateOf<PinRequest?>(null) }
    val gKey = groupKey(pid, group)
    val isLocked = group != Browse.ALL && gKey in locked && gKey !in app.unlocked
    val list = remember(cat.channels, group, query) { Browse.filter(cat.channels, group, query) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        LazyColumn(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)) {
            items(listOf(Browse.ALL) + groups, key = { it }) { g ->
                val gk = groupKey(pid, g)
                val label = when (g) { Browse.ALL -> stringResource(R.string.all_channels); Browse.OTHER -> stringResource(R.string.group_other); else -> g }
                Tv({ group = g }, Modifier.fillMaxWidth().height(56.dp), RoundedCornerShape(14.dp), selected = g == group, onFocus = { if (it) group = g },
                    onLongClick = if (g == Browse.ALL) null else { { pinReq = if (hasPin) PinRequest.Verify { store.toggleLockedGroup(gk) } else PinRequest.SetNew { store.setPin(it); store.toggleLockedGroup(gk) } } }) {
                    Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (gk in locked) Icon(Glyph.Lock, C.muted, 20)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SearchField(query) { query = it }
            Spacer(Modifier.height(12.dp))
            if (isLocked) {
                val fr = remember { FocusRequester() }
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Glyph.Lock, C.muted, 64)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.group_locked), style = T.title)
                    Spacer(Modifier.height(24.dp))
                    TvButton(stringResource(R.string.unlock), { pinReq = PinRequest.Verify { app.unlocked = app.unlocked + gKey } }, primary = true, focusRequester = fr)
                }
            } else {
                val empty = when {
                    cat.loading && cat.channels.isEmpty() -> null
                    cat.error != null && cat.channels.isEmpty() -> null
                    query.isNotBlank() -> stringResource(R.string.no_results)
                    else -> stringResource(R.string.empty_group)
                }
                ChannelGrid(app, cat, list, empty ?: "", onPlay, restoreKey, showStatus = true)
            }
        }
    }
    PinHost(pinReq, store) { pinReq = null }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    FocusField(query, onChange, stringResource(R.string.search_hint), Modifier.fillMaxWidth(),
        leading = { Icon(Glyph.Search, C.muted, 26) }, imeAction = androidx.compose.ui.text.input.ImeAction.Search)
}

@Composable
fun ChannelGrid(
    app: UrtvApp, cat: CatalogState, list: List<Channel>, emptyText: String,
    onPlay: (List<Channel>, Channel) -> Unit, restoreKey: String?, showStatus: Boolean = false,
) {
    val store = app.store
    val pid = cat.playlistId.orEmpty()
    val epg by app.catalog.epg.collectAsState()
    val favs by store.favs.collectAsState()
    val now by rememberNow()
    val state = rememberLazyGridState()
    // Focus bookkeeping: remember the focused card; if it disappears (e.g. un-favourited) or we come back
    // from the player, put focus on the nearest card so the D-pad never loses its place.
    var lastIdx by remember { mutableStateOf(-1) }
    var focusIdx by remember(restoreKey) { mutableStateOf(if (restoreKey == null) -1 else list.indexOfFirst { chKey(pid, it) == restoreKey }) }
    var prevSize by remember { mutableStateOf(list.size) }
    LaunchedEffect(list.size) {
        if (list.size < prevSize && lastIdx >= 0 && list.isNotEmpty()) focusIdx = minOf(lastIdx, list.size - 1)
        prevSize = list.size
    }
    val fr = remember { FocusRequester() }
    LaunchedEffect(focusIdx, list.size) {
        if (focusIdx in list.indices) { state.scrollToItem(focusIdx); runCatching { fr.requestFocus() }; lastIdx = focusIdx; focusIdx = -1 }
    }

    Box(Modifier.fillMaxSize()) {
        if (list.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showStatus && cat.loading) { Spinner(); Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.loading), style = T.body.copy(color = C.muted)) }
                else if (showStatus && cat.error != null) {
                    Text(stringResource(when (cat.error) { LoadError.Network -> R.string.err_network; LoadError.Format -> R.string.err_format; else -> R.string.err_empty }), style = T.title)
                    Spacer(Modifier.height(20.dp))
                    TvButton(stringResource(R.string.retry), { app.catalog.refreshActive() }, primary = true)
                } else Text(emptyText, style = T.body.copy(color = C.muted))
            }
        } else LazyVerticalGrid(GridCells.Adaptive(320.dp), Modifier.fillMaxSize(), state, contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(list, key = { _, c -> c.url }) { i, ch ->
                val k = chKey(pid, ch)
                ChannelCard(ch, epg.nowNext(ch.epgId, now).first, now, k in favs,
                    onClick = { onPlay(list, ch) }, onLong = { store.toggleFav(k) },
                    focusRequester = if (i == focusIdx) fr else null, onFocus = { if (it) lastIdx = i })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelCard(ch: Channel, now: Programme?, nowMs: Long, fav: Boolean, onClick: () -> Unit, onLong: () -> Unit, focusRequester: FocusRequester? = null, onFocus: (Boolean) -> Unit = {}) {
    Tv(onClick, Modifier.fillMaxWidth().height(96.dp), RoundedCornerShape(18.dp), focusRequester = focusRequester, onFocus = onFocus, onLongClick = onLong) {
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(ch, 64)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(ch.name, style = T.bodyBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now != null) {
                    Text(now.title, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    ProgressBar((nowMs - now.startMs).toFloat() / (now.stopMs - now.startMs))
                }
            }
            if (fav) { Spacer(Modifier.width(10.dp)); Icon(Glyph.Heart, C.accent2, 24, filled = true) }
        }
    }
}

@Composable
fun Logo(ch: Channel, size: Int) {
    val url = ch.logo
    if (url.isNullOrBlank()) Monogram(ch.name, size)
    else coil3.compose.SubcomposeAsyncImage(
        model = url, contentDescription = null, modifier = Modifier.size(size.dp), contentScale = ContentScale.Fit,
        error = { Monogram(ch.name, size) }, loading = { Monogram(ch.name, size) },
    )
}
