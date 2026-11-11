package com.urtv.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Local persistence: playlists (JSON file) and small preferences. Nothing leaves the device. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("urtv", Context.MODE_PRIVATE)
    private val file = File(ctx.filesDir, "playlists.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** False until the encrypted files (playlists, watch list) are read off the main thread; keeps process start light. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private val _playlists = MutableStateFlow<List<PlaylistDef>>(emptyList())
    val playlists: StateFlow<List<PlaylistDef>> = _playlists
    private val _favs = MutableStateFlow(sp.getStringSet("favs", emptySet())!!.toSet())
    val favs: StateFlow<Set<String>> = _favs
    private val _recents = MutableStateFlow(sp.getString("recents", "")!!.split('\n').filter { it.isNotEmpty() })
    val recents: StateFlow<List<String>> = _recents
    private val _locked = MutableStateFlow(sp.getStringSet("locked", emptySet())!!.toSet())
    val lockedGroups: StateFlow<Set<String>> = _locked
    private val _active = MutableStateFlow(sp.getString("active", null))
    val activeId: StateFlow<String?> = _active
    private val _lang = MutableStateFlow(sp.getString("lang", Language.AUTO)!!)
    val lang: StateFlow<String> = _lang
    private val _refreshH = MutableStateFlow(sp.getInt("refreshH", 24))
    val refreshHours: StateFlow<Int> = _refreshH
    private val _buffer = MutableStateFlow(BufferMode.entries.firstOrNull { it.name == sp.getString("buffer", "Normal") } ?: BufferMode.Normal)
    val buffer: StateFlow<BufferMode> = _buffer
    private val _resume = MutableStateFlow(sp.getBoolean("resume", true))
    val resumeLast: StateFlow<Boolean> = _resume
    private val _hasPin = MutableStateFlow(sp.getString("pinHash", null) != null)
    val hasPin: StateFlow<Boolean> = _hasPin

    /** Reads the encrypted files (Keystore + disk). Call from a background thread; safe to call once at start. */
    @Synchronized fun loadHeavy() {
        val pl = readPlaylists()
        val w = runCatching { Secrets.readText(wFile)?.let { json.decodeFromString(ListSerializer(WatchItem.serializer()), it) } ?: emptyList() }.getOrDefault(emptyList())
        _playlists.compareAndSet(emptyList(), pl)          // never overwrite something the user already added meanwhile
        _watching.compareAndSet(emptyList(), w)
        _ready.value = true
    }

    // ---- playlists
    private fun readPlaylists(): List<PlaylistDef> = runCatching {
        Secrets.readText(file)?.let { json.decodeFromString(ListSerializer(PlaylistDef.serializer()), it) } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun writePlaylists(l: List<PlaylistDef>) {
        Secrets.writeText(file, json.encodeToString(ListSerializer(PlaylistDef.serializer()), l))   // encrypted: holds credentials
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

    // ---- «find match»: the channel the user picked last time for a competition (key = normalised competition name)
    fun matchPick(comp: String): String? = if (comp.isEmpty()) null else sp.getString("mpick_${comp.take(60)}", null)
    fun setMatchPick(comp: String, channelKey: String) { if (comp.isNotEmpty()) sp.edit().putString("mpick_${comp.take(60)}", channelKey).apply() }

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
    private fun <E : Enum<E>> pref(key: String, all: Array<E>, def: E): E = all.firstOrNull { it.name == sp.getString(key, null) } ?: def
    private val _appearance = MutableStateFlow(Appearance(
        // "oled" is the old pure-black switch: keep it working for people who already turned it on.
        theme = pref("theme", ThemeId.entries.toTypedArray(), if (sp.getBoolean("oled", false)) ThemeId.Oled else ThemeId.Default),
        accent = pref("accent", AccentId.entries.toTypedArray(), AccentId.Blue),
        focus = pref("focusStyle", FocusStyle.entries.toTypedArray(), FocusStyle.Solid),
        corner = pref("cornerStyle", CornerStyle.entries.toTypedArray(), CornerStyle.Rounded),
        background = pref("bgStyle", BgStyle.entries.toTypedArray(), BgStyle.Gradient),
    ))
    val appearance: StateFlow<Appearance> = _appearance
    fun setAppearance(a: Appearance) {
        _appearance.value = a
        sp.edit().putString("theme", a.theme.name).putString("accent", a.accent.name).putString("focusStyle", a.focus.name)
            .putString("cornerStyle", a.corner.name).putString("bgStyle", a.background.name).apply()
    }
    fun setTextScale(v: Int) { _textScale.value = v; sp.edit().putInt("textScale", v).apply() }

    // ---- continue watching (movies and episodes), newest first, capped
    private val wFile = File(ctx.filesDir, "watching.json")
    private val _watching = MutableStateFlow<List<WatchItem>>(emptyList())
    val watching: StateFlow<List<WatchItem>> = _watching
    private fun writeWatching(l: List<WatchItem>) {
        _watching.value = l
        runCatching { Secrets.writeText(wFile, json.encodeToString(ListSerializer(WatchItem.serializer()), l)) }
    }
    /** Called while playing. At 95 % the item counts as watched and leaves the row. */
    fun updateWatching(item: WatchItem) {
        val done = item.durMs > 0 && item.posMs > item.durMs * 0.95
        if (done) markWatched(item.key) else if (item.posMs >= 5_000) markWatched(item.key, false)
        val rest = _watching.value.filter { it.key != item.key }
        writeWatching(if (done || item.posMs < 5_000) rest else (listOf(item) + rest).take(24))
    }
    fun removeWatching(key: String) { writeWatching(_watching.value.filter { it.key != key }); savePosition(key, 0, 0) }
    /** Queue an unstarted episode so the series shows up again in the row. */
    fun offerNext(item: WatchItem) { if (_watching.value.none { it.key == item.key }) writeWatching((listOf(item) + _watching.value).take(24)) }

    // ---- custom lists ("my lists"): named sets of channels per playlist. Keys: "playlistId|name" -> channel keys.
    private val _lists = MutableStateFlow(
        sp.getString("lists", "")!!.lines().filter { it.contains('\t') }.associate { l -> l.substringBefore('\t') to l.substringAfter('\t').split(',').filter { it.isNotEmpty() }.toSet() })
    val customLists: StateFlow<Map<String, Set<String>>> = _lists
    private fun saveListsMap(m: Map<String, Set<String>>) = saveLists(m)
    private fun saveLists(m: Map<String, Set<String>>) { _lists.value = m; sp.edit().putString("lists", m.entries.joinToString("\n") { it.key.replace('\t', ' ').replace('\n', ' ') + "\t" + it.value.joinToString(",") }).apply() }
    fun createList(pid: String, name: String): String {
        val clean = name.trim().replace('\t', ' ').replace('\n', ' ').take(40).ifBlank { "List" }
        saveLists(_lists.value + ("$pid|$clean" to (_lists.value["$pid|$clean"] ?: emptySet()))); return clean
    }
    fun toggleInList(pid: String, name: String, chKey: String) {
        val k = "$pid|$name"; val cur = _lists.value[k] ?: emptySet()
        saveLists(_lists.value + (k to if (chKey in cur) cur - chKey else cur + chKey))
    }
    fun deleteList(pid: String, name: String) = saveLists(_lists.value - "$pid|$name")
    fun listNames(pid: String): List<String> = _lists.value.keys.filter { it.startsWith("$pid|") }.map { it.substringAfter('|') }

    // ---- in-app programme reminders (no background service, so they fire while the app is open)
    data class Reminder(val key: String, val startMs: Long, val title: String, val channel: String)
    private val _reminders = MutableStateFlow(sp.getString("reminders", "")!!.lines().mapNotNull { l ->
        val p = l.split('\t'); if (p.size == 4) p[1].toLongOrNull()?.let { Reminder(p[0], it, p[2], p[3]) } else null })
    val reminders: StateFlow<List<Reminder>> = _reminders
    private fun saveReminders(l: List<Reminder>) { _reminders.value = l; sp.edit().putString("reminders", l.joinToString("\n") { listOf(it.key, it.startMs, it.title, it.channel).joinToString("\t") { x -> x.toString().replace('\t', ' ').replace('\n', ' ') } }).apply() }
    fun toggleReminder(r: Reminder) { val cur = _reminders.value; saveReminders(if (cur.any { it.key == r.key }) cur.filter { it.key != r.key } else (cur + r).sortedBy { it.startMs }.take(100)) }
    /** Reminders that are due (start within the last minute or the next minute); they are removed so each fires once. */
    fun takeDueReminders(now: Long): List<Reminder> {
        val due = _reminders.value.filter { it.startMs - now <= 60_000 && it.startMs - now >= -60_000 }
        val stale = _reminders.value.filter { it.startMs < now - 60_000 }
        if (due.isNotEmpty() || stale.isNotEmpty()) saveReminders(_reminders.value - (due + stale).toSet())
        return due
    }

    // ---- playback dev/advanced switches
    private val _tunnel = MutableStateFlow(sp.getBoolean("tunnel", false)); val tunneling: StateFlow<Boolean> = _tunnel
    fun setTunneling(v: Boolean) { _tunnel.value = v; sp.edit().putBoolean("tunnel", v).apply() }
    private val _stats = MutableStateFlow(sp.getBoolean("stats", false)); val statsOverlay: StateFlow<Boolean> = _stats
    // ---- channels the person hid from the lists (key = playlist id + hashed url); shown again from Settings
    private val _hiddenCh = MutableStateFlow(sp.getStringSet("hiddenCh", emptySet())!!.toSet()); val hiddenChannels: StateFlow<Set<String>> = _hiddenCh
    fun toggleHiddenChannel(key: String) { val s = _hiddenCh.value.toMutableSet(); if (!s.add(key)) s.remove(key); _hiddenCh.value = s; sp.edit().putStringSet("hiddenCh", s).apply() }
    fun clearHiddenChannels(pid: String) { val s = _hiddenCh.value.filterNot { it.startsWith("$pid|") }.toSet(); _hiddenCh.value = s; sp.edit().putStringSet("hiddenCh", s).apply() }

    private val _autoAlt = MutableStateFlow(sp.getBoolean("autoalt", false)); val autoAlt: StateFlow<Boolean> = _autoAlt
    fun setAutoAlt(v: Boolean) { _autoAlt.value = v; sp.edit().putBoolean("autoalt", v).apply() }
    fun setStatsOverlay(v: Boolean) { _stats.value = v; sp.edit().putBoolean("stats", v).apply() }
    private val _prefetch = MutableStateFlow(sp.getBoolean("prefetch", true)); val prefetchNeighbours: StateFlow<Boolean> = _prefetch
    fun setPrefetch(v: Boolean) { _prefetch.value = v; sp.edit().putBoolean("prefetch", v).apply() }

    // ---- start screen, home rows, live preview, watched marks
    private val _start = MutableStateFlow(sp.getString("start", "home")!!)
    val startScreen: StateFlow<String> = _start          // home | live | guide | movies | series | last
    fun setStartScreen(v: String) { _start.value = v; sp.edit().putString("start", v).apply() }
    private val _homeRows = MutableStateFlow(sp.getString("homeRows", "continue\nfavs\nrecent")!!.split('\n').filter { it.isNotEmpty() })
    val homeRows: StateFlow<List<String>> = _homeRows   // ids in display order; hidden rows are simply absent
    fun setHomeRows(l: List<String>) { _homeRows.value = l; sp.edit().putString("homeRows", l.joinToString("\n")).apply() }
    private val _preview = MutableStateFlow(if (sp.contains("preview")) sp.getBoolean("preview", true) else !(ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice)
    val livePreview: StateFlow<Boolean> = _preview
    fun setLivePreview(v: Boolean) { _preview.value = v; sp.edit().putBoolean("preview", v).apply() }
    fun isWatched(key: String) = sp.getBoolean("w_$key", false)
    fun markWatched(key: String, v: Boolean = true) { sp.edit().apply { if (v) putBoolean("w_$key", true) else remove("w_$key") }.apply() }

    // ---- backup (no PIN, no watch history, no caches). Credentials are inside, so the result is only ever written encrypted.
    fun exportJson(): String {
        fun strs(c: Collection<String>) = JsonArray(c.map { JsonPrimitive(it) })
        val o = buildJsonObject {
            put("v", 1)
            put("playlists", json.encodeToJsonElement(ListSerializer(PlaylistDef.serializer()), _playlists.value))
            put("active", _active.value ?: ""); put("favs", strs(_favs.value)); put("recents", strs(_recents.value)); put("locked", strs(_locked.value)); put("hidden", strs(_hidden.value))
            put("order", buildJsonObject { _order.value.forEach { (k, v) -> put(k, strs(v)) } })
            put("lists", buildJsonObject { _lists.value.forEach { (k, v) -> put(k, strs(v)) } })
            put("epgOff", buildJsonObject { _offsets.value.forEach { (k, v) -> put(k, v) } })
            put("homeRows", strs(_homeRows.value)); put("start", _start.value); put("lang", _lang.value); put("refreshH", _refreshH.value)
            put("buffer", _buffer.value.name); put("preview", _preview.value); put("resume", _resume.value); put("textScale", _textScale.value)
            val a = _appearance.value
            put("appearance", buildJsonObject { put("theme", a.theme.name); put("accent", a.accent.name); put("focus", a.focus.name); put("corner", a.corner.name); put("bg", a.background.name) })
        }
        return o.toString()
    }

    /** Applies a backup produced by [exportJson]. Unknown or malformed parts are ignored; sizes are bounded. Returns false if it is not a backup. */
    fun importJson(text: String): Boolean {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        if (o["v"]?.jsonPrimitive?.intOrNull != 1) return false
        fun strs(k: String, max: Int = 20_000) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.take(max)
        fun map(k: String) = (o[k] as? JsonObject)?.entries?.take(500)
        val e = sp.edit()
        (o["playlists"] as? JsonArray)?.let { arr ->
            val l = runCatching { json.decodeFromJsonElement(ListSerializer(PlaylistDef.serializer()), arr) }.getOrNull()?.take(50)?.filter { it.kind == PlaylistDef.KIND_M3U || it.kind == PlaylistDef.KIND_XTREAM }
            if (l != null) writePlaylists(l)
        }
        (o["active"] as? JsonPrimitive)?.content?.let { a -> setActive(a.takeIf { id -> _playlists.value.any { it.id == id } } ?: _playlists.value.firstOrNull()?.id) }
        strs("favs")?.let { _favs.value = it.toSet(); e.putStringSet("favs", it.toSet()) }
        strs("recents")?.let { _recents.value = it.take(30); e.putString("recents", it.take(30).joinToString("\n")) }
        strs("locked")?.let { _locked.value = it.toSet(); e.putStringSet("locked", it.toSet()) }
        strs("hidden")?.let { _hidden.value = it.toSet(); e.putStringSet("hidden", it.toSet()) }
        strs("homeRows")?.let { _homeRows.value = it; e.putString("homeRows", it.joinToString("\n")) }
        map("order")?.forEach { (k, v) -> val l = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(); _order.value = _order.value + (k to l); e.putString("gorder_$k", l.joinToString("\n")) }
        map("epgOff")?.forEach { (k, v) -> (v as? JsonPrimitive)?.intOrNull?.let { n -> val c = n.coerceIn(-720, 720); _offsets.value = _offsets.value + (k to c); e.putInt("epgoff_$k", c) } }
        map("lists")?.let { m -> saveListsMap(m.associate { (k, v) -> k to ((v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.take(5000)?.toSet() ?: emptySet()) }) }
        (o["start"] as? JsonPrimitive)?.content?.takeIf { it in setOf("home", "live", "guide", "movies", "series", "last") }?.let { _start.value = it; e.putString("start", it) }
        (o["lang"] as? JsonPrimitive)?.content?.takeIf { it == "ar" || it == "en" || it == Language.AUTO }?.let { e.apply(); setLang(it) }
        (o["refreshH"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..168 }?.let { _refreshH.value = it; e.putInt("refreshH", it) }
        (o["buffer"] as? JsonPrimitive)?.content?.let { n -> BufferMode.entries.firstOrNull { it.name == n }?.let { _buffer.value = it; e.putString("buffer", it.name) } }
        (o["preview"] as? JsonPrimitive)?.booleanOrNull?.let { _preview.value = it; e.putBoolean("preview", it) }
        (o["resume"] as? JsonPrimitive)?.booleanOrNull?.let { _resume.value = it; e.putBoolean("resume", it) }
        (o["textScale"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..2 }?.let { _textScale.value = it; e.putInt("textScale", it) }
        (o["appearance"] as? JsonObject)?.let { a ->
            fun <E : Enum<E>> pick(k: String, all: Array<E>, d: E) = all.firstOrNull { it.name == (a[k] as? JsonPrimitive)?.content } ?: d
            val cur = _appearance.value
            e.apply()
            setAppearance(Appearance(pick("theme", ThemeId.entries.toTypedArray(), cur.theme), pick("accent", AccentId.entries.toTypedArray(), cur.accent),
                pick("focus", FocusStyle.entries.toTypedArray(), cur.focus), pick("corner", CornerStyle.entries.toTypedArray(), cur.corner), pick("bg", BgStyle.entries.toTypedArray(), cur.background)))
            return true
        }
        e.apply(); return true
    }

    // ---- settings
    /** Sets the choice (auto, ar, en) and the language to apply now; returns the language that will be used. */
    fun setLang(v: String): String {
        val c = if (v == "ar" || v == "en") v else Language.AUTO
        _lang.value = c
        val r = Language.resolve(c, sp.getString("urtvLang", null), Language.systemLanguage())
        sp.edit().putString("lang", c).putString("resolvedLang", r).apply(); return r
    }
    /** The language the UI is shown in right now (read synchronously at activity start). */
    fun resolvedLang(): String = sp.getString("resolvedLang", null) ?: Language.resolve(sp.getString("lang", Language.AUTO)!!, sp.getString("urtvLang", null), Language.systemLanguage())
    /** Called when UrTV's language (or null: not installed) was read. Returns true when the UI language changed and the screen must be recreated. */
    fun onUrtvLanguage(urtv: String?): Boolean {
        // a missing answer (UrTV slow to wake) must not erase the remembered one
        val known = urtv ?: sp.getString("urtvLang", null)
        if (urtv != null) sp.edit().putString("urtvLang", urtv).apply()
        val r = Language.resolve(_lang.value, known, Language.systemLanguage())
        val changed = r != resolvedLang()
        if (changed) sp.edit().putString("resolvedLang", r).apply()
        return changed
    }
    fun setRefreshHours(v: Int) { _refreshH.value = v; sp.edit().putInt("refreshH", v).apply() }
    fun setBuffer(v: BufferMode) { _buffer.value = v; sp.edit().putString("buffer", v.name).apply() }
    fun setResume(v: Boolean) { _resume.value = v; sp.edit().putBoolean("resume", v).apply() }

    // ---- parental PIN: PBKDF2-HMAC-SHA256 with a random salt (the PIN is never stored) + lockout after repeated mistakes
    private fun hash(pin: String, salt: String): String {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt.toByteArray(), 60_000, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    }

    fun setPin(pin: String?) {
        val e = sp.edit()
        if (pin == null) { e.remove("pinHash").remove("pinSalt").remove("pinFails").remove("pinLockUntil") } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            e.putString("pinSalt", salt).putString("pinHash", hash(pin, salt)).putInt("pinFails", 0).putLong("pinLockUntil", 0)
        }
        e.apply(); _hasPin.value = pin != null
        if (pin == null) { _locked.value = emptySet(); sp.edit().remove("locked").apply() }
    }

    /** Seconds the PIN pad is locked for (0 = open). 5 wrong tries lock it for 30 s, then it doubles up to 15 min. */
    fun pinLockedSeconds(now: Long = System.currentTimeMillis()): Int = ((sp.getLong("pinLockUntil", 0) - now) / 1000).toInt().coerceAtLeast(0)

    fun checkPin(pin: String, now: Long = System.currentTimeMillis()): Boolean {
        val h = sp.getString("pinHash", null) ?: return true
        if (pinLockedSeconds(now) > 0) return false
        val ok = MessageDigest.isEqual(h.toByteArray(), hash(pin, sp.getString("pinSalt", "")!!).toByteArray())
        val e = sp.edit()
        if (ok) e.putInt("pinFails", 0) else {
            val f = sp.getInt("pinFails", 0) + 1
            e.putInt("pinFails", f)
            if (f >= 5) e.putLong("pinLockUntil", now + minOf(30_000L shl ((f - 5).coerceAtMost(5)), 900_000L))
        }
        e.apply()
        return ok
    }
    fun toggleLockedGroup(key: String) {
        val s = _locked.value.toMutableSet(); if (!s.add(key)) s.remove(key)
        _locked.value = s; sp.edit().putStringSet("locked", s).apply()
    }
}
