package com.urtv.player.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class M3uVodTest {
    private fun k(url: String, group: String = "", name: String = "") = M3uVod.classify(url, group, name)

    @Test fun theUrlLayoutDecidesFirst() {
        assertEquals(M3uVod.Kind.Movie, k("http://h/movie/u/p/12.mkv", "Sports"))
        assertEquals(M3uVod.Kind.Series, k("http://h/series/u/p/12.mp4", "Anything"))
        assertEquals(M3uVod.Kind.Live, k("http://h/live/u/p/12.m3u8", "Movies HD 24/7"), "a live channel in a movie group stays live")
    }
    @Test fun fileExtensionsAndGroupNamesCome_Next() {
        assertEquals(M3uVod.Kind.Movie, k("http://h/files/a.mp4", "Action"))
        assertEquals(M3uVod.Kind.Series, k("http://h/files/a.mp4", "مسلسلات عربية"))
        assertEquals(M3uVod.Kind.Series, k("http://h/files/a.mkv", "TV", "Show S01 E02"))
        assertEquals(M3uVod.Kind.Live, k("http://h/u/p/55", "Sport"), "no telling URL or group: live")
        assertEquals(M3uVod.Kind.Live, k("http://h/u/p/55.ts", "Series Channel"))
    }
    @Test fun episodeTitlesAreSplitIntoShowSeasonAndNumber() {
        assertEquals(M3uVod.EpisodeRef("Breaking Bad", 1, 2), M3uVod.parseEpisode("Breaking Bad S01 E02"))
        assertEquals(M3uVod.EpisodeRef("Show - Name", 3, 14), M3uVod.parseEpisode("Show - Name - S03E14 - Title"))
        assertEquals(M3uVod.EpisodeRef("Show", 2, 5), M3uVod.parseEpisode("Show Season 2 Episode 5"))
        assertEquals(M3uVod.EpisodeRef("Show", 4, 10), M3uVod.parseEpisode("Show 4x10"))
        assertEquals(M3uVod.EpisodeRef("باب الحارة", 2, 7), M3uVod.parseEpisode("باب الحارة الموسم 2 الحلقة 7"))
        assertNull(M3uVod.parseEpisode("Channel 24 HD")); assertNull(M3uVod.parseEpisode("S01E02"), "no show name: not an episode")
    }
    @Test fun splitKeepsLiveLightAndSortsTheRest() {
        val c = { n: String, u: String, g: String -> Channel(n, n, u, g) }
        val s = M3uVod.split(listOf(c("A", "http://h/live/u/p/1.m3u8", "Sport"), c("Film", "http://h/movie/u/p/2.mp4", "Action"), c("X S01 E01", "http://h/series/u/p/3.mkv", "Drama"), c("Y", "http://h/u/p/9", "News")))
        assertEquals(listOf("A", "Y"), s.live.map { it.name }); assertEquals(listOf("Film"), s.movies.map { it.name }); assertEquals(1, s.episodes.size)
    }
}
