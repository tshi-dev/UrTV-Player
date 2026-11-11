package com.urtv.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Diagnostic switches for finding what makes a screen slow on a given TV, flipped from a computer without rebuilding:
 *
 *     adb shell setprop debug.urtv.bisect "nopreview,nologos"      (any mix of the names below, comma separated)
 *     adb shell setprop debug.urtv.bisect ""                        (all back to normal)
 *
 * The list is re-read each time the Home screen is entered or a tab changes. Nothing is stored, nothing is exported; with the property
 * empty (the default) the app behaves exactly as shipped.
 */
object Bisect {
    val ALL = setOf(
        "nopreview",      // no preview pane at all next to the list
        "nobackdrop",     // plain background colour instead of the gradient / poster backdrop
        "nologos",        // no channel logo images
        "nofocus",        // no focus animation (scale, colour fade)
        "nomarquee",      // the focused row's name does not scroll
        "springscroll",   // the list scrolls with the framework's spring animation instead of jumping to the row
        "nometer",        // no frame meter even when stats are on
    )
    var flags by mutableStateOf(emptySet<String>()); private set
    fun on(f: String) = f in flags

    fun parse(raw: String?): Set<String> = (raw ?: "").split(',', ' ', ';').map { it.trim().lowercase() }.filter { it in ALL }.toSet()

    fun refresh() { Thread({ val p = parse(read()); if (p != flags) flags = p }, "bisect").apply { isDaemon = true }.start() }

    private fun read(): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java).invoke(null, "debug.urtv.bisect", "") as String
    }.getOrElse { runCatching { Runtime.getRuntime().exec(arrayOf("getprop", "debug.urtv.bisect")).inputStream.bufferedReader().readText().trim() }.getOrDefault("") }
}
