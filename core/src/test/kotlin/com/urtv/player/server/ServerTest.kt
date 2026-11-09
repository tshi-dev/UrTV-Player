package com.urtv.player.server

import kotlin.test.*

class Fake : Transport {
    val seen = mutableListOf<Req>()
    val routes = mutableListOf<Pair<String, Resp>>()
    fun on(contains: String, body: String, code: Int = 200) { routes += contains to Resp(code, body) }
    override fun send(req: Req): Resp { seen += req; return routes.firstOrNull { req.method + " " + req.url contains it.first }?.second ?: Resp(404, "") }
    private infix fun String.contains(o: String) = this.contains(o, false)
}

private fun cfg(kind: ServerKind = ServerKind.Jellyfin, base: String = "http://192.168.1.10:8096") =
    ServerConfig("s1", kind, "Home", base, "u1", "sam", "SECRETTOKEN123")

class NetSafetyTest {
    @Test fun privateRanges() {
        listOf("192.168.1.5", "10.0.0.2", "172.16.0.1", "172.31.255.1", "169.254.1.1", "100.64.0.9", "127.0.0.1", "nas", "jellyfin.local", "[fd00::1]", "fe80::1", "localhost")
            .forEach { assertTrue(NetSafety.isPrivateHost(it), it) }
        listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.128.0.1", "example.com", "1.2.3.4", "300.1.1.1", "192.169.1.1", "evil.example.com.local.attacker.io", "2001:db8::1", "")
            .forEach { assertFalse(NetSafety.isPrivateHost(it), it) }
    }
    @Test fun httpOnlyForPrivateHosts() {
        assertTrue(NetSafety.isAllowedBase("http://192.168.1.2:8096"))
        assertTrue(NetSafety.isAllowedBase("https://media.example.com"))
        assertFalse(NetSafety.isAllowedBase("http://media.example.com"))
        assertFalse(NetSafety.isAllowedBase("http://8.8.8.8:8096"))
        assertFalse(NetSafety.isAllowedBase("file:///etc/passwd"))
        assertFalse(NetSafety.isAllowedBase("http://user:pw@192.168.1.2"))
        assertFalse(NetSafety.isAllowedBase("ftp://192.168.1.2"))
        assertFalse(NetSafety.isAllowedBase("http://192.168.1.2:99999"))
    }
    @Test fun candidatesPreferHttpsAndNeverPublicHttp() {
        assertEquals(listOf("https://nas.local", "https://nas.local:8920", "http://nas.local:8096"), NetSafety.candidates("nas.local"))
        assertEquals(listOf("https://media.example.com", "https://media.example.com:8920"), NetSafety.candidates("media.example.com"))
        assertEquals(listOf("https://192.168.1.2:8920", "http://192.168.1.2:8920"), NetSafety.candidates("192.168.1.2:8920"))
        assertEquals(emptyList(), NetSafety.candidates("http://evil.example.com"))
    }
    @Test fun redirectsCannotDowngrade() {
        assertFalse(NetSafety.redirectAllowed("https://a.example.com/x", "http://192.168.1.2/x"))
        assertFalse(NetSafety.redirectAllowed("http://192.168.1.2/x", "http://evil.example.com/x"))
        assertTrue(NetSafety.redirectAllowed("http://192.168.1.2/x", "https://evil.example.com/x"))
    }
}

class DiscoveryTest {
    private val reply = """{"Address":"http://192.168.1.10:8096","Id":"abc123","Name":"Home","EndpointAddress":null}"""
    @Test fun acceptsPrivateReply() {
        val d = Discovery.parseReply(reply.toByteArray(), "192.168.1.10")!!
        assertEquals("http://192.168.1.10:8096", d.baseUrl); assertEquals("Home", d.name)
    }
    @Test fun dropsPublicSenderAndBadAddresses() {
        assertNull(Discovery.parseReply(reply.toByteArray(), "8.8.8.8"))
        assertNull(Discovery.parseReply("""{"Address":"file:///etc","Id":"x"}""".toByteArray(), "192.168.1.10"))
    }
    @Test fun publicAdvertisedNameFallsBackToTheLanSender() {
        val d = Discovery.parseReply("""{"Address":"https://media.example.com","Id":"x","Name":"Home"}""".toByteArray(), "192.168.1.10")!!
        assertEquals("http://192.168.1.10:8096", d.baseUrl)
        assertEquals("http://192.168.1.10:8096", Discovery.parseReply("""{"Address":"http://8.8.8.8:8096","Id":"x","Name":"Evil"}""".toByteArray(), "192.168.1.10")!!.baseUrl)
    }
    @Test fun garbageAndHugeRepliesAreIgnored() {
        assertNull(Discovery.parseReply("not json".toByteArray(), "192.168.1.10"))
        assertNull(Discovery.parseReply(ByteArray(10_000) { 'a'.code.toByte() }, "192.168.1.10"))
        assertNull(Discovery.parseReply("[1,2]".toByteArray(), "192.168.1.10"))
    }
}

