package com.urtv.player.ui

import android.util.Log

/**
 * One line per measured step for `adb logcat -s UrTVPerf`: tab open, list build, search, time to first frame of a channel, cold start.
 * Numbers only (milliseconds and counts); never names, addresses or credentials.
 */
object Perf {
    const val TAG = "UrTVPerf"
    fun log(what: String, ms: Long, note: String = "") { Log.i(TAG, "$what ${ms} ms" + if (note.isEmpty()) "" else " ($note)") }
    inline fun <T> time(what: String, note: String = "", block: () -> T): T {
        val t0 = System.nanoTime(); val r = block(); log(what, (System.nanoTime() - t0) / 1_000_000, note); return r
    }
}
