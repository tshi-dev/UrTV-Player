package com.urtv.player.core

/** One playable channel. No channel ships with the app: all come from the user's own playlists. */
data class Channel(
    val id: String,          // stable within a playlist (tvg-id, stream id or url)
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String? = null,
    val epgId: String? = null,
    val number: Int? = null,
    /** Xtream stream id (for catch-up URLs); null for plain M3U. */
    val streamId: String? = null,
    /** Days of catch-up the provider keeps (0 = none). */
    val archiveDays: Int = 0,
)

data class ParsedPlaylist(
    val channels: List<Channel>,
    val epgUrls: List<String> = emptyList(),
    val skipped: Int = 0,
)

data class Programme(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String = "",
)

/** A movie (VOD) entry from an Xtream provider. Metadata comes from the provider only. */
data class Movie(
    val id: String, val name: String, val url: String, val category: String = "",
    val poster: String? = null, val rating: String? = null, val year: String? = null, val added: Long = 0,
)

data class Series(
    val id: String, val name: String, val category: String = "", val poster: String? = null,
    val plot: String = "", val rating: String? = null, val year: String? = null, val added: Long = 0,
)

data class Episode(val id: String, val season: Int, val number: Int, val title: String, val url: String, val plot: String = "", val image: String? = null, val durationSec: Int = 0)

data class VodInfo(val plot: String = "", val cast: String = "", val genre: String = "", val director: String = "", val durationSec: Int = 0, val backdrop: String? = null)
