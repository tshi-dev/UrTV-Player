package com.urtv.player.server

enum class ServerKind { Jellyfin, Emby, Plex }
enum class ItemType { Movie, Series, Season, Episode, BoxSet, Other }
enum class StreamType { Video, Audio, Subtitle }
enum class Img { Poster, Backdrop, Logo, Thumb }
enum class Sort { Name, Added, Year, Rating }

data class Library(val id: String, val name: String, val kind: LibraryKind)
enum class LibraryKind { Movies, Shows, Collections, Other }

data class Person(val name: String, val role: String?, val kind: String?)

data class StreamInfo(
    val index: Int,
    /** Plex stream id (needed to select/fetch it); null for Jellyfin. */
    val id: String? = null,
    val type: StreamType,
    val codec: String? = null,
    val language: String? = null,        // ISO 639-2 or -1, as the server reports it
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isHearingImpaired: Boolean = false,
    val isExternal: Boolean = false,
    val channels: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoRange: String? = null,      // SDR, HDR10, HLG, DolbyVision …
    val profile: String? = null,
    /** Server-relative URL of an external/sidecar subtitle (no token inside). */
    val deliveryPath: String? = null,
)

data class Marker(val kind: MarkerKind, val startMs: Long, val endMs: Long)
enum class MarkerKind { Intro, Credits }

data class MediaItem(
    val id: String,
    val type: ItemType,
    val name: String,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val year: Int? = null,
    val overview: String = "",
    val tagline: String = "",
    val officialRating: String? = null,
    val rating: Double? = null,           // 0..10
    val runtimeMs: Long = 0,
    val resumeMs: Long = 0,
    val played: Boolean = false,
    val genres: List<String> = emptyList(),
    val people: List<Person> = emptyList(),
    val streams: List<StreamInfo> = emptyList(),
    val container: String? = null,
    val childCount: Int = 0,
    val unplayed: Int = 0,
    /** Image paths keyed by [Img]; relative to the server, no token. */
    val images: Map<Img, String> = emptyMap(),
    val trailers: List<String> = emptyList(),   // only local (server-hosted) trailer ids, never external links
    val addedMs: Long = 0,
    /** Plex: server path of the media file (direct play) and its bitrate. */
    val partPath: String? = null,
    val bitrate: Long? = null,
) {
    val progress: Float get() = if (runtimeMs > 0 && resumeMs > 0) (resumeMs.toFloat() / runtimeMs).coerceIn(0f, 1f) else 0f
    val video: StreamInfo? get() = streams.firstOrNull { it.type == StreamType.Video }
    val audio: List<StreamInfo> get() = streams.filter { it.type == StreamType.Audio }
    val subtitles: List<StreamInfo> get() = streams.filter { it.type == StreamType.Subtitle }
}

data class Page<T>(val items: List<T>, val total: Int)

data class Query(
    val type: ItemType? = null,
    val sort: Sort = Sort.Name,
    val descending: Boolean = false,
    val genre: String? = null,
    val unplayedOnly: Boolean = false,
    val start: Int = 0,
    val limit: Int = 60,
)

/** One server the user added. [token] is only ever persisted through the Keystore-backed vault. */
data class ServerConfig(
    val id: String,
    val kind: ServerKind,
    val name: String,
    val baseUrl: String,
    val userId: String,
    val userName: String,
    val token: String,
    /** SHA-256 of the one certificate the user explicitly approved after an error, or null. */
    val pinnedCert: String? = null,
) {
    override fun toString() = "ServerConfig($kind, $name, $baseUrl, user=$userName, token=***)"
}

class ServerException(val reason: Reason, message: String, val fingerprint: String? = null) : Exception(Redact.text(message)) {
    enum class Reason { Network, Auth, Certificate, Http, Parse, Refused }
}

data class Req(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: String? = null) {
    override fun toString() = "Req($method ${Redact.url(url)})"
}
data class Resp(val code: Int, val body: String)

/** Blocking HTTP seam: the app supplies the real one, tests supply canned responses. */
fun interface Transport { fun send(req: Req): Resp }
