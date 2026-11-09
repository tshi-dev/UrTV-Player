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
            )
        }
        return ParsedPlaylist(ch, listOf(account.xmltvUrl()), skipped)
    }

    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
