package com.urtv.player

import android.app.Application
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
class UrtvApp : Application() {
    lateinit var store: Store; private set
    lateinit var catalog: Catalog; private set
    lateinit var vod: Vod; private set
    lateinit var player: PlayerController; private set
    /** Locked groups the user has unlocked with the PIN during this run. */
    var unlocked by mutableStateOf(emptySet<String>())
    var resumeHandled = false

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        catalog = Catalog(this, store, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        vod = Vod(this, store, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        player = PlayerController(this)
    }
}
