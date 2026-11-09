package com.urtv.player.core

import kotlinx.serialization.json.*

/** Xtream Codes API helpers: URL building and tolerant JSON parsing (servers return numbers as strings, nulls, or garbage). */
data class XtreamAccount(val server: String, val user: String, val pass: String) {
    val base: String get() = normalizeServer(server)
    fun apiUrl(action: String? = null, extra: String = ""): String {
        val q = "username=${enc(user)}&password=${enc(pass)}" + (action?.let { "&action=$it" } ?: "") + extra
        return "$base/player_api.php?$q"
    }
    fun categoriesUrl() = apiUrl("get_live_categories")
    fun streamsUrl() = apiUrl("get_live_streams")
    fun streamUrl(streamId: String, ext: String = "m3u8") = "$base/live/${enc(user)}/${enc(pass)}/$streamId.$ext"
    fun vodCategoriesUrl() = apiUrl("get_vod_categories")
    fun vodStreamsUrl() = apiUrl("get_vod_streams")
    fun vodInfoUrl(id: String) = apiUrl("get_vod_info", "&vod_id=${enc(id)}")
    fun seriesCategoriesUrl() = apiUrl("get_series_categories")
    fun seriesUrl() = apiUrl("get_series")
    fun seriesInfoUrl(id: String) = apiUrl("get_series_info", "&series_id=${enc(id)}")
    fun movieUrl(id: String, ext: String) = "$base/movie/${enc(user)}/${enc(pass)}/$id.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(id: String, ext: String) = "$base/series/${enc(user)}/${enc(pass)}/$id.${ext.ifBlank { "mp4" }}"
    /** Catch-up (timeshift) stream for a live channel: [startMs] in UTC, [minutes] long. */
    fun catchupUrl(streamId: String, startMs: Long, minutes: Int): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return "$base/streaming/timeshift.php?username=${enc(user)}&password=${enc(pass)}&stream=${enc(streamId)}&start=${f.format(java.util.Date(startMs))}&duration=$minutes"
    }
    fun xmltvUrl() = "$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}"

    companion object {
        fun normalizeServer(s: String): String {
            var t = s.trim().trimEnd('/')
            if (!t.startsWith("http://", true) && !t.startsWith("https://", true)) t = "http://$t"
            return t.substringBefore("/player_api.php").substringBefore("/get.php").trimEnd('/')
        }
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

object XtreamParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseCategories(body: String): Map<String, String> {
        val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val id = o.str("category_id") ?: continue
            out[id] = o.str("category_name") ?: ""
        }
        return out
    }

    fun parseStreams(body: String, account: XtreamAccount, categories: Map<String, String> = emptyMap()): ParsedPlaylist {
        val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return ParsedPlaylist(emptyList(), skipped = 1)
        val ch = ArrayList<Channel>(); var skipped = 0
        for (e in arr) {
            val o = e as? JsonObject
            val sid = o?.str("stream_id")
            if (o == null || sid == null) { skipped++; continue }
            ch += Channel(
                id = sid, name = o.str("name") ?: sid, url = account.streamUrl(sid),
                group = o.str("category_id")?.let { categories[it] } ?: "",
                logo = o.str("stream_icon")?.ifBlank { null },
                epgId = o.str("epg_channel_id")?.ifBlank { null },
                number = o.str("num")?.toIntOrNull(),
                streamId = sid,
                archiveDays = if (o.str("tv_archive") == "1") (o.str("tv_archive_duration")?.toIntOrNull() ?: 0) else 0,
            )
        }
        return ParsedPlaylist(ch, listOf(account.xmltvUrl()), skipped)
    }

    fun parseMovies(body: String, account: XtreamAccount, categories: Map<String, String> = emptyMap()): List<Movie> =
        array(body).mapNotNull { o ->
            val id = o.str("stream_id") ?: return@mapNotNull null
            Movie(id, o.str("name") ?: id, account.movieUrl(id, o.str("container_extension") ?: ""),
                o.str("category_id")?.let { categories[it] } ?: "", o.str("stream_icon")?.ifBlank { null },
                o.str("rating")?.ifBlank { null }, null, o.str("added")?.toLongOrNull() ?: 0)
        }

    fun parseSeriesList(body: String, categories: Map<String, String> = emptyMap()): List<Series> =
        array(body).mapNotNull { o ->
            val id = o.str("series_id") ?: return@mapNotNull null
            Series(id, o.str("name") ?: id, o.str("category_id")?.let { categories[it] } ?: "", o.str("cover")?.ifBlank { null },
                o.str("plot") ?: "", o.str("rating")?.ifBlank { null }, o.str("releaseDate")?.take(4)?.ifBlank { null }, o.str("last_modified")?.toLongOrNull() ?: 0)
        }

    fun parseVodInfo(body: String): VodInfo {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return VodInfo()
        val i = root["info"] as? JsonObject ?: return VodInfo()
        return VodInfo(i.str("plot") ?: i.str("description") ?: "", i.str("cast") ?: i.str("actors") ?: "", i.str("genre") ?: "", i.str("director") ?: "",
            i.str("duration_secs")?.toIntOrNull() ?: 0, (i["backdrop_path"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content })
    }

    /** Episodes of a series, sorted by season then number. */
    fun parseEpisodes(body: String, account: XtreamAccount): List<Episode> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val eps = root["episodes"]
        val groups: List<Pair<String?, JsonElement>> = when (eps) {
            is JsonObject -> eps.entries.map { it.key to it.value }
            is JsonArray -> eps.map { null to it }   // some servers return a list of season arrays
            else -> return emptyList()
        }
        val out = ArrayList<Episode>()
        for ((seasonKey, v) in groups) for (e in (v as? JsonArray).orEmpty()) {
            val o = e as? JsonObject ?: continue
            val id = o.str("id") ?: continue
            val info = o["info"] as? JsonObject
            out += Episode(id, o.str("season")?.toIntOrNull() ?: seasonKey?.toIntOrNull() ?: 1, o.str("episode_num")?.toIntOrNull() ?: out.size + 1,
                o.str("title") ?: "S${seasonKey ?: 1}E${o.str("episode_num") ?: ""}", account.episodeUrl(id, o.str("container_extension") ?: ""),
                info?.str("plot") ?: "", info?.str("movie_image")?.ifBlank { null }, info?.str("duration_secs")?.toIntOrNull() ?: 0)
        }
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }

    private fun array(body: String): List<JsonObject> =
        runCatching { json.parseToJsonElement(body).jsonArray.mapNotNull { it as? JsonObject } }.getOrDefault(emptyList())

    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
