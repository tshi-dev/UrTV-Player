package com.urtv.player

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Runs only where the TMDB_API_KEY secret exists (the release job): asks the real service about a few real titles through the same client the
 * app uses and prints what came back (titles and counts, never the key). Skipped everywhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TmdbLiveCheckTest {
    private val key = System.getenv("TMDB_API_KEY").orEmpty()

    @Test fun realTitlesComeBackWithPostersStoriesCastAndTrailers() {
        assumeTrue(key.isNotBlank())
        val c = com.urtv.player.data.TmdbClient(ApplicationProvider.getApplicationContext(), key)
        var found = 0
        for ((title, year, series) in listOf(Triple("Inception", "2010", false), Triple("The Dark Knight", "2008", false), Triple("الفيل الأزرق", "2014", false), Triple("Game of Thrones", null, true), Triple("باب الحارة", null, true))) {
            val i = runBlocking { c.info(title, year, series) }
            if (i == null) { println("TMDBCHECK $title: not found"); continue }
            found++
            val arabic = i.overview.any { it in '\u0600'..'\u06FF' }
            println("TMDBCHECK $title: year=${i.year} runtime=${i.runtimeMin} age='${i.age}' rating=${i.rating} story=${i.overview.length} chars (${if (arabic) "Arabic" else if (i.overview.isBlank()) "none" else "English"}) genres=${i.genres.size} cast=${i.cast.size} photos=${i.cast.count { it.photo != null }} trailer=${i.trailerKey != null} poster=${i.poster != null} backdrop=${i.backdrop != null}")
        }
        val t = runBlocking { c.trending() }
        println("TMDBCHECK trending: ${t.size} titles, first: ${t.take(3).joinToString { it.title }}")
        assertTrue(found >= 3, "only $found of 5 titles found")
        assertTrue(t.size >= 10, "trending gave ${t.size}")
    }
}
