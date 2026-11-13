package com.urtv.player.core

/**
 * An M3U list often mixes live channels with movies and series. These rules separate them, the way the established players do:
 * the URL layout first (Xtream puts /movie/ and /series/ in the path whatever the provider names its groups), then a video-file
 * extension, then the group name, and live by default. A live HLS/TS stream is never a movie because of its group name.
 */
object M3uVod {
    enum class Kind { Live, Movie, Series }

    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v", "webm", "mpg", "mpeg")
    private val LIVE_EXT = setOf("m3u8", "ts", "m3u", "mpd")
    private val SERIES_WORDS = listOf("series", "مسلسل", "serie", "tv show", "tvshow", "season")
    private val MOVIE_WORDS = listOf("movie", "movies", "vod", "film", "فيلم", "أفلام", "افلام", "cinema")

    fun classify(url: String, group: String, name: String = ""): Kind {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "")
        when {
            "/series/" in path -> return Kind.Series
            "/movie/" in path -> return Kind.Movie
            "/live/" in path || "/timeshift/" in path -> return Kind.Live
        }
        if (ext in LIVE_EXT) return Kind.Live
        val g = group.lowercase()
        val seriesGroup = SERIES_WORDS.any { it in g }; val movieGroup = MOVIE_WORDS.any { it in g }
        if (ext in VIDEO_EXT) return if (seriesGroup || parseEpisode(name) != null) Kind.Series else Kind.Movie
        // no telling extension (Xtream style «/user/pass/123»): only a clear group name moves it out of live
        return when { seriesGroup && !movieGroup -> Kind.Series; else -> Kind.Live }
    }

    data class EpisodeRef(val show: String, val season: Int, val number: Int)

    private val SXXEYY = Regex("""(?i)\bS(\d{1,2})\s*[ ._-]?\s*E(\d{1,3})\b""")
    private val SEASON_EPISODE = Regex("""(?i)\bseason\s*(\d{1,2})\W{0,3}episode\s*(\d{1,3})\b""")
    private val NXM = Regex("""\b(\d{1,2})x(\d{2,3})\b""")
    private val AR = Regex("""الموسم\s*(\d{1,2}).{0,6}الحلقة\s*(\d{1,3})""")

    /** «Name S01 E02», «Name S01E02», «Name Season 1 Episode 2», «Name 1x02», «Name الموسم 1 الحلقة 2» → show, season, episode. */
    fun parseEpisode(title: String): EpisodeRef? {
        for (r in listOf(SXXEYY, SEASON_EPISODE, NXM, AR)) {
            val m = r.find(title) ?: continue
            val show = title.substring(0, m.range.first).trim().trim('-', ':', '|', '.', '_', ' ', '/').trim()
            val n = m.groupValues
            if (show.isEmpty()) continue
            return EpisodeRef(show, n[1].toInt(), n[2].toInt())
        }
        return null
    }

    data class Split(val live: List<Channel>, val movies: List<Channel>, val episodes: List<Pair<EpisodeRef?, Channel>>)

    /** Live channels stay in the live list; everything else goes to movies or series (an episode without a recognisable title is a one-line series entry of its own). */
    fun split(channels: List<Channel>): Split {
        val live = ArrayList<Channel>(channels.size); val movies = ArrayList<Channel>(); val eps = ArrayList<Pair<EpisodeRef?, Channel>>()
        for (c in channels) when (classify(c.url, c.group, c.name)) {
            Kind.Live -> live += c
            Kind.Movie -> movies += c
            Kind.Series -> eps += parseEpisode(c.name) to c
        }
        return Split(live, movies, eps)
    }
}
