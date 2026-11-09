package com.urtv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.urtv.player.core.MatchFinder
import com.urtv.player.ui.FindMatchRoot
import com.urtv.player.ui.UrtvTheme

/**
 * «Find the channel for this match». Called by the UrTV home app with startActivityForResult and action
 * sa.urtv.player.action.FIND_MATCH. Anything else, or a caller that is not UrTV (package sa.urtv.home or sa.urtv.screensaver, signed by UrTV's release certificate), is ignored: the activity
 * closes at once with RESULT_CANCELED and nothing is read from the intent. All extras are untrusted text used only to compare
 * with the user's own channel names and guide; no URL from the caller is ever opened.
 *
 * Extras: home_ar, home_en, away_ar, away_en, competition_ar, competition_en (String), kickoff (Long, epoch ms), channels (String[]).
 */
class FindMatchActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(localized(base))

    private fun callerIsUrtv(): Boolean {
        val pkg = callingPackage ?: return false
        return com.urtv.player.data.Integrity.callerAllowed(pkg, com.urtv.player.data.Integrity.certDigestsOf(this, pkg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (intent?.action != ACTION || !callerIsUrtv()) { finish(); return }
        val req = readRequest(intent, System.currentTimeMillis())
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as UrtvApp
        com.urtv.player.ui.Fonts.init(this)
        setContent {
            val look by app.store.appearance.collectAsState(); val scale by app.store.textScale.collectAsState()
            UrtvTheme(look, scale) {
                FindMatchRoot(app, req, onPlayed = { setResult(RESULT_OK) }, onSearch = { startActivity(Intent(this, MainActivity::class.java)); finish() }, onClose = { finish() })
            }
        }
    }

    companion object {
        const val ACTION = "sa.urtv.player.action.FIND_MATCH"

        /** Reads the extras defensively (wrong types, huge values) and returns a sanitised request. */
        fun readRequest(i: Intent, nowMs: Long): MatchFinder.Request {
            fun s(k: String) = runCatching { i.getStringExtra(k) }.getOrNull().orEmpty()
            val list = runCatching { i.getStringArrayExtra("channels") }.getOrNull()?.filterNotNull().orEmpty()
            val kick = runCatching { i.getLongExtra("kickoff", 0L) }.getOrDefault(0L)
            return MatchFinder.Request(s("home_ar"), s("home_en"), s("away_ar"), s("away_en"), s("competition_ar"), s("competition_en"), kick, list).sanitized(nowMs)
        }
    }
}
