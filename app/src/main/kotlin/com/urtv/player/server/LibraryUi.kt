package com.urtv.player.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.urtv.player.R
import com.urtv.player.ui.C
import com.urtv.player.ui.FocusField
import com.urtv.player.ui.Rd
import com.urtv.player.ui.Spinner
import com.urtv.player.ui.T
import com.urtv.player.ui.Tv
import com.urtv.player.ui.TvButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Name A-Z, newest added, newest year, best rated. */
private val SORTS = listOf(Sort.Name to false, Sort.Added to true, Sort.Year to true, Sort.Rating to true)

@Composable
private fun sortName(s: Sort) = stringResource(when (s) { Sort.Name -> R.string.srv_sort_name; Sort.Added -> R.string.srv_sort_added; Sort.Year -> R.string.srv_sort_year; Sort.Rating -> R.string.srv_sort_rating })

/** Poster grid of one library with sort, genre filter and unplayed filter. Pages are fetched as focus nears the end. */
@Composable
fun LibraryScreen(backend: MediaBackend, lib: Library, onOpen: (MediaItem) -> Unit) {
    var sortIx by remember(lib.id) { mutableStateOf(0) }
    val sort = SORTS[sortIx].first; val desc = SORTS[sortIx].second
    var genre by remember(lib.id) { mutableStateOf<String?>(null) }
    var unplayed by remember(lib.id) { mutableStateOf(false) }
    var pickGenre by remember { mutableStateOf(false) }
    val q = Query(sort = sort, descending = desc, genre = genre, unplayedOnly = unplayed)
    var items by remember(lib.id, q) { mutableStateOf(emptyList<MediaItem>()) }
    var total by remember(lib.id, q) { mutableStateOf(-1) }
    var want by remember(lib.id, q) { mutableStateOf(60) }
    var failed by remember(lib.id, q) { mutableStateOf(false) }
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    val genres by load(lib.id) { backend.genres(lib) }

    LaunchedEffect(lib.id, q, want) {
        if (total >= 0 && items.size >= minOf(want, total)) return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { backend.browse(lib, q.copy(start = items.size, limit = 60)) } }
        r.onSuccess { items = items + it.items; total = it.total; if (it.items.isEmpty()) total = items.size }.onFailure { failed = true; total = items.size }
    }

    Column(Modifier.fillMaxSize()) {
        Hero(focused, backend, 250.dp)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lib.name, style = T.title, modifier = Modifier.padding(end = 12.dp))
            TvButton(sortName(sort) + if (desc) " ↓" else " ↑", { sortIx = (sortIx + 1) % SORTS.size })
            TvButton(genre ?: stringResource(R.string.srv_genre), { pickGenre = true })
            TvButton(stringResource(R.string.srv_unplayed) + if (unplayed) " ✓" else "", { unplayed = !unplayed })
            if (total >= 0) Text("$total", style = T.small)
        }
        when {
            total < 0 -> Centered { Spinner() }
            items.isEmpty() -> Centered { Text(stringResource(if (failed) R.string.srv_unreachable else R.string.srv_empty), style = T.title) }
            else -> LazyVerticalGrid(GridCells.Adaptive(168.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                itemsIndexed(items, key = { _, it -> it.id }) { i, it ->
                    PosterCard(it, backend, { onOpen(it) }, onFocus = { f -> if (f) { focused = it; if (i >= items.size - 24 && items.size < total) want = items.size + 60 } })
                }
            }
        }
    }
    if (pickGenre) {
        val list = (genres as? Res.Ok)?.v ?: emptyList()
        Picker(stringResource(R.string.srv_genre), listOf<String?>(null) + list, { it ?: stringResource(R.string.srv_all) }, genre, { genre = it; pickGenre = false }, { pickGenre = false })
    }
}

/** Simple modal list; Back closes. */
@Composable
fun <T> Picker(title: String, options: List<T>, label: @Composable (T) -> String, current: T, onPick: (T) -> Unit, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().padding(0.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().androidxBackground(C.scrim))
        Column(Modifier.width(520.dp).androidxBackground(C.surface, Rd(20)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = T.title)
            LazyColumn(Modifier.height(480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options) { o ->
                    Tv({ onPick(o) }, Modifier.fillMaxWidth().height(56.dp), selected = o == current, focusRequester = if (o == current) fr else null) {
                        Text(label(o), style = T.body, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp), maxLines = 1)
                    }
                }
            }
        }
    }
}

internal fun Modifier.androidxBackground(c: androidx.compose.ui.graphics.Color, s: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape): Modifier =
    this.then(Modifier.background(c, s))

/** Search over movies, shows and collections of the active server. */
@Composable
fun SearchScreen(backend: MediaBackend, onOpen: (MediaItem) -> Unit) {
    var text by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Res<List<MediaItem>>>(Res.Ok(emptyList())) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(text) {
        if (text.trim().length < 2) { results = Res.Ok(emptyList()); return@LaunchedEffect }
        delay(450); results = Res.Loading
        results = withContext(Dispatchers.IO) { try { Res.Ok(backend.search(text.trim())) } catch (e: ServerException) { Res.Fail(e) } catch (e: Exception) { Res.Fail(ServerException(ServerException.Reason.Network, "")) } }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FocusField(text, { text = it }, stringResource(R.string.search_hint), Modifier.fillMaxWidth().padding(horizontal = 8.dp), focusRequester = fr, imeAction = ImeAction.Search)
        when (val r = results) {
            is Res.Loading -> Centered { Spinner() }
            is Res.Fail -> Centered { Text(errorText(r.e), style = T.title) }
            is Res.Ok -> if (r.v.isEmpty()) { if (text.trim().length >= 2) Centered { Text(stringResource(R.string.srv_empty), style = T.title) } }
                else LazyVerticalGrid(GridCells.Adaptive(168.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(r.v, key = { _, it -> it.id }) { _, it -> PosterCard(it, backend, { onOpen(it) }) }
                }
        }
    }
}
