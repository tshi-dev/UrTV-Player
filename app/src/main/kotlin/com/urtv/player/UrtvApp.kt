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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons (no DI framework: keeps the APK small). */
class UrtvApp : Application(), coil3.SingletonImageLoader.Factory {
    lateinit var store: Store; private set
    lateinit var catalog: Catalog; private set
    lateinit var vod: Vod; private set
    lateinit var player: PlayerController; private set
    /** Locked groups the user has unlocked with the PIN during this run. */
    var unlocked by mutableStateOf(emptySet<String>())
    var resumeHandled = false
    /** The launch animation plays once per process. */
    var splashDone by mutableStateOf(false)

    /** Small, bounded image caches: friendly to 1-2 GB TV boxes. */
    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(context, 0.12).build() }
        .diskCache { coil3.disk.DiskCache.Builder().directory(cacheDir.resolve("img").toOkioPath()).maxSizeBytes(64L * 1024 * 1024).build() }
        .build()

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        catalog = Catalog(this, store, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        vod = Vod(this, store, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        player = PlayerController(this, store)
    }
}
