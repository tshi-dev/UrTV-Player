package com.urtv.player.data

import kotlinx.serialization.Serializable

/** A user-added source. Nothing is built in: every playlist here was typed or sent by the user. */
@Serializable
data class PlaylistDef(
    val id: String,
    val name: String,
    val kind: String,            // "m3u" or "xtream"
    val url: String = "",
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val epgUrl: String = "",
) {
    val isXtream get() = kind == KIND_XTREAM
    companion object { const val KIND_M3U = "m3u"; const val KIND_XTREAM = "xtream" }
}

enum class BufferMode(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufMs: Int) {
    Low(2000, 8000, 1000, 2000),
    Normal(5000, 20000, 1500, 3000),
    High(15000, 50000, 2500, 5000),
}

enum class AspectMode { Fit, Fill, Stretch, R16x9, R4x3 }

enum class LoadError { Network, Format, Empty }
