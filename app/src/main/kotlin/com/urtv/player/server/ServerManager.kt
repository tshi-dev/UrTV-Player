package com.urtv.player.server

import android.content.Context
import android.media.MediaCodecList
import com.urtv.player.data.Secrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * The user's media servers. Tokens live only in `servers.json`, written through [Secrets] (AES key in the Android
 * Keystore), and are never logged. Non-secret options sit in a normal preferences file.
 */
class ServerManager(ctx: Context) {
    private val sp = ctx.getSharedPreferences("urtv_server", Context.MODE_PRIVATE)
    private val file = File(ctx.filesDir, "servers.json")

    private val _servers = MutableStateFlow(read())
    val servers: StateFlow<List<ServerConfig>> = _servers
    private val _active = MutableStateFlow(sp.getString("active", null))
    val activeId: StateFlow<String?> = _active
    private val _homeOnly = MutableStateFlow(sp.getBoolean("homeOnly", false))
    /** «وضع خادم المنزل فقط»: IPTV is hidden everywhere and the app is only a media-server client. */
    val homeOnly: StateFlow<Boolean> = _homeOnly

    private val _style = MutableStateFlow(readStyle())
    val subtitleStyle: StateFlow<SubtitleStyle> = _style
    private val _mode = MutableStateFlow(SubtitleMode.entries.firstOrNull { it.name == sp.getString("subMode", null) } ?: SubtitleMode.Auto)
    val subtitleMode: StateFlow<SubtitleMode> = _mode
    private val _prefs = MutableStateFlow(LangPrefs(
        (sp.getString("audioLangs", "ar,en") ?: "ar,en").split(',').filter { it.length in 2..3 },
        (sp.getString("subLangs", "ar,en") ?: "ar,en").split(',').filter { it.length in 2..3 }))
    val langPrefs: StateFlow<LangPrefs> = _prefs

    val deviceId: String = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().replace("-", "").also { sp.edit().putString("deviceId", it).apply() }

    val active: ServerConfig? get() = _servers.value.firstOrNull { it.id == _active.value } ?: _servers.value.firstOrNull()