class RedactTest {
    @Test fun tokensNeverSurvive() {
        assertEquals("http://h/x?api_key=***&a=1", Redact.url("http://h/x?api_key=SECRET&a=1"))
        assertEquals("http://h/x?a=1&X-Plex-Token=***", Redact.url("http://h/x?a=1&X-Plex-Token=SECRET"))
        assertFalse(Redact.text("MediaBrowser Client=\"x\", Token=\"SECRET\"").contains("SECRET"))
        assertEquals("http://h/v?a=1", stripAuthParams("http://h/v?api_key=S&a=1&ApiKey=Z"))
        assertFalse(ServerException(ServerException.Reason.Http, "failed http://h/?api_key=SECRET").message!!.contains("SECRET"))
        assertFalse(cfg().toString().contains("SECRETTOKEN123"))
        assertFalse(Req("GET", "http://h/?X-Plex-Token=SECRET").toString().contains("SECRET"))
    }
}

class JellyfinTest {
    private val itemsJson = """{"Items":[
      {"Id":"m1","Type":"Movie","Name":"Example Movie","ProductionYear":2020,"Overview":"Plot","CommunityRating":7.8,"OfficialRating":"PG-13","RunTimeTicks":72000000000,
       "ImageTags":{"Primary":"tp","Logo":"tl"},"BackdropImageTags":["tb"],"Genres":["Drama"],"People":[{"Name":"A B","Role":"Hero","Type":"Actor"}],
       "UserData":{"PlaybackPositionTicks":18000000000,"Played":false},
       "MediaSources":[{"Id":"src1","Container":"mkv","Bitrate":8000000,"MediaStreams":[
         {"Index":0,"Type":"Video","Codec":"hevc","Width":3840,"Height":2160,"VideoRangeType":"HDR10"},
         {"Index":1,"Type":"Audio","Codec":"eac3","Language":"eng","Channels":6,"IsDefault":true},
         {"Index":2,"Type":"Subtitle","Codec":"subrip","Language":"ara","IsExternal":false},
         {"Index":3,"Type":"Subtitle","Codec":"PGSSUB","Language":"eng","IsForced":true}]}]},
      {"Id":"e1","Type":"Episode","Name":"Pilot","SeriesName":"Example Show","SeriesId":"s1","SeasonId":"se1","ParentIndexNumber":1,"IndexNumber":2,"SeriesPrimaryImageTag":"sp","ImageTags":{"Primary":"ep"},"ParentBackdropItemId":"s1","ParentBackdropImageTags":["pb"]}
    ],"TotalRecordCount":2}"""

    private fun backend(f: Fake, kind: ServerKind = ServerKind.Jellyfin) = JellyfinBackend(cfg(kind), f, "dev1")

