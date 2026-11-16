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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class VodState(
    val playlistId: String? = null,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    /** Goes up whenever the index changed: screens that list from it ask again. */
    val rev: Int = 0,
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** Refresh progress: categories done of total, the one being read, rows written so far in a whole-list answer. */
    val doneCats: Int = 0,
    val totalCats: Int = 0,
    val current: String = "",
    val rows: Int = 0,
    /** Films and series first seen in the last days (not on the very first load): the «جديد» badge. */
    val fresh: Int = 0,
) { val any get() = movieCount > 0 || seriesCount > 0 }

/**
 * Movies and series: a disk index ([VodDb]) shown at once from the last refresh, refreshed in the background category by category and written as a
 * diff, so a 150 000-film account never sits in memory and never shows a 0 % screen. Details load on demand.
 */
class Vod(ctx: Context, private val store: Store, private val scope: CoroutineScope, val db: VodDb = VodDb(ctx)) {
    private val dir = File(ctx.filesDir, "cache").apply { mkdirs() }
    private val _state = MutableStateFlow(VodState())
    val state: StateFlow<VodState> = _state
    private var job: Job? = null
    @Volatile private var skipped = false
    private val checkEveryMs = 6L * 3_600_000      // how often a list is looked at again in the background (the diff makes it cheap to write)

    fun account(def: PlaylistDef) = XtreamAccount(def.server, def.user, def.pass)

    private fun snap(pid: String, over: VodState = _state.value, bump: Boolean = true) =
        over.copy(playlistId = pid, movieCount = db.count(pid, VodRow.MOVIE), seriesCount = db.count(pid, VodRow.SERIES), fresh = db.fresh(pid), rev = over.rev + if (bump) 1 else 0)

    /** The refresh stops here and keeps everything written so far («تخطّي»). */
    fun skip() { skipped = true }

    private val fetched = HashSet<String>()     // playlists already asked in this run: switching between Movies and Series does not ask again
    /** [fetch] false: only what is on disk is read (start-up). */
    fun open(def: PlaylistDef?, force: Boolean = false, fetch: Boolean = true) {
        val cur = _state.value
        // the cache-only open at start must never cancel a refresh in progress or replace what is already showing
        if (!fetch && def != null && cur.playlistId == def.id && (cur.loading || cur.any)) return
        if (fetch && !force && def != null && def.id in fetched && cur.playlistId == def.id) return
        job?.cancel()
        if (def == null) { _state.value = VodState(); return }
        skipped = false
        job = scope.launch {
            withContext(Dispatchers.IO) { if (def.isXtream) migrateLegacy(def) else readM3u(def) }
            _state.value = withContext(Dispatchers.IO) { snap(def.id, VodState()) }
            if (!fetch || !def.isXtream) return@launch
            fetched += def.id
            val last = withContext(Dispatchers.IO) { db.meta(def.id, "checked")?.toLongOrNull() ?: 0L }
            val stale = force || !_state.value.any || System.currentTimeMillis() - last > checkEveryMs
            if (!stale) return@launch
            _state.value = _state.value.copy(loading = true, failed = false, doneCats = 0, totalCats = 0, current = "", rows = 0)
            val acc = account(def)
            val ok = withContext(Dispatchers.IO) {
                val m = runCatching { refreshKind(def, acc, VodRow.MOVIE) { skipped || !isActive } }
                val sr = runCatching { refreshKind(def, acc, VodRow.SERIES) { skipped || !isActive } }
                (m.exceptionOrNull() ?: sr.exceptionOrNull())?.let { if (it is kotlinx.coroutines.CancellationException) throw it; Diag.record(it) }
                if (m.isSuccess || sr.isSuccess) db.setMeta(def.id, "checked", System.currentTimeMillis().toString())
                m.isSuccess || sr.isSuccess
            }
            if (!ok) fetched -= def.id      // nothing came: the next visit to the tab asks again
            _state.value = snap(def.id).copy(loading = false, failed = !ok && !_state.value.any, doneCats = 0, totalCats = 0, current = "")
        }
    }

