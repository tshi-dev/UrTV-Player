package com.urtv.player.data

import android.content.Context
import com.urtv.player.core.Channel
import com.urtv.player.core.M3uParser
import com.urtv.player.core.XmltvParser
import com.urtv.player.core.XtreamAccount
import com.urtv.player.core.XtreamLink
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
    /** Groups, per-group lists, numbers and search names of [channels], built off the main thread. */
    val index: ChannelIndex = ChannelIndex.EMPTY,
    val loading: Boolean = false,
    val error: LoadError? = null,
    val epgLoading: Boolean = false,
    /** True once the first answer is in (cache read or network try finished). Until then the UI shows skeleton rows, never an empty-state flash. */
    val settled: Boolean = false,
    /** Channels read so far while a playlist is downloading/parsing (0 when not loading). */
    val progress: Int = 0,
)

/** Loads the active playlist (cache first, then network) and its XMLTV guide. */
/** Where the time of the last playlist load went, in ms (shown in the stats and kept in memory only). */
data class LoadTimings(val fetchParseMs: Long = 0, val cacheWriteMs: Long = 0, val channels: Int = 0)

class Catalog(ctx: Context, private val store: Store, private val scope: CoroutineScope) {
    @Volatile var timings = LoadTimings(); private set
    private val dir = File(ctx.filesDir, "cache").apply { mkdirs() }
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state
    private val _epg = MutableStateFlow(EpgIndex.EMPTY)
    val epg: StateFlow<EpgIndex> = _epg
    private var job: Job? = null

    fun open(id: String?, force: Boolean = false) {
        job?.cancel()
        val def = store.playlists.value.firstOrNull { it.id == id }
        if (def == null) { _state.value = CatalogState(settled = true); _epg.value = EpgIndex.EMPTY; return }
        val sameList = _state.value.playlistId == id
        _state.value = CatalogState(playlistId = id, channels = if (sameList) _state.value.channels else emptyList(), index = if (sameList) _state.value.index else ChannelIndex.EMPTY, loading = true)
        job = scope.launch {
            val cacheFile = chFile(def.id)
            val cached = withContext(Dispatchers.IO) { readCache(cacheFile) }
            if (cached.isNotEmpty()) _state.value = _state.value.copy(channels = cached, index = withContext(Dispatchers.Default) { ChannelIndex(cached, def.id) }, loading = false, settled = true)
            val maxAgeMs = store.refreshHours.value * 3_600_000L
            val stale = !cacheFile.exists() || cached.isEmpty() || (maxAgeMs > 0 && System.currentTimeMillis() - cacheFile.lastModified() > maxAgeMs)
            var epgUrls = emptyList<String>()
            if (force || stale) {
                _state.value = _state.value.copy(loading = true, error = null)
                val t0 = System.currentTimeMillis()
                val r = withContext(Dispatchers.IO) { runCatching { fetch(def) } }
                r.onSuccess { (list, urls) ->
                    epgUrls = urls
                    detected?.let { acc -> convertDetected(def, acc, list, cached); detected = null }
                    vodSplit?.let { sp -> vodSplit = null; withContext(Dispatchers.IO) { onVod?.invoke(def, sp) } }
                    if (list.isEmpty()) _state.value = _state.value.copy(loading = false, settled = true, error = if (cached.isEmpty()) LoadError.Empty else null)
                    else {
                        // the list is on screen first; the (encrypted) cache is written afterwards, off the critical path
                        _state.value = _state.value.copy(channels = list, index = withContext(Dispatchers.Default) { ChannelIndex(list, def.id) }, loading = false, settled = true, error = null, progress = 0)
                        val t1 = System.currentTimeMillis()
                        withContext(Dispatchers.IO) { writeCache(cacheFile, list) }
                        timings = LoadTimings(t1 - t0, System.currentTimeMillis() - t1, list.size)
                    }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Diag.record(it)
                    _state.value = _state.value.copy(loading = false, settled = true, error = if (cached.isEmpty()) LoadError.Network else null)
                }
            } else _state.value = _state.value.copy(loading = false, settled = true)
            if (android.os.Build.FINGERPRINT != "robolectric") kotlinx.coroutines.delay(3_000)       // the guide waits until the screen has settled: it is the heaviest background job
            loadEpg(def, force || stale, epgUrls)
        }
    }

    /** Keeps favourites, recents, hidden channels and own lists: old M3U stream URLs are matched to the Xtream ones by stream id. */
    private fun convertDetected(def: PlaylistDef, acc: XtreamAccount, list: List<Channel>, old: List<Channel>) {
        val newByStream = HashMap<String, Channel>(list.size * 2).also { m -> for (c in list) c.streamId?.let { m[it] = c } }
        val map = HashMap<String, String>()
        for (c in old) { val sid = XtreamLink.streamIdOf(c.url) ?: continue; val n = newByStream[sid] ?: continue; map[com.urtv.player.ui.chKey(def.id, c)] = com.urtv.player.ui.chKey(def.id, n) }
        store.convertToXtream(def.id, acc, map)
    }

    fun refreshActive() = open(_state.value.playlistId, force = true)

    fun forget(id: String) { chFile(id).delete(); epgFile(id).delete(); File(dir, "epgurls_$id.txt").delete() }

    private fun chFile(id: String) = File(dir, "ch_$id.tsv")
    private fun epgFile(id: String) = File(dir, "epg_$id.xml")

    private fun fetchXtream(acc: XtreamAccount): Pair<List<Channel>, List<String>> {
        val cats = XtreamParser.parseCategories(Http.text(acc.categoriesUrl()))
        val parsed = Http.open(acc.streamsUrl()).use { XtreamParser.parseStreams(it, acc, cats) }
        return parsed.channels to parsed.epgUrls
    }

