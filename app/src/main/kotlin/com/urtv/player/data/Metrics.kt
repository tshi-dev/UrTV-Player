package com.urtv.player.data

import android.content.Context
import com.urtv.player.core.Assessment
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a BETA build measures about itself on the device, for the «تقييم الأداء» screen and the beta report. Numbers and fixed words only: timings, counts,
 * the failure TYPE (never the address or the channel), memory, crash counts and the crash class name. Kept in a small private file, nothing leaves the
 * TV unless the person scans the report. Off in stable builds (no work at all there).
 */
object Metrics {
    @Volatile var enabled = false
    private const val FILE = "urtv_metrics"
    private val lock = Any()

    private var coldStartMs = -1L
    private val tabOpen = ArrayList<Long>(); private val zap = ArrayList<Long>()
    private var playlistLoadMs = -1L; private var playlistChannels = 0; private var epgLoadMs = -1L
    private var playSeconds = 0L; private var bufferingSeconds = 0L; private var rebuffers = 0; private var dropped = 0L; private var decoderErrors = 0; private var plays = 0
    private val failures = HashMap<String, Int>()
    private var heapPeakMb = 0; private var heapLimitMb = 0; private var pssPeakMb = 0
    private var crashes = 0; private var anrs = 0; private var lowMemoryKills = 0; private var lastCrash = ""; private var lastExitTs = 0L
    private var lastSave = 0L

    private fun <T> ring(l: ArrayList<T>, v: T, max: Int) { l += v; while (l.size > max) l.removeAt(0) }

    /** Called from the one place every timing already goes through (Perf.log): picks out the ones the assessment needs. */
    fun timing(what: String, ms: Long) { if (!enabled) return; synchronized(lock) { when { what == "cold_start_first_frame" -> coldStartMs = ms; what.startsWith("tab_open_") -> ring(tabOpen, ms, 60) } } }
    fun zap(ms: Long) { if (!enabled) return; synchronized(lock) { ring(zap, ms, 120) } }
    fun playlist(ms: Long, channels: Int) { if (!enabled) return; synchronized(lock) { playlistLoadMs = ms; playlistChannels = channels } }
    fun epg(ms: Long) { if (!enabled) return; synchronized(lock) { epgLoadMs = ms } }
    fun play() { if (!enabled) return; synchronized(lock) { plays++ } }
    /** One call per second of watching: [buffering] true while it waits after having started, [dropped] frames dropped in that second. */
    fun second(buffering: Boolean, droppedFrames: Int) { if (!enabled) return; synchronized(lock) { if (buffering) bufferingSeconds++ else playSeconds++; dropped += droppedFrames.coerceIn(0, 1000) } }
    fun rebuffer() { if (!enabled) return; synchronized(lock) { rebuffers++ } }
    fun decoderError() { if (!enabled) return; synchronized(lock) { decoderErrors++ } }
    /** A stream that ended in failure, by TYPE ([com.urtv.player.core.FailureReason.Reason] name): never which channel or server. */
    fun failure(type: String) { if (!enabled) return; synchronized(lock) { failures[type] = (failures[type] ?: 0) + 1 } }
    fun memory(heapMb: Int, limitMb: Int, pssMb: Int) { if (!enabled) return; synchronized(lock) { heapPeakMb = maxOf(heapPeakMb, heapMb); heapLimitMb = limitMb; pssPeakMb = maxOf(pssPeakMb, pssMb) } }

    /** An uncaught exception: the class name only (kept only if it is a plain identifier). Written at once, the process is about to die. */
    fun crash(ctx: Context, t: Throwable) {
        if (!enabled) return
        synchronized(lock) { crashes++; lastCrash = t.javaClass.simpleName.takeIf { Regex("[A-Za-z][A-Za-z0-9_]{0,48}").matches(it) } ?: "" }
        save(ctx, force = true, sync = true)
    }