    private fun toRow(m: Movie) = VodRow(VodRow.MOVIE, m.id, m.name, m.category, m.poster, m.rating, m.year, m.added, ext = m.url.substringAfterLast('.', "").take(8))
    private fun toRow(s: Series) = VodRow(VodRow.SERIES, s.id, s.name, s.category, s.poster, s.rating, s.year, s.added, plot = s.plot.take(600))

    /**
     * One kind (films or series), category by category: each category's answer is small, written as a diff, and shown as soon as it is in. A provider that
     * ignores the category filter (every answer is the whole list) is noticed from the first rows and read as one whole answer instead.
     */
    private fun refreshKind(def: PlaylistDef, acc: XtreamAccount, kind: Int, stop: () -> Boolean) {
        val pid = def.id
        val cats = runCatching { Http.retrying { XtreamParser.parseCategories(Http.text(if (kind == 0) acc.vodCategoriesUrl() else acc.seriesCategoriesUrl())) } }.getOrDefault(emptyMap())
        val initial = db.count(pid, kind) == 0
        var lastPublish = 0L
        fun publish(over: VodState, now: Long = System.currentTimeMillis(), always: Boolean = false) { if (always || now - lastPublish > 500) { lastPublish = now; _state.value = snap(pid, over) } }
        var whole = cats.isEmpty()
        var failedCats = 0
        if (!whole) {
            _state.value = _state.value.copy(totalCats = _state.value.totalCats + cats.size)
            var base = 0L
            for ((cid, cname) in cats) {
                if (stop()) break
                _state.value = _state.value.copy(current = cname)
                var mismatch = false
                val r = runCatching { Http.retrying { Http.open(if (kind == 0) acc.vodStreamsUrl(cid) else acc.seriesUrl(cid)).use { input ->
                    val rows = (if (kind == 0) XtreamParser.movieSeq(input, acc, cats).map { (c, m) -> c to toRow(m) } else XtreamParser.seriesSeq(input, cats).map { (c, x) -> c to toRow(x) })
                        .takeWhile { (c, _) -> if (c != cid && c.isNotEmpty()) mismatch = true; !mismatch && !stop() }.map { it.second }
                    db.apply(pid, kind, cname, rows, base, initial, complete = { !mismatch && !stop() })
                } } }
                if (mismatch) { whole = true; break }
                if (r.isFailure) { failedCats++; if (r.exceptionOrNull() is kotlinx.coroutines.CancellationException) throw r.exceptionOrNull()!! }
                base += 10_000_000L
                publish(_state.value.copy(doneCats = _state.value.doneCats + 1))
            }
            if (!whole && failedCats == cats.size) throw java.io.IOException("every category failed")
        }
        if (whole) {
            _state.value = _state.value.copy(totalCats = _state.value.totalCats + (if (cats.isEmpty()) 1 else 0), current = "")
            val map = cats
            Http.retrying { Http.open(if (kind == 0) acc.vodStreamsUrl() else acc.seriesUrl()).use { input ->
                val rows = if (kind == 0) XtreamParser.movieSeq(input, acc, map).map { toRow(it.second) } else XtreamParser.seriesSeq(input, map).map { toRow(it.second) }
                db.apply(pid, kind, null, rows, 0, initial, complete = { !stop() }, onBatch = { n -> publish(_state.value.copy(rows = n)) })
            } }
            _state.value = _state.value.copy(doneCats = _state.value.doneCats + 1)
        }
        publish(_state.value, always = true)
    }

