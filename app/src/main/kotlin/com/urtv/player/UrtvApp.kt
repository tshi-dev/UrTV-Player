package com.urtv.player

import android.app.Application
import okio.Path.Companion.toOkioPath
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.urtv.player.data.Catalog
import com.urtv.player.data.Store
import com.urtv.player.data.Vod
import com.urtv.player.player.PlayerController
import com.urtv.player.server.ServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** What the channel search should show when the app is brought to the Live tab by FIND_MATCH: the text for the box and a line saying why. */
data class PendingSearch(val text: String, val hint: String)

/** Process-wide singletons (no DI framework: keeps the APK small). */
class UrtvApp : Application(), coil3.SingletonImageLoader.Factory {
    lateinit var store: Store; private set
    // Everything below is created on first use, so process start does the bare minimum.
    val catalog: Catalog by lazy { Catalog(this, store, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    val vod: Vod by lazy { Vod(this, store, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    val player: PlayerController by lazy { PlayerController(this, store) }
    val commentary: com.urtv.player.player.CommentaryController by lazy { com.urtv.player.player.CommentaryController(this, player, commentaryTier == com.urtv.player.core.Commentary.Tier.Warn) }
    /** «دمج التعليق» needs two decoders and two connections: under 2000 MB of RAM the row stays, with a warning and the lightest second player. */
    val commentaryTier: com.urtv.player.core.Commentary.Tier by lazy {
        val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        com.urtv.player.core.Commentary.tier(android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem / (1024 * 1024), am.isLowRamDevice)
    }
    val servers: ServerManager by lazy { ServerManager(this) }
    /** Locked groups the user has unlocked with the PIN during this run. */
    var unlocked by mutableStateOf(emptySet<String>())
    var resumeHandled = false
    /** Official / debug / unofficial build; computed off the main thread at start. Gates the LAN pairing page and backup restore. */
    var integrity by mutableStateOf(com.urtv.player.data.Integrity.Status.Official)
    /** Data written by a newer Player than this one: the app stops with a notice instead of touching it. */
    var schemaTooNew by mutableStateOf(false)
    /** What UrTV last said about a newer Player (cached; asked at most once a day). */
    var update by mutableStateOf(com.urtv.player.data.Update.State(com.urtv.player.data.Update.Kind.UpToDate))
    /** True only on the very first start in Auto language, until UrTV's answer (or a short timeout) is in: the content stays behind a static splash. */
    var langGate by mutableStateOf(false)
    /** Text to put in the channel search when the app is brought up (FIND_MATCH's «search channels»); in-process only, never from an intent. */
    var pendingSearch by mutableStateOf<PendingSearch?>(null)
    /** The launch animation plays once per process. */
    var splashDone by mutableStateOf(false)

    /** Small, bounded image caches: friendly to 1-2 GB TV boxes. */
    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(context, 0.12).build() }
        .diskCache { coil3.disk.DiskCache.Builder().directory(cacheDir.resolve("img").toOkioPath()).maxSizeBytes(64L * 1024 * 1024).build() }
        .build()

    // ---- the channel search handed over by FIND_MATCH survives a cold start of the main screen: kept in preferences for 30 seconds
    fun handOverSearch(p: PendingSearch, now: Long = System.currentTimeMillis()) {
        pendingSearch = p
        getSharedPreferences("urtv", MODE_PRIVATE).edit().putString("pendingSearch", "${now + 30_000}\t${p.text.replace('\t', ' ').replace('\n', ' ')}\t${p.hint.replace('\t', ' ').replace('\n', ' ')}").apply()
    }
    fun restorePendingSearch(now: Long = System.currentTimeMillis()) {
        val sp = getSharedPreferences("urtv", MODE_PRIVATE)
        val raw = sp.getString("pendingSearch", null) ?: return
        val parts = raw.split('\t')
        if (parts.size >= 3 && (parts[0].toLongOrNull() ?: 0L) > now && pendingSearch == null) pendingSearch = PendingSearch(parts[1].take(80), parts[2].take(160)) else sp.edit().remove("pendingSearch").apply()
    }
    fun consumePendingSearch(): PendingSearch? { val p = pendingSearch; pendingSearch = null; getSharedPreferences("urtv", MODE_PRIVATE).edit().remove("pendingSearch").apply(); return p }

    override fun onCreate() {
        super.onCreate()
        com.urtv.player.phone.PhoneServer.logger = { android.util.Log.i("UrtvPhone", it) }
        Thread.getDefaultUncaughtExceptionHandler().let { prev -> Thread.setDefaultUncaughtExceptionHandler { t, e -> com.urtv.player.data.Diag.record(e); prev?.uncaughtException(t, e) } }   // class names only, kept in memory
        store = Store(this)                                    // preferences only: cheap
        val sp = getSharedPreferences("urtv", MODE_PRIVATE)
        schemaTooNew = com.urtv.player.data.Schema.check(sp) == com.urtv.player.data.Schema.Status.TooNew
        update = com.urtv.player.data.Update.load(sp, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        langGate = com.urtv.player.data.Language.needsFirstAnswer(sp.getString("lang", com.urtv.player.data.Language.AUTO) ?: "auto", sp.getString("urtvLang", null), sp.getBoolean("urtvLangChecked", false))
        restorePendingSearch()
        Thread({
            store.loadHeavy(); integrity = com.urtv.player.data.Integrity.check(this)
            com.urtv.player.data.SelfUpdater.cleanup(this)
            // updates: only the official build, at most once a day, a few seconds after start, never while something plays
            if (integrity == com.urtv.player.data.Integrity.Status.Official && !schemaTooNew) {
                Thread.sleep(5000)
                val now = System.currentTimeMillis()
                if (com.urtv.player.data.Update.due(sp.getLong("upd_last", 0), now) && !PlayerController.active) {
                    // UrTV answers: it is the authority. UrTV absent: the Player looks for its own update on GitHub's release host (same daily limit)
                    val s = com.urtv.player.data.Update.decide(com.urtv.player.data.Update.read(this, BuildConfig.VERSION_CODE), com.urtv.player.data.SelfUpdater.allowed(true)) { com.urtv.player.data.SelfUpdater.lookup(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME) }
                    com.urtv.player.data.Update.save(sp, s, now); update = s
                }
            }
        }, "load-store").start()     // Keystore, encrypted files, the certificate check and the daily update question, off the main thread     // Keystore, encrypted files and the certificate check, off the main thread
    }
}
