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
)

/** Movies and series for Xtream playlists: cached on disk, fetched when stale. Details load on demand. */
class Vod(ctx: Context, private val store: Store, private val scope: CoroutineScope) {
    private val dir = File(ctx.filesDir, "cache").apply { mkdirs() }
    private val _state = MutableStateFlow(VodState())
    val state: StateFlow<VodState> = _state
    private var job: Job? = null

    fun account(def: PlaylistDef) = XtreamAccount(def.server, def.user, def.pass)

    fun open(def: PlaylistDef?, force: Boolean = false) {
        job?.cancel()
        if (def == null || !def.isXtream) { _state.value = VodState(); return }
        val mf = File(dir, "movies_${def.id}.tsv"); val sf = File(dir, "series_${def.id}.tsv")
        job = scope.launch {
            val (m, s) = withContext(Dispatchers.IO) { readMovies(mf) to readSeries(sf) }
            _state.value = VodState(def.id, m, s, loading = false)
            val maxAge = (store.refreshHours.value.takeIf { it > 0 } ?: 24) * 3_600_000L
            val stale = force || !mf.exists() || System.currentTimeMillis() - mf.lastModified() > maxAge
            if (!stale) return@launch
            _state.value = _state.value.copy(loading = true, failed = false)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val acc = account(def)
                    val mc = XtreamParser.parseCategories(Http.text(acc.vodCategoriesUrl()))
                    val movies = XtreamParser.parseMovies(Http.text(acc.vodStreamsUrl()), acc, mc).sortedByDescending { it.added }
                    val sc = XtreamParser.parseCategories(Http.text(acc.seriesCategoriesUrl()))
                    val series = XtreamParser.parseSeriesList(Http.text(acc.seriesUrl()), sc).sortedByDescending { it.added }
                    writeMovies(mf, movies); writeSeries(sf, series)
                    movies to series
                }
            }
            r.onSuccess { (mv, sr) -> _state.value = VodState(def.id, mv, sr) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Diag.record(it); _state.value = _state.value.copy(loading = false, failed = m.isEmpty() && s.isEmpty()) }
        }
    }

    suspend fun movieInfo(def: PlaylistDef, id: String): VodInfo = withContext(Dispatchers.IO) {
        runCatching { XtreamParser.parseVodInfo(Http.text(account(def).vodInfoUrl(id))) }.getOrDefault(VodInfo())
    }

    suspend fun episodes(def: PlaylistDef, seriesId: String): List<Episode> = withContext(Dispatchers.IO) {
        runCatching { XtreamParser.parseEpisodes(Http.text(account(def).seriesInfoUrl(seriesId)), account(def)) }.getOrDefault(emptyList())
    }

    fun forget(id: String) { File(dir, "movies_$id.tsv").delete(); File(dir, "series_$id.tsv").delete() }

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
