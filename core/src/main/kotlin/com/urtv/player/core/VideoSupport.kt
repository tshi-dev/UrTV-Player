package com.urtv.player.core

/**
 * «Sound but no picture»: a stream can carry a video track the TV has no decoder for (HEVC 2160p, 10-bit, some 50/60 fps profiles).
 * The player then silently plays the sound only. These rules turn that into a plain message instead.
 */
object VideoSupport {
    data class Track(val mime: String?, val width: Int, val height: Int, val selected: Boolean)

    /** The video track that cannot be shown (the biggest one), or null when a video track plays or the stream has no video at all. */
    fun blocked(videoTracks: List<Track>): Track? =
        if (videoTracks.isNotEmpty() && videoTracks.none { it.selected }) videoTracks.maxByOrNull { it.width.toLong() * it.height } else null

    /** «4K HEVC», «1080p H.264», … (empty parts are left out). */
    fun label(mime: String?, width: Int, height: Int): String {
        val res = when {
            height >= 2000 || width >= 3800 -> "4K"
            height >= 1400 -> "1440p"
            height >= 1000 -> "1080p"
            height >= 700 -> "720p"
            height > 0 -> "${height}p"
            else -> ""
        }
        val codec = when (mime?.removePrefix("video/")?.lowercase()) {
            "hevc" -> "HEVC"; "avc" -> "H.264"; "av01" -> "AV1"; "x-vnd.on2.vp9" -> "VP9"; "mpeg2" -> "MPEG-2"; "dolby-vision" -> "Dolby Vision"
            null, "" -> ""; else -> mime.removePrefix("video/")
        }
        return listOf(res, codec).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** One short token for the problem report: letters, digits and ._- only. */
    fun reportToken(mime: String?, width: Int, height: Int, decoders: Int): String =
        ("no_picture_" + label(mime, width, height).replace(' ', '_') + "_${width}x${height}_decoders$decoders").filter { it.code < 128 && (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') }.take(60)
}
