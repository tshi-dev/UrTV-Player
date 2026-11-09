package com.urtv.player.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.tv.material3.Text
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.ui.C
import com.urtv.player.ui.PosterBackdrop
import com.urtv.player.ui.Rd
import com.urtv.player.ui.SettingsScreen
import com.urtv.player.ui.T
import com.urtv.player.ui.Tv
import com.urtv.player.ui.TvButton
import com.urtv.player.ui.mutedInk

private sealed interface Dest {
    data object Home : Dest
    data class Lib(val lib: Library) : Dest
    data object Search : Dest
    data object Settings : Dest
    data object Add : Dest
    data class Detail(val id: String) : Dest
    data class Play(val item: MediaItem, val startMs: Long, val queue: List<MediaItem>) : Dest
}

/** The whole home-server experience: its own pill navigation, library pages, detail pages and the player. */
@Composable
fun ServerRoot(app: UrtvApp, onLanguage: () -> Unit, onIptv: () -> Unit) {
    val mgr = app.servers
    val servers by mgr.servers.collectAsState(); val homeOnly by mgr.homeOnly.collectAsState(); val activeId by mgr.activeId.collectAsState()
    val cfg = servers.firstOrNull { it.id == activeId } ?: servers.firstOrNull()
    val backend = remember(cfg) { cfg?.let { mgr.backend(it) } }
    var top by remember { mutableStateOf<Dest>(Dest.Home) }
    var pushed by remember { mutableStateOf(listOf<Dest>()) }
    val current = pushed.lastOrNull() ?: top
    val libs by load(cfg?.id) { backend?.libraries() ?: emptyList() }

    BackHandler(enabled = pushed.isNotEmpty() || top != Dest.Home || !homeOnly) {
        when {
            pushed.isNotEmpty() -> pushed = pushed.dropLast(1)
            top != Dest.Home -> top = Dest.Home
            else -> onIptv()
        }
    }
    fun open(i: MediaItem) { pushed = pushed + Dest.Detail(i.id) }
    fun play(i: MediaItem, at: Long, q: List<MediaItem>) { pushed = pushed + Dest.Play(i, at, q) }

    if (current is Dest.Play && backend != null) {
        ServerPlayerScreen(app, backend, current.item, current.startMs, current.queue, onExit = { pushed = pushed.dropLast(1) })
        return
    }

    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (cfg == null) runCatching { tabFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(C.bgBrush)) {
        PosterBackdrop()
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("UrTV", style = T.display.copy(fontSize = 36.sp), modifier = Modifier.padding(end = 32.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Pill(stringResource(R.string.srv_tab_home), top == Dest.Home, if (top == Dest.Home) tabFocus else null) { top = Dest.Home; pushed = emptyList() } }
                    if (cfg != null) items((libs as? Res.Ok)?.v.orEmpty().take(5), key = { it.id }) { l ->
                        Pill(l.name, (top as? Dest.Lib)?.lib?.id == l.id) { top = Dest.Lib(l); pushed = emptyList() } }
                    if (cfg != null) item { Pill(stringResource(R.string.srv_tab_search), top == Dest.Search) { top = Dest.Search; pushed = emptyList() } }
                    item { Pill(stringResource(R.string.tab_settings), top == Dest.Settings) { top = Dest.Settings; pushed = emptyList() } }
                    if (!homeOnly) item { Pill(stringResource(R.string.srv_tab_iptv), false) { onIptv() } }
                }
                Spacer(Modifier.weight(1f))
                cfg?.let { Text(it.name, style = T.small, maxLines = 1) }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    current is Dest.Add -> AddServerScreen(app) { pushed = emptyList(); top = Dest.Home }
                    top == Dest.Settings && pushed.isEmpty() -> SettingsScreen(app, onAdd = { onIptv() }, onLanguage = onLanguage, onManageGroups = {}, onAddServer = { pushed = pushed + Dest.Add })
                    cfg == null || backend == null -> NoServer(onAdd = { pushed = pushed + Dest.Add })
                    current is Dest.Detail -> DetailScreen(backend, current.id, ::play, ::open)
                    current is Dest.Lib -> LibraryScreen(backend, current.lib, ::open)
                    current is Dest.Search -> SearchScreen(backend, ::open)
                    else -> ServerHome(backend, onOpen = ::open, onPlay = { i -> play(i, i.resumeMs, emptyList()) })
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Tv(onClick, Modifier.height(52.dp), Rd(26), selected = selected, onFocus = { if (it) onClick() }, focusRequester = focusRequester) {
        Text(label, style = if (selected) T.bodyBold else T.body.copy(color = mutedInk()), modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun NoServer(onAdd: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.srv_none_title), style = T.display)
            Text(stringResource(R.string.srv_none_hint), style = T.body.copy(color = mutedInk()))
            TvButton(stringResource(R.string.srv_add_server), onAdd, primary = true, focusRequester = fr)
        }
    }
}
