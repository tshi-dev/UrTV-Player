package com.urtv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import com.urtv.player.UrtvApp
import com.urtv.player.core.Channel

private enum class Screen { Home, Player, Add, Movie, Series, Vod }

@Composable
fun AppRoot(app: UrtvApp, onLanguageChanged: () -> Unit) {
    val store = app.store
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var tab by rememberSaveable { mutableStateOf(Tab.Live) }
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
    LaunchedEffect(cat.playlistId, def?.isXtream) { app.vod.open(def) }
    if (tab == Tab.Movies || tab == Tab.Series) { if (def?.isXtream != true) tab = Tab.Live }

    // Open the active playlist once per process; optionally resume the last channel.
    LaunchedEffect(Unit) {
        if (cat.playlistId == null) app.catalog.open(store.activeId.value ?: store.playlists.value.firstOrNull()?.id)
    }
    LaunchedEffect(cat.channels, cat.playlistId) {
        if (!app.resumeHandled && cat.channels.isNotEmpty()) {
            app.resumeHandled = true
            val key = store.lastKey()
            val pid = cat.playlistId.orEmpty()
            val ch = key?.let { k -> cat.channels.firstOrNull { chKey(pid, it) == k } }
            if (store.resumeLast.value && ch != null && screen == Screen.Home) { queue = cat.channels; playing = ch; screen = Screen.Player }
        }
    }
    LaunchedEffect(screen) { if (screen == Screen.Home && playing == null) restoreKey = null }

    when (screen) {
        Screen.Home -> HomeScreen(
            app, tab, { tab = it },
            onPlay = { list, ch -> queue = list; playing = ch; screen = Screen.Player },
            onAdd = { screen = Screen.Add }, onLanguage = onLanguageChanged, restoreKey = restoreKey,
            onOpenMovie = { detailId = it; screen = Screen.Movie }, onOpenSeries = { detailId = it; screen = Screen.Series },
            onCatchup = { ch, p ->
                val d = def
                if (d != null && ch.streamId != null) {
                    val mins = ((p.stopMs - p.startMs) / 60_000L).toInt() + 1
                    vodItems = listOf(VodPlay("${ch.name} · ${p.title}", app.vod.account(d).catchupUrl(ch.streamId!!, p.startMs, mins), "cu|${d.id}|${ch.streamId}|${p.startMs}"))
                    vodIndex = 0; vodStart = 0; vodBack = Screen.Home; screen = Screen.Vod
                }
            },
        )
        Screen.Movie -> def?.let { d -> MovieDetail(app, d, detailId, onPlay = { v, at -> vodItems = listOf(v); vodIndex = 0; vodStart = at; vodBack = Screen.Movie; screen = Screen.Vod }, onBack = { screen = Screen.Home }) } ?: run { screen = Screen.Home }
        Screen.Series -> def?.let { d -> SeriesDetail(app, d, detailId, onPlay = { l, i, at -> vodItems = l; vodIndex = i; vodStart = at; vodBack = Screen.Series; screen = Screen.Vod }, onBack = { screen = Screen.Home }) } ?: run { screen = Screen.Home }
        Screen.Vod -> if (vodItems.isEmpty()) screen = Screen.Home else VodPlayerScreen(app, vodItems, vodIndex, vodStart, onExit = { screen = vodBack })
        Screen.Player -> playing?.let { ch ->
            PlayerScreen(app, queue, ch, onExit = { last -> restoreKey = chKey(cat.playlistId.orEmpty(), last); playing = null; screen = Screen.Home })
        } ?: run { screen = Screen.Home }
        Screen.Add -> AddPlaylistScreen(app) { screen = Screen.Home }
    }
}
