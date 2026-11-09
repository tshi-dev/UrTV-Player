package com.urtv.player

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.urtv.player.ui.AppRoot
import com.urtv.player.ui.UrtvTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) {
        val lang = base.getSharedPreferences("urtv", MODE_PRIVATE).getString("lang", "ar") ?: "ar"
        val loc = Locale.forLanguageTag("$lang-u-nu-latn")   // Western digits in Arabic: clearer for channel numbers and times
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration).apply { setLocale(loc); setLayoutDirection(loc) }
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as UrtvApp
        setContent {
            val oled by app.store.oled.collectAsState(); val scale by app.store.textScale.collectAsState()
            UrtvTheme(oled, scale) { AppRoot(app, onLanguageChanged = { recreate() }) }
        }
    }
}