    @Test fun parsesMoviesAndEpisodes() {
        val f = Fake(); f.on("/Items?", itemsJson); f.on("/Views", """{"Items":[{"Id":"L1","Name":"Films","CollectionType":"movies"},{"Id":"L2","Name":"Music","CollectionType":"music"}]}""")
        val b = backend(f)
        val libs = b.libraries(); assertEquals(1, libs.size); assertEquals(LibraryKind.Movies, libs[0].kind)
        val page = b.browse(libs[0], Query(sort = Sort.Added, descending = true, genre = "Drama", unplayedOnly = true))
        assertEquals(2, page.total)
        val m = page.items[0]
        assertEquals(2020, m.year); assertEquals(7.8, m.rating); assertEquals(7_200_000L, m.runtimeMs); assertEquals(1_800_000L, m.resumeMs)
        assertEquals(0.25f, m.progress)
        assertEquals("hevc", m.video?.codec); assertEquals("HDR10", m.video?.videoRange)
        assertEquals(listOf("ara", "eng"), m.subtitles.map { it.language })
        assertEquals("/Items/m1/Images/Backdrop/0?tag=tb", m.images[Img.Backdrop])
        val e = page.items[1]
        assertEquals("Example Show", e.seriesName); assertEquals(1, e.seasonNumber); assertEquals(2, e.episodeNumber)
        assertEquals("/Items/s1/Images/Primary?tag=sp", e.images[Img.Poster]); assertEquals("/Items/s1/Images/Backdrop/0?tag=pb", e.images[Img.Backdrop])
        val url = f.seen.last().url
        assertTrue("SortBy=DateCreated" in url && "Descending" in url && "Genres=Drama" in url && "Filters=IsUnplayed" in url, url)
    }

    @Test fun tokenGoesInHeaderNeverInUrls() {
        val f = Fake(); f.on("/Items", itemsJson)
        val b = backend(f)
        b.search("abc")
        assertTrue(f.seen.all { "SECRETTOKEN123" !in it.url })
        assertTrue(f.seen.all { it.headers["Authorization"]?.contains("Token=\"SECRETTOKEN123\"") == true })
        val m = b.parseItem(parseObj(itemsJson)!!.a("Items")[0])!!
        assertFalse(b.imageUrl(m, Img.Poster, 400)!!.contains("SECRETTOKEN"))
        assertTrue(b.imageUrl(m, Img.Poster, 400)!!.startsWith("http://192.168.1.10:8096/Items/m1/Images/Primary?tag=tp&maxWidth=400"))
    }

    @Test fun embyUsesItsPrefixAndHeaders() {
        val f = Fake(); f.on("/emby/Users/u1/Views", """{"Items":[]}""")
        backend(f, ServerKind.Emby).libraries()
        assertTrue(f.seen[0].url.contains("/emby/Users/u1/Views")); assertEquals("SECRETTOKEN123", f.seen[0].headers["X-Emby-Token"])
    }

    @Test fun directPlayWhenSupported() {
        val f = Fake(); f.on("/Items?", itemsJson)
        f.on("/PlaybackInfo", """{"PlaySessionId":"ps1","MediaSources":[{"Id":"src1","Container":"mkv","SupportsDirectPlay":true,"Bitrate":8000000,"MediaStreams":[
            {"Index":0,"Type":"Video","Codec":"hevc","Height":2160,"VideoRangeType":"HDR10"},{"Index":1,"Type":"Audio","Codec":"eac3","Language":"eng","IsDefault":true}]}]}""")
        val b = backend(f); val m = b.parseItem(parseObj(itemsJson)!!.a("Items")[0])!!
        val caps = DeviceCaps(setOf("hevc", "h264"), setOf("eac3", "aac"), setOf("mkv", "mp4"), 2160, hdr = true)
        val plan = b.playback(m, caps, PlayOptions())
        assertEquals(PlayMode.DirectPlay, plan.mode); assertFalse(plan.hls)
        assertTrue(plan.url.contains("/Videos/m1/stream?static=true&mediaSourceId=src1")); assertFalse(plan.url.contains("api_key")); assertFalse(plan.url.contains("SECRET"))
        assertEquals("ps1", plan.playSessionId)
    }

    @Test fun fallsBackToServerTranscodeAndStripsKeyFromUrl() {
        val f = Fake()
        f.on("/PlaybackInfo", """{"PlaySessionId":"ps2","MediaSources":[{"Id":"src1","Container":"mkv","SupportsDirectPlay":true,"TranscodingUrl":"/videos/m1/master.m3u8?MediaSourceId=src1&api_key=SECRETTOKEN123&VideoCodec=h264","MediaStreams":[
            {"Index":0,"Type":"Video","Codec":"av1","Height":2160},{"Index":1,"Type":"Audio","Codec":"aac","Language":"eng"}]}]}""")
        val b = backend(f); val m = MediaItem("m1", ItemType.Movie, "x")
        val plan = b.playback(m, DeviceCaps(), PlayOptions(startMs = 60_000))      // default caps: h264 only
        assertEquals(PlayMode.Transcode, plan.mode); assertTrue(plan.hls)
        assertEquals("http://192.168.1.10:8096/videos/m1/master.m3u8?MediaSourceId=src1&VideoCodec=h264", plan.url)
        assertTrue("StartTimeTicks" in f.seen[0].body!!)
    }

