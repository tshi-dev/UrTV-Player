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

private enum class Screen { Home, Player, Add }

@Composable
fun AppRoot(app: UrtvApp, onLanguageChanged: () -> Unit) {
    val store = app.store
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var tab by rememberSaveable { mutableStateOf(Tab.Live) }
    var queue by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var playing by remember { mutableStateOf<Channel?>(null) }
    var restoreKey by remember { mutableStateOf<String?>(null) }
    val cat by app.catalog.state.collectAsState()

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
        )
        Screen.Player -> playing?.let { ch ->
            PlayerScreen(app, queue, ch, onExit = { last -> restoreKey = chKey(cat.playlistId.orEmpty(), last); playing = null; screen = Screen.Home })
        } ?: run { screen = Screen.Home }
        Screen.Add -> AddPlaylistScreen(app) { screen = Screen.Home }
    }
}
