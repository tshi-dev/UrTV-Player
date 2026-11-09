package com.urtv.player.server

/** Small technical labels for a movie/episode page: resolution, dynamic range, audio. Derived only from server-reported streams. */
object Badges {
    fun of(item: MediaItem): List<String> {
        val out = ArrayList<String>()
        val v = item.video
        if (v != null) {
            val h = v.height ?: 0; val w = v.width ?: 0
            when { h >= 2000 || w >= 3600 -> out += "4K"; h >= 1000 || w >= 1800 -> out += "1080p"; h >= 700 || w >= 1200 -> out += "720p" }
            val r = v.videoRange?.lowercase() ?: ""
            when { r.contains("dolby") || r.startsWith("dovi") -> out += "Dolby Vision"; r.contains("hdr10+") || r.contains("hdr10plus") -> out += "HDR10+"; r.contains("hdr") -> out += "HDR10"; r.contains("hlg") -> out += "HLG" }
            when (Codecs.video(v.codec)) { "hevc" -> out += "HEVC"; "av1" -> out += "AV1" }
        }
        val a = item.audio.maxByOrNull { (it.channels ?: 0) + if (it.isDefault) 1 else 0 }
        if (a != null) {
            val atmos = a.title?.contains("atmos", true) == true
            when (Codecs.audio(a.codec)) { "eac3" -> out += if (atmos) "Dolby Atmos" else "Dolby Digital+"; "ac3" -> out += "Dolby Digital"; "truehd" -> out += if (atmos) "Dolby Atmos" else "Dolby TrueHD"; "dts" -> out += "DTS" }
            when (a.channels) { 6 -> out += "5.1"; 8 -> out += "7.1" }
        }
        return out.distinct()
    }
}
