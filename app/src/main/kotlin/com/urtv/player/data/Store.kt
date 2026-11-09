package com.urtv.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Local persistence: playlists (JSON file) and small preferences. Nothing leaves the device. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("urtv", Context.MODE_PRIVATE)
    private val file = File(ctx.filesDir, "playlists.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _playlists = MutableStateFlow(readPlaylists())
    val playlists: StateFlow<List<PlaylistDef>> = _playlists
    private val _favs = MutableStateFlow(sp.getStringSet("favs", emptySet())!!.toSet())
    val favs: StateFlow<Set<String>> = _favs
    private val _recents = MutableStateFlow(sp.getString("recents", "")!!.split('\n').filter { it.isNotEmpty() })
    val recents: StateFlow<List<String>> = _recents
    private val _locked = MutableStateFlow(sp.getStringSet("locked", emptySet())!!.toSet())
    val lockedGroups: StateFlow<Set<String>> = _locked
    private val _active = MutableStateFlow(sp.getString("active", null))
    val activeId: StateFlow<String?> = _active
    private val _lang = MutableStateFlow(sp.getString("lang", "ar")!!)
    val lang: StateFlow<String> = _lang
    private val _refreshH = MutableStateFlow(sp.getInt("refreshH", 24))
    val refreshHours: StateFlow<Int> = _refreshH
    private val _buffer = MutableStateFlow(BufferMode.entries.firstOrNull { it.name == sp.getString("buffer", "Normal") } ?: BufferMode.Normal)
    val buffer: StateFlow<BufferMode> = _buffer
    private val _resume = MutableStateFlow(sp.getBoolean("resume", true))
    val resumeLast: StateFlow<Boolean> = _resume
    private val _hasPin = MutableStateFlow(sp.getString("pinHash", null) != null)
    val hasPin: StateFlow<Boolean> = _hasPin

    // ---- playlists
    private fun readPlaylists(): List<PlaylistDef> = runCatching {
        if (file.exists()) json.decodeFromString(ListSerializer(PlaylistDef.serializer()), file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun writePlaylists(l: List<PlaylistDef>) {
        val tmp = File(file.parentFile, "playlists.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(PlaylistDef.serializer()), l)); tmp.renameTo(file)
        _playlists.value = l
    }

    fun addPlaylist(p: PlaylistDef) { writePlaylists(_playlists.value + p); setActive(p.id) }
    fun removePlaylist(id: String) {
        writePlaylists(_playlists.value.filter { it.id != id })
        if (_active.value == id) setActive(_playlists.value.firstOrNull()?.id)
        _favs.value.filter { it.substringBefore('|') == id }.forEach { toggleFav(it) }
        _locked.value = _locked.value.filterNot { it.substringBefore('|') == id }.toSet(); sp.edit().putStringSet("locked", _locked.value).apply()
    }
    fun setActive(id: String?) { _active.value = id; sp.edit().putString("active", id).apply() }
    fun newId() = java.lang.Long.toString(System.nanoTime(), 36)

    // ---- favourites / recents / last channel. Keys are "playlistId|channelUrl".
    fun toggleFav(key: String) {
        val s = _favs.value.toMutableSet(); if (!s.add(key)) s.remove(key)
        _favs.value = s; sp.edit().putStringSet("favs", s).apply()
    }
    fun pushRecent(key: String) {
        val l = (listOf(key) + _recents.value.filter { it != key }).take(30)
        _recents.value = l; sp.edit().putString("recents", l.joinToString("\n")).putString("last", key).apply()
    }
    fun lastKey(): String? = sp.getString("last", null)

    // ---- playback positions for movies/episodes/catch-up ("resume where you stopped")
    fun position(key: String): Long = sp.getLong("pos_$key", 0L)
    fun savePosition(key: String, ms: Long, durationMs: Long) {
        val e = sp.edit()
        if (ms < 5_000 || (durationMs > 0 && ms > durationMs * 0.95)) e.remove("pos_$key") else e.putLong("pos_$key", ms)
        e.apply()
    }

    // ---- per-playlist: EPG time offset, hidden groups, group order
    private val _hidden = MutableStateFlow(sp.getStringSet("hidden", emptySet())!!.toSet())
    val hiddenGroups: StateFlow<Set<String>> = _hidden
    private val _order = MutableStateFlow(sp.all.filterKeys { it.startsWith("gorder_") }.mapKeys { it.key.removePrefix("gorder_") }.mapValues { (it.value as String).split('\n').filter { s -> s.isNotEmpty() } })
    val groupOrder: StateFlow<Map<String, List<String>>> = _order
    private val _offsets = MutableStateFlow(sp.all.filterKeys { it.startsWith("epgoff_") }.mapKeys { it.key.removePrefix("epgoff_") }.mapValues { (it.value as Int) })
    val epgOffsetMin: StateFlow<Map<String, Int>> = _offsets
    fun toggleHiddenGroup(key: String) { val s = _hidden.value.toMutableSet(); if (!s.add(key)) s.remove(key); _hidden.value = s; sp.edit().putStringSet("hidden", s).apply() }
    fun setGroupOrder(pid: String, l: List<String>) { _order.value = _order.value + (pid to l); sp.edit().putString("gorder_$pid", l.joinToString("\n")).apply() }
    fun setEpgOffset(pid: String, min: Int) { _offsets.value = _offsets.value + (pid to min); sp.edit().putInt("epgoff_$pid", min).apply() }

    // ---- appearance
    private val _textScale = MutableStateFlow(sp.getInt("textScale", 0))
    val textScale: StateFlow<Int> = _textScale
    private val _oled = MutableStateFlow(sp.getBoolean("oled", false))
    val oled: StateFlow<Boolean> = _oled
    fun setTextScale(v: Int) { _textScale.value = v; sp.edit().putInt("textScale", v).apply() }
    fun setOled(v: Boolean) { _oled.value = v; sp.edit().putBoolean("oled", v).apply() }

    // ---- continue watching (movies and episodes), newest first, capped
    private val wFile = File(ctx.filesDir, "watching.json")
    private val _watching = MutableStateFlow(runCatching { if (wFile.exists()) json.decodeFromString(ListSerializer(WatchItem.serializer()), wFile.readText()) else emptyList() }.getOrDefault(emptyList()))
    val watching: StateFlow<List<WatchItem>> = _watching
    private fun writeWatching(l: List<WatchItem>) {
        _watching.value = l
        runCatching { val t = File(wFile.parentFile, "watching.json.tmp"); t.writeText(json.encodeToString(ListSerializer(WatchItem.serializer()), l)); t.renameTo(wFile) }
    }
    /** Called while playing. At 95 % the item counts as watched and leaves the row. */
    fun updateWatching(item: WatchItem) {
        val done = item.durMs > 0 && item.posMs > item.durMs * 0.95
        val rest = _watching.value.filter { it.key != item.key }
        writeWatching(if (done || item.posMs < 5_000) rest else (listOf(item) + rest).take(24))
    }
    fun removeWatching(key: String) { writeWatching(_watching.value.filter { it.key != key }); savePosition(key, 0, 0) }
    /** Queue an unstarted episode so the series shows up again in the row. */
    fun offerNext(item: WatchItem) { if (_watching.value.none { it.key == item.key }) writeWatching((listOf(item) + _watching.value).take(24)) }

    // ---- settings
    fun setLang(v: String) { _lang.value = v; sp.edit().putString("lang", v).apply() }
    fun setRefreshHours(v: Int) { _refreshH.value = v; sp.edit().putInt("refreshH", v).apply() }
    fun setBuffer(v: BufferMode) { _buffer.value = v; sp.edit().putString("buffer", v.name).apply() }
    fun setResume(v: Boolean) { _resume.value = v; sp.edit().putBoolean("resume", v).apply() }
    fun langNow(): String = sp.getString("lang", "ar")!!

    // ---- parental PIN (salted SHA-256; the PIN itself is never stored)
    private fun hash(pin: String, salt: String) =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun setPin(pin: String?) {
        val e = sp.edit()
        if (pin == null) { e.remove("pinHash").remove("pinSalt") } else {
            val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            e.putString("pinSalt", salt).putString("pinHash", hash(pin, salt))
        }
        e.apply(); _hasPin.value = pin != null
        if (pin == null) { _locked.value = emptySet(); sp.edit().remove("locked").apply() }
    }
    fun checkPin(pin: String): Boolean {
        val h = sp.getString("pinHash", null) ?: return true
        return MessageDigest.isEqual(h.toByteArray(), hash(pin, sp.getString("pinSalt", "")!!).toByteArray())
    }
    fun toggleLockedGroup(key: String) {
        val s = _locked.value.toMutableSet(); if (!s.add(key)) s.remove(key)
        _locked.value = s; sp.edit().putStringSet("locked", s).apply()
    }
}
