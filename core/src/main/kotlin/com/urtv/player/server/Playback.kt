package com.urtv.player.server

/** What this TV can decode, filled in by the app from MediaCodecList. Names are normalised (h264, hevc, av1, aac, eac3…). */
data class DeviceCaps(
    val videoCodecs: Set<String> = setOf("h264"),
    val audioCodecs: Set<String> = setOf("aac", "mp3", "ac3"),
    val containers: Set<String> = setOf("mp4", "mkv", "ts"),
    val maxHeight: Int = 1080,
    val hdr: Boolean = false,
    val maxBitrate: Long = 40_000_000,
)

enum class PlayMode { DirectPlay, DirectStream, Transcode }

data class Decision(val mode: PlayMode, val reason: String)

object Codecs {
    fun video(c: String?): String = when (c?.lowercase()?.replace("-", "")) { "h265", "hevc" -> "hevc"; "avc", "h264", "avc1" -> "h264"; null -> ""; else -> c.lowercase() }
    fun audio(c: String?): String = when (c?.lowercase()?.replace("-", "")) { "eac3", "ec3", "ddp" -> "eac3"; "ac3", "dolby" -> "ac3"; "dca", "dts" -> "dts"; "truehd", "mlp" -> "truehd"; null -> ""; else -> c.lowercase() }
    fun container(c: String?): String = c?.lowercase()?.split(',')?.firstOrNull()?.trim().let { when (it) { "matroska", "webm", "mkv" -> "mkv"; "mov", "m4v", "mp4", "isom" -> "mp4"; "mpegts", "ts", "m2ts" -> "ts"; null -> ""; else -> it } }
}

/**
 * Direct play first. If only the audio is unsupported the server can remux and transcode just the audio (DirectStream);
 * if the video, container, resolution, bitrate or a subtitle burn-in demands it, full transcoding.
 */
object PlaybackDecision {
    fun decide(container: String?, video: StreamInfo?, audio: StreamInfo?, bitrate: Long?, burnSubtitle: Boolean, caps: DeviceCaps): Decision {
        if (burnSubtitle) return Decision(PlayMode.Transcode, "subtitle must be burned in")
        if (video == null) return Decision(PlayMode.Transcode, "no video stream")
        val vc = Codecs.video(video.codec)
        if (vc !in caps.videoCodecs) return Decision(PlayMode.Transcode, "video codec $vc not supported")
        if ((video.height ?: 0) > caps.maxHeight) return Decision(PlayMode.Transcode, "resolution above device limit")
        if (!caps.hdr && video.videoRange?.let { it.isNotBlank() && !it.equals("SDR", true) } == true) return Decision(PlayMode.Transcode, "HDR not supported")
        if (bitrate != null && bitrate > caps.maxBitrate) return Decision(PlayMode.Transcode, "bitrate above limit")
        val cont = Codecs.container(container)
        val ac = Codecs.audio(audio?.codec)
        val audioOk = audio == null || ac in caps.audioCodecs
        val contOk = cont in caps.containers
        return when {
            contOk && audioOk -> Decision(PlayMode.DirectPlay, "direct play")
            !audioOk -> Decision(PlayMode.DirectStream, "audio codec $ac not supported")
            else -> Decision(PlayMode.DirectStream, "container $cont not supported")
        }
    }
}

data class PlayOptions(
    val startMs: Long = 0,
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,     // index of a subtitle to BURN IN (null = none)
    val maxBitrate: Long? = null,
)

/** Where and how to play. URLs never contain the access token: the app sends it as a header ([headers]). */
data class PlaybackPlan(
    val itemId: String,
    val sourceId: String,
    val url: String,
    val mode: PlayMode,
    val reason: String,
    val playSessionId: String?,
    val hls: Boolean,
    val streams: List<StreamInfo>,
    val container: String?,
    val runtimeMs: Long,
    val headers: Map<String, String>,
    val ratingKey: String? = null,      // Plex: needed for timeline updates
    val audioIndex: Int? = null,        // the audio stream the server was told to use (burn-in / transcode)
)

data class RemoteSubtitle(val id: String, val name: String, val language: String?, val provider: String?, val format: String?, val downloads: Int?, val rating: Double?, val forced: Boolean = false, val hearingImpaired: Boolean = false)

interface MediaBackend {
    val kind: ServerKind
    val config: ServerConfig
    fun libraries(): List<Library>
    fun continueWatching(limit: Int = 20): List<MediaItem>
    fun nextUp(limit: Int = 20): List<MediaItem>
    fun latest(lib: Library, limit: Int = 20): List<MediaItem>
    fun browse(lib: Library, q: Query): Page<MediaItem>
    fun genres(lib: Library): List<String>
    fun item(id: String): MediaItem?
    fun seasons(seriesId: String): List<MediaItem>
    fun episodes(seriesId: String, seasonId: String): List<MediaItem>
    fun children(id: String): List<MediaItem>          // box set members
    fun similar(id: String, limit: Int = 12): List<MediaItem>
    fun search(text: String, limit: Int = 40): List<MediaItem>
    fun imageUrl(item: MediaItem, kind: Img, width: Int): String?
    /** Headers to attach to every request to this server (media, images, subtitles). Contains the token. */
    fun authHeaders(): Map<String, String>
    fun playback(item: MediaItem, caps: DeviceCaps, opts: PlayOptions): PlaybackPlan
    fun markers(item: MediaItem): List<Marker>
    fun subtitleUrl(plan: PlaybackPlan, s: StreamInfo): String?
    fun reportStart(plan: PlaybackPlan, positionMs: Long)
    fun reportProgress(plan: PlaybackPlan, positionMs: Long, paused: Boolean)
    fun reportStop(plan: PlaybackPlan, positionMs: Long)
    fun setPlayed(item: MediaItem, played: Boolean)
    fun searchSubtitles(item: MediaItem, language: String): List<RemoteSubtitle>
    fun downloadSubtitle(item: MediaItem, sub: RemoteSubtitle)
}

internal fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