    @Test fun burnInForVobSubForcesTranscode() {
        val f = Fake(); f.on("/PlaybackInfo", """{"MediaSources":[{"Id":"s","Container":"mkv","SupportsDirectPlay":true,"TranscodingUrl":"/videos/m/master.m3u8?x=1","MediaStreams":[
            {"Index":0,"Type":"Video","Codec":"h264","Height":1080},{"Index":1,"Type":"Audio","Codec":"aac"},{"Index":4,"Type":"Subtitle","Codec":"dvdsub","Language":"eng"}]}]}""")
        val plan = backend(f).playback(MediaItem("m", ItemType.Movie, "x", streams = listOf(StreamInfo(4, type = StreamType.Subtitle, codec = "dvdsub"))), DeviceCaps(containers = setOf("mkv")), PlayOptions(subtitleIndex = 4))
        assertEquals(PlayMode.Transcode, plan.mode); assertTrue("\"SubtitleStreamIndex\":4" in f.seen[0].body!!)
    }

    @Test fun markersFromSegments() {
        val f = Fake(); f.on("/MediaSegments/e1", """{"Items":[{"Type":"Intro","StartTicks":300000000,"EndTicks":900000000},{"Type":"Outro","StartTicks":35000000000,"EndTicks":36000000000},{"Type":"Commercial","StartTicks":1,"EndTicks":2}]}""")
        val ms = backend(f).markers(MediaItem("e1", ItemType.Episode, "x"))
        assertEquals(listOf(Marker(MarkerKind.Intro, 30_000, 90_000), Marker(MarkerKind.Credits, 3_500_000, 3_600_000)), ms)
    }

    @Test fun progressReportsCarrySessionAndNeverThrow() {
        val f = Fake(); f.on("/Sessions/Playing", "", 500)
        val b = backend(f); val plan = PlaybackPlan("m1", "src1", "u", PlayMode.DirectPlay, "", "ps", false, emptyList(), null, 0, emptyMap())
        b.reportProgress(plan, 12_345, true)            // 500 from server: swallowed
        val body = f.seen[0].body!!
        assertTrue("\"PositionTicks\":123450000" in body && "\"IsPaused\":true" in body && "\"PlaySessionId\":\"ps\"" in body, body)
    }

    @Test fun subtitleSearchGoesThroughTheServerPlugin() {
        val f = Fake(); f.on("RemoteSearch/Subtitles/ara", """[{"Id":"abc","Name":"Movie.2020.ar","ProviderName":"OpenSubtitles","Format":"srt","ThreeLetterISOLanguageName":"ara","DownloadCount":120}, 5, "x", {"NoId":1}]""")
        val subs = backend(f).searchSubtitles(MediaItem("m1", ItemType.Movie, "x"), "ar")
        assertEquals(1, subs.size); assertEquals("OpenSubtitles", subs[0].provider)
        assertTrue(f.seen[0].url.endsWith("/Items/m1/RemoteSearch/Subtitles/ara"))
    }

    @Test fun authErrorsAreTyped() {
        val f = Fake(); f.on("/Views", "", 401)
        val e = assertFailsWith<ServerException> { backend(f).libraries() }
        assertEquals(ServerException.Reason.Auth, e.reason)
    }

