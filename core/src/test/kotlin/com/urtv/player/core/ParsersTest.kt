package com.urtv.player.core

import kotlin.test.*

class M3uParserTest {
    @Test fun arabicNamesAndAttrs() {
        val r = M3uParser.parse("""
            #EXTM3U url-tvg="http://e.example/epg.xml"
            #EXTINF:-1 tvg-id="a.sa" tvg-logo="http://l/a.png" group-title="أخبار",قناة الإخبارية, السعودية
            http://s.example/a.m3u8
        """.trimIndent())
        assertEquals(1, r.channels.size)
        val c = r.channels[0]
        assertEquals("قناة الإخبارية, السعودية", c.name)
        assertEquals("أخبار", c.group); assertEquals("a.sa", c.epgId)
        assertEquals(listOf("http://e.example/epg.xml"), r.epgUrls)
    }
    @Test fun commaInsideQuotedAttr() {
        val c = M3uParser.parse("#EXTINF:-1 group-title=\"News, World\",Name\nhttps://x/y").channels.single()
        assertEquals("Name", c.name); assertEquals("News, World", c.group)
    }
    @Test fun malformedInputDoesNotThrow() {
        val r = M3uParser.parse("﻿#EXTM3U\r\n#EXTINF:-1,NoUrl\n#EXTINF:-1,Ok\r\nhttp://ok/1\nnot a url\n#EXTINF\n")
        assertEquals(1, r.channels.size); assertEquals("Ok", r.channels[0].name); assertTrue(r.skipped >= 2)
    }
    @Test fun emptyAndGarbage() {
        assertTrue(M3uParser.parse("").channels.isEmpty())
        assertTrue(M3uParser.parse("<html>404</html>").channels.isEmpty())
    }
    @Test fun extgrpAndBareUrl() {
        val r = M3uParser.parse("#EXTGRP:Sport\nhttp://h/live/stream.ts?x=1")
        assertEquals("Sport", r.channels[0].group); assertEquals("stream.ts", r.channels[0].name)
    }
}

class XtreamTest {
    private val acc = XtreamAccount("host:8080/", "u s", "p")
    @Test fun urls() {
        assertEquals("http://host:8080", acc.base)
        assertEquals("http://host:8080/live/u%20s/p/5.m3u8", acc.streamUrl("5"))
        assertEquals("http://h", XtreamAccount.normalizeServer("http://h/player_api.php?x=1"))
    }
    @Test fun parse() {
        val cats = XtreamParser.parseCategories("""[{"category_id":"1","category_name":"رياضة"},{"x":1}]""")
        assertEquals(mapOf("1" to "رياضة"), cats)
        val r = XtreamParser.parseStreams("""[{"num":1,"name":"بي إن","stream_id":77,"category_id":"1","epg_channel_id":"","stream_icon":null},"junk",{"name":"noid"}]""", acc, cats)
        assertEquals(1, r.channels.size); assertEquals(2, r.skipped)
        assertEquals("رياضة", r.channels[0].group); assertNull(r.channels[0].epgId)
    }
    @Test fun badJson() {
        assertTrue(XtreamParser.parseCategories("<html>").isEmpty())
        assertTrue(XtreamParser.parseStreams("{}", acc).channels.isEmpty())
    }
}

class XmltvTest {
    @Test fun parsesWithOffsetAndArabic() {
        val x = """<?xml version="1.0" encoding="UTF-8"?><tv>
          <channel id="a"><display-name>A</display-name></channel>
          <programme start="20240101120000 +0300" stop="20240101130000 +0300" channel="a"><title lang="ar">نشرة الأخبار</title><desc>وصف</desc></programme>
          <programme start="bad" stop="20240101130000" channel="a"><title>x</title></programme>
          <programme start="20240101130000 +0000" stop="20240101120000 +0000" channel="a"><title>reversed</title></programme>
          <programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="b"><title>B</title></programme></tv>"""
        val p = XmltvParser.parse(x)
        assertEquals(2, p.size)
        assertEquals("نشرة الأخبار", p[0].title)
        assertEquals(1704099600000L, p[0].startMs) // 2024-01-01T09:00:00Z
        assertEquals(1, XmltvParser.parse(x, setOf("b")).size)
    }
    @Test fun truncatedKeepsPartial() {
        val p = XmltvParser.parse("""<tv><programme start="20240101000000" stop="20240101010000" channel="a"><title>T</title></programme><programme start="2024""")
        assertEquals(1, p.size)
    }
    @Test fun garbage() { assertTrue(XmltvParser.parse("not xml").isEmpty()) }
    @Test fun time() { assertEquals(0L, XmltvParser.parseTime("19700101000000 +0000")); assertNull(XmltvParser.parseTime("x")) }
}

class XtreamVodTest {
    private val acc = XtreamAccount("host.example:8080", "user", "p w")

    @Test fun moviesTolerateStringsAndMissingFields() {
        val m = XtreamParser.parseMovies("""[{"stream_id":"7","name":"فيلم تجريبي","container_extension":"mkv","category_id":"3","rating":"7.5"},{"name":"no id"},{"stream_id":9}]""", acc, mapOf("3" to "أفلام"))
        assertEquals(2, m.size)
        assertEquals("http://host.example:8080/movie/user/p%20w/7.mkv", m[0].url); assertEquals("أفلام", m[0].category)
        assertTrue(m[1].url.endsWith("/9.mp4"))
    }

    @Test fun seriesAndEpisodes() {
        assertEquals("S", XtreamParser.parseSeriesList("""[{"series_id":1,"name":"S","cover":"http://i/c.jpg","releaseDate":"2020-05-01"}]""").single().name)
        val eps = XtreamParser.parseEpisodes("""{"episodes":{"2":[{"id":"22","episode_num":1,"title":"B","season":2,"container_extension":"mp4"}],"1":[{"id":"12","episode_num":2,"title":"A2","season":1},{"id":"11","episode_num":"1","title":"A1","season":1,"info":{"plot":"p","duration_secs":"600"}}]}}""", acc)
        assertEquals(listOf("A1", "A2", "B"), eps.map { it.title })
        assertEquals(600, eps[0].durationSec); assertTrue(eps[2].url.contains("/series/user/"))
    }

    @Test fun garbageIsEmpty() {
        assertTrue(XtreamParser.parseMovies("<html>", acc).isEmpty()); assertTrue(XtreamParser.parseEpisodes("[]", acc).isEmpty())
        assertEquals("", XtreamParser.parseVodInfo("nope").plot)
    }

    @Test fun catchupUrlUsesUtcAndArchiveFlag() {
        val u = acc.catchupUrl("5", 1_700_000_000_000, 90)
        assertTrue(u.contains("stream=5&start=2023-11-14:22-13&duration=90"), u)
        val c = XtreamParser.parseStreams("""[{"stream_id":"5","name":"x","tv_archive":1,"tv_archive_duration":"3"}]""", acc).channels.single()
        assertEquals(3, c.archiveDays); assertEquals("5", c.streamId)
    }
}
