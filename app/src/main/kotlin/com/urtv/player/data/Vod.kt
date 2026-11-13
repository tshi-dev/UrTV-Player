package com.urtv.player.data

import android.content.Context
import com.urtv.player.core.Episode
import com.urtv.player.core.Movie
import com.urtv.player.core.Series
import com.urtv.player.core.VodInfo
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

data class VodState(
    val playlistId: String? = null,
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** The provider's category order (names), for the left list of the Movies and Series tabs. */
    val movieCats: List<String> = emptyList(),
    val seriesCats: List<String> = emptyList(),
)

/** Movies and series for Xtream playlists: cached on disk, fetched when stale. Details load on demand. */
class Vod(ctx: Context, private val store: Store, private val scope: CoroutineScope) {
    private val dir = File(ctx.filesDir, "cache").apply { mkdirs() }
    private val _state = MutableStateFlow(VodState())
    val state: StateFlow<VodState> = _state
    private var job: Job? = null

    fun account(def: PlaylistDef) = XtreamAccount(def.server, def.user, def.pass)

    /** [fetch] false: only the cached lists are read (start-up: nothing is downloaded or parsed until Movies or Series is opened). */
    private val fetched = HashSet<String>()     // playlists already asked in this run: switching between Movies and Series does not ask again
    fun open(def: PlaylistDef?, force: Boolean = false, fetch: Boolean = true) {
        // the cache-only open at start must never cancel a fetch in progress or replace lists that are already showing (this made the lists vanish after loading)
        if (!fetch && def != null && _state.value.playlistId == def.id && (_state.value.loading || _state.value.movies.isNotEmpty() || _state.value.series.isNotEmpty())) return
        if (fetch && !force && def != null && def.id in fetched && _state.value.playlistId == def.id) return
        job?.cancel()
        if (def == null) { _state.value = VodState(); return }
        if (!def.isXtream) {      // an M3U list: its movies and series were split off when it was loaded; only the cached copy is read
            job = scope.launch {
                val (m, s) = withContext(Dispatchers.IO) { readMovies(File(dir, "movies_${def.id}.tsv")) to readSeries(File(dir, "series_${def.id}.tsv")) }
                m3uEpisodes = withContext(Dispatchers.IO) { readEpisodes(File(dir, "m3uep_${def.id}.tsv")) }
                _state.value = VodState(def.id, m, s)
            }
            return
        }
        val mf = File(dir, "movies_${def.id}.tsv"); val sf = File(dir, "series_${def.id}.tsv")
        job = scope.launch {
            val (m, s) = withContext(Dispatchers.IO) { readMovies(mf) to readSeries(sf) }
            val (mc0, sc0) = withContext(Dispatchers.IO) { readCats(File(dir, "mcats_${def.id}.txt")) to readCats(File(dir, "scats_${def.id}.txt")) }
            _state.value = VodState(def.id, m, s, loading = false, movieCats = mc0, seriesCats = sc0)
            val maxAge = (store.refreshHours.value.takeIf { it > 0 } ?: 24) * 3_600_000L
            if (!fetch) return@launch
            fetched += def.id
            val stale = force || !mf.exists() || System.currentTimeMillis() - mf.lastModified() > maxAge
            if (!stale) return@launch
            _state.value = _state.value.copy(loading = true, failed = false)
            // movies and series are asked separately: one that fails or times out never empties the other
            val acc = account(def)
            val mr = withContext(Dispatchers.IO) { runCatching {
                val mc = runCatching { XtreamParser.parseCategories(Http.text(acc.vodCategoriesUrl())) }.getOrDefault(emptyMap())
                Http.open(acc.vodStreamsUrl()).use { XtreamParser.parseMovies(it, acc, mc) }.also { writeMovies(mf, it); writeCats(File(dir, "mcats_${def.id}.txt"), mc.values.toList()) }     // the provider's own order, never re-sorted
            } }
            if (mr.isSuccess) _state.value = _state.value.copy(playlistId = def.id, movies = mr.getOrThrow(), movieCats = readCats(File(dir, "mcats_${def.id}.txt")))
            val sr = withContext(Dispatchers.IO) { runCatching {
                val sc = runCatching { XtreamParser.parseCategories(Http.text(acc.seriesCategoriesUrl())) }.getOrDefault(emptyMap())
                Http.open(acc.seriesUrl()).use { XtreamParser.parseSeriesList(it, sc) }.also { writeSeries(sf, it); writeCats(File(dir, "scats_${def.id}.txt"), sc.values.toList()) }
            } }
            if (sr.isSuccess) _state.value = _state.value.copy(playlistId = def.id, series = sr.getOrThrow(), seriesCats = readCats(File(dir, "scats_${def.id}.txt")))
            (mr.exceptionOrNull() ?: sr.exceptionOrNull())?.let { if (it is kotlinx.coroutines.CancellationException) throw it; Diag.record(it) }
            if (mr.isFailure && sr.isFailure) fetched -= def.id      // nothing came: the next visit to the tab asks again
            _state.value = _state.value.copy(loading = false, failed = mr.isFailure && sr.isFailure && _state.value.movies.isEmpty() && _state.value.series.isEmpty())
        }
    }