    // ---- persistence
    private fun read(): List<ServerConfig> {
        val o = parseJson(Secrets.readText(file)) as? JsonArray ?: return emptyList()
        return o.mapNotNull { e ->
            val j = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (j[k] as? JsonPrimitive)?.content
            val kind = ServerKind.entries.firstOrNull { it.name == s("kind") } ?: return@mapNotNull null
            val base = s("baseUrl")?.let(NetSafety::normalizeBase) ?: return@mapNotNull null   // re-validated on every load
            ServerConfig(s("id") ?: return@mapNotNull null, kind, s("name") ?: "Server", base, s("userId") ?: "", s("userName") ?: "", s("token") ?: return@mapNotNull null, s("pinnedCert"))
        }
    }
    private fun parseJson(s: String?) = s?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull() }

    private fun write(l: List<ServerConfig>) {
        val arr = buildJsonArray { l.forEach { c -> add(buildJsonObject {
            put("id", c.id); put("kind", c.kind.name); put("name", c.name); put("baseUrl", c.baseUrl); put("userId", c.userId); put("userName", c.userName); put("token", c.token)
            c.pinnedCert?.let { put("pinnedCert", it) }
        }) } }
        Secrets.writeText(file, arr.toString())
        _servers.value = l
    }

    fun add(c: ServerConfig) { write(_servers.value.filter { it.id != c.id } + c); setActive(c.id) }
    fun remove(id: String) { write(_servers.value.filter { it.id != id }); if (_active.value == id) setActive(_servers.value.firstOrNull()?.id) }
    fun setActive(id: String?) { _active.value = id; sp.edit().putString("active", id).apply() }
    fun newId() = UUID.randomUUID().toString().take(12)

    fun setHomeOnly(v: Boolean) { _homeOnly.value = v; sp.edit().putBoolean("homeOnly", v).apply() }

    // ---- subtitles
    private fun readStyle() = SubtitleStyle(
        size = sp.getFloat("subSize", 0.058f), color = sp.getLong("subColor", 0xFFFFFFFF), background = sp.getLong("subBg", 0x00000000),
        edge = Edge.entries.firstOrNull { it.name == sp.getString("subEdge", null) } ?: Edge.Outline, edgeColor = sp.getLong("subEdgeColor", 0xFF000000),
        font = sp.getString("subFont", "sans") ?: "sans", bold = sp.getBoolean("subBold", false), bottom = sp.getFloat("subBottom", 0.07f)).coerced()
    fun setSubtitleStyle(s: SubtitleStyle) {
        val c = s.coerced(); _style.value = c
        sp.edit().putFloat("subSize", c.size).putLong("subColor", c.color).putLong("subBg", c.background).putString("subEdge", c.edge.name)
            .putLong("subEdgeColor", c.edgeColor).putString("subFont", c.font).putBoolean("subBold", c.bold).putFloat("subBottom", c.bottom).apply()
    }
    fun setSubtitleMode(m: SubtitleMode) { _mode.value = m; sp.edit().putString("subMode", m.name).apply() }
    fun setLangPrefs(p: LangPrefs) { _prefs.value = p; sp.edit().putString("audioLangs", p.audio.joinToString(",")).putString("subLangs", p.subtitle.joinToString(",")).apply() }
    /** Per-item sync offset (ms); remembered so a late subtitle file is fixed once. */
    fun subOffset(itemKey: String): Long = sp.getLong("off_$itemKey", 0L)
    fun setSubOffset(itemKey: String, ms: Long) { sp.edit().apply { if (ms == 0L) remove("off_$itemKey") else putLong("off_$itemKey", ms) }.apply() }

    // ---- backends
    fun transport(pinned: String? = null): Transport = JavaTransport(pinned)
    fun backend(c: ServerConfig): MediaBackend = when (c.kind) {
        ServerKind.Plex -> PlexBackend(c, transport(c.pinnedCert), deviceId)
        else -> JellyfinBackend(c, transport(c.pinnedCert), deviceId)
    }

    /** What this TV can decode, from MediaCodecList. Used to choose direct play vs server transcoding. */
    val caps: DeviceCaps by lazy { detectCaps() }

    private fun detectCaps(): DeviceCaps {
        val video = HashSet<String>(); val audio = HashSet<String>(setOf("aac", "mp3", "opus", "flac", "vorbis"))
        var maxH = 1080; var hdr = false
        runCatching {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                for (t in info.supportedTypes) when (t.lowercase()) {
                    "video/avc" -> video += "h264"
                    "video/hevc" -> { video += "hevc"; hdr = hdr || info.getCapabilitiesForType(t).colorFormats.isNotEmpty() && info.getCapabilitiesForType(t).profileLevels.any { it.profile == 0x1000 /* Main10 */ || it.profile == 2 } }
                    "video/av01" -> video += "av1"
                    "video/x-vnd.on2.vp9" -> video += "vp9"
                    "audio/ac3" -> audio += "ac3"
                    "audio/eac3" -> audio += "eac3"
                    "audio/vnd.dts", "audio/vnd.dts.hd" -> audio += "dts"
                    "audio/true-hd" -> audio += "truehd"
                }
                if (info.supportedTypes.any { it.equals("video/hevc", true) || it.equals("video/avc", true) }) runCatching {
                    val t = info.supportedTypes.first { it.startsWith("video/") }
                    val v = info.getCapabilitiesForType(t).videoCapabilities
                    if (v.isSizeSupported(3840, 2160)) maxH = 2160
                }
            }
        }
        if (video.isEmpty()) video += "h264"
        return DeviceCaps(video, audio + "ac3", setOf("mp4", "mkv", "ts"), maxH, hdr, 80_000_000)
    }
}
