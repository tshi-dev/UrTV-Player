package com.urtv.player.core

/** Pure parts of «اختبار القائمة»: which channels to sample and how to read the results. Nothing here touches the network. */
object ListProbe {
    enum class Fail { None, Http4xx, Http5xx, Timeout, Connection, Skipped }
    data class Result(val ok: Boolean, val ttfbMs: Long, val fail: Fail = Fail.None)

    data class Summary(val tested: Int, val reachable: Int, val reachablePct: Int, val medianMs: Long, val p90Ms: Long, val fails: Map<Fail, Int>, val verdict: Verdict)
    enum class Verdict { Good, Fair, Weak, NoData }

    const val DEFAULT_SAMPLE = 30

    /** Up to [n] channels spread evenly over the whole list (so every group gets a share), http(s) only, no duplicates by URL. */
    fun sample(channels: List<Channel>, n: Int = DEFAULT_SAMPLE): List<Channel> {
        val web = channels.filter { it.url.startsWith("http://", true) || it.url.startsWith("https://", true) }.distinctBy { it.url }
        if (web.size <= n) return web
        return List(n) { i -> web[(i.toLong() * web.size / n).toInt()] }
    }

    fun summarize(results: List<Result>): Summary {
        val real = results.filter { it.fail != Fail.Skipped }
        if (real.isEmpty()) return Summary(0, 0, 0, 0, 0, emptyMap(), Verdict.NoData)
        val okTimes = real.filter { it.ok }.map { it.ttfbMs }.sorted()
        val pct = (okTimes.size * 100.0 / real.size).toInt()
        val median = okTimes.getOrNull(okTimes.size / 2) ?: 0L
        val p90 = okTimes.getOrNull(((okTimes.size - 1) * 0.9).toInt()) ?: 0L
        val fails = real.filter { !it.ok }.groupingBy { it.fail }.eachCount()
        val verdict = when { pct >= 90 && median <= 1500 -> Verdict.Good; pct >= 70 && median <= 3500 -> Verdict.Fair; else -> Verdict.Weak }
        return Summary(real.size, okTimes.size, pct, median, p90, fails, verdict)
    }
}