    @Test fun loginAndQuickConnect() {
        val f = Fake()
        f.on("/System/Info/Public", """{"Id":"sid","ServerName":"Home","Version":"10.9.0","ProductName":"Jellyfin Server"}""")
        f.on("AuthenticateByName", """{"AccessToken":"tok","User":{"Id":"u1","Name":"sam"}}""")
        f.on("/QuickConnect/Enabled", "true"); f.on("/QuickConnect/Initiate", """{"Code":"123456","Secret":"sec"}""")
        f.on("/QuickConnect/Connect", """{"Authenticated":true}"""); f.on("WithQuickConnect", """{"AccessToken":"tok2","User":{"Id":"u1","Name":"sam"}}""")
        val a = JellyfinAuth(ServerKind.Jellyfin, "http://192.168.1.10:8096", "dev", f)
        assertEquals("Home", a.info()!!.name)
        assertEquals("tok", a.login("sam", "pw").token)
        assertTrue(a.quickConnectEnabled()); val qc = a.quickConnectStart(); assertEquals("123456", qc.code)
        assertTrue(a.quickConnectApproved(qc.secret)); assertEquals("tok2", a.quickConnectFinish(qc.secret).token)
        assertTrue(f.seen.none { it.url.contains("pw") && it.method == "GET" })
    }

    @Test fun wrongPasswordAndHostileLoginReplies() {
        val f = Fake(); f.on("AuthenticateByName", "", 401)
        assertEquals(ServerException.Reason.Auth, assertFailsWith<ServerException> { JellyfinAuth(ServerKind.Jellyfin, "http://192.168.1.10", "d", f).login("a", "b") }.reason)
        val g = Fake(); g.on("AuthenticateByName", """{"AccessToken":"","User":{}}""")
        assertFailsWith<ServerException> { JellyfinAuth(ServerKind.Jellyfin, "http://192.168.1.10", "d", g).login("a", "b") }
        val h = Fake(); h.on("/System/Info/Public", "<html>not jellyfin</html>")
        assertNull(JellyfinAuth(ServerKind.Jellyfin, "http://192.168.1.10", "d", h).info())
    }
}

class HostileJsonTest {
    private val hostile = listOf(
        "", "null", "[]", "[[[[[[[[[[1]]]]]]]]]]", "{", """{"Items":"nope"}""", """{"Items":[null,1,"x",[],{}]}""", """{"Items":[{"Id":5,"Type":["x"],"Name":{"a":1}}]}""",
        """{"Items":[{"Id":"a","RunTimeTicks":"abc","ProductionYear":1e99,"CommunityRating":"NaN","MediaStreams":"x","People":[1,2],"UserData":[]}]}""",
        "{\"Items\":[{\"Id\":\"a\",\"Name\":\"" + "x".repeat(100_000) + "\"}]}",
        "[".repeat(100_000),
    )
    @Test fun jellyfinNeverThrows() {
        for (h in hostile) {
            val f = Fake(); f.on("/", h)
            val b = JellyfinBackend(cfg(), f, "d")
            b.browse(Library("L", "x", LibraryKind.Movies), Query()); b.continueWatching(); b.nextUp(); b.search("x"); b.libraries(); b.genres(Library("L", "x", LibraryKind.Movies))
            b.latest(Library("L", "x", LibraryKind.Movies)); b.item("a"); b.seasons("a"); b.markers(MediaItem("a", ItemType.Movie, "x")); b.similar("a")
        }
    }
    @Test fun plexNeverThrows() {
        for (h in hostile) {
            val f = Fake(); f.on("/", h)
            val b = PlexBackend(cfg(ServerKind.Plex, "http://192.168.1.10:32400"), f, "d")
            b.libraries(); b.continueWatching(); b.nextUp(); b.search("x"); b.item("1"); b.markers(MediaItem("1", ItemType.Movie, "x")); b.similar("1")
            b.browse(Library("1", "x", LibraryKind.Movies), Query()); b.genres(Library("1", "x", LibraryKind.Movies))
        }
    }
    @Test fun longStringsAreCapped() {
        val f = Fake(); f.on("/", hostile[9])
        val it = JellyfinBackend(cfg(), f, "d").search("x").single()
        assertTrue(it.name.length <= 4000)
    }
    @Test fun badIdsAreDropped() {
        val f = Fake(); f.on("/", """{"MediaContainer":{"Metadata":[{"ratingKey":"../../etc","type":"movie","title":"x"},{"ratingKey":"12","type":"movie","title":"ok"}]}}""")
        val l = PlexBackend(cfg(ServerKind.Plex, "http://192.168.1.10:32400"), f, "d").continueWatching()
        assertEquals(listOf("12"), l.map { it.id })
    }
}

