package com.urtv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.urtv.player.UrtvApp
import com.urtv.player.core.Channel
import com.urtv.player.data.WatchItem
import kotlinx.coroutines.launch

private enum class Screen { Home, Player, Add, Groups, HomeRows, Movie, Series, Vod, Server }

@Composable
fun AppRoot(app: UrtvApp, onLanguageChanged: () -> Unit) {
    val store = app.store
    val scope = rememberCoroutineScope()
    val homeOnly by app.servers.homeOnly.collectAsState()
    // Start screen: «home server only» forces the server; otherwise the start-screen setting may pick it.
    var screen by rememberSaveable { mutableStateOf(if (app.servers.homeOnly.value || (store.startScreen.value == "server" && app.servers.servers.value.isNotEmpty())) Screen.Server else Screen.Home) }
    var tab by rememberSaveable { mutableStateOf(startTab(store.startScreen.value)) }
    var queue by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var playing by remember { mutableStateOf<Channel?>(null) }
    var restoreKey by remember { mutableStateOf<String?>(null) }
    var detailId by remember { mutableStateOf("") }
    var vodItems by remember { mutableStateOf<List<VodPlay>>(emptyList()) }
    var vodIndex by remember { mutableStateOf(0) }
    var vodStart by remember { mutableStateOf(0L) }
    var vodBack by remember { mutableStateOf(Screen.Home) }
    val cat by app.catalog.state.collectAsState()
    val playlists by store.playlists.collectAsState()
    val def = playlists.firstOrNull { it.id == cat.playlistId }
    // Home server only: nothing of IPTV is loaded or reachable.
    LaunchedEffect(homeOnly) { if (homeOnly && screen != Screen.Server) screen = Screen.Server }
    LaunchedEffect(cat.playlistId, def?.isXtream) { if (!homeOnly) app.vod.open(def) }
    if ((tab == Tab.Movies || tab == Tab.Series) && def?.isXtream != true && playlists.isNotEmpty() && cat.playlistId != null) tab = Tab.Home

    // Open the active playlist once per process; optionally resume the last channel ("last" start screen or the resume setting).
    LaunchedEffect(Unit) { if (cat.playlistId == null && !app.servers.homeOnly.value) app.catalog.open(store.activeId.value ?: store.playlists.value.firstOrNull()?.id) }
    LaunchedEffect(cat.channels, cat.playlistId) {
        if (!app.resumeHandled && cat.channels.isNotEmpty() && !homeOnly && screen == Screen.Home) {
            app.resumeHandled = true
            val key = store.lastKey(); val pid = cat.playlistId.orEmpty()
            val ch = key?.let { k -> cat.channels.firstOrNull { chKey(pid, it) == k } }
            if ((store.resumeLast.value || store.startScreen.value == "last") && ch != null && screen == Screen.Home) { queue = cat.channels; playing = ch; screen = Screen.Player }
        }
    }

    fun playVod(items: List<VodPlay>, index: Int, at: Long, back: Screen) { vodItems = items; vodIndex = index; vodStart = at; vodBack = back; screen = Screen.Vod }

    /** Resume a "Continue watching" item; episodes get their following episodes queued so auto-next works. */
    fun resume(w: WatchItem) {
        val d = def ?: return
        val single = VodPlay(w.title, w.url, w.key, w.pid, w.kind, w.image, w.seriesId, w.season, w.episode, w.subtitle)
        if (w.kind == "episode" && w.seriesId != null) scope.launch {
            val eps = app.vod.episodes(d, w.seriesId)
            val i = eps.indexOfFirst { vodKey(d.id, it.id) == w.key }
            if (i >= 0) {
                val s = app.vod.state.value.series.firstOrNull { it.id == w.seriesId }
                playVod(eps.drop(i).map { VodPlay(w.title, it.url, vodKey(d.id, it.id), d.id, "episode", it.image ?: s?.poster, w.seriesId, it.season, it.number, it.title) }, 0, w.posMs, Screen.Home)
            } else playVod(listOf(single), 0, w.posMs, Screen.Home)
        } else playVod(listOf(single), 0, w.posMs, Screen.Home)
    }

    if (homeOnly && screen != Screen.Server) return    // the effect above moves to the server screen on the next frame
    when (screen) {
        Screen.Server -> com.urtv.player.server.ServerRoot(app, onLanguage = onLanguageChanged, onIptv = { screen = Screen.Home })
        Screen.Home -> HomeScreen(
            app, tab, { tab = it },
            onPlay = { list, ch -> queue = list; playing = ch; screen = Screen.Player },
            onAdd = { screen = Screen.Add }, onServer = { screen = Screen.Server }, onLanguage = onLanguageChanged, restoreKey = restoreKey,
            onManageGroups = { screen = Screen.Groups }, onManageHome = { screen = Screen.HomeRows },
            onOpenMovie = { detailId = it; screen = Screen.Movie }, onOpenSeries = { detailId = it; screen = Screen.Series },
            onCatchup = { ch, p ->
                val d = def
                if (d != null && ch.streamId != null) {
                    val mins = ((p.stopMs - p.startMs) / 60_000L).toInt() + 1
                    playVod(listOf(VodPlay(ch.name, app.vod.account(d).catchupUrl(ch.streamId!!, p.startMs, mins), "cu|${d.id}|${ch.streamId}|${p.startMs}", episodeTitle = p.title)), 0, 0, Screen.Home)
                }
            },
            onResume = { resume(it) },
        )
        Screen.Player -> playing?.let { ch ->
            PlayerScreen(app, queue, ch, onExit = { last -> restoreKey = chKey(cat.playlistId.orEmpty(), last); playing = null; screen = Screen.Home })
        } ?: run { screen = Screen.Home }
        Screen.Add -> AddPlaylistScreen(app) { screen = Screen.Home }
        Screen.Groups -> GroupsManage(app) { tab = Tab.Settings; screen = Screen.Home }
        Screen.HomeRows -> HomeManage(app) { tab = Tab.Settings; screen = Screen.Home }
        Screen.Movie -> def?.let { d -> MovieDetail(app, d, detailId, onPlay = { v, at -> playVod(listOf(v), 0, at, Screen.Movie) }, onBack = { screen = Screen.Home }) } ?: run { screen = Screen.Home }
        Screen.Series -> def?.let { d -> SeriesDetail(app, d, detailId, onPlay = { l, i, at -> playVod(l, i, at, Screen.Series) }, onBack = { screen = Screen.Home }) } ?: run { screen = Screen.Home }
        Screen.Vod -> if (vodItems.isEmpty()) screen = Screen.Home else VodPlayerScreen(app, vodItems, vodIndex, vodStart, onExit = { screen = vodBack })
    }
}

private fun startTab(s: String) = when (s) { "live" -> Tab.Live; "guide" -> Tab.Guide; "movies" -> Tab.Movies; "series" -> Tab.Series; else -> Tab.Home }
