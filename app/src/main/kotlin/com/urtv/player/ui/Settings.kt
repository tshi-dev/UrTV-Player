package com.urtv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.data.BufferMode

private val REFRESH = listOf(0, 6, 12, 24, 48)

@Composable
fun SettingsScreen(app: UrtvApp, onAdd: () -> Unit, onLanguage: () -> Unit, onManageGroups: () -> Unit) {
    val store = app.store
    val playlists by store.playlists.collectAsState()
    val active by store.activeId.collectAsState()
    val lang by store.lang.collectAsState()
    val refresh by store.refreshHours.collectAsState()
    val buffer by store.buffer.collectAsState()
    val resume by store.resumeLast.collectAsState()
    val hasPin by store.hasPin.collectAsState()
    val textScale by store.textScale.collectAsState()
    val look by store.appearance.collectAsState()
    val offsets by store.epgOffsetMin.collectAsState()
    var pin by remember { mutableStateOf<PinRequest?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var appearance by remember { mutableStateOf(false) }
    if (appearance) { AppearanceScreen(store) { appearance = false }; return }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionLabel(stringResource(R.string.set_playlists)) }
        for (p in playlists) item(key = p.id) {
            Tv({ store.setActive(p.id); app.catalog.open(p.id) }, Modifier.fillMaxWidth().height(72.dp), selected = p.id == active) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = T.bodyBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(if (p.isXtream) R.string.kind_xtream else R.string.kind_m3u), style = T.tiny)
                    }
                    if (p.id == active) Icon(Glyph.Check, accentInk(), 28)
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(stringResource(R.string.add_playlist), onAdd, primary = playlists.isEmpty())
            if (active != null) {
                TvButton(stringResource(R.string.refresh_now), { app.catalog.refreshActive() })
                TvButton(stringResource(R.string.remove_playlist), { confirmDelete = active })
            }
        } }
        item { Spacer(Modifier.height(8.dp)); SectionLabel(stringResource(R.string.set_general)) }
        item { Setting(stringResource(R.string.set_language), if (lang == "ar") "العربية" else "English") { store.setLang(if (lang == "ar") "en" else "ar"); onLanguage() } }
        item { Setting(stringResource(R.string.set_refresh), if (refresh == 0) stringResource(R.string.manual) else stringResource(R.string.every_hours, refresh)) { store.setRefreshHours(REFRESH[(REFRESH.indexOf(refresh) + 1) % REFRESH.size]) } }
        item { Setting(stringResource(R.string.set_buffer), stringResource(when (buffer) { BufferMode.Low -> R.string.buf_low; BufferMode.Normal -> R.string.buf_normal; BufferMode.High -> R.string.buf_high }), stringResource(R.string.set_buffer_hint)) {
            store.setBuffer(BufferMode.entries[(buffer.ordinal + 1) % BufferMode.entries.size]); app.player.buffer = store.buffer.value } }
        item { Setting(stringResource(R.string.set_resume), stringResource(if (resume) R.string.on else R.string.off)) { store.setResume(!resume) } }
        item { Setting(stringResource(R.string.set_theme), stringResource(themeName(look.theme)) + " · " + stringResource(accentName(look.accent))) { appearance = true } }
        if (active != null) {
            item { Spacer(Modifier.height(8.dp)); SectionLabel(stringResource(R.string.set_active_playlist)) }
            item {
                val off = offsets[active] ?: 0
                Setting(stringResource(R.string.set_epg_offset), (if (off > 0) "+" else "") + "%.1f h".format(java.util.Locale.US, off / 60f), stringResource(R.string.set_epg_offset_hint)) {
                    val next = if (off >= 720) -720 else off + 30
                    store.setEpgOffset(active!!, next); app.catalog.open(active)
                }
            }
            item { Setting(stringResource(R.string.set_groups), "", stringResource(R.string.set_groups_hint)) { onManageGroups() } }
        }
        item { Spacer(Modifier.height(8.dp)); SectionLabel(stringResource(R.string.set_parental)) }
        item { Setting(stringResource(R.string.set_pin), stringResource(if (hasPin) R.string.pin_set else R.string.pin_none), stringResource(R.string.set_pin_hint)) {
            pin = if (hasPin) PinRequest.Verify { pin = PinRequest.SetNew { store.setPin(it) } } else PinRequest.SetNew { store.setPin(it) } } }
        if (hasPin) item { Setting(stringResource(R.string.pin_remove), "") { pin = PinRequest.Verify { store.setPin(null); app.unlocked = emptySet() } } }
        item { Spacer(Modifier.height(8.dp)); SectionLabel(stringResource(R.string.set_about)) }
        item { Text(stringResource(R.string.about_text), style = T.small, modifier = Modifier.padding(horizontal = 8.dp)) }
    }
    PinHost(pin, store) { pin = null }
    confirmDelete?.let { id ->
        ConfirmDialog(stringResource(R.string.remove_confirm), onYes = { app.catalog.forget(id); store.removePlaylist(id); app.catalog.open(store.activeId.value); confirmDelete = null }, onNo = { confirmDelete = null })
    }
}

