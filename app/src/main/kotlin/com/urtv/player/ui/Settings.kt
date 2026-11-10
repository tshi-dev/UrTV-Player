package com.urtv.player.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.data.Browse
import com.urtv.player.server.serverSettings
import com.urtv.player.data.BufferMode

private val REFRESH = listOf(0, 6, 12, 24, 48)
private enum class Section(val label: Int) { Server(R.string.srv_settings_title), General(R.string.set_general), Playlists(R.string.set_playlists), Playback(R.string.set_playback), Appearance(R.string.set_theme), Home(R.string.set_home), Parental(R.string.set_parental), Backup(R.string.set_backup), About(R.string.set_about) }
private val STARTS = listOf("home", "live", "guide", "movies", "series", "last", "server")

@Composable
fun SettingsScreen(app: UrtvApp, onAdd: () -> Unit, onLanguage: () -> Unit, onManageGroups: () -> Unit, onManageHome: () -> Unit, onBackup: (Boolean) -> Unit = {}, onAddServer: () -> Unit = {}) {
    val homeOnly by app.servers.homeOnly.collectAsState()
    val hasServers by app.servers.servers.collectAsState()
    var removeServer by remember { mutableStateOf<String?>(null) }
    val store = app.store
    val playlists by store.playlists.collectAsState()
    val active by store.activeId.collectAsState()
    val lang by store.lang.collectAsState()
    val refresh by store.refreshHours.collectAsState()
    val buffer by store.buffer.collectAsState()
    val preview by store.livePreview.collectAsState()
    val tunnel by store.tunneling.collectAsState()
    val statsOn by store.statsOverlay.collectAsState()
    val prefetch by store.prefetchNeighbours.collectAsState()
    val resume by store.resumeLast.collectAsState()
    val hasPin by store.hasPin.collectAsState()
    val textScale by store.textScale.collectAsState()
    val look by store.appearance.collectAsState()
    var appearance by remember { mutableStateOf(false) }
    if (appearance) { AppearanceScreen(store) { appearance = false }; return }
    var share by remember { mutableStateOf(false) }
    if (share) { ShareScreen(app) { share = false }; return }
    val offsets by store.epgOffsetMin.collectAsState()
    val start by store.startScreen.collectAsState()
    val isXtream = playlists.firstOrNull { it.id == active }?.isXtream == true
    // «Home server only» hides everything that belongs to IPTV.
    val shown = Section.entries.filter { !homeOnly || it == Section.Server || it == Section.General || it == Section.Appearance || it == Section.About }
    var section by remember { mutableStateOf(if (homeOnly) Section.Server else Section.General) }
    var pin by remember { mutableStateOf<PinRequest?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (s in shown) Tv({ section = s }, Modifier.fillMaxWidth().height(56.dp), selected = s == section, onFocus = { if (it) section = s }) {
                Text(stringResource(s.label), style = if (s == section) T.bodyBold else T.body.copy(color = C.muted), modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight().wrapContentWidth(Alignment.Start).widthIn(max = 900.dp), contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (section) {
                Section.Server -> serverSettings(app, onAddServer) { removeServer = it }
                Section.General -> {
                    item { Setting(stringResource(R.string.set_language), when (lang) { "ar" -> "العربية"; "en" -> "English"; else -> stringResource(R.string.lang_auto) }, stringResource(R.string.set_language_hint)) { store.setLang(com.urtv.player.data.Language.next(lang)); onLanguage() } }
                    if (!homeOnly) item { Setting(stringResource(R.string.set_refresh), if (refresh == 0) stringResource(R.string.manual) else stringResource(R.string.every_hours, refresh)) { store.setRefreshHours(REFRESH[(REFRESH.indexOf(refresh) + 1) % REFRESH.size]) } }
                    if (!homeOnly) item { Setting(stringResource(R.string.set_resume), stringResource(if (resume) R.string.on else R.string.off), stringResource(R.string.set_resume_hint)) { store.setResume(!resume) } }
                }
                Section.Playlists -> {
                    for (p in playlists) item(key = p.id) {
                        Tv({ store.setActive(p.id); app.catalog.open(p.id) }, Modifier.fillMaxWidth().height(72.dp), selected = p.id == active) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, style = T.row, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Chip(stringResource(if (p.isXtream) R.string.kind_xtream else R.string.kind_m3u)); Spacer(Modifier.width(14.dp))
                                if (p.id == active) Icon(Glyph.Check, C.accent, 26)
                            }
                        }
                    }
                    item { Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(stringResource(R.string.add_playlist), onAdd, primary = playlists.isEmpty())
                        if (active != null) { TvButton(stringResource(R.string.refresh_now), { app.catalog.refreshActive(); app.vod.open(playlists.firstOrNull { it.id == active }, true) }); TvButton(stringResource(R.string.remove_playlist), { confirmDelete = active }) }
                    } }
                    if (active != null) {
                        item {
                            val off = offsets[active] ?: 0
                            Setting(stringResource(R.string.set_epg_offset), (if (off > 0) "+" else "") + "%.1f".format(java.util.Locale.US, off / 60f) + " h", stringResource(R.string.set_epg_offset_hint)) {
                                store.setEpgOffset(active!!, if (off >= 720) -720 else off + 30); app.catalog.open(active)
                            }
                        }
                        item { Setting(stringResource(R.string.set_groups), "", stringResource(R.string.set_groups_hint)) { onManageGroups() } }
                    }
                }
                Section.Playback -> {
                    item { Setting(stringResource(R.string.set_buffer), stringResource(when (buffer) { BufferMode.Low -> R.string.buf_low; BufferMode.Normal -> R.string.buf_normal; BufferMode.High -> R.string.buf_high }), stringResource(R.string.set_buffer_hint)) {
                        store.setBuffer(BufferMode.entries[(buffer.ordinal + 1) % BufferMode.entries.size]); app.player.buffer = store.buffer.value } }
                    item { Setting(stringResource(R.string.set_preview), stringResource(if (preview) R.string.on else R.string.off), stringResource(R.string.set_preview_hint)) { store.setLivePreview(!preview) } }
                    item { Setting(stringResource(R.string.set_prefetch), stringResource(if (prefetch) R.string.on else R.string.off), stringResource(R.string.set_prefetch_hint)) { store.setPrefetch(!prefetch) } }
                    item { Setting(stringResource(R.string.set_tunnel), stringResource(if (tunnel) R.string.on else R.string.off), stringResource(R.string.set_tunnel_hint)) { store.setTunneling(!tunnel) } }
                    item { val alt by store.autoAlt.collectAsState(); Setting(stringResource(R.string.set_autoalt), stringResource(if (alt) R.string.on else R.string.off), stringResource(R.string.set_autoalt_hint)) { store.setAutoAlt(!alt) } }
                    item { Setting(stringResource(R.string.set_stats), stringResource(if (statsOn) R.string.on else R.string.off), stringResource(R.string.set_stats_hint)) { store.setStatsOverlay(!statsOn) } }
                }
                Section.Appearance -> {
                    item { Setting(stringResource(R.string.set_text_size), stringResource(listOf(R.string.size_normal, R.string.size_large, R.string.size_xl)[textScale])) { store.setTextScale((textScale + 1) % 3) } }
                    item { Setting(stringResource(R.string.set_theme), stringResource(themeName(look.theme)) + "  " + stringResource(accentName(look.accent)), stringResource(R.string.set_theme_hint)) { appearance = true } }
                }
                Section.Home -> {
                    item {
                        val opts = STARTS.filter { (isXtream || (it != "movies" && it != "series")) && (it != "server" || hasServers.isNotEmpty()) }
                        Setting(stringResource(R.string.set_start), startLabel(start), stringResource(R.string.set_start_hint)) { store.setStartScreen(opts[(opts.indexOf(start).coerceAtLeast(0) + 1) % opts.size]) }
                    }
                    item { Setting(stringResource(R.string.home_customize), "", stringResource(R.string.home_customize_hint)) { onManageHome() } }
                }
                Section.Parental -> {
                    item { Setting(stringResource(R.string.set_pin), stringResource(if (hasPin) R.string.pin_set else R.string.pin_none), stringResource(R.string.set_pin_hint)) {
                        pin = if (hasPin) PinRequest.Verify { pin = PinRequest.SetNew { store.setPin(it) } } else PinRequest.SetNew { store.setPin(it) } } }
                    if (hasPin) item { Setting(stringResource(R.string.pin_remove), "") { pin = PinRequest.Verify { store.setPin(null); app.unlocked = emptySet() } } }
                }
                Section.Backup -> {
                    item { Text(stringResource(R.string.backup_hint), style = T.small, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                    item { Setting(stringResource(R.string.backup_to_phone), "") { onBackup(false) } }
                    item { Setting(stringResource(R.string.restore_from_phone), "") { onBackup(true) } }
                }
                Section.About -> item {
                    Column(Modifier.padding(horizontal = 12.dp).width(900.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.about_text), style = T.body.copy(color = C.muted))
                        Text(stringResource(R.string.about_version, com.urtv.player.BuildConfig.VERSION_NAME), style = T.bodyBold)
                        Setting(stringResource(R.string.share_row), "", stringResource(R.string.share_row_hint)) { share = true }
                        when (app.integrity) {
                            com.urtv.player.data.Integrity.Status.Official -> Text(stringResource(R.string.about_official), style = T.body.copy(color = C.accent))
                            com.urtv.player.data.Integrity.Status.Debug -> Text(stringResource(R.string.about_debug), style = T.body.copy(color = C.muted))
                            else -> Text(stringResource(R.string.about_unofficial), style = T.body.copy(color = C.danger))
                        }
                    }
                }
            }
        }
    }
    PinHost(pin, store) { pin = null }
    removeServer?.let { id -> ConfirmDialog(stringResource(R.string.srv_remove_confirm), onYes = { app.servers.remove(id); removeServer = null }, onNo = { removeServer = null }) }
    confirmDelete?.let { id ->
        ConfirmDialog(stringResource(R.string.remove_confirm), onYes = { app.catalog.forget(id); app.vod.forget(id); store.removePlaylist(id); app.catalog.open(store.activeId.value); confirmDelete = null }, onNo = { confirmDelete = null })
    }
}

