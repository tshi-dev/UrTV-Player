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
import androidx.tv.material3.Text
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.Channel
import com.urtv.player.core.Programme
import com.urtv.player.data.CatalogState

private val DP_PER_MIN = 4.5.dp
private val ROW_H = 72.dp
private val NAME_W = 230.dp
private const val SPAN_MIN = 24 * 60
private const val SLOT_MS = 30 * 60_000L

/**
 * EPG grid. Every row shares one horizontal ScrollState, so focus moving along a row (or between rows)
 * scrolls the whole timeline smoothly while channel names stay pinned.
 */
@Composable
fun GuideScreen(app: UrtvApp, cat: CatalogState, onPlay: (List<Channel>, Channel) -> Unit) {
    val epg by app.catalog.epg.collectAsState()
    val nowMs by rememberNow()
    val rows = remember(cat.channels, epg) { cat.channels.filter { epg.forChannel(it.epgId).isNotEmpty() } }
    var detail by remember { mutableStateOf<Programme?>(null) }
    val scroll = rememberScrollState()
    val start = remember(nowMs / SLOT_MS) { (nowMs / SLOT_MS) * SLOT_MS - SLOT_MS }
    val nowOffset: Dp = DP_PER_MIN * ((nowMs - start) / 60_000f)

    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (cat.epgLoading) { Spinner(); Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.loading), style = T.body.copy(color = C.muted)) }
            else { Text(stringResource(R.string.no_guide), style = T.title); Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.no_guide_hint), style = T.body.copy(color = C.muted)) }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 12.dp)) {
            val d = detail
            Text(d?.title ?: " ", style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d != null) {
                Text("${fmtTime(d.startMs)} – ${fmtTime(d.stopMs)}", style = T.small)
                if (d.description.isNotBlank()) Text(d.description, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // time header
        Row(Modifier.fillMaxWidth().height(36.dp)) {
            Spacer(Modifier.width(NAME_W))
            Box(Modifier.weight(1f).horizontalScroll(scroll, enabled = false)) {
                Box(Modifier.width(DP_PER_MIN * SPAN_MIN)) {
                    for (i in 0 until SPAN_MIN / 30) Text(fmtTime(start + i * SLOT_MS), style = T.tiny, modifier = Modifier.offset(x = DP_PER_MIN * (i * 30f) + 8.dp))
                }
            }
        }
        val listState = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = PaddingValues(vertical = 6.dp)) {
            items(rows, key = { it.url }) { ch ->
                Row(Modifier.fillMaxWidth().height(ROW_H).padding(vertical = 3.dp)) {
                    Row(Modifier.width(NAME_W).fillMaxSize().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Logo(ch, 40); Spacer(Modifier.width(10.dp))
                        Text(ch.name, style = T.small.copy(color = C.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                        GuideRow(ch, epg.forChannel(ch.epgId), start, nowOffset, nowMs, onFocus = { detail = it }) { onPlay(rows, ch) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideRow(ch: Channel, progs: List<Programme>, start: Long, nowOffset: Dp, nowMs: Long, onFocus: (Programme) -> Unit, onClick: () -> Unit) {
    val end = start + SPAN_MIN * 60_000L
    Box(Modifier.width(DP_PER_MIN * SPAN_MIN).fillMaxSize().drawBehind {
        val x = nowOffset.toPx(); drawLine(C.accent2.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, size.height), 3f)
    }) {
        for (p in progs) {
            if (p.stopMs <= start || p.startMs >= end) continue
            val s = maxOf(p.startMs, start); val e = minOf(p.stopMs, end)
            val x = DP_PER_MIN * ((s - start) / 60_000f); val w = DP_PER_MIN * ((e - s) / 60_000f) - 4.dp
            if (w < 12.dp) continue
            Tv(onClick, Modifier.offset(x = x).width(w).fillMaxSize(), RoundedCornerShape(12.dp), selected = nowMs in p.startMs until p.stopMs, onFocus = { if (it) onFocus(p) }) {
                Text(p.title, style = T.small.copy(color = C.text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
            }
        }
    }
}
