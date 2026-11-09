package com.urtv.player.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
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
import com.urtv.player.ui.ConfirmDialog
import com.urtv.player.ui.Glyph
import com.urtv.player.ui.Icon
import com.urtv.player.ui.SectionLabel
import com.urtv.player.ui.T
import com.urtv.player.ui.Tv
import com.urtv.player.ui.TvButton
import com.urtv.player.ui.accentInk

@Composable
private fun SRow(label: String, value: String, hint: String? = null, onClick: () -> Unit) {
    Tv(onClick, Modifier.fillMaxWidth().height(if (hint != null) 84.dp else 68.dp)) {
        Row(Modifier.padding(horizontal = 20.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(label, style = T.body); if (hint != null) Text(hint, style = T.tiny) }
            Text(value, style = T.bodyBold.copy(color = accentInk()))
        }
    }
}

private val LANG_CHOICES = listOf(listOf("ar", "en"), listOf("en", "ar"), listOf("ar"), listOf("en"), listOf("en", "ar", "fr"))
private fun langText(l: List<String>) = l.joinToString(" › ") { java.util.Locale(it).getDisplayLanguage(java.util.Locale.getDefault()).ifBlank { it } }

/**
 * Home-server rows of Settings: the «Home server only» switch, servers, language and subtitle preferences.
 * [onConfirmRemove] is called with the server id to ask for confirmation.
 */
fun LazyListScope.serverSettings(app: UrtvApp, onAddServer: () -> Unit, onHomeOnlyChanged: (Boolean) -> Unit) {
    item { SectionLabel(stringResource(R.string.srv_settings_title)) }
    item { ServerRows(app, onAddServer, onHomeOnlyChanged) }
}

@Composable
private fun ServerRows(app: UrtvApp, onAddServer: () -> Unit, onHomeOnlyChanged: (Boolean) -> Unit) {
    val mgr = app.servers
    val servers by mgr.servers.collectAsState(); val activeId by mgr.activeId.collectAsState(); val homeOnly by mgr.homeOnly.collectAsState()
    val style by mgr.subtitleStyle.collectAsState(); val mode by mgr.subtitleMode.collectAsState(); val prefs by mgr.langPrefs.collectAsState()
    val startOnServer by mgr.startOnServer.collectAsState()
    var remove by remember { mutableStateOf<String?>(null) }
    Column(Modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SRow(stringResource(R.string.srv_home_only), stringResource(if (homeOnly) R.string.on else R.string.off), stringResource(R.string.srv_home_only_hint)) { mgr.setHomeOnly(!homeOnly); onHomeOnlyChanged(!homeOnly) }
        if (!homeOnly) SRow(stringResource(R.string.srv_start_on), stringResource(if (startOnServer) R.string.srv_start_server else R.string.srv_start_iptv)) { mgr.setStartOnServer(!startOnServer) }
        for (s in servers) Tv({ mgr.setActive(s.id) }, Modifier.fillMaxWidth().height(72.dp), selected = s.id == (activeId ?: servers.firstOrNull()?.id), onLongClick = { remove = s.id }) {
            Row(Modifier.padding(horizontal = 20.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = T.bodyBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.kind.name + "  " + s.baseUrl.substringAfter("://") + if (s.userName.isNotBlank()) "  " + s.userName else "", style = T.tiny, maxLines = 1)
                }
                if (s.id == (activeId ?: servers.firstOrNull()?.id)) Icon(Glyph.Check, accentInk(), 28)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(stringResource(R.string.srv_add_server), onAddServer, primary = servers.isEmpty())
            if (servers.isNotEmpty()) TvButton(stringResource(R.string.srv_remove), { remove = activeId ?: servers.firstOrNull()?.id })
        }
        SRow(stringResource(R.string.srv_pref_audio), langText(prefs.audio)) { mgr.setLangPrefs(prefs.copy(audio = LANG_CHOICES[(LANG_CHOICES.indexOf(prefs.audio).coerceAtLeast(-1) + 1) % LANG_CHOICES.size])) }
        SRow(stringResource(R.string.srv_pref_subs), langText(prefs.subtitle)) { mgr.setLangPrefs(prefs.copy(subtitle = LANG_CHOICES[(LANG_CHOICES.indexOf(prefs.subtitle).coerceAtLeast(-1) + 1) % LANG_CHOICES.size])) }
        SRow(stringResource(R.string.srv_sub_mode), stringResource(when (mode) { SubtitleMode.Auto -> R.string.srv_mode_auto; SubtitleMode.Always -> R.string.srv_mode_always; SubtitleMode.Off -> R.string.srv_off }), stringResource(R.string.srv_sub_mode_hint)) {
            mgr.setSubtitleMode(SubtitleMode.entries[(mode.ordinal + 1) % SubtitleMode.entries.size]) }
        Box(Modifier.fillMaxWidth().height(110.dp).padding(vertical = 4.dp)) { SubtitleOverlay(listOf(Cue(0, 1, stringResource(R.string.srv_sample))), style) }
        for (r in styleRows(style)) SRow(stringResource(r.label), if (r.valueRes != 0) stringResource(r.valueRes) else r.valueText) { mgr.setSubtitleStyle(r.next) }
    }
    remove?.let { id -> ConfirmDialog(stringResource(R.string.srv_remove_confirm), onYes = { mgr.remove(id); remove = null }, onNo = { remove = null }) }
}
