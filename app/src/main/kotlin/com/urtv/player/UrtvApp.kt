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

/** Process-wide singletons (no DI framework: keeps the APK small). */
class UrtvApp : Application(), coil3.SingletonImageLoader.Factory {
    lateinit var store: Store; private set
    // Everything below is created on first use, so process start does the bare minimum.
    val catalog: Catalog by lazy { Catalog(this, store, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    val vod: Vod by lazy { Vod(this, store, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    val player: PlayerController by lazy { PlayerController(this, store) }
    val servers: ServerManager by lazy { ServerManager(this) }
    /** Locked groups the user has unlocked with the PIN during this run. */
    var unlocked by mutableStateOf(emptySet<String>())
    var resumeHandled = false
    /** Official / debug / unofficial build; computed off the main thread at start. Gates the LAN pairing page and backup restore. */
    var integrity by mutableStateOf(com.urtv.player.data.Integrity.Status.Official)
    /** The launch animation plays once per process. */
    var splashDone by mutableStateOf(false)

    /** Small, bounded image caches: friendly to 1-2 GB TV boxes. */
    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(context, 0.12).build() }
        .diskCache { coil3.disk.DiskCache.Builder().directory(cacheDir.resolve("img").toOkioPath()).maxSizeBytes(64L * 1024 * 1024).build() }
        .build()

    override fun onCreate() {
        super.onCreate()
        com.urtv.player.phone.PhoneServer.logger = { android.util.Log.i("UrtvPhone", it) }
        Thread.getDefaultUncaughtExceptionHandler().let { prev -> Thread.setDefaultUncaughtExceptionHandler { t, e -> com.urtv.player.data.Diag.record(e); prev?.uncaughtException(t, e) } }   // class names only, kept in memory
        store = Store(this)                                    // preferences only: cheap
        Thread({ store.loadHeavy(); integrity = com.urtv.player.data.Integrity.check(this) }, "load-store").start()     // Keystore, encrypted files and the certificate check, off the main thread
    }
}
