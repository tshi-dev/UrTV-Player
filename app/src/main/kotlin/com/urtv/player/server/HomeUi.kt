package com.urtv.player.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.urtv.player.R
import com.urtv.player.ui.Spinner
import com.urtv.player.ui.T
import com.urtv.player.ui.TvButton
import com.urtv.player.ui.SectionLabel

data class HomeData(val libs: List<Library>, val resume: List<MediaItem>, val next: List<MediaItem>, val latest: List<Pair<Library, List<MediaItem>>>)

/** Infuse-style home: a backdrop hero for the focused title, then Continue watching, Next up and Recently added per library. */
@Composable
fun ServerHome(backend: MediaBackend, onOpen: (MediaItem) -> Unit, onPlay: (MediaItem) -> Unit) {
    var reloadKey by remember { mutableStateOf(0) }
    val res by load(Pair(backend.config.id, reloadKey)) {
        val libs = backend.libraries()
        // Each row is optional: a failing row must not blank the whole page.
        val resume = runCatching { backend.continueWatching() }.getOrDefault(emptyList())
        val next = runCatching { backend.nextUp() }.getOrDefault(emptyList()).filter { n -> resume.none { it.id == n.id } }
        val latest = libs.map { it to runCatching { backend.latest(it) }.getOrDefault(emptyList()) }.filter { it.second.isNotEmpty() }
        HomeData(libs, resume, next, latest)
    }
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    when (val r = res) {
        is Res.Loading -> Centered { Spinner() }
        is Res.Fail -> Centered { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(errorText(r.e), style = T.title); TvButton(stringResource(R.string.retry), { reloadKey++ }, primary = true) } }
        is Res.Ok -> {
            val d = r.v
            if (d.resume.isEmpty() && d.next.isEmpty() && d.latest.isEmpty()) Centered { Text(stringResource(R.string.srv_empty), style = T.title) }
            else Column(Modifier.fillMaxSize()) {
                val shown = focused ?: d.resume.firstOrNull() ?: d.next.firstOrNull() ?: d.latest.firstOrNull()?.second?.firstOrNull()
                androidx.compose.runtime.LaunchedEffect(shown?.id) { ServerBackdrop.item = shown; ServerBackdrop.backend = backend }
                Hero(shown, 360.dp)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (d.resume.isNotEmpty()) item(key = "resume") { ItemRow(stringResource(R.string.srv_continue), d.resume, backend, wide = true, onFocus = { focused = it }, onClick = onPlay, onLong = onOpen) }
                    if (d.next.isNotEmpty()) item(key = "next") { ItemRow(stringResource(R.string.srv_next_up), d.next, backend, wide = true, onFocus = { focused = it }, onClick = onPlay, onLong = onOpen) }
                    for ((lib, list) in d.latest) item(key = "latest-" + lib.id) { ItemRow(stringResource(R.string.srv_recent_in, lib.name), list, backend, wide = false, onFocus = { focused = it }, onClick = onOpen, onLong = null) }
                }
            }
        }
    }
}

@Composable
fun ItemRow(title: String, items: List<MediaItem>, backend: MediaBackend, wide: Boolean, onFocus: (MediaItem) -> Unit, onClick: (MediaItem) -> Unit, onLong: ((MediaItem) -> Unit)?) {
    Column {
        SectionLabel(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(items, key = { it.id }) { it ->
                if (wide) WideCard(it, backend, { onClick(it) }, onFocus = { f -> if (f) onFocus(it) }, onLongClick = onLong?.let { l -> { l(it) } })
                else PosterCard(it, backend, { onClick(it) }, onFocus = { f -> if (f) onFocus(it) }, onLongClick = onLong?.let { l -> { l(it) } })
            }
        }
    }
}
