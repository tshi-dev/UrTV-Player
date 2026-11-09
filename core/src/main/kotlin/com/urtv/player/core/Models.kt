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
