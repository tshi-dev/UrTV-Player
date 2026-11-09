package com.urtv.player.server

import kotlin.test.*

class SubtitleChoiceTest {
    private fun au(i: Int, l: String, d: Boolean = false, ch: Int = 2, t: String? = null) = StreamInfo(i, type = StreamType.Audio, language = l, isDefault = d, channels = ch, title = t)
    private fun su(i: Int, l: String, forced: Boolean = false, codec: String = "subrip", hi: Boolean = false, d: Boolean = false, ext: Boolean = false) =
        StreamInfo(i, type = StreamType.Subtitle, language = l, isForced = forced, codec = codec, isHearingImpaired = hi, isDefault = d, isExternal = ext)
    private val prefs = LangPrefs(listOf("ar", "en"), listOf("ar", "en"))

    @Test fun langCodesNormalise() {
        assertEquals("ar", Lang.norm("ara")); assertEquals("ar", Lang.norm("AR-SA")); assertEquals("en", Lang.norm("eng")); assertEquals("fr", Lang.norm("fre"))
        assertNull(Lang.norm("und")); assertNull(Lang.norm(null)); assertEquals("ara", Lang.toIso3("ar")); assertEquals("eng", Lang.toIso3("en"))
        assertTrue(Lang.isRtl("ara")); assertFalse(Lang.isRtl("eng"))
    }
    @Test fun audioFollowsPreferenceThenDefault() {
        val a = listOf(au(1, "eng", d = true), au(2, "ara"), au(3, "fra"))
        assertEquals(2, SubtitleChoice.pickAudio(a, prefs)?.index)
        assertEquals(1, SubtitleChoice.pickAudio(a, LangPrefs(listOf("de"), listOf("de")))?.index)
        assertEquals(5, SubtitleChoice.pickAudio(listOf(au(4, "ara", t = "Director commentary"), au(5, "ara", ch = 6)), prefs)?.index)
        assertNull(SubtitleChoice.pickAudio(emptyList(), prefs))
    }
    @Test fun foreignAudioGetsFullSubtitlesInPreferredLanguage() {
        val subs = listOf(su(10, "eng"), su(11, "ara", hi = true), su(12, "ara"), su(13, "ara", forced = true))
        assertEquals(12, SubtitleChoice.pickSubtitle(subs, au(1, "eng"), prefs)?.index)
    }
    @Test fun nativeAudioOnlyForcedOrNothing() {
        val subs = listOf(su(10, "eng"), su(12, "ara"), su(13, "ara", forced = true))
        assertEquals(13, SubtitleChoice.pickSubtitle(subs, au(1, "ara"), prefs)?.index)
        assertNull(SubtitleChoice.pickSubtitle(subs.filter { !it.isForced }, au(1, "ara"), prefs))
        assertEquals(12, SubtitleChoice.pickSubtitle(subs, au(1, "ara"), prefs, SubtitleMode.Always)?.index)
        assertNull(SubtitleChoice.pickSubtitle(subs, au(1, "eng"), prefs, SubtitleMode.Off))
    }
    @Test fun fallsBackToSecondLanguageAndPrefersTextOverImage() {
        assertEquals(20, SubtitleChoice.pickSubtitle(listOf(su(20, "eng")), au(1, "fra"), prefs)?.index)
        val subs = listOf(su(30, "ara", codec = "pgs"), su(31, "ara", codec = "subrip"))
        assertEquals(31, SubtitleChoice.pickSubtitle(subs, au(1, "eng"), prefs)?.index)
        assertNull(SubtitleChoice.pickSubtitle(listOf(su(40, "jpn")), au(1, "eng"), prefs))
    }
    @Test fun imageFormatsAreClassified() {
        assertTrue(SubtitleChoice.isImage(su(1, "ara", codec = "PGSSUB"))); assertFalse(SubtitleChoice.needsBurnIn(su(1, "ara", codec = "pgssub")))
        assertTrue(SubtitleChoice.needsBurnIn(su(1, "ara", codec = "dvdsub"))); assertTrue(SubtitleChoice.needsBurnIn(su(1, "ara", codec = "vobsub")))
        assertFalse(SubtitleChoice.isImage(su(1, "ara", codec = "ass")))
    }
}

