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
    @Test fun streamingParseKeepsWhatWasReadWhenTheAnswerIsCut() {
        val acc2 = XtreamAccount("http://h", "u", "p")
        val body = """[{"stream_id":1,"name":"A"},{"stream_id":"2","name":"B"},{"stream_id":3,"na"""
        val r = XtreamParser.parseStreams(body.byteInputStream(), acc2)
        assertEquals(listOf("1", "2"), r.channels.map { it.id }, "the entries before the cut are kept")
        val big = "[" + (1..2000).joinToString(",") { """{"stream_id":$it,"name":"C$it","category_id":"1"}""" } + "]"
        assertEquals(2000, XtreamParser.parseStreams(big.byteInputStream(), acc2).channels.size)
        assertEquals(2000, XtreamParser.parseMovies(big.byteInputStream(), acc2).size)
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

class HostileInputTest {
    @Test fun m3uRejectsLocalSchemesAndHugeLines() {
        val r = M3uParser.parse("#EXTM3U\n#EXTINF:-1,A\nfile:///etc/passwd\n#EXTINF:-1,B\ncontent://x/y\n#EXTINF:-1,C\nintent://x#Intent;end\n#EXTINF:-1,D\nhttp://example.com/ok\n" + "x".repeat(20_000) + "\n")
        assertEquals(listOf("D"), r.channels.map { it.name })
    }

    @Test fun xmltvDoesNotExpandExternalEntities() {
        val xml = """<?xml version="1.0"?><!DOCTYPE tv [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <tv><programme start="20240101000000 +0000" stop="20240101010000 +0000" channel="c"><title>&x;</title></programme></tv>"""
        val p = XmltvParser.parse(xml)
        assertTrue(p.none { it.title.contains("root:") }, "entity must not be resolved")
    }

    @Test fun xmltvLimitsFieldLengthsAndSurvivesTruncation() {
        val long = "t".repeat(5000)
        val p = XmltvParser.parse("""<tv><programme start="20240101000000" stop="20240101010000" channel="c"><title>$long</title></programme><programme start="2024""").single()
        assertEquals(200, p.title.length)
    }

    @Test fun xtreamJsonOfWrongShapeIsHarmless() {
        val acc = XtreamAccount("h.example", "u", "p")
        assertTrue(XtreamParser.parseStreams("{\"a\":1}", acc).channels.isEmpty())
        assertTrue(XtreamParser.parseMovies("[[],1,null,\"x\"]", acc).isEmpty())
        assertEquals(emptyMap(), XtreamParser.parseCategories("not json"))
    }
}

class XtreamLinkTest {
    @Test fun aGetPhpLinkWithOnlyLoginKeysIsAnXtreamAccount() {
        val a = XtreamLink.parse("http://host.example:8000/get.php?username=bob&password=p%40ss&type=m3u_plus&output=ts")!!
        assertEquals("http://host.example:8000", a.server); assertEquals("bob", a.user); assertEquals("p@ss", a.pass)
        assertEquals("https://h.example/sub", XtreamLink.parse("https://h.example/sub/player_api.php?username=u&password=p")!!.server)
    }
    @Test fun anythingElseStaysAPlainM3u() {
        for (u in listOf("http://h/list.m3u", "http://h/get.php?username=u&password=p&token=abc", "http://h/get.php?username=u", "http://h/get.php?username=u&username=v&password=p",
            "http://h/get.php?username=u&password=p#x", "ftp://h/get.php?username=u&password=p", "http://h/other.php?username=u&password=p", "http://h/get.php?username=&password=p", ""))
            assertNull(XtreamLink.parse(u), u)
    }
    @Test fun streamIdsAreReadFromTheEndOfALiveUrl() {
        assertEquals("123", XtreamLink.streamIdOf("http://h:80/u/p/123")); assertEquals("77", XtreamLink.streamIdOf("http://h/live/u/p/77.m3u8"))
        assertEquals("5", XtreamLink.streamIdOf("http://h/live/u/p/5.ts?x=1")); assertNull(XtreamLink.streamIdOf("http://h/live/u/p/abc.m3u8"))
    }
}

class XtreamEpisodesTest {
    private val acc = XtreamAccount("http://h", "u", "p")
    @Test fun episodesAsAMapOfSeasonsAsAListOfSeasonListsAndAsAFlatListAreAllRead() {
        val ep = { id: Int, s: Int, n: Int -> """{"id":"$id","season":$s,"episode_num":$n,"title":"E$n","container_extension":"mkv"}""" }
        val map = """{"episodes":{"1":[${ep(1, 1, 1)},${ep(2, 1, 2)}],"2":[${ep(3, 2, 1)}]}}"""
        val lists = """{"episodes":[[${ep(1, 1, 1)},${ep(2, 1, 2)}],[${ep(3, 2, 1)}]]}"""
        val flat = """{"episodes":[${ep(1, 1, 1)},${ep(2, 1, 2)},${ep(3, 2, 1)}]}"""
        for (b in listOf(map, lists, flat)) assertEquals(listOf("1", "2", "3"), XtreamParser.parseEpisodes(b, acc).map { it.id }, b)
    }
}

class XmltvWindowTest {
    @Test fun onlyTheWindowAndAFewProgrammesPerChannelAreKept() {
        val h = 3_600_000L
        fun t(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms)) + " +0000"
        val base = 1_700_000_000_000L
        val xml = "<tv>" + (-30..60).joinToString("") { i -> """<programme start="${t(base + i * h)}" stop="${t(base + (i + 1) * h)}" channel="a"><title>P$i</title></programme>""" } + "</tv>"
        val all = XmltvParser.parse(xml.byteInputStream())
        assertEquals(91, all.size)
        val w = XmltvParser.parse(xml.byteInputStream(), null, fromMs = base - 2 * h, toMs = base + 14 * h, perChannelMax = 12)
        assertEquals(12, w.size, "capped per channel")
        assertEquals("P-3", w.first().title, "the programme that was running 2 h ago still overlaps the window")
        assertEquals(0, XmltvParser.parse(xml.byteInputStream(), null, fromMs = base + 100 * h, toMs = base + 120 * h).size, "nothing in the window")
    }
}

class XtreamOrderTest {
    @Test fun moviesAndSeriesKeepTheOrderTheServerSent() {
        val acc = XtreamAccount("http://h", "u", "p")
        val m = XtreamParser.parseMovies("""[{"stream_id":9,"name":"Z","added":"1"},{"stream_id":3,"name":"A","added":"999"},{"stream_id":5,"name":"M","added":"50"}]""", acc)
        assertEquals(listOf("Z", "A", "M"), m.map { it.name })
        val c = XtreamParser.parseCategories("""[{"category_id":"7","category_name":"Second"},{"category_id":"2","category_name":"First"}]""")
        assertEquals(listOf("Second", "First"), c.values.toList(), "categories too")
    }
}
