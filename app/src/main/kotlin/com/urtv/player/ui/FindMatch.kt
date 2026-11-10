package com.urtv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.Channel
import com.urtv.player.core.MatchFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private sealed interface FmState {
    object Finding : FmState
    data class Pick(val hits: List<MatchFinder.Hit>, val remembered: String?, val pids: Map<String, String>) : FmState
    data class Play(val channel: Channel, val pid: String = "") : FmState
    object None : FmState
}

/** One hit plays at once; several show a focused list (best first); none shows a short message with a way to search. */
@Composable
fun FindMatchRoot(app: UrtvApp, req: MatchFinder.Request, onPlayed: () -> Unit, onSearch: (String) -> Unit, onClose: () -> Unit) {
    val store = app.store
    val cat by app.catalog.state.collectAsState()
    val epg by app.catalog.epg.collectAsState()
    var state by remember { mutableStateOf<FmState>(FmState.Finding) }

    LaunchedEffect(Unit) {
        store.ready.first { it }
        if (app.catalog.state.value.playlistId == null) app.catalog.open(store.activeId.value ?: store.playlists.value.firstOrNull()?.id)
    }
    // wait for the channel list (cache first), then give the guide a short moment to load; never wait forever
    LaunchedEffect(cat.settled, cat.channels.isNotEmpty(), cat.epgLoading) {
        if (state !is FmState.Finding || !cat.settled) return@LaunchedEffect
        if (cat.channels.isNotEmpty() && cat.epgLoading) delay(6000)
        else if (cat.channels.any { it.epgId != null } && app.catalog.epg.value.isEmpty) delay(1500)   // the guide starts loading just after the list
        val active = cat.playlistId.orEmpty()
        val recents = store.recents.value
        val lists = store.playlists.value.map { it.id }
        val activeChannels = cat.channels
        val guide = app.catalog.epg.value
        // every playlist's channel names (from the cache); the guide only exists for the active one
        val found = withContext(Dispatchers.Default) {
            lists.flatMap { id ->
                val chs = if (id == active) activeChannels else app.catalog.cachedChannels(id)
                MatchFinder.find(req, chs, recentRank = { c -> recents.indexOf(chKey(id, c)).let { if (it < 0) Int.MAX_VALUE else it } }) { e -> if (id == active) guide.forChannel(e) else emptyList() }
                    .map { id to it }
            }.distinctBy { it.second.channel.url }
                .sortedWith(compareByDescending<Pair<String, MatchFinder.Hit>> { it.second.score }.thenByDescending { it.second.quality }.thenBy { if (it.first == active) 0 else 1 })
        }
        val hits = found.map { it.second }
        val pids = found.associate { it.second.channel.url to it.first }
        val remembered = store.matchPick(req.competitionKey)
        state = when {
            hits.isEmpty() -> FmState.None
            hits.size == 1 -> FmState.Play(hits[0].channel, pids[hits[0].channel.url].orEmpty())
            else -> {
                val front = hits.firstOrNull { chKey(pids[it.channel.url].orEmpty(), it.channel) == remembered }
                FmState.Pick(if (front != null) listOf(front) + hits.filter { it !== front } else hits, remembered, pids)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        when (val s = state) {
            FmState.Finding -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { Spinner(); Text(stringResource(R.string.fm_finding), Modifier.padding(top = 24.dp), style = T.body) }
            FmState.None -> {
                BackHandler { onClose() }
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                Column(Modifier.fillMaxSize().padding(80.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.fm_none), style = T.title, textAlign = TextAlign.Center)
                    Row(Modifier.padding(top = 40.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        // the channel search opens with the first channel name the caller listed (most likely to match), else the home team
                        val ar = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar"
                        val seed = req.channels.firstOrNull() ?: (if (ar) req.homeAr.ifBlank { req.homeEn } else req.homeEn.ifBlank { req.homeAr })
                        TvButton(stringResource(R.string.fm_search), { onSearch(seed) }, primary = true, focusRequester = first)
                        TvButton(stringResource(R.string.fm_back), onClose)
                    }
                }
            }
            is FmState.Pick -> {
                BackHandler { onClose() }
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                Column(Modifier.fillMaxSize().padding(horizontal = 160.dp, vertical = 70.dp)) {
                    Text(stringResource(R.string.fm_title), style = T.title)
                    if (req.homeEn.isNotBlank() || req.homeAr.isNotBlank()) {
                        val ar = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar"
                        val h = if (ar) req.homeAr.ifBlank { req.homeEn } else req.homeEn.ifBlank { req.homeAr }
                        val a = if (ar) req.awayAr.ifBlank { req.awayEn } else req.awayEn.ifBlank { req.awayAr }
                        Text("$h – $a", Modifier.padding(top = 6.dp), style = T.small)
                    }
                    LazyColumn(Modifier.padding(top = 28.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(s.hits) { i, h ->
                            val hp = s.pids[h.channel.url].orEmpty(); val key = chKey(hp, h.channel)
                            Tv(onClick = {
                                store.setMatchPick(req.competitionKey, key)
                                state = FmState.Play(h.channel, hp)
                            }, Modifier.fillMaxWidth().height(84.dp).let { if (i == 0) it.focusRequester(first) else it }, shape = Rd(14), restColor = C.surface) {
                                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Logo(h.channel, 52)
                                    Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                                        Text(h.channel.name, style = T.row, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val why = when (h.why) { MatchFinder.Why.Guide -> R.string.fm_why_guide; MatchFinder.Why.Named -> R.string.fm_why_named; MatchFinder.Why.Group -> R.string.fm_why_group }
                                        val extra = if (key == s.remembered) " · " + stringResource(R.string.fm_last_pick) else ""
                                        Text(stringResource(why) + extra, style = T.small, maxLines = 1)
                                    }
                                    val q = when (h.quality) { 4 -> "4K"; 3 -> "FHD"; 2 -> "HD"; 0 -> "SD"; else -> "" }
                                    if (q.isNotEmpty()) Text(q, style = T.bodyBold)
                                }
                            }
                        }
                    }
                }
            }
            is FmState.Play -> {
                LaunchedEffect(Unit) { onPlayed(); if (s.pid.isNotEmpty() && s.pid != app.catalog.state.value.playlistId) app.catalog.open(s.pid) }
                PlayerScreen(app, listOf(s.channel), s.channel, onExit = { onClose() })
            }
        }
    }
}
