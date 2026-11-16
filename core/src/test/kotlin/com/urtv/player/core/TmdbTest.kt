package com.urtv.player.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbTest {
    @Test fun searchAnswersAreMatchedByTitleAndYear() {
        val hits = Tmdb.parseHits("""{"results":[{"id":1,"title":"Heat","release_date":"2022-01-01","poster_path":"/a.jpg","popularity":3.1},{"id":2,"title":"Heat","release_date":"1995-12-15","poster_path":"/b.jpg"},{"id":3,"name":"Show","first_air_date":"2020-05-05"},{"nothing":1}]}""")
        assertEquals(3, hits.size)
        assertEquals(2L, Tmdb.bestHit(hits, "Heat (1995) [HD]", "1995")?.id, "year picks the right one")
        assertEquals(1L, Tmdb.bestHit(hits, "heat", null)?.id, "no year: the first with that title")
        assertEquals(3L, Tmdb.bestHit(hits, "Show", null)?.id, "tv names count as titles")
        assertNull(Tmdb.bestHit(hits, "Other", null))
    }

    @Test fun detailsCastTrailerAndAgeRatingAreRead() {
        val i = Tmdb.parseInfo("""{"id":9,"overview":"قصة","release_date":"2024-03-01","runtime":125,"vote_average":7.84,"genres":[{"name":"أكشن"},{"name":"دراما"}],"poster_path":"/p.jpg",
          "credits":{"cast":[{"name":"A","profile_path":"/a.jpg"},{"name":"B","profile_path":null}]},
          "videos":{"results":[{"site":"Vimeo","key":"v","type":"Trailer"},{"site":"YouTube","key":"teaser1","type":"Teaser"},{"site":"YouTube","key":"trail1","type":"Trailer"}]},
          "release_dates":{"results":[{"iso_3166_1":"FR","release_dates":[{"certification":"12"}]},{"iso_3166_1":"US","release_dates":[{"certification":""},{"certification":"PG-13"}]}]}}""")!!
        assertEquals(9L, i.id); assertEquals("2024", i.year); assertEquals(125, i.runtimeMin); assertEquals(listOf("أكشن", "دراما"), i.genres)
        assertEquals(listOf("A", "B"), i.cast.map { it.name }); assertEquals("trail1", i.trailerKey, "a YouTube trailer, not a teaser or another site"); assertEquals("+13", i.age)
        assertEquals("https://image.tmdb.org/t/p/w185/a.jpg", Tmdb.profile(i.cast[0].photo))
    }

    @Test fun seriesUseContentRatingsAndEpisodeRunTime() {
        val i = Tmdb.parseInfo("""{"id":5,"first_air_date":"2021-01-01","episode_run_time":[45],"content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-MA"}]}}""")!!
        assertEquals("+18", i.age); assertEquals(45, i.runtimeMin); assertEquals("2021", i.year)
        assertNull(Tmdb.parseInfo("<html>")); assertEquals(emptyList(), Tmdb.parseHits("garbage"))
    }

    @Test fun titlesAreComparedWithoutPunctuationOrTails() {
        assertEquals(Tmdb.normalize("The Movie: Part 2 (2019) [4K]"), Tmdb.normalize("the movie part 2"))
    }
}
