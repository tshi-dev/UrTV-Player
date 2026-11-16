package com.urtv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.Channel
import com.urtv.player.core.Commentary
import com.urtv.player.data.Browse

/**
 * «دمج التعليق»: a bottom card opened from one row of the OK panel. Source and delay changes are heard at once while it is open;
 * «تطبيق» keeps them (the delay is remembered for this picture + sound pairing) and closes; «إلغاء» and Back put back the source and
 * delay that were active when the card was opened, then close. Small TVs are asked «try it?» first.
 */
@Composable
internal fun CommentaryPanel(app: UrtvApp, all: List<Channel>, current: Channel, warn: Boolean = false, onClose: () -> Unit) {
    val cm = app.commentary
    val st by cm.state.collectAsState()
    val recent by cm.recent.collectAsState()
    val opened = remember { cm.state.value }                                   // what to go back to on «cancel»
    fun cancel() { cm.cancelTo(opened); onClose() }
    fun apply() { cm.applyCurrent(); onClose() }
    var picking by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(!warn) }                            // small TVs: «try it?» comes first, the focus on «cancel»
    var pickGroup by remember { mutableStateOf<String?>(null) }                // the folder (a group of the subscription) opened in the picker
    BackHandler { if (asked) cancel() else onClose() }
    // the zoom is only for syncing: it goes back to normal when the card closes
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { cm.resetZoom() } }
    BackHandler(picking) { if (pickGroup != null) pickGroup = null else picking = false }     // registered later: wins while picking
    val first = remember { FocusRequester() }
    LaunchedEffect(asked, picking, st.audio?.url, st.failure) { repeat(10) { if (runCatching { first.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect; kotlinx.coroutines.delay(30) } }
    val card = Modifier.fillMaxWidth().padding(horizontal = 56.dp).padding(bottom = 48.dp).background(C.surface, Rd(22)).padding(horizontal = 36.dp, vertical = 30.dp)
    when {
        !asked -> Column(card, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.cm_title), style = T.title.copy(fontSize = 32.sp))
            Text(stringResource(R.string.cm_small_msg), style = T.body)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tv({ asked = true }, Modifier.weight(1f).height(72.dp), Rd(14), restColor = C.surfaceHi) { Text(stringResource(R.string.cm_try), style = T.bodyBold, modifier = Modifier.align(Alignment.Center)) }
                Tv(onClose, Modifier.weight(1f).height(72.dp), Rd(14), focusRequester = first, restColor = C.surfaceHi) { Text(stringResource(R.string.cm_cancel), style = T.bodyBold, modifier = Modifier.align(Alignment.Center)) }
            }
        }
        picking -> Column(card.fillMaxHeight(0.8f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.cm_title), style = T.title.copy(fontSize = 32.sp))
            Picker(all, current, st.audio?.url, first, app.catalog.state.collectAsState().value.index, pickGroup, { pickGroup = it }, onPick = { cm.start(it); picking = false; pickGroup = null })
        }
        else -> Row(card, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.cm_title), style = T.title.copy(fontSize = 32.sp))
                val f = st.failure
                if (f != null) Column(Modifier.fillMaxWidth().background(Color(0xFF2A1D1F), Rd(14)).padding(22.dp)) {
                    Text(stringResource(when (f) { Commentary.Failure.OneConnection -> R.string.cm_fail_one; Commentary.Failure.NotSeekable -> R.string.cm_fail_seek; Commentary.Failure.Unreachable -> R.string.cm_fail_unreach }), style = T.bodyBold)
                    Text(stringResource(R.string.cm_fail_kept), style = T.small, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cm_source), style = T.small)
                    Text(stringResource(R.string.cm_video, current.name), style = T.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourceCard(stringResource(R.string.cm_original), current.name, st.audio == null, Modifier.weight(1f), if (st.audio == null) first else null) { if (st.audio != null) cm.original() else cm.clearFailure() }
                    for (c in recent.take(2)) SourceCard(c.name, stringResource(if (st.audio?.url == c.url && !st.ready) R.string.cm_loading else R.string.cm_audio_from_short) + "  ·  " + stringResource(R.string.cm_hold_remove), st.audio?.url == c.url, Modifier.weight(1f), onLong = { cm.forget(c) }, focus = if (st.audio?.url == c.url) first else null) { if (st.audio?.url != c.url) cm.start(c) }
                    SourceCard("+ " + stringResource(R.string.cm_choose), stringResource(R.string.cm_pick_sub), false, Modifier.weight(1f), null) { picking = true }
                }
                if (st.audio != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text(stringResource(R.string.cm_delay), style = T.small)
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(Commentary.label(st.delayMs) + " " + stringResource(R.string.cm_sec), style = T.title.copy(fontSize = 40.sp))
                        }
                    }
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (step in Commentary.STEPS) Tv({ cm.press(step) }, Modifier.weight(1f).height(72.dp), Rd(12), restColor = C.surfaceHi) {
                                Text(if (step == 0L) "0" else Commentary.label(step), style = T.bodyBold, maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.cm_earlier), style = T.small)      // the − side: the sound comes earlier
                            Text(stringResource(R.string.cm_later), style = T.small)        // the + side: the sound comes later
                        }
                    }
                } else if (f == null) Text(stringResource(R.string.cm_sub), style = T.small)
            }
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(190.dp).background(C.surfaceHi, Rd(14)), contentAlignment = Alignment.Center) {
                    Logo(st.audio ?: current, 96)
                    if (st.audio != null) Text(stringResource(R.string.cm_preview_tag), style = T.tiny.copy(color = Color.White), modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color(0x99000000), Rd(8)).padding(horizontal = 10.dp, vertical = 3.dp))
                    Text((st.audio ?: current).name, style = T.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
                // reading the score and the match clock: the picture above can be enlarged from a corner of the screen
                val zoom by cm.zoom.collectAsState()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(stringResource(R.string.cm_zoom_n, zoom.level), { cm.cycleZoom() }, Modifier.weight(1f))
                    if (zoom.level > 1) TvButton(stringResource(listOf(R.string.pos_tl, R.string.pos_tc, R.string.pos_tr, R.string.pos_bl, R.string.pos_bc, R.string.pos_br)[zoom.pos]), { cm.cycleZoomPos() }, Modifier.weight(1f))
                }
                if (st.audio != null) TvButton(stringResource(R.string.cm_remove), { cm.original() }, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(stringResource(R.string.cm_apply), { apply() }, Modifier.weight(1f), primary = true)
                    TvButton(stringResource(R.string.cm_cancel), { cancel() }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SourceCard(title: String, sub: String, selected: Boolean, modifier: Modifier, focus: FocusRequester?, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Tv(onClick, modifier.height(84.dp), Rd(14), selected = selected, focusRequester = focus, onLongClick = onLong, restColor = C.surfaceHi) {
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) { Text("✓", style = T.bodyBold.copy(color = C.accent)); Spacer(Modifier.width(8.dp)) }
                Text(title, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(sub, style = T.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The person's own list with a search box: the typed text filters the names (Arabic and Latin, layout slips included). */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Picker(all: List<Channel>, current: Channel, chosenUrl: String?, first: FocusRequester, index: com.urtv.player.data.ChannelIndex, group: String?, onGroup: (String?) -> Unit, onPick: (Channel) -> Unit) {
    var q by remember { mutableStateOf("") }
    val fast = index.channels === all
    // a folder first (the groups of the subscription, in its order), then the channels inside it; typing searches the folder, or everything when none is open
    val folders = remember(index) { if (fast) index.groups.map { it to (index.byGroup[it]?.size ?: 0) } else emptyList() }
    val inFolder = remember(all, group, index) { if (group == null) all else if (fast) index.byGroup[group].orEmpty() else Browse.filter(all, group, "") }
    val hits = remember(inFolder, q, all, index) { (if (q.isBlank()) inFolder else if (fast && group == null) index.search(q) else Browse.filter(inFolder, Browse.ALL, q)).filter { it.url != current.url } }
    val showFolders = group == null && q.isBlank() && folders.isNotEmpty()
    val keys = remember(hits) { uniqueKeys(hits.map { it.url }) }
    if (group != null) Text(if (group == Browse.OTHER) stringResource(R.string.group_other) else group, style = T.bodyBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    FocusField(q, { q = it }, stringResource(R.string.cm_search), Modifier.fillMaxWidth(), focusRequester = first)
    Text(stringResource(if (showFolders) R.string.cm_pick_folder_hint else R.string.cm_pick_hint), style = T.tiny)
    // opening a folder removes the row that had the focus: the first channel of the folder takes it (else the search box)
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(group, showFolders) { kotlinx.coroutines.delay(80); if (!runCatching { firstRow.requestFocus(); true }.getOrDefault(false)) runCatching { first.requestFocus() } }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showFolders) {
            itemsIndexed(folders, key = { _, f -> "g|" + f.first }) { i, f ->
                Tv({ onGroup(f.first) }, Modifier.fillMaxWidth().height(64.dp), Rd(12), focusRequester = if (i == 0) firstRow else null, restColor = C.surfaceHi, flat = true) {
                    Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (f.first == Browse.OTHER) stringResource(R.string.group_other) else f.first, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${f.second}", style = T.tiny)
                    }
                }
            }
        } else itemsIndexed(hits, key = { i, _ -> keys[i] }) { i, ch ->
            val picked = ch.url == chosenUrl
            Tv({ onPick(ch) }, Modifier.fillMaxWidth().height(64.dp), Rd(12), selected = picked, focusRequester = if (i == 0) firstRow else null, restColor = C.surfaceHi, flat = true) {
                Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (picked) "✓" else stringResource(R.string.cm_pick_ok), style = if (picked) T.bodyBold.copy(color = C.accent) else T.tiny)      // OK picks it; the mark shows what is chosen now
                }
            }
        }
    }
}
