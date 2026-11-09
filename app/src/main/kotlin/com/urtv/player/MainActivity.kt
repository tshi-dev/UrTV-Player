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
    override fun attachBaseContext(base: Context) = super.attachBaseContext(localized(base))

    override fun onResume() {
        super.onResume()
        // Auto language follows UrTV: re-check whenever we come back to the foreground (off the main thread).
        val app = application as UrtvApp
        Thread({ val u = com.urtv.player.data.Language.readUrtv(applicationContext); if (app.store.onUrtvLanguage(u)) runOnUiThread { recreate() } }, "lang-check").start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as UrtvApp
        com.urtv.player.ui.Fonts.init(this)
        setContent {
            val look by app.store.appearance.collectAsState(); val scale by app.store.textScale.collectAsState()
            UrtvTheme(look, scale) { AppRoot(app, onLanguageChanged = { recreate() }) }
        }
    }
}

/** The app language (Auto/ar/en, Western digits) applied to a base context; shared by every activity. */
fun localized(base: Context): Context {
    val sp = base.getSharedPreferences("urtv", Context.MODE_PRIVATE)
    val lang = sp.getString("resolvedLang", null) ?: com.urtv.player.data.Language.resolve(sp.getString("lang", com.urtv.player.data.Language.AUTO) ?: "auto", sp.getString("urtvLang", null), com.urtv.player.data.Language.systemLanguage())
    val loc = Locale.forLanguageTag("$lang-u-nu-latn")   // Western digits in Arabic: clearer for channel numbers and times
    Locale.setDefault(loc)
    val cfg = Configuration(base.resources.configuration).apply { setLocale(loc); setLayoutDirection(loc) }
    return base.createConfigurationContext(cfg)
}
