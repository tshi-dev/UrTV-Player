package com.urtv.player.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.Channel
import com.urtv.player.core.Programme
import com.urtv.player.data.CatalogState

private val DP_PER_MIN = 8.dp
private val ROW_H = 84.dp
private val NAME_W = 300.dp
private const val SPAN_MIN = 24 * 60
private const val SLOT_MS = 30 * 60_000L

/**
 * EPG grid. Every row shares one horizontal ScrollState, so focus moving along a row (or between rows)
 * scrolls the whole timeline smoothly while channel names stay pinned.
 */
@Composable
fun GuideScreen(app: UrtvApp, cat: CatalogState, onPlay: (List<Channel>, Channel) -> Unit, onCatchup: (Channel, Programme) -> Unit) {
    val epg by app.catalog.epg.collectAsState()
    val nowMs by rememberNow()
    val reminders by app.store.reminders.collectAsState()
    val reminded = remember(reminders) { reminders.map { it.key }.toSet() }
    val rows = remember(cat.channels, epg) { cat.channels.filter { epg.forChannel(it.epgId).isNotEmpty() } }
    var detail by remember { mutableStateOf<Programme?>(null) }
    val scroll = rememberScrollState()
    val start = remember(nowMs / SLOT_MS) { (nowMs / SLOT_MS) * SLOT_MS - SLOT_MS }
    val nowOffset: Dp = DP_PER_MIN * ((nowMs - start) / 60_000f)

    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (cat.epgLoading || !cat.settled) SkeletonRows(8, 84)
            else { Text(stringResource(R.string.no_guide), style = T.title); Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.no_guide_hint), style = T.body.copy(color = C.muted)) }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 8.dp)) {
            val d = detail
            Text(d?.title ?: " ", style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d != null) {
                Text("${fmtTime(d.startMs)} – ${fmtTime(d.stopMs)}", style = T.small)
                if (d.startMs > nowMs) Text(stringResource(R.string.reminder_hint), style = T.tiny)
                if (d.description.isNotBlank()) Text(d.description, style = T.tiny.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // time header
        Row(Modifier.fillMaxWidth().height(40.dp)) {
            Spacer(Modifier.width(NAME_W))
            Box(Modifier.weight(1f).horizontalScroll(scroll, enabled = false)) {
                Box(Modifier.width(DP_PER_MIN * SPAN_MIN)) {
                    for (i in 0 until SPAN_MIN / 30) Text(fmtTime(start + i * SLOT_MS), style = T.tiny, modifier = Modifier.offset(x = DP_PER_MIN * (i * 30f) + 12.dp))
                }
            }
        }
        val listState = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = PaddingValues(vertical = 8.dp)) {
            items(rows, key = { it.url }) { ch ->
                Row(Modifier.fillMaxWidth().height(ROW_H).drawBehind { drawLine(C.hairline, Offset(0f, 0f), Offset(size.width, 0f), 1f) }.padding(vertical = 4.dp)) {
                    Row(Modifier.width(NAME_W).fillMaxSize().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${com.urtv.player.data.Browse.numberOf(cat.channels, ch)}", style = T.tiny, modifier = Modifier.width(48.dp))
                        Logo(ch, 38); Spacer(Modifier.width(12.dp))
                        Text(ch.name, style = T.small.copy(color = C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                        GuideRow(ch, epg.forChannel(ch.epgId), start, nowOffset, nowMs, reminded, onLong = { p -> app.store.toggleReminder(com.urtv.player.data.Store.Reminder("${ch.url}|${p.startMs}", p.startMs, p.title, ch.name)) }, onFocus = { detail = it }) { p ->
                            if (p.stopMs < nowMs && ch.archiveDays > 0 && ch.streamId != null) onCatchup(ch, p) else onPlay(rows, ch)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideRow(ch: Channel, progs: List<Programme>, start: Long, nowOffset: Dp, nowMs: Long, reminded: Set<String>, onLong: (Programme) -> Unit, onFocus: (Programme) -> Unit, onClick: (Programme) -> Unit) {
    val end = start + SPAN_MIN * 60_000L
    Box(Modifier.width(DP_PER_MIN * SPAN_MIN).fillMaxSize().drawBehind {
        val raw = nowOffset.toPx(); val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - raw else raw
        drawLine(C.accent, Offset(x, 0f), Offset(x, size.height), 2f)
    }) {
        for (p in progs) {
            if (p.stopMs <= start || p.startMs >= end) continue
            val s = maxOf(p.startMs, start); val e = minOf(p.stopMs, end)
            val x = DP_PER_MIN * ((s - start) / 60_000f); val w = DP_PER_MIN * ((e - s) / 60_000f) - 4.dp
            if (w < 12.dp) continue
            val catchup = p.stopMs < nowMs && ch.archiveDays > 0
            Tv({ onClick(p) }, Modifier.offset(x = x).width(w).fillMaxSize().drawBehind { drawLine(C.hairline, Offset(0f, 8f), Offset(0f, size.height - 8f), 1f) }, Rd(10), lift = 1.02f, onFocus = { if (it) onFocus(p) }, onLongClick = if (p.startMs > nowMs) { { onLong(p) } } else null) {
                Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp)) {
                    Text(p.title, style = T.small.copy(color = C.text, fontSize = 19.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtTime(p.startMs), style = T.tiny.copy(color = if (catchup) C.accent else C.faint, fontSize = 15.sp))
                        if ("${ch.url}|${p.startMs}" in reminded) { Spacer(Modifier.width(6.dp)); Icon(Glyph.Bell, C.accent, 16) }
                    }
                }
            }
        }
    }
}