    /** Exits the system recorded since last time (API 30+): ANRs, native crashes and kills for low memory; each counted once. Call off the main thread. */
    fun readExits(ctx: Context) {
        if (!enabled || android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            synchronized(lock) {
                var newest = lastExitTs
                for (e in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 20)) {
                    if (e.timestamp <= lastExitTs) continue
                    newest = maxOf(newest, e.timestamp)
                    when (e.reason) {
                        android.app.ApplicationExitInfo.REASON_ANR -> anrs++
                        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> { crashes++; lastCrash = "NativeCrash" }
                        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> lowMemoryKills++
                    }
                }
                lastExitTs = newest
            }
            save(ctx, force = true)
        }
    }

    fun snapshot(): Assessment.Snapshot = synchronized(lock) {
        Assessment.Snapshot(coldStartMs, tabOpen.toList(), playlistLoadMs, playlistChannels, epgLoadMs, zap.toList(), playSeconds, bufferingSeconds, rebuffers, dropped, decoderErrors, failures.toMap(), plays,
            heapPeakMb, heapLimitMb, pssPeakMb, crashes, anrs, lowMemoryKills, lastCrash)
    }

    fun reset(ctx: Context? = null) { synchronized(lock) { coldStartMs = -1; tabOpen.clear(); zap.clear(); playlistLoadMs = -1; playlistChannels = 0; epgLoadMs = -1; playSeconds = 0; bufferingSeconds = 0; rebuffers = 0; dropped = 0; decoderErrors = 0; plays = 0; failures.clear(); heapPeakMb = 0; heapLimitMb = 0; pssPeakMb = 0; crashes = 0; anrs = 0; lowMemoryKills = 0; lastCrash = "" }; ctx?.let { save(it, force = true) } }

    fun save(ctx: Context, force: Boolean = false, sync: Boolean = false) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val j = synchronized(lock) {
            if (!force && now - lastSave < 60_000) return; lastSave = now
            JSONObject().put("cold", coldStartMs).put("tab", JSONArray(tabOpen)).put("zap", JSONArray(zap)).put("pl", playlistLoadMs).put("plc", playlistChannels).put("epg", epgLoadMs)
                .put("ps", playSeconds).put("bs", bufferingSeconds).put("rb", rebuffers).put("dr", dropped).put("de", decoderErrors).put("pn", plays)
                .put("f", JSONObject(failures as Map<*, *>)).put("hp", heapPeakMb).put("hl", heapLimitMb).put("pss", pssPeakMb).put("cr", crashes).put("anr", anrs).put("lm", lowMemoryKills).put("lc", lastCrash).put("ex", lastExitTs)
        }
        val e = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("m", j.toString())
        if (sync) e.commit() else e.apply()
    }

    fun load(ctx: Context) {
        if (!enabled) return
        val s = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("m", null) ?: return
        runCatching {
            val j = JSONObject(s)
            synchronized(lock) {
                coldStartMs = j.optLong("cold", -1); playlistLoadMs = j.optLong("pl", -1); playlistChannels = j.optInt("plc"); epgLoadMs = j.optLong("epg", -1)
                playSeconds = j.optLong("ps"); bufferingSeconds = j.optLong("bs"); rebuffers = j.optInt("rb"); dropped = j.optLong("dr"); decoderErrors = j.optInt("de"); plays = j.optInt("pn")
                heapPeakMb = j.optInt("hp"); heapLimitMb = j.optInt("hl"); pssPeakMb = j.optInt("pss"); crashes = j.optInt("cr"); anrs = j.optInt("anr"); lowMemoryKills = j.optInt("lm"); lastCrash = j.optString("lc"); lastExitTs = j.optLong("ex")
                tabOpen.clear(); j.optJSONArray("tab")?.let { a -> for (i in 0 until a.length()) tabOpen += a.optLong(i) }
                zap.clear(); j.optJSONArray("zap")?.let { a -> for (i in 0 until a.length()) zap += a.optLong(i) }
                failures.clear(); j.optJSONObject("f")?.let { o -> for (k in o.keys()) failures[k] = o.optInt(k) }
            }
        }
    }
}
