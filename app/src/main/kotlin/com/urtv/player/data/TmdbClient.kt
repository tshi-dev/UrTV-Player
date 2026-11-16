package com.urtv.player.data

import android.content.Context
import com.urtv.player.core.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/**
 * Posters, stories, cast, trailers, age ratings and «trending» from The Movie Database. Needs a free API key given at build time
 * (the TMDB_API_KEY secret); without one every call answers «nothing» and the app shows only what the provider sends. Answers are cached
 * on disk (details 30 days, trending 12 hours). The key is never logged and never part of a message.
 */
class TmdbClient(ctx: Context, private val key: String) {
    val enabled: Boolean get() = key.isNotBlank()
    private val dir = File(ctx.filesDir, "cache/tmdb").apply { mkdirs() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun url(path: String, q: String) = "https://api.themoviedb.org/3/$path?api_key=$key&$q"

    private fun cached(name: String, maxAgeMs: Long, fetch: () -> String?): String? {
        val f = File(dir, name)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return runCatching { f.readText() }.getOrNull()
        val body = runCatching { fetch() }.getOrNull()
        if (body != null) runCatching { f.writeText(body) }
        return body ?: runCatching { if (f.exists()) f.readText() else null }.getOrNull()      // stale beats nothing
    }

    /**
     * Details of one film or series: by the provider's own TMDB id when it sends one, else by searching the title (as given, in Arabic and in English,
     * with the year and without) and taking the closest title + year. The match is cached per item. Null when nothing fits.
     */
    suspend fun info(title: String, year: String?, series: Boolean, tmdbId: Long? = null): Tmdb.Info? = withContext(Dispatchers.IO) {
        if (!enabled || (title.isBlank() && tmdbId == null)) return@withContext null
        val kind = if (series) "tv" else "movie"
        val name = Integer.toHexString("$kind|${Tmdb.normalize(title)}|${year.orEmpty()}|${tmdbId ?: 0}".hashCode()) + ".json"
        val body = cached(name, 30L * 24 * 3_600_000) {
            val id = tmdbId?.takeIf { it > 0 }?.takeIf { details(kind, it) != null } ?: search(kind, title, year)?.id ?: return@cached null
            details(kind, id)
        }
        body?.let { Tmdb.parseInfo(it) }
    }

    private fun search(kind: String, title: String, year: String?): Tmdb.Hit? {
        val q = enc(Tmdb.normalize(title))
        val yq = if (year.isNullOrBlank()) "" else "&" + (if (kind == "tv") "first_air_date_year" else "year") + "=" + year
        val hits = ArrayList<Tmdb.Hit>()
        for (lang in listOf("ar", "en")) for (y in if (yq.isEmpty()) listOf("") else listOf(yq, "")) {
            runCatching { hits += Tmdb.parseHits(Http.retrying { Http.text(url("search/$kind", "query=$q&language=$lang$y"), 2_000_000) }) }
            if (y.isEmpty() && yq.isNotEmpty() && Tmdb.bestHit(hits, title, year) != null) break
        }
        return Tmdb.bestHit(hits.distinctBy { it.id }, listOf(title), year)
    }

    private fun details(kind: String, id: Long): String? = runCatching {
        val append = "credits,videos," + if (kind == "tv") "content_ratings" else "release_dates"
        val ar = Http.retrying { Http.text(url("$kind/$id", "language=ar&append_to_response=$append&include_video_language=ar,en,null"), 2_000_000) }
        val parsed = Tmdb.parseInfo(ar) ?: return@runCatching null
        if (parsed.overview.isBlank()) {     // no Arabic story: the English one
            val en = runCatching { Tmdb.parseInfo(Http.retrying { Http.text(url("$kind/$id", "language=en"), 2_000_000) }) }.getOrNull()
            if (en != null && en.overview.isNotBlank()) return@runCatching ar.replaceFirst("\"overview\":\"\"", "\"overview\":" + kotlinx.serialization.json.JsonPrimitive(en.overview).toString())
        }
        ar
    }.getOrNull()

    /** This week's trending films and series (titles and years), or empty. */
    suspend fun trending(): List<Tmdb.Hit> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val a = cached("trend_movie.json", 12L * 3_600_000) { Http.retrying { Http.text(url("trending/movie/week", "language=ar"), 2_000_000) } }
        val b = cached("trend_tv.json", 12L * 3_600_000) { Http.retrying { Http.text(url("trending/tv/week", "language=ar"), 2_000_000) } }
        (a?.let { Tmdb.parseHits(it) }.orEmpty() + b?.let { Tmdb.parseHits(it) }.orEmpty())
    }
}
