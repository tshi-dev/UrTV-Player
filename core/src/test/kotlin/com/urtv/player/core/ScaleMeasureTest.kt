package com.urtv.player.core

import kotlin.test.Test

/** Prints (never asserts) what the in-memory path costs for a 150k films + 55k channels + 20k series account: parse time and the heap the lists keep. */
class ScaleMeasureTest {
    private fun heapMb(): Long { repeat(3) { System.gc() }; val r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) / 1_048_576 }
    @Test fun inMemoryListsOnABigAccount() {
        val acc = XtreamAccount("http://host.example:8000", "user", "pass")
        val cats = XtreamParser.parseCategories(SyntheticAccount.cats()); val vodJson = SyntheticAccount.vod(); val liveJson = SyntheticAccount.live(); val serJson = SyntheticAccount.series()
        println("SCALE json sizes: films ${vodJson.length / 1_048_576} MB, live ${liveJson.length / 1_048_576} MB, series ${serJson.length / 1_048_576} MB")
        val base = heapMb()
        var t = System.nanoTime()
        val movies = XtreamParser.parseMovies(vodJson.byteInputStream(), acc, cats)
        println("SCALE parse ${movies.size} films: ${(System.nanoTime() - t) / 1_000_000} ms, heap kept ${heapMb() - base} MB")
        t = System.nanoTime(); val live = XtreamParser.parseStreams(liveJson.byteInputStream(), acc, cats).channels
        println("SCALE parse ${live.size} channels: ${(System.nanoTime() - t) / 1_000_000} ms, heap kept (all lists) ${heapMb() - base} MB")
        t = System.nanoTime(); val ser = XtreamParser.parseSeriesList(serJson.byteInputStream(), cats)
        println("SCALE parse ${ser.size} series: ${(System.nanoTime() - t) / 1_000_000} ms, heap kept (all lists) ${heapMb() - base} MB")
        // what the home screen did on the main thread with the lists (poster rows, trending index by title)
        t = System.nanoTime(); val by = HashMap<String, Movie>(); for (m in movies) by.putIfAbsent(Tmdb.normalize(m.name), m)
        println("SCALE trending title index over films (was on the main thread): ${(System.nanoTime() - t) / 1_000_000} ms")
        t = System.nanoTime(); val sorted = movies.map { it.added }.sortedDescending().take(24)
        println("SCALE «recently added» sort: ${(System.nanoTime() - t) / 1_000_000} ms (${sorted.size})")
        t = System.nanoTime(); val q = movies.filter { it.name.contains("Number 1234", true) }
        println("SCALE one search keystroke over films: ${(System.nanoTime() - t) / 1_000_000} ms (${q.size})")
    }
}