class SubtitleParserTest {
    @Test fun srtWithArabicAndTags() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>مرحبا</i> بالعالم!\r\n\r\n2\r\n00:01:02,5 --> 00:01:04,000\r\n{\\an8}Top line\r\nsecond"
        val c = SubtitleParser.parse(srt)
        assertEquals(2, c.size); assertEquals("مرحبا بالعالم!", c[0].text); assertEquals(1000, c[0].startMs); assertEquals(3500, c[0].endMs)
        assertTrue(c[1].top); assertEquals("Top line\nsecond", c[1].text); assertEquals(62_500, c[1].startMs)
    }
    @Test fun webVtt() {
        val v = "WEBVTT\n\nNOTE x\n\ncue1\n00:01.000 --> 00:02.000 line:0\nHello &amp; bye\n\n01:00:00.000 --> 01:00:01.000\nLate"
        val c = SubtitleParser.parse(v)
        assertEquals(listOf("Hello & bye", "Late"), c.map { it.text }); assertTrue(c[0].top); assertEquals(3_600_000, c[1].startMs)
    }
    @Test fun assDialogue() {
        val ass = "[Script Info]\nTitle: x\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\\i1}Hi{\\i0}\\Nthere, friend\nDialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,{\\an8}Up"
        val c = SubtitleParser.parse(ass)
        assertEquals("Hi\nthere, friend", c[0].text); assertEquals(2500, c[0].endMs); assertTrue(c[1].top)
    }
    @Test fun garbageNeverThrows() {
        listOf("", "\u0000\u0000", "-->", "1\n00:00:01,000 --> \n", "WEBVTT\n\n" + "x".repeat(100_000), "00:00:05,000 --> 00:00:01,000\nbackwards").forEach { SubtitleParser.parse(it) }
        assertTrue(SubtitleParser.parse("00:00:05,000 --> 00:00:01,000\nbackwards").isEmpty())
    }
    @Test fun lookupWithSyncOffset() {
        val t = CueTrack(SubtitleParser.parse("1\n00:00:10,000 --> 00:00:12,000\nA\n\n2\n00:00:11,000 --> 00:00:15,000\nB\n\n3\n00:00:20,000 --> 00:00:21,000\nC"))
        assertTrue(t.at(9_999).isEmpty()); assertEquals(listOf("A"), t.at(10_500).map { it.text }); assertEquals(listOf("A", "B"), t.at(11_500).map { it.text })
        assertEquals(listOf("B"), t.at(13_000).map { it.text }); assertTrue(t.at(16_000).isEmpty())
        // Subtitles late by 2 s: shift them 2 s later => at 12.5 s we should now see A (which really starts at 12 s), not B only.
        assertEquals(listOf("A"), t.at(10_500 + 2000, offsetMs = 2000).map { it.text })
        assertEquals(listOf("C"), t.at(19_000, offsetMs = -1000).map { it.text })
    }
}

class BidiTest {
    @Test fun arabicLineGetsRtlMarkLatinGetsLtrMark() {
        assertEquals("‏مرحبا!", Bidi.fixLine("مرحبا!"))
        assertEquals("‏مرحبا!", Bidi.fixLine("‏مرحبا!"))        // idempotent
        assertEquals("‎...Hello", Bidi.fixLine("...Hello"))
        assertEquals("‏... كيف حالك؟", Bidi.fixLine("... كيف حالك؟"))
        assertEquals("123", Bidi.fixLine("123"))                            // no strong letter: untouched
        assertEquals("‏أهلا\n‎OK", Bidi.fix("أهلا\nOK"))
    }
    @Test fun mixedLineWithLatinFirstStaysLtr() { assertTrue(Bidi.fixLine("Netflix هو").startsWith("‎")) }
}

class SubtitleStyleTest {
    @Test fun valuesAreClamped() {
        val s = SubtitleStyle(size = 5f, bottom = -1f, font = "comic").coerced()
        assertEquals(0.12f, s.size); assertEquals(0.02f, s.bottom); assertEquals("sans", s.font)
    }
}

class BadgesTest {
    @Test fun labelsFromStreams() {
        val m = MediaItem("1", ItemType.Movie, "x", streams = listOf(
            StreamInfo(0, type = StreamType.Video, codec = "hevc", width = 3840, height = 2160, videoRange = "DOVIWithHDR10"),
            StreamInfo(1, type = StreamType.Audio, codec = "eac3", channels = 6, isDefault = true, title = "English Dolby Atmos")))
        assertEquals(listOf("4K", "Dolby Vision", "HEVC", "Dolby Atmos", "5.1"), Badges.of(m))
        assertEquals(listOf("1080p"), Badges.of(MediaItem("2", ItemType.Movie, "x", streams = listOf(StreamInfo(0, type = StreamType.Video, codec = "h264", height = 1080, videoRange = "SDR")))))
        assertTrue(Badges.of(MediaItem("3", ItemType.Movie, "x")).isEmpty())
    }
}
