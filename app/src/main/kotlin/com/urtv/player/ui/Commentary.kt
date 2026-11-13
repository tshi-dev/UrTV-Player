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
 * «دمج التعليق» panel, opened from one row of the OK panel. Three states: the original sound (and the way to pick a second channel),
 * the second channel with the live delay row, and the honest failure. Delay presses take effect at once; there is no «apply».
 */
@Composable
internal fun CommentaryPanel(app: UrtvApp, all: List<Channel>, current: Channel, warn: Boolean = false, onClose: () -> Unit) {
    val cm = app.commentary
    val st by cm.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(!warn) }       // small TVs: «try it?» comes first, the focus on «cancel»
    BackHandler(picking) { picking = false }
    val first = remember { FocusRequester() }
    LaunchedEffect(asked, picking, st.audio?.url, st.failure) { repeat(10) { if (runCatching { first.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect; kotlinx.coroutines.delay(30) } }
    Column(Modifier.fillMaxHeight().width(700.dp).padding(vertical = 56.dp).background(C.surface, Rd(22)).padding(40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.cm_title), style = T.title.copy(fontSize = 34.sp))
        if (!asked) {
            Text(stringResource(R.string.cm_small_msg), style = T.body, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tv({ asked = true }, Modifier.weight(1f).height(72.dp), Rd(14), restColor = C.surfaceHi) { Text(stringResource(R.string.cm_try), style = T.bodyBold, modifier = Modifier.align(Alignment.Center)) }
                Tv(onClose, Modifier.weight(1f).height(72.dp), Rd(14), focusRequester = first, restColor = C.surfaceHi) { Text(stringResource(R.string.cm_cancel), style = T.bodyBold, modifier = Modifier.align(Alignment.Center)) }
            }
            return@Column
        }
        if (picking) { Picker(all, current, first, onPick = { cm.start(it); picking = false }) ; return@Column }
        val f = st.failure
        if (f != null) {
            Column(Modifier.fillMaxWidth().background(Color(0xFF2A1D1F), Rd(14)).padding(26.dp)) {
                Text(stringResource(when (f) { Commentary.Failure.OneConnection -> R.string.cm_fail_one; Commentary.Failure.NotSeekable -> R.string.cm_fail_seek; Commentary.Failure.Unreachable -> R.string.cm_fail_unreach }), style = T.bodyBold)
                Text(stringResource(R.string.cm_fail_kept), style = T.small, modifier = Modifier.padding(top = 10.dp))
            }
            OriginalRow(current, selected = true, first) { cm.clearFailure() }
        } else if (st.audio == null) {
            Text(stringResource(R.string.cm_sub), style = T.small)
            OriginalRow(current, selected = true, first) { }
            Tv({ picking = true }, Modifier.fillMaxWidth().height(92.dp), Rd(14), restColor = C.surfaceHi) {
                Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 26.dp)) {
                    Text(stringResource(R.string.cm_pick), style = T.body); Text(stringResource(R.string.cm_pick_sub), style = T.small)
                }
            }
        } else {
            val a = st.audio!!
            Text(stringResource(R.string.cm_video, current.name), style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Tv({ picking = true }, Modifier.fillMaxWidth().height(92.dp), Rd(14), restColor = C.surfaceHi) {
                Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 26.dp)) {
                    Text(a.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (st.ready) R.string.cm_audio_from else R.string.cm_loading), style = T.small)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.cm_delay), style = T.small)
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(Commentary.label(st.delayMs) + " " + stringResource(R.string.cm_sec), style = T.title.copy(fontSize = 44.sp))
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (step in Commentary.STEPS) {
                        Tv({ cm.press(step) }, Modifier.weight(1f).height(80.dp), Rd(12), restColor = C.surfaceHi, focusRequester = if (step == 0L) first else null) {
                            Text(if (step == 0L) "0" else Commentary.label(step), style = T.bodyBold, maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.cm_earlier), style = T.small)      // the − side: the sound comes earlier
                    Text(stringResource(R.string.cm_later), style = T.small)        // the + side: the sound comes later
                }
            }
            Tv({ cm.original() }, Modifier.fillMaxWidth().height(80.dp), Rd(14), restColor = C.surfaceHi) {
                Text(stringResource(R.string.cm_back_orig), style = T.body, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 26.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        TvButton(stringResource(R.string.cm_close), onClose, Modifier.fillMaxWidth())
    }
}

@Composable
private fun OriginalRow(current: Channel, selected: Boolean, focus: FocusRequester, onClick: () -> Unit) {
    Tv(onClick, Modifier.fillMaxWidth().height(92.dp), Rd(14), selected = selected, focusRequester = focus, restColor = C.surfaceHi) {
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 26.dp)) {
            Text(stringResource(R.string.cm_original), style = T.body)
            Text(stringResource(R.string.cm_original_sub, current.name), style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The person's own list with a search box: the typed text filters the names (Arabic and Latin, layout slips included). */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Picker(all: List<Channel>, current: Channel, first: FocusRequester, onPick: (Channel) -> Unit) {
    var q by remember { mutableStateOf("") }
    val hits = remember(all, q) { Browse.filter(all, Browse.ALL, q).filter { it.url != current.url }.take(80) }
    val keys = remember(hits) { uniqueKeys(hits.map { it.url }) }
    FocusField(q, { q = it }, stringResource(R.string.cm_search), Modifier.fillMaxWidth(), focusRequester = first)
    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(hits, key = { i, _ -> keys[i] }) { _, ch ->
            Tv({ onPick(ch) }, Modifier.fillMaxWidth().height(64.dp), Rd(12), restColor = C.surfaceHi, flat = true) {
                Text(ch.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 22.dp))
            }
        }
    }
}