@Composable
private fun Setting(label: String, value: String, hint: String? = null, onClick: () -> Unit) {
    Tv(onClick, Modifier.fillMaxWidth().height(if (hint != null) 84.dp else 68.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = T.body)
                if (hint != null) Text(hint, style = T.tiny)
            }
            Text(value, style = T.bodyBold.copy(color = accentInk()))
        }
    }
}

@Composable
fun ConfirmDialog(text: String, onYes: () -> Unit, onNo: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onNo)
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background2(C.scrim), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).background2(C.surface, Rd(28)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = T.title)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(stringResource(R.string.no), onNo, focusRequester = fr)
                TvButton(stringResource(R.string.yes), onYes, primary = true)
            }
        }
    }
}

private fun Modifier.background2(c: androidx.compose.ui.graphics.Color, s: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape) =
    this.then(Modifier.background(c, s))

/** Show/hide and reorder groups of the active playlist. */
@Composable
fun GroupsManage(app: UrtvApp, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val store = app.store
    val cat by app.catalog.state.collectAsState()
    val order by store.groupOrder.collectAsState()
    val hidden by store.hiddenGroups.collectAsState()
    val pid = cat.playlistId.orEmpty()
    val all = remember(cat.channels) { com.urtv.player.data.Browse.groups(cat.channels) }
    val groups = com.urtv.player.data.Browse.arrange(all, order[pid].orEmpty(), emptySet(), includeHidden = true)
    fun move(i: Int, d: Int) { val l = groups.toMutableList(); val j = i + d; if (j in l.indices) { val t = l[i]; l[i] = l[j]; l[j] = t; store.setGroupOrder(pid, l) } }
    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 64.dp, vertical = 36.dp)) {
        Text(stringResource(R.string.set_groups), style = T.display)
        Text(stringResource(R.string.set_groups_hint), style = T.small)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
            itemsIndexed(groups, key = { _, g -> g }) { i, g ->
                val hid = "$pid|$g" in hidden
                val label = if (g == com.urtv.player.data.Browse.OTHER) stringResource(R.string.group_other) else g
                Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tv({ store.toggleHiddenGroup("$pid|$g") }, Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = T.body.copy(color = if (hid) mutedInk() else ink()), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(stringResource(if (hid) R.string.hidden else R.string.shown), style = T.small.copy(color = if (hid) dangerInk() else accentInk()))
                        }
                    }
                    Tv({ move(i, -1) }, Modifier.size(64.dp), Rd(16)) { Text("▲", style = T.body, modifier = Modifier.align(Alignment.Center)) }
                    Tv({ move(i, 1) }, Modifier.size(64.dp), Rd(16)) { Text("▼", style = T.body, modifier = Modifier.align(Alignment.Center)) }
                }
            }
        }
        TvButton(stringResource(R.string.back), onBack)
    }
}
