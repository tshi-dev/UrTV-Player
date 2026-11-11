package com.urtv.player.ui

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window

/**
 * What a D-pad press costs, frame by frame, for finding the "one late frame per key" on a TV. Switched on with
 *
 *     adb shell setprop debug.urtv.bisect keytrace      (then press a tab or re-enter Home)
 *     adb logcat -s UrTVKeyTrace
 *
 * Each key press is logged once ("key"), then every frame drawn in the next 400 ms is logged with its stages in ms: input handling, animation
 * (this is where Compose recomposition runs), layout/measure, draw, sync, GPU command issue, buffer swap, the unknown delay before the frame
 * started, and the total, with the time since the key and whether the frame missed its deadline. Nothing is stored or sent.
 */
object KeyTrace {
    private const val TAG = "UrTVKeyTrace"
    @Volatile private var lastKeyAt = 0L
    @Volatile private var on = false
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var thread: HandlerThread? = null
    private var window: Window? = null

    fun onKey(e: KeyEvent) {
        if (!on || e.action != KeyEvent.ACTION_DOWN) return
        lastKeyAt = SystemClock.uptimeMillis()
        Log.i(TAG, "key ${KeyEvent.keyCodeToString(e.keyCode)} repeat=${e.repeatCount} dispatchLag=${lastKeyAt - e.eventTime}ms")
    }

    @Synchronized fun set(w: Window?, enable: Boolean) {
        if (enable == on && w === window) return
        stop()
        if (!enable || w == null) return
        val t = HandlerThread("key-trace").apply { start() }; thread = t; window = w; on = true
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val intended = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) / 1_000_000
            val since = intended - lastKeyAt
            if (lastKeyAt == 0L || since < -20 || since > 400) return@OnFrameMetricsAvailableListener
            fun ms(id: Int) = m.getMetric(id) / 1_000_000
            val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
            val late = if (android.os.Build.VERSION.SDK_INT >= 31) total > m.getMetric(FrameMetrics.DEADLINE) else total > 25_000_000
            Log.i(TAG, "  +${since}ms frame: unknown=${ms(FrameMetrics.UNKNOWN_DELAY_DURATION)} input=${ms(FrameMetrics.INPUT_HANDLING_DURATION)} anim+compose=${ms(FrameMetrics.ANIMATION_DURATION)} " +
                "layout=${ms(FrameMetrics.LAYOUT_MEASURE_DURATION)} draw=${ms(FrameMetrics.DRAW_DURATION)} sync=${ms(FrameMetrics.SYNC_DURATION)} cmd=${ms(FrameMetrics.COMMAND_ISSUE_DURATION)} " +
                "swap=${ms(FrameMetrics.SWAP_BUFFERS_DURATION)} total=${total / 1_000_000}ms late=$late")
        }
        listener = l
        w.addOnFrameMetricsAvailableListener(l, Handler(t.looper))
    }

    @Synchronized private fun stop() {
        on = false
        listener?.let { runCatching { window?.removeOnFrameMetricsAvailableListener(it) } }
        thread?.quitSafely(); thread = null; listener = null; window = null
    }
}