class PlexTest {
    private val meta = """{"MediaContainer":{"totalSize":2,"Metadata":[
      {"ratingKey":"100","type":"movie","title":"Example Movie","year":2021,"summary":"Plot","audienceRating":8.1,"contentRating":"R","duration":7200000,"viewOffset":1800000,"thumb":"/library/metadata/100/thumb/1","art":"/library/metadata/100/art/1",
       "Genre":[{"tag":"Drama"}],"Role":[{"tag":"A B","role":"Hero"}],
       "Media":[{"container":"mkv","bitrate":8000,"videoCodec":"hevc","Part":[{"key":"/library/parts/55/1/file.mkv","Stream":[
          {"id":1,"index":0,"streamType":1,"codec":"hevc","height":2160,"colorTrc":"smpte2084"},
          {"id":2,"index":1,"streamType":2,"codec":"eac3","languageCode":"eng","channels":6,"default":true},
          {"id":3,"index":2,"streamType":3,"codec":"srt","languageCode":"ara","key":"/library/streams/3"},
          {"id":4,"index":3,"streamType":3,"codec":"pgs","languageCode":"eng","forced":true}]}]}],
       "Marker":[{"type":"intro","startTimeOffset":5000,"endTimeOffset":65000}]},
      {"ratingKey":"200","type":"episode","title":"Pilot","grandparentTitle":"Example Show","grandparentRatingKey":"90","parentRatingKey":"91","parentIndex":1,"index":2,"thumb":"/library/metadata/200/thumb/1","grandparentThumb":"/library/metadata/90/thumb/1","grandparentArt":"/library/metadata/90/art/1"}
    ]}}"""
    private fun backend(f: Fake) = PlexBackend(cfg(ServerKind.Plex, "http://192.168.1.10:32400"), f, "dev1")