    suspend fun movieInfo(def: PlaylistDef, id: String): VodInfo = withContext(Dispatchers.IO) {
        runCatching { XtreamParser.parseVodInfo(Http.text(account(def).vodInfoUrl(id))) }.getOrDefault(VodInfo())
    }

    suspend fun episodes(def: PlaylistDef, seriesId: String): List<Episode> = if (seriesId.startsWith("m3u:")) m3uEpisodes[seriesId].orEmpty() else withContext(Dispatchers.IO) {
        runCatching { XtreamParser.parseEpisodes(Http.text(account(def).seriesInfoUrl(seriesId)), account(def)) }.getOrDefault(emptyList())
    }

    fun forget(id: String) { File(dir, "movies_$id.tsv").delete(); File(dir, "series_$id.tsv").delete(); File(dir, "m3uep_$id.tsv").delete() }

    @Volatile private var m3uEpisodes: Map<String, List<Episode>> = emptyMap()

    /** The movies and series found inside an M3U list: grouped by show name where the titles say «Name S01 E02», otherwise one entry each. */
    fun setFromM3u(def: PlaylistDef, split: com.urtv.player.core.M3uVod.Split) {
        val movies = split.movies.mapIndexed { i, c -> Movie("m3u:$i", c.name, c.url, c.group, c.logo, null, null, 0) }
        val shows = LinkedHashMap<String, MutableList<Pair<com.urtv.player.core.M3uVod.EpisodeRef?, com.urtv.player.core.Channel>>>()
        for ((ref, c) in split.episodes) shows.getOrPut(if (ref != null) "m3u:" + ref.show.lowercase() else "m3u:one:" + c.url.hashCode()) { ArrayList() } += (ref to c)
        val series = ArrayList<Series>(); val eps = HashMap<String, List<Episode>>()
        for ((id, l) in shows) {
            val first = l.first().second
            series += Series(id, l.first().first?.show ?: first.name, first.group, l.firstNotNullOfOrNull { it.second.logo }, "", null, null, 0)
            eps[id] = l.mapIndexed { i, (ref, c) -> Episode("$id#$i", ref?.season ?: 1, ref?.number ?: (i + 1), c.name, c.url, "", c.logo, 0) }.sortedWith(compareBy({ it.season }, { it.number }))
        }
        writeMovies(File(dir, "movies_${def.id}.tsv"), movies); writeSeries(File(dir, "series_${def.id}.tsv"), series); writeEpisodes(File(dir, "m3uep_${def.id}.tsv"), eps)
        m3uEpisodes = eps
        _state.value = VodState(def.id, movies, series)
    }
    private fun writeCats(f: File, l: List<String>) = runCatching { f.writeText(l.joinToString("\n") { s(it) }) }
    private fun readCats(f: File): List<String> = runCatching { if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList() }.getOrDefault(emptyList())
    private fun writeEpisodes(f: File, m: Map<String, List<Episode>>) = write(f, m.flatMap { (sid, l) -> l.map { listOf(s(sid), s(it.id), it.season.toString(), it.number.toString(), s(it.title), s(it.url), s(it.image)) } })
    private fun readEpisodes(f: File): Map<String, List<Episode>> = rows(f, 7).groupBy({ it[0] }) { Episode(it[1], it[2].toIntOrNull() ?: 1, it[3].toIntOrNull() ?: 0, it[4], it[5], "", it[6].ifEmpty { null }, 0) }

    private fun s(x: String?) = (x ?: "").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    private fun writeMovies(f: File, l: List<Movie>) = write(f, l.map { listOf(s(it.id), s(it.name), s(it.url), s(it.category), s(it.poster), s(it.rating), it.added.toString()) })
    private fun writeSeries(f: File, l: List<Series>) = write(f, l.map { listOf(s(it.id), s(it.name), s(it.category), s(it.poster), s(it.rating), s(it.year), it.added.toString(), s(it.plot)) })
    private fun write(f: File, rows: List<List<String>>) {
        Secrets.write(f) { o -> o.bufferedWriter().use { w -> for (r in rows) w.append(r.joinToString("\t")).append('\n') } }   // URLs embed credentials
    }
    private fun rows(f: File, min: Int): List<List<String>> {
        if (!f.exists()) return emptyList()
        val out = ArrayList<List<String>>()
        runCatching { Secrets.read(f) { i -> i.bufferedReader().useLines { ls -> for (ln in ls) { val p = ln.split('\t'); if (p.size >= min) out += p } } } }
        return out
    }
    private fun readMovies(f: File) = rows(f, 7).map { Movie(it[0], it[1], it[2], it[3], it[4].ifEmpty { null }, it[5].ifEmpty { null }, null, it[6].toLongOrNull() ?: 0) }
    private fun readSeries(f: File) = rows(f, 8).map { Series(it[0], it[1], it[2], it[3].ifEmpty { null }, it[7], it[4].ifEmpty { null }, it[5].ifEmpty { null }, it[6].toLongOrNull() ?: 0) }
}