    /** Set when a pasted M3U link answered like an Xtream account: the next steps convert the playlist (see [open]). */
    @Volatile private var detected: XtreamAccount? = null
    /** Movies and series split out of an M3U list while it was parsed (handed to [onVod] once the live list is on screen). */
    @Volatile private var vodSplit: com.urtv.player.core.M3uVod.Split? = null
    @Volatile var onVod: ((PlaylistDef, com.urtv.player.core.M3uVod.Split) -> Unit)? = null

    private fun fetch(def: PlaylistDef): Pair<List<Channel>, List<String>> {
        if (def.isXtream) return fetchXtream(XtreamAccount(def.server, def.user, def.pass))
        // a «get.php?username=..&password=..» link is really an Xtream account: ask its API first (live, movies and series then work with details);
        // anything that does not answer like one falls back to the M3U exactly as before
        val link = XtreamLink.parse(def.url)
        if (link != null) {
            val x = runCatching { fetchXtream(link) }.getOrNull()
            if (x != null && x.first.isNotEmpty()) { detected = link; return x }
        }
        detected = null; vodSplit = null
        var seen = 0
        val parsed = Http.open(def.url).bufferedReader(Charsets.UTF_8).use { r ->
            // count entries as they stream in so the screen can say "N channels" instead of an empty wait
            M3uParser.parse(r.lineSequence().onEach { l -> if (l.startsWith("#EXTINF")) { seen++; if (seen % 25 == 0) _state.value = _state.value.copy(progress = seen) } })
        }
        val sp = com.urtv.player.core.M3uVod.split(parsed.channels)
        vodSplit = sp.takeIf { it.movies.isNotEmpty() || it.episodes.isNotEmpty() }
        return sp.live to parsed.epgUrls
    }

    private suspend fun loadEpg(def: PlaylistDef, fetchNew: Boolean, urlsFromPlaylist: List<String>) {
        val file = epgFile(def.id)
        val urlFile = File(dir, "epgurls_${def.id}.txt")
        if (urlsFromPlaylist.isNotEmpty()) runCatching { Secrets.writeText(urlFile, urlsFromPlaylist.joinToString("\n")) }
        val remembered = if (urlsFromPlaylist.isEmpty() && urlFile.exists()) runCatching { Secrets.readText(urlFile)?.lines().orEmpty() }.getOrDefault(emptyList()) else urlsFromPlaylist
        val urls = (listOf(def.epgUrl).filter { it.isNotBlank() } + remembered).filter { it.isNotBlank() }.distinct()
        val keep = HashSet<String>()
        for (c in _state.value.channels) c.epgId?.let { keep += it; keep += it.lowercase() }
        if (keep.isEmpty()) { _epg.value = EpgIndex.EMPTY; return }
        _state.value = _state.value.copy(epgLoading = true)
        try {
            withContext(Dispatchers.IO) {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }     // the guide must never compete with the screen for the CPU
                val maxAge = (store.refreshHours.value.takeIf { it > 0 } ?: 24) * 3_600_000L
                if (urls.isNotEmpty() && (fetchNew && urls.isNotEmpty() || !file.exists() || System.currentTimeMillis() - file.lastModified() > maxAge)) {
                    val tmp = File(file.path + ".tmp")
                    val ok = urls.any { u ->
                        runCatching { Http.open(u, 30000, Http.MAX_GUIDE).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }; true }.getOrDefault(false)
                    }
                    if (ok) { file.delete(); tmp.renameTo(file) } else tmp.delete()
                }
                if (file.exists()) {
                    val now = System.currentTimeMillis()
                    val off = (store.epgOffsetMin.value[def.id] ?: 0) * 60_000L
                    // only what the screens show (the running programme and the next hours), a handful per channel: never the whole week of a big provider
                    val list = file.inputStream().buffered().use { XmltvParser.parse(it, keep, fromMs = now - 2 * 3_600_000L - off, toMs = now + 14 * 3_600_000L - off, perChannelMax = 12) }
                    val shifted = if (off == 0L) list else list.map { it.copy(startMs = it.startMs + off, stopMs = it.stopMs + off) }
                    _epg.value = EpgIndex.build(shifted, now - 3 * 3_600_000L, now + 36 * 3_600_000L)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { /* guide is optional (also when memory runs out: the app keeps running without it) */ }
        _state.value = _state.value.copy(epgLoading = false)
    }

    // Plain TSV cache: id, name, url, group, logo, epgId, number
    private fun writeCache(f: File, l: List<Channel>) {
        fun s(x: String?) = (x ?: "").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        // Stream URLs of Xtream playlists embed the credentials, so the cache is encrypted at rest.
        Secrets.write(f) { o -> o.bufferedWriter().use { w -> for (c in l) w.append(listOf(s(c.id), s(c.name), s(c.url), s(c.group), s(c.logo), s(c.epgId), c.number?.toString() ?: "", s(c.streamId), c.archiveDays.toString()).joinToString("\t")).append('\n') } }
    }

    /** Channels last cached for a playlist (empty if never loaded). Call off the main thread. */
    fun cachedChannels(id: String): List<Channel> = readCache(chFile(id))

    private fun readCache(f: File): List<Channel> {
        if (!f.exists()) return emptyList()
        val out = ArrayList<Channel>()
        runCatching {
            Secrets.read(f) { i -> i.bufferedReader().useLines { lines ->
                for (ln in lines) {
                    val p = ln.split('\t'); if (p.size < 7) continue
                    out += Channel(p[0], p[1], p[2], p[3], p[4].ifEmpty { null }, p[5].ifEmpty { null }, p[6].toIntOrNull(), p.getOrNull(7)?.ifEmpty { null }, p.getOrNull(8)?.toIntOrNull() ?: 0)
                }
            } }
        }
        return out
    }
}