@Composable
private fun startLabel(v: String) = stringResource(when (v) { "live" -> R.string.tab_live; "guide" -> R.string.tab_guide; "movies" -> R.string.tab_movies; "series" -> R.string.tab_series; "last" -> R.string.start_last; "server" -> R.string.srv_tab_server; else -> R.string.tab_home })

@Composable
private fun Setting(label: String, value: String, hint: String? = null, onClick: () -> Unit) {
    Tv(onClick, Modifier.fillMaxWidth().height(if (hint != null) 84.dp else 68.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = T.row)
                if (hint != null) Text(hint, style = T.tiny)
            }
            Text(value, style = T.bodyBold.copy(color = C.muted), maxLines = 1)
        }
    }
}

@Composable
fun ConfirmDialog(text: String, onYes: () -> Unit, onNo: () -> Unit) {
    BackHandler(onBack = onNo)
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(C.scrim), contentAlignment = Alignment.Center) {
        Column(Modifier.width(540.dp).background(C.surface, Rd(22)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = T.title)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { TvButton(stringResource(R.string.no), onNo, focusRequester = fr); TvButton(stringResource(R.string.yes), onYes, primary = true) }
        }
    }
}

/** Show/hide and reorder groups of the active playlist. */
@Composable
fun GroupsManage(app: UrtvApp, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val store = app.store
    val cat by app.catalog.state.collectAsState()
    val order by store.groupOrder.collectAsState()
    val hidden by store.hiddenGroups.collectAsState()
    val pid = cat.playlistId.orEmpty()
    val all = remember(cat.channels) { Browse.groups(cat.channels) }
    val groups = Browse.arrange(all, order[pid].orEmpty(), emptySet(), includeHidden = true)
    ManageList(stringResource(R.string.set_groups), stringResource(R.string.set_groups_hint), groups.map { it to (if (it == Browse.OTHER) stringResource(R.string.group_other) else it) }, { g -> "$pid|$g" !in hidden },
        onToggle = { store.toggleHiddenGroup("$pid|$it") }, onMove = { i, d -> val l = groups.toMutableList(); val j = i + d; if (j in l.indices) { val t = l[i]; l[i] = l[j]; l[j] = t; store.setGroupOrder(pid, l) } }, onBack = onBack)
}

