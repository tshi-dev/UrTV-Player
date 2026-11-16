package com.urtv.player.core

import kotlinx.serialization.json.*

/**
 * Parsing of the answers of The Movie Database (themoviedb.org) API v3, tolerant of missing fields. Pure functions: the network and the
 * cache live in the app module. Data and images come from TMDB; the app shows the attribution line their terms ask for.
 */
object Tmdb {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Hit(val id: Long, val title: String, val year: String, val poster: String?, val popularity: Double)
    data class Person(val name: String, val photo: String?)
    data class Info(
        val id: Long = 0, val overview: String = "", val year: String = "", val runtimeMin: Int = 0, val genres: List<String> = emptyList(),
        val rating: Double = 0.0, val age: String = "", val cast: List<Person> = emptyList(), val trailerKey: String? = null,
        val poster: String? = null, val backdrop: String? = null,
    )

    private fun obj(body: String) = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.d(k: String) = s(k)?.toDoubleOrNull() ?: 0.0
    private fun JsonObject.arr(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** search/movie, search/tv and trending answers: «results». */
    fun parseHits(body: String): List<Hit> = obj(body)?.arr("results").orEmpty().mapNotNull { o ->
        val id = o.s("id")?.toLongOrNull() ?: return@mapNotNull null
        val title = o.s("title") ?: o.s("name") ?: return@mapNotNull null
        Hit(id, title, (o.s("release_date") ?: o.s("first_air_date") ?: "").take(4), o.s("poster_path"), o.d("popularity"))
    }

    /** The first result whose title (and year when both are known) matches; null when nothing fits. */
    fun bestHit(hits: List<Hit>, title: String, year: String?): Hit? {
        val n = normalize(title)
        return hits.firstOrNull { normalize(it.title) == n && (year.isNullOrBlank() || it.year.isBlank() || it.year == year) }
            ?: hits.firstOrNull { normalize(it.title) == n }
    }

    /** Lower case, no punctuation, no «(2020)» / «[HD]» tails: for comparing a provider's title with TMDB's. */
    fun normalize(t: String): String = t.lowercase().replace(Regex("""[\(\[][^\)\]]*[\)\]]"""), " ").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** movie/{id} or tv/{id} with append_to_response=credits,videos,release_dates,content_ratings. */
    fun parseInfo(body: String): Info? {
        val o = obj(body) ?: return null
        val id = o.s("id")?.toLongOrNull() ?: return null
        val cast = (o["credits"] as? JsonObject)?.arr("cast").orEmpty().take(12).mapNotNull { c -> c.s("name")?.let { Person(it, c.s("profile_path")) } }
        val trailer = (o["videos"] as? JsonObject)?.arr("results").orEmpty()
            .filter { it.s("site").equals("YouTube", true) && it.s("key") != null }
            .let { v -> v.firstOrNull { it.s("type") == "Trailer" } ?: v.firstOrNull() }?.s("key")
        return Info(
            id = id, overview = o.s("overview").orEmpty(), year = (o.s("release_date") ?: o.s("first_air_date") ?: "").take(4),
            runtimeMin = o.s("runtime")?.toIntOrNull() ?: (o["episode_run_time"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0,
            genres = o.arr("genres").mapNotNull { it.s("name") }, rating = o.d("vote_average"), age = ageOf(o), cast = cast, trailerKey = trailer,
            poster = o.s("poster_path"), backdrop = o.s("backdrop_path"),
        )
    }

    /** «+13», «+18», «للجميع»-style label from the certification of the first country that has one (SA, AE, US, GB). */
    private fun ageOf(o: JsonObject): String {
        val prefer = listOf("SA", "AE", "US", "GB")
        val movie = (o["release_dates"] as? JsonObject)?.arr("results").orEmpty()
        val tv = (o["content_ratings"] as? JsonObject)?.arr("results").orEmpty()
        for (cc in prefer) {
            movie.firstOrNull { it.s("iso_3166_1") == cc }?.arr("release_dates")?.firstNotNullOfOrNull { it.s("certification")?.takeIf { c -> c.isNotBlank() } }?.let { return ageLabel(it) }
            tv.firstOrNull { it.s("iso_3166_1") == cc }?.s("rating")?.takeIf { it.isNotBlank() }?.let { return ageLabel(it) }
        }
        return ""
    }

    fun ageLabel(cert: String): String = when (cert.trim().uppercase().replace(" ", "")) {
        "G", "TV-G", "TV-Y", "U", "0", "ALL" -> "+0"
        "PG", "TV-PG", "TV-Y7", "7", "7+" -> "+7"
        "PG-13", "TV-14", "12", "12A", "13", "13+" -> "+13"
        "15", "16", "16+" -> "+16"
        "R", "TV-MA", "NC-17", "18", "18+" -> "+18"
        else -> cert.trim().take(6).let { if (it.all { c -> c.isDigit() || c == '+' }) (if (it.startsWith("+")) it else "+$it") else "" }
    }

    const val IMG = "https://image.tmdb.org/t/p/"
    fun poster(path: String?) = path?.let { IMG + "w342" + it }
    fun profile(path: String?) = path?.let { IMG + "w185" + it }
    fun backdrop(path: String?) = path?.let { IMG + "w780" + it }
    fun youtube(key: String) = "https://www.youtube.com/watch?v=$key"
}
