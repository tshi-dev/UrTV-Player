package com.urtv.player.data

import android.content.Context
import com.urtv.player.core.Channel
import com.urtv.player.core.M3uParser
import com.urtv.player.core.XmltvParser
import com.urtv.player.core.XtreamAccount
import com.urtv.player.core.XtreamParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CatalogState(
    val playlistId: String? = null,
    val channels: List<Channel> = emptyList(),
    val loading: Boolean = false,
    val error: LoadError? = null,
    val epgLoading: Boolean = false,
)

/** Loads the active playlist (cache first, then network) and its XMLTV guide. */
class Catalog(ctx: Context, private val store: Store, private val scope: CoroutineScope) {
    private val dir = File(ctx.filesDir, "cache").apply { mkdirs() }
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state
    private val _epg = MutableStateFlow(EpgIndex.EMPTY)
    val epg: StateFlow<EpgIndex> = _epg
    private var job: Job? = null

    fun open(id: String?, force: Boolean = false) {
        job?.cancel()
        val def = store.playlists.value.firstOrNull { it.id == id }
        if (def == null) { _state.value = CatalogState(); _epg.value = EpgIndex.EMPTY; return }
        val sameList = _state.value.playlistId == id
        _state.value = CatalogState(playlistId = id, channels = if (sameList) _state.value.channels else emptyList(), loading = true)
        job = scope.launch {
            val cacheFile = chFile(def.id)
            val cached = withContext(Dispatchers.IO) { readCache(cacheFile) }
            if (cached.isNotEmpty()) _state.value = _state.value.copy(channels = cached, loading = false)
            val maxAgeMs = store.refreshHours.value * 3_600_000L
            val stale = !cacheFile.exists() || cached.isEmpty() || (maxAgeMs > 0 && System.currentTimeMillis() - cacheFile.lastModified() > maxAgeMs)
            var epgUrls = emptyList<String>()
            if (force || stale) {
                _state.value = _state.value.copy(loading = true, error = null)
                val r = withContext(Dispatchers.IO) { runCatching { fetch(def) } }
                r.onSuccess { (list, urls) ->
                    epgUrls = urls
                    if (list.isEmpty()) _state.value = _state.value.copy(loading = false, error = if (cached.isEmpty()) LoadError.Empty else null)
                    else { withContext(Dispatchers.IO) { writeCache(cacheFile, list) }; _state.value = _state.value.copy(channels = list, loading = false, error = null) }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    _state.value = _state.value.copy(loading = false, error = if (cached.isEmpty()) LoadError.Network else null)
                }
            } else _state.value = _state.value.copy(loading = false)
            loadEpg(def, force || stale, epgUrls)
        }
    }

    fun refreshActive() = open(_state.value.playlistId, force = true)

    fun forget(id: String) { chFile(id).delete(); epgFile(id).delete(); File(dir, "epgurls_$id.txt").delete() }

    private fun chFile(id: String) = File(dir, "ch_$id.tsv")
    private fun epgFile(id: String) = File(dir, "epg_$id.xml")

    private fun fetch(def: PlaylistDef): Pair<List<Channel>, List<String>> {
        if (def.isXtream) {
            val acc = XtreamAccount(def.server, def.user, def.pass)
            val cats = XtreamParser.parseCategories(Http.text(acc.categoriesUrl()))
            val parsed = XtreamParser.parseStreams(Http.text(acc.streamsUrl()), acc, cats)
            return parsed.channels to parsed.epgUrls
        }
        val parsed = M3uParser.parse(Http.text(def.url))
        return parsed.channels to parsed.epgUrls
    }

    private suspend fun loadEpg(def: PlaylistDef, fetchNew: Boolean, urlsFromPlaylist: List<String>) {
        val file = epgFile(def.id)
        val urlFile = File(dir, "epgurls_${def.id}.txt")
        if (urlsFromPlaylist.isNotEmpty()) runCatching { urlFile.writeText(urlsFromPlaylist.joinToString("\n")) }
        val remembered = if (urlsFromPlaylist.isEmpty() && urlFile.exists()) runCatching { urlFile.readLines() }.getOrDefault(emptyList()) else urlsFromPlaylist
        val urls = (listOf(def.epgUrl).filter { it.isNotBlank() } + remembered).filter { it.isNotBlank() }.distinct()
        val keep = HashSet<String>()
        for (c in _state.value.channels) c.epgId?.let { keep += it; keep += it.lowercase() }
        if (keep.isEmpty()) { _epg.value = EpgIndex.EMPTY; return }
        _state.value = _state.value.copy(epgLoading = true)
        try {
            withContext(Dispatchers.IO) {
                val maxAge = (store.refreshHours.value.takeIf { it > 0 } ?: 24) * 3_600_000L
                if (urls.isNotEmpty() && (fetchNew && urls.isNotEmpty() || !file.exists() || System.currentTimeMillis() - file.lastModified() > maxAge)) {
                    val tmp = File(file.path + ".tmp")
                    val ok = urls.any { u ->
                        runCatching { Http.open(u, 30000).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }; true }.getOrDefault(false)
                    }
                    if (ok) { file.delete(); tmp.renameTo(file) } else tmp.delete()
                }
                if (file.exists()) {
                    val now = System.currentTimeMillis()
                    val list = file.inputStream().buffered().use { XmltvParser.parse(it, keep) }
                    _epg.value = EpgIndex.build(list, now - 3 * 3_600_000L, now + 36 * 3_600_000L)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { /* guide is optional */ }
        _state.value = _state.value.copy(epgLoading = false)
    }

    // Plain TSV cache: id, name, url, group, logo, epgId, number
    private fun writeCache(f: File, l: List<Channel>) {
        fun s(x: String?) = (x ?: "").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w -> for (c in l) w.append(listOf(s(c.id), s(c.name), s(c.url), s(c.group), s(c.logo), s(c.epgId), c.number?.toString() ?: "").joinToString("\t")).append('\n') }
        f.delete(); tmp.renameTo(f)
    }

    private fun readCache(f: File): List<Channel> {
        if (!f.exists()) return emptyList()
        val out = ArrayList<Channel>()
        runCatching {
            f.bufferedReader().useLines { lines ->
                for (ln in lines) {
                    val p = ln.split('\t'); if (p.size < 7) continue
                    out += Channel(p[0], p[1], p[2], p[3], p[4].ifEmpty { null }, p[5].ifEmpty { null }, p[6].toIntOrNull())
                }
            }
        }
        return out
    }
}
