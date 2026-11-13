package com.urtv.player.ui

import android.util.Log

/**
 * One line per measured step for `adb logcat -s UrTVPerf`: tab open, list build, search, time to first frame of a channel, cold start.
 * Numbers only (milliseconds and counts); never names, addresses or credentials.
 */
object Perf {
    const val TAG = "UrTVPerf"
    fun log(what: String, ms: Long, note: String = "") { Log.i(TAG, "$what ${ms} ms" + if (note.isEmpty()) "" else " ($note)") }
    /** Logs how long it takes until the second frame is drawn after now. Uses the platform Choreographer, not Compose's frame clock: a pending Compose frame request would keep the UI tests from ever being idle. */
    /** The unit-test environment has no real frames: a pending Choreographer callback there keeps the UI tests from ever being idle. */
    val inTests: Boolean get() = android.os.Build.FINGERPRINT == "robolectric"

    fun afterTwoFrames(what: String, note: String = "") {
        if (inTests) return
        val t0 = System.nanoTime(); val c = android.view.Choreographer.getInstance()
        c.postFrameCallback { c.postFrameCallback { log(what, (System.nanoTime() - t0) / 1_000_000, note) } }
    }
    inline fun <T> time(what: String, note: String = "", block: () -> T): T {
        val t0 = System.nanoTime(); val r = block(); log(what, (System.nanoTime() - t0) / 1_000_000, note); return r
    }
}