/** Choose and reorder the rows of the Home screen (continue watching, favourites, recent, movies, series, any group). */
@Composable
fun HomeManage(app: UrtvApp, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val store = app.store
    val rows by store.homeRows.collectAsState()
    val cat by app.catalog.state.collectAsState()
    val isXtream = app.store.playlists.collectAsState().value.firstOrNull { it.id == cat.playlistId }?.isXtream == true
    val groups = remember(cat.channels) { Browse.groups(cat.channels).map { "group:$it" } }
    val available = listOf("continue", "favs", "recent") + (if (isXtream) listOf("movies", "series") else emptyList()) + groups
    val enabled = rows.filter { it in available }
    val full = enabled + available.filter { it !in enabled }
    @Composable fun name(id: String) = when (id) {
        "continue" -> stringResource(R.string.cont_watching); "favs" -> stringResource(R.string.tab_favs); "recent" -> stringResource(R.string.recent_channels)
        "movies" -> stringResource(R.string.tab_movies); "series" -> stringResource(R.string.tab_series)
        else -> id.removePrefix("group:").let { if (it == Browse.OTHER) stringResource(R.string.group_other) else it }
    }
    ManageList(stringResource(R.string.home_customize), stringResource(R.string.home_customize_hint), full.map { it to name(it) }, { it in enabled },
        onToggle = { id -> store.setHomeRows(if (id in enabled) enabled - id else enabled + id) },
        onMove = { i, d -> val l = full.toMutableList(); val j = i + d; if (j in l.indices) { val t = l[i]; l[i] = l[j]; l[j] = t; store.setHomeRows(l.filter { it in enabled }) } }, onBack = onBack)
}

@Composable
private fun ManageList(title: String, hint: String, items: List<Pair<String, String>>, shown: (String) -> Boolean, onToggle: (String) -> Unit, onMove: (Int, Int) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 72.dp, vertical = 40.dp)) {
        Text(title, style = T.display); Text(hint, style = T.small)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(items, key = { _, g -> g.first }) { i, (id, label) ->
                val on = shown(id)
                Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tv({ onToggle(id) }, Modifier.weight(1f).fillMaxHeight(), restColor = C.surface) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = T.row.copy(color = if (on) C.text else C.faint), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(stringResource(if (on) R.string.shown else R.string.hidden), style = T.small.copy(color = if (on) C.accent else C.faint))
                        }
                    }
                    Tv({ onMove(i, -1) }, Modifier.size(60.dp), Rd(12), restColor = C.surface) { Text("▲", style = T.body, modifier = Modifier.align(Alignment.Center)) }
                    Tv({ onMove(i, 1) }, Modifier.size(60.dp), Rd(12), restColor = C.surface) { Text("▼", style = T.body, modifier = Modifier.align(Alignment.Center)) }
                }
            }
        }
        TvButton(stringResource(R.string.back), onBack)
    }
}
