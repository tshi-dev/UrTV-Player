package com.urtv.player.ui

import androidx.compose.foundation.background
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
fun SettingsScreen(app: UrtvApp, onAdd: () -> Unit, onLanguage: () -> Unit) {
    val store = app.store
    val playlists by store.playlists.collectAsState()
    val active by store.activeId.collectAsState()
    val lang by store.lang.collectAsState()
    val refresh by store.refreshHours.collectAsState()
    val buffer by store.buffer.collectAsState()
    val resume by store.resumeLast.collectAsState()
    val hasPin by store.hasPin.collectAsState()
    var pin by remember { mutableStateOf<PinRequest?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionLabel(stringResource(R.string.set_playlists)) }
        for (p in playlists) item(key = p.id) {
            Tv({ store.setActive(p.id); app.catalog.open(p.id) }, Modifier.fillMaxWidth().height(72.dp), selected = p.id == active) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = T.bodyBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(if (p.isXtream) R.string.kind_xtream else R.string.kind_m3u), style = T.tiny)
                    }
                    if (p.id == active) Icon(Glyph.Check, C.accent2, 28)
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
            Text(value, style = T.bodyBold.copy(color = C.accent2))
        }
    }
}

@Composable
fun ConfirmDialog(text: String, onYes: () -> Unit, onNo: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onNo)
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background2(C.scrim), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).background2(C.surface, RoundedCornerShape(28.dp)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