    /** Lists cached by the versions before the disk index: read once into it (streaming, never as a whole), then removed. */
    private fun migrateLegacy(def: PlaylistDef) {
        val mf = File(dir, "movies_${def.id}.tsv"); val sf = File(dir, "series_${def.id}.tsv")
        if (db.count(def.id) > 0) { listOf(mf, sf, File(dir, "mcats_${def.id}.txt"), File(dir, "scats_${def.id}.txt")).forEach { it.delete() }; return }
        if (mf.exists()) runCatching { Secrets.read(mf) { i -> i.bufferedReader().useLines { ls ->
            db.apply(def.id, 0, null, ls.map { it.split('\t') }.filter { it.size >= 7 }.map { VodRow(0, it[0], it[1], it[3], it[4].ifEmpty { null }, it[5].ifEmpty { null }, null, it[6].toLongOrNull() ?: 0, ext = it[2].substringAfterLast('.', "").take(8)) }, 0, initial = true)
        } } }
        if (sf.exists()) runCatching { Secrets.read(sf) { i -> i.bufferedReader().useLines { ls ->
            db.apply(def.id, 1, null, ls.map { it.split('\t') }.filter { it.size >= 8 }.map { VodRow(1, it[0], it[1], it[2], it[3].ifEmpty { null }, it[4].ifEmpty { null }, it[5].ifEmpty { null }, it[6].toLongOrNull() ?: 0, plot = it[7].take(600)) }, 0, initial = true)
        } } }
        listOf(mf, sf, File(dir, "mcats_${def.id}.txt"), File(dir, "scats_${def.id}.txt")).forEach { it.delete() }
    }

    /** An M3U list's films and series: stream addresses stay in the encrypted file (they may carry a login), the index holds the rest. */
    private fun readM3u(def: PlaylistDef) {
        val movies = readMovies(File(dir, "movies_${def.id}.tsv")); val series = readSeries(File(dir, "series_${def.id}.tsv"))
        m3uUrls = movies.associate { it.id to it.url }
        m3uEpisodes = readEpisodes(File(dir, "m3uep_${def.id}.tsv"))
        if (db.count(def.id, 0) != movies.size || db.count(def.id, 1) != series.size) {
            db.apply(def.id, 0, null, movies.asSequence().map(::toRow), 0, initial = db.count(def.id, 0) == 0)
            db.apply(def.id, 1, null, series.asSequence().map(::toRow), 0, initial = db.count(def.id, 1) == 0)
        }
    }

    /** A film with its stream address, or null. A single-row read by key: fine on any thread. */
    fun movie(def: PlaylistDef, id: String): Movie? = db.get(def.id, VodRow.MOVIE, id)?.let {
        Movie(it.id, it.name, if (def.isXtream) account(def).movieUrl(it.id, it.ext) else m3uUrls[it.id].orEmpty(), it.cat, it.poster, it.rating, it.year, it.added)
    }
    fun series(pid: String, id: String): Series? = db.get(pid, VodRow.SERIES, id)?.let { Series(it.id, it.name, it.cat, it.poster, it.plot, it.rating, it.year, it.added) }

    suspend fun movieInfo(def: PlaylistDef, id: String): VodInfo = withContext(Dispatchers.IO) {
        runCatching { XtreamParser.parseVodInfo(Http.text(account(def).vodInfoUrl(id))) }.getOrDefault(VodInfo())
    }

    suspend fun episodes(def: PlaylistDef, seriesId: String): List<Episode> = if (seriesId.startsWith("m3u:")) m3uEpisodes[seriesId].orEmpty() else withContext(Dispatchers.IO) {
        runCatching { XtreamParser.parseEpisodes(Http.text(account(def).seriesInfoUrl(seriesId)), account(def)) }.getOrDefault(emptyList())
    }

    fun forget(id: String) { runCatching { db.forget(id) }; File(dir, "movies_$id.tsv").delete(); File(dir, "series_$id.tsv").delete(); File(dir, "m3uep_$id.tsv").delete() }

    @Volatile private var m3uEpisodes: Map<String, List<Episode>> = emptyMap()
    @Volatile private var m3uUrls: Map<String, String> = emptyMap()

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
        m3uEpisodes = eps; m3uUrls = movies.associate { it.id to it.url }
        db.apply(def.id, 0, null, movies.asSequence().map(::toRow), 0, initial = db.count(def.id, 0) == 0)
        db.apply(def.id, 1, null, series.asSequence().map(::toRow), 0, initial = db.count(def.id, 1) == 0)
        _state.value = snap(def.id, VodState())
    }
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