    @Test fun parsesItems() {
        val f = Fake(); f.on("/library/sections/1/all", meta)
        val p = backend(f).browse(Library("1", "Films", LibraryKind.Movies), Query(sort = Sort.Year, descending = true, unplayedOnly = true))
        assertEquals(2, p.total)
        val m = p.items[0]; assertEquals(8.1, m.rating); assertEquals(2021, m.year); assertEquals("HDR10", m.video?.videoRange)
        assertTrue(m.subtitles[0].isExternal); assertEquals("/library/parts/55/1/file.mkv", m.partPath)
        val e = p.items[1]; assertEquals("Example Show", e.seriesName); assertEquals("90", e.seriesId); assertEquals(2, e.episodeNumber)
        assertTrue("sort=year%3Adesc" in f.seen[0].url && "unwatched=1" in f.seen[0].url, f.seen[0].url)
        assertTrue(f.seen.all { "SECRETTOKEN123" !in it.url }); assertEquals("SECRETTOKEN123", f.seen[0].headers["X-Plex-Token"])
    }
    @Test fun directPlayOrServerTranscode() {
        val f = Fake(); val b = backend(f)
        val m = b.parseItem(parseObj(meta)!!.o("MediaContainer")!!.a("Metadata")[0])!!
        val ok = b.playback(m, DeviceCaps(setOf("hevc"), setOf("eac3"), setOf("mkv"), 2160, hdr = true), PlayOptions())
        assertEquals(PlayMode.DirectPlay, ok.mode); assertEquals("http://192.168.1.10:32400/library/parts/55/1/file.mkv", ok.url)
        val tc = b.playback(m, DeviceCaps(), PlayOptions(startMs = 30_000))
        assertEquals(PlayMode.Transcode, tc.mode); assertTrue(tc.hls); assertTrue("offset=30" in tc.url && "directPlay=0" in tc.url); assertFalse("SECRET" in tc.url)
    }
    @Test fun markersAndTimeline() {
        val f = Fake(); f.on("/library/metadata/100", meta); f.on("/:/timeline", "")
        val b = backend(f)
        assertEquals(listOf(Marker(MarkerKind.Intro, 5_000, 65_000)), b.markers(MediaItem("100", ItemType.Movie, "x")))
        b.reportProgress(PlaybackPlan("100", "100", "u", PlayMode.DirectPlay, "", "sess", false, emptyList(), null, 7_200_000, emptyMap(), ratingKey = "100"), 10_000, true)
        val u = f.seen.last().url; assertTrue("state=paused" in u && "time=10000" in u && "ratingKey=100" in u, u)
    }
    @Test fun pinFlowAndServerChoice() {
        val f = Fake()
        f.on("POST https://plex.tv/api/v2/pins", """{"id":42,"code":"ABCD"}""")
        f.on("GET https://plex.tv/api/v2/pins/42", """{"id":42,"code":"ABCD","authToken":"acct"}""")
        f.on("/api/v2/resources", """[{"name":"Home","provides":"server","clientIdentifier":"m1","owned":true,"accessToken":"srvtok","connections":[
            {"protocol":"http","uri":"http://192.168.1.10:32400","local":true,"relay":false},
            {"protocol":"https","uri":"https://192-168-1-10.abc.plex.direct:32400","local":true,"relay":false},
            {"protocol":"https","uri":"https://relay.plex.tv:443","local":false,"relay":true},
            {"protocol":"http","uri":"http://8.8.8.8:32400","local":false,"relay":false}]},
            {"name":"Phone","provides":"player","clientIdentifier":"p"}]""")
        val tv = PlexTv("dev1", f)
        val pin = tv.startPin(); assertEquals("ABCD", pin.code); assertEquals("acct", tv.pollPin(pin.id))
        val s = tv.servers("acct").single()
        assertEquals(listOf("https://192-168-1-10.abc.plex.direct:32400", "http://192.168.1.10:32400"), s.candidates)
        assertEquals("srvtok", s.token)
    }
    @Test fun pendingPinIsNull() { val f = Fake(); f.on("/pins/9", """{"id":9,"code":"X","authToken":null}"""); assertNull(PlexTv("d", f).pollPin(9)) }
    @Test fun hostilePinReply() { val f = Fake(); f.on("/pins", """{"id":1,"code":"<script>"}"""); assertFailsWith<ServerException> { PlexTv("d", f).startPin() } }
    @Test fun subtitleSearchViaServerAgent() {
        val f = Fake(); f.on("/subtitles?language=ara", """{"MediaContainer":{"Stream":[{"key":"/library/streams/x?a=1","displayTitle":"Arabic (SRT)","providerTitle":"OpenSubtitles","codec":"srt","languageCode":"ara"}]}}""")
        f.on("PUT", "")
        val b = backend(f); val r = b.searchSubtitles(MediaItem("100", ItemType.Movie, "x"), "ar").single()
        b.downloadSubtitle(MediaItem("100", ItemType.Movie, "x"), r)
        assertEquals("PUT", f.seen.last().method); assertTrue("providerTitle=OpenSubtitles" in f.seen.last().url)
    }
}

class DecisionTest {
    private val v = { c: String, h: Int, r: String? -> StreamInfo(0, type = StreamType.Video, codec = c, height = h, videoRange = r) }
    private val a = { c: String -> StreamInfo(1, type = StreamType.Audio, codec = c) }
    @Test fun table() {
        val caps = DeviceCaps(setOf("h264", "hevc"), setOf("aac", "ac3"), setOf("mp4", "mkv"), 2160, hdr = false)
        assertEquals(PlayMode.DirectPlay, PlaybackDecision.decide("matroska,webm", v("hevc", 2160, "SDR"), a("ac3"), 5_000_000, false, caps).mode)
        assertEquals(PlayMode.DirectStream, PlaybackDecision.decide("mkv", v("h264", 1080, null), a("dts"), null, false, caps).mode)
        assertEquals(PlayMode.Transcode, PlaybackDecision.decide("mkv", v("av1", 1080, null), a("aac"), null, false, caps).mode)
        assertEquals(PlayMode.Transcode, PlaybackDecision.decide("mkv", v("hevc", 2160, "HDR10"), a("aac"), null, false, caps).mode)
        assertEquals(PlayMode.Transcode, PlaybackDecision.decide("mkv", v("h264", 1080, null), a("aac"), null, true, caps).mode)
        assertEquals(PlayMode.Transcode, PlaybackDecision.decide("mkv", v("h264", 1080, null), a("aac"), 90_000_000, false, caps).mode)
        assertEquals(PlayMode.DirectStream, PlaybackDecision.decide("avi", v("h264", 480, null), a("aac"), null, false, caps).mode)
    }
}
