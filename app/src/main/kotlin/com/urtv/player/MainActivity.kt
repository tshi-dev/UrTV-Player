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
    override fun attachBaseContext(base: Context) = super.attachBaseContext(localized(base, fresh = false))     // remembered answer or the TV's language: nothing waits for UrTV before the first frame

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean { com.urtv.player.ui.KeyTrace.onKey(event); return super.dispatchKeyEvent(event) }

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
        // Very first start in Auto: the first frame waits (behind a static splash, off the main thread, at most 700 ms) for UrTV's language, so the
        // whole first run is in the right language. A slower answer is picked up by onResume's check, which re-creates the screen at once.
        if (app.langGate) Thread({
            val u = com.urtv.player.data.Language.within(700) { com.urtv.player.data.Language.readUrtv(applicationContext) }
            val changed = app.store.onUrtvLanguage(u)
            getSharedPreferences("urtv", MODE_PRIVATE).edit().putBoolean("urtvLangChecked", true).apply()
            runOnUiThread { app.langGate = false; if (changed) recreate() }
        }, "lang-first").start()
        setContent {
            val look by app.store.appearance.collectAsState(); val scale by app.store.textScale.collectAsState()
            UrtvTheme(look, scale) { AppRoot(app, onLanguageChanged = { recreate() }) }
        }
    }
}

/**
 * The app language (Auto/ar/en, Western digits) applied to a base context; shared by every activity. In Auto it asks the UrTV
 * provider right here, before anything is drawn (cold start included), and keeps the last answer only as a fallback.
 */
fun localized(base: Context, fresh: Boolean = true): Context {
    val sp = base.getSharedPreferences("urtv", Context.MODE_PRIVATE)
    val choice = sp.getString("lang", com.urtv.player.data.Language.AUTO) ?: com.urtv.player.data.Language.AUTO
    val cached = sp.getString("urtvLang", null)
    val lang = if (fresh) {
        val r = com.urtv.player.data.Language.resolveAtStart(choice, cached, com.urtv.player.data.Language.systemLanguage()) { com.urtv.player.data.Language.within(if (cached == null) 800 else 250) { com.urtv.player.data.Language.readUrtv(base) } }     // first ever start waits longer; later starts use the remembered answer if UrTV is slow to wake
        sp.edit().apply { r.urtvToCache?.let { if (it != cached) putString("urtvLang", it) } }.putString("resolvedLang", r.lang).apply()
        r.lang
    } else com.urtv.player.data.Language.resolve(choice, cached, com.urtv.player.data.Language.systemLanguage())     // never trust an old resolvedLang: it is rewritten below with what this screen really uses
    sp.edit().putString("resolvedLang", lang).apply()      // the language IN USE: a later UrTV answer is compared with it to decide whether to re-create the screen
    val loc = Locale.forLanguageTag("$lang-u-nu-latn")   // Western digits in Arabic: clearer for channel numbers and times
    Locale.setDefault(loc)
    val cfg = Configuration(base.resources.configuration).apply { setLocale(loc); setLayoutDirection(loc) }
    return base.createConfigurationContext(cfg)
}

/**
 * For a screen UrTV starts (FindMatch): UrTV is running and answers at once, so it is asked right here (bounded wait). Without this the
 * screen used the remembered answer only, which does not exist until the Player has been opened once, and showed the TV's own language.
 */
fun localizedForCaller(base: Context): Context = localized(base, fresh = true)
