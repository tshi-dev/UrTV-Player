package com.urtv.player.server

import kotlinx.serialization.json.JsonObject

/** plex.tv account side: the official PIN link flow (the user types the code at plex.tv/link on a phone). */
class PlexTv(private val clientId: String, private val http: Transport) {
    private fun hdr(token: String? = null) = buildMap {
        put("Accept", "application/json"); put("X-Plex-Client-Identifier", safeId(clientId)); put("X-Plex-Product", "UrTV")
        put("X-Plex-Version", "0.1.0"); put("X-Plex-Platform", "Android"); put("X-Plex-Device-Name", "UrTV (Android TV)")
        if (token != null) put("X-Plex-Token", token)
    }
    data class Pin(val id: Long, val code: String)

    fun startPin(): Pin {
        val r = http.send(Req("POST", "https://plex.tv/api/v2/pins?strong=false", hdr()))
        if (r.code !in 200..299) throw ServerException(ServerException.Reason.Http, "plex.tv HTTP ${r.code}")
        val o = parseObj(r.body) ?: throw ServerException(ServerException.Reason.Parse, "Unexpected reply")
        val id = o.l("id"); val code = o.s("code")
        if (id == null || code.isNullOrBlank() || !code.all { it.isLetterOrDigit() } || code.length > 12) throw ServerException(ServerException.Reason.Parse, "Unexpected reply")
        return Pin(id, code)
    }

    /** The account token once the user has entered the code, else null. */
    fun pollPin(id: Long): String? {
        val r = http.send(Req("GET", "https://plex.tv/api/v2/pins/$id", hdr()))
        if (r.code == 404) throw ServerException(ServerException.Reason.Auth, "The code expired")
        if (r.code !in 200..299) throw ServerException(ServerException.Reason.Http, "plex.tv HTTP ${r.code}")
        return parseObj(r.body)?.s("authToken")?.takeIf { it.isNotBlank() && it.length <= 200 }
    }

    data class PlexServer(val name: String, val machineId: String, val token: String, val owned: Boolean, val candidates: List<String>)

    fun servers(accountToken: String): List<PlexServer> {
        val r = http.send(Req("GET", "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=0&includeIPv6=0", hdr(accountToken)))
        if (r.code == 401) throw ServerException(ServerException.Reason.Auth, "plex.tv refused the token")
        if (r.code !in 200..299) throw ServerException(ServerException.Reason.Http, "plex.tv HTTP ${r.code}")
        val arr = (parseJson(r.body) as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        return arr.mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            if (!(d.s("provides") ?: "").split(',').contains("server")) return@mapNotNull null
            val mid = d.s("clientIdentifier") ?: return@mapNotNull null
            val conns = d.a("connections").filter { it.b("relay") != true }
            // https first (plex.direct names carry a valid certificate), local before remote; http only if private.
            val ordered = conns.sortedWith(compareBy({ it.s("protocol") != "https" }, { it.b("local") != true }))
            val urls = ordered.mapNotNull { it.s("uri")?.let(NetSafety::normalizeBase) }.distinct()
            if (urls.isEmpty()) null else PlexServer(d.s("name") ?: "Plex", mid.take(64), d.s("accessToken") ?: accountToken, d.b("owned") == true, urls)
        }.take(20)
    }
}

class PlexBackend(override val config: ServerConfig, private val http: Transport, private val clientId: String) : MediaBackend {
    override val kind get() = ServerKind.Plex
    private val base = config.baseUrl.trimEnd('/')
    private var genreKeys = HashMap<String, Map<String, String>>()

    override fun authHeaders(): Map<String, String> = mapOf(
        "X-Plex-Token" to config.token, "X-Plex-Client-Identifier" to safeId(clientId), "X-Plex-Product" to "UrTV",
        "X-Plex-Version" to "0.1.0", "X-Plex-Platform" to "Android", "X-Plex-Device-Name" to "UrTV (Android TV)",
    )
    private fun send(method: String, path: String): Resp {
        val r = http.send(Req(method, base + path, authHeaders() + mapOf("Accept" to "application/json")))
        when (r.code) {
            in 200..299 -> {}
            401, 403 -> throw ServerException(ServerException.Reason.Auth, "Session expired, link the account again")
            else -> throw ServerException(ServerException.Reason.Http, "HTTP ${r.code}")
        }
        return r
    }
    private fun container(path: String) = parseObj(send("GET", path).body)?.o("MediaContainer")
    private fun q(vararg p: Pair<String, String?>) = p.filter { it.second != null }.joinToString("&") { "${it.first}=${enc(it.second!!)}" }
    private fun sep(path: String) = if (path.contains('?')) "&" else "?"
    private fun paged(path: String, start: Int, size: Int) = "$path${sep(path)}X-Plex-Container-Start=$start&X-Plex-Container-Size=$size"

    fun parseItem(o: JsonObject): MediaItem? {
        val id = o.s("ratingKey")?.takeIf { it.isNotBlank() && it.length <= 32 && it.all { c -> c.isLetterOrDigit() } } ?: return null
        val type = when (o.s("type")) { "movie" -> ItemType.Movie; "show" -> ItemType.Series; "season" -> ItemType.Season; "episode" -> ItemType.Episode; "collection" -> ItemType.BoxSet; else -> ItemType.Other }
        val img = HashMap<Img, String>()
        fun put(k: Img, p: String?) { if (p != null && p.startsWith("/") && p.length < 400) img[k] = p }
        if (type == ItemType.Episode) { put(Img.Thumb, o.s("thumb")); put(Img.Poster, o.s("grandparentThumb")); put(Img.Backdrop, o.s("grandparentArt") ?: o.s("art")) }
        else { put(Img.Poster, o.s("thumb") ?: o.s("parentThumb")); put(Img.Backdrop, o.s("art") ?: o.s("parentArt")) }
        o.a("Image").firstOrNull { it.s("type") == "clearLogo" }?.let { put(Img.Logo, it.s("url")) }
        val media = o.a("Media").firstOrNull(); val part = media?.a("Part")?.firstOrNull()
        val streams = part?.a("Stream")?.mapNotNull(::parseStream)?.take(200) ?: emptyList()
        val leaf = o.i("leafCount") ?: 0; val viewedLeaf = o.i("viewedLeafCount") ?: 0
        return MediaItem(
            id = id, type = type, name = o.s("title") ?: "", seriesId = o.s("grandparentRatingKey").takeIf { type == ItemType.Episode },
            seriesName = o.s("grandparentTitle").takeIf { type == ItemType.Episode },
            seasonId = o.s("parentRatingKey").takeIf { type == ItemType.Episode },
            seasonNumber = (o.i("parentIndex").takeIf { type == ItemType.Episode }) ?: o.i("index").takeIf { type == ItemType.Season },
            episodeNumber = o.i("index").takeIf { type == ItemType.Episode },
            year = o.i("year")?.takeIf { it in 1800..2200 }, overview = o.s("summary") ?: "", tagline = o.s("tagline") ?: "",
            officialRating = o.s("contentRating"), rating = (o.d("audienceRating") ?: o.d("rating"))?.takeIf { it in 0.0..10.0 },
            runtimeMs = o.l("duration")?.coerceAtLeast(0) ?: media?.l("duration") ?: 0, resumeMs = o.l("viewOffset")?.coerceAtLeast(0) ?: 0,
            played = (o.i("viewCount") ?: 0) > 0 || (leaf > 0 && viewedLeaf >= leaf),
            genres = o.a("Genre").mapNotNull { it.s("tag") }.take(12),
            people = o.a("Role").mapNotNull { r -> r.s("tag")?.let { Person(it, r.s("role"), "Actor") } }.take(40),
            streams = streams, container = media?.s("container"), childCount = o.i("childCount") ?: leaf,
            unplayed = if (leaf > 0) (leaf - viewedLeaf).coerceAtLeast(0) else 0, images = img,
            addedMs = (o.l("addedAt") ?: 0) * 1000, partPath = part?.s("key")?.takeIf { it.startsWith("/") },
            bitrate = media?.l("bitrate")?.let { it * 1000 },
        )
    }

    private fun parseStream(s: JsonObject): StreamInfo? {
        val type = when (s.i("streamType")) { 1 -> StreamType.Video; 2 -> StreamType.Audio; 3 -> StreamType.Subtitle; else -> return null }
        val codec = s.s("codec")?.lowercase()?.let { if (it == "dvd_subtitle") "vobsub" else it }
        return StreamInfo(
            index = s.i("index") ?: s.i("id") ?: return null, id = s.s("id"), type = type, codec = codec,
            language = s.s("languageCode") ?: s.s("language"), title = s.s("displayTitle") ?: s.s("title"),
            isDefault = s.b("default") == true, isForced = s.b("forced") == true, isHearingImpaired = s.b("hearingImpaired") == true,
            isExternal = s.s("key") != null && type == StreamType.Subtitle, channels = s.i("channels"), width = s.i("width"), height = s.i("height"),
            videoRange = if (s.b("DOVIPresent") == true) "DolbyVision" else when (s.s("colorTrc")) { "smpte2084" -> "HDR10"; "arib-std-b67" -> "HLG"; else -> null },
            profile = s.s("profile"), deliveryPath = s.s("key")?.takeIf { it.startsWith("/") && type == StreamType.Subtitle },
        )
    }

    private fun metas(c: JsonObject?) = c?.a("Metadata")?.mapNotNull(::parseItem) ?: emptyList()

    override fun libraries(): List<Library> = (container("/library/sections")?.a("Directory") ?: emptyList()).mapNotNull { d ->
        val key = d.s("key")?.takeIf { it.all { c -> c.isDigit() } && it.isNotEmpty() } ?: return@mapNotNull null
        val kind = when (d.s("type")) { "movie" -> LibraryKind.Movies; "show" -> LibraryKind.Shows; else -> return@mapNotNull null }
        Library(key, d.s("title") ?: "", kind)
    }

    private fun onDeck() = metas(container("/library/onDeck"))
    override fun continueWatching(limit: Int): List<MediaItem> {
        val hub = runCatching { metas(container(paged("/hubs/continueWatching/items", 0, limit))) }.getOrNull()
        return (hub ?: onDeck().filter { it.resumeMs > 0 }).take(limit)
    }
    override fun nextUp(limit: Int) = onDeck().filter { it.resumeMs == 0L && it.type == ItemType.Episode }.take(limit)
    override fun latest(lib: Library, limit: Int) = metas(container(paged("/library/sections/${lib.id}/recentlyAdded", 0, limit)))

    override fun browse(lib: Library, q: Query): Page<MediaItem> {
        val type = q.type ?: when (lib.kind) { LibraryKind.Shows -> ItemType.Series; else -> ItemType.Movie }
        val sort = when (q.sort) { Sort.Name -> "titleSort"; Sort.Added -> "addedAt"; Sort.Year -> "year"; Sort.Rating -> "audienceRating" } + if (q.descending) ":desc" else ":asc"
        val gk = q.genre?.let { g -> genreMap(lib)[g] }
        val path = "/library/sections/${lib.id}/all?" + q("type" to if (type == ItemType.Series) "2" else "1", "sort" to sort, "genre" to gk, "unwatched" to if (q.unplayedOnly) "1" else null)
        val c = container(paged(path, q.start.coerceAtLeast(0), q.limit.coerceIn(1, 200))) ?: return Page(emptyList(), 0)
        val list = metas(c)
        return Page(list, c.i("totalSize") ?: list.size)
    }
    private fun genreMap(lib: Library): Map<String, String> = genreKeys.getOrPut(lib.id) {
        (container("/library/sections/${lib.id}/genre")?.a("Directory") ?: emptyList()).mapNotNull { d -> d.s("title")?.let { t -> d.s("key")?.let { t to it } } }.toMap()
    }
    override fun genres(lib: Library) = genreMap(lib).keys.sorted().take(100)

    override fun item(id: String): MediaItem? = metas(container("/library/metadata/${enc(id)}?includeMarkers=1")).firstOrNull()
    override fun seasons(seriesId: String) = metas(container("/library/metadata/${enc(seriesId)}/children")).filter { it.type == ItemType.Season }
    override fun episodes(seriesId: String, seasonId: String) = metas(container("/library/metadata/${enc(seasonId)}/children"))
    override fun children(id: String) = metas(container("/library/metadata/${enc(id)}/children"))
    override fun similar(id: String, limit: Int) = runCatching {
        container("/hubs/metadata/${enc(id)}/related")?.a("Hub")?.firstOrNull()?.let { metas(it) }?.take(limit)
    }.getOrNull() ?: emptyList()
    override fun search(text: String, limit: Int) =
        container("/hubs/search?" + q("query" to text.take(100), "limit" to "$limit"))?.a("Hub")?.flatMap { metas(it) }
            ?.filter { it.type == ItemType.Movie || it.type == ItemType.Series || it.type == ItemType.BoxSet }?.distinctBy { it.id }?.take(limit) ?: emptyList()

    override fun imageUrl(item: MediaItem, kind: Img, width: Int): String? = item.images[kind]?.let {
        val w = width.coerceIn(64, 3840); val h = if (kind == Img.Poster) w * 3 / 2 else w * 9 / 16
        "$base/photo/:/transcode?width=$w&height=$h&minSize=1&upscale=1&url=${enc(it)}"
    }

    override fun playback(item: MediaItem, caps: DeviceCaps, opts: PlayOptions): PlaybackPlan {
        val full = if (item.partPath == null) item(item.id) ?: item else item
        val sub = opts.subtitleIndex?.let { i -> full.streams.firstOrNull { it.index == i && it.type == StreamType.Subtitle } }
        val audio = full.streams.firstOrNull { it.type == StreamType.Audio && it.index == opts.audioIndex } ?: SubtitleChoice.pickAudio(full.audio, LangPrefs())
        val d = PlaybackDecision.decide(full.container, full.video, audio, full.bitrate, sub != null, caps)
        val session = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        if (d.mode == PlayMode.DirectPlay && full.partPath != null)
            return PlaybackPlan(full.id, full.id, base + full.partPath, PlayMode.DirectPlay, d.reason, session, false, full.streams, full.container, full.runtimeMs, authHeaders(), ratingKey = full.id, audioIndex = audio?.index)
        val params = q(
            "hasMDE" to "1", "path" to "/library/metadata/${full.id}", "mediaIndex" to "0", "partIndex" to "0", "protocol" to "hls", "fastSeek" to "1",
            "directPlay" to "0", "directStream" to if (d.mode == PlayMode.DirectStream) "1" else "0", "directStreamAudio" to "1",
            "offset" to "${opts.startMs / 1000}", "session" to session, "X-Plex-Session-Identifier" to session,
            "maxVideoBitrate" to "${(opts.maxBitrate ?: caps.maxBitrate) / 1000}", "videoResolution" to "${if (caps.maxHeight >= 2160) "3840x2160" else "1920x1080"}",
            "audioStreamID" to audio?.id, "subtitles" to if (sub != null) "burn" else "none", "subtitleStreamID" to sub?.id,
            "X-Plex-Client-Identifier" to safeId(clientId), "X-Plex-Product" to "UrTV", "X-Plex-Platform" to "Android",
        )
        return PlaybackPlan(full.id, full.id, "$base/video/:/transcode/universal/start.m3u8?$params", d.mode, d.reason, session, true, full.streams, full.container, full.runtimeMs, authHeaders(), ratingKey = full.id, audioIndex = audio?.index)
    }

    override fun markers(item: MediaItem): List<Marker> {
        val m = runCatching { container("/library/metadata/${enc(item.id)}?includeMarkers=1")?.a("Metadata")?.firstOrNull()?.a("Marker") }.getOrNull() ?: return emptyList()
        return m.mapNotNull { k ->
            val kind = when (k.s("type")) { "intro" -> MarkerKind.Intro; "credits" -> MarkerKind.Credits; else -> return@mapNotNull null }
            val a = k.l("startTimeOffset") ?: return@mapNotNull null; val b = k.l("endTimeOffset") ?: return@mapNotNull null
            if (b > a && a >= 0) Marker(kind, a, b) else null
        }.take(10)
    }

    override fun subtitleUrl(plan: PlaybackPlan, s: StreamInfo): String? = s.deliveryPath?.let { base + it }

    private fun timeline(plan: PlaybackPlan, state: String, ms: Long) {
        val rk = plan.ratingKey ?: return
        runCatching { send("GET", "/:/timeline?" + q("ratingKey" to rk, "key" to "/library/metadata/$rk", "state" to state, "time" to "${ms.coerceAtLeast(0)}",
            "duration" to "${plan.runtimeMs}", "identifier" to "com.plexapp.plugins.library", "X-Plex-Session-Identifier" to plan.playSessionId)) }
    }
    override fun reportStart(plan: PlaybackPlan, positionMs: Long) = timeline(plan, "playing", positionMs)
    override fun reportProgress(plan: PlaybackPlan, positionMs: Long, paused: Boolean) = timeline(plan, if (paused) "paused" else "playing", positionMs)
    override fun reportStop(plan: PlaybackPlan, positionMs: Long) = timeline(plan, "stopped", positionMs)

    override fun setPlayed(item: MediaItem, played: Boolean) { send("GET", (if (played) "/:/scrobble?" else "/:/unscrobble?") + q("key" to item.id, "identifier" to "com.plexapp.plugins.library")) }

    // Uses the server's own subtitle agents; the app holds no provider key.
    override fun searchSubtitles(item: MediaItem, language: String): List<RemoteSubtitle> {
        val lang = Lang.toIso3(language)
        return (container("/library/metadata/${enc(item.id)}/subtitles?" + q("language" to lang, "hearingImpaired" to "0", "forced" to "0"))?.a("Stream") ?: emptyList()).mapNotNull { s ->
            val key = s.s("key") ?: return@mapNotNull null
            RemoteSubtitle(key, s.s("displayTitle") ?: s.s("title") ?: key, s.s("languageCode") ?: lang, s.s("providerTitle"), s.s("codec"), null, null,
                forced = s.b("forced") == true, hearingImpaired = s.b("hearingImpaired") == true)
        }.take(30)
    }
    override fun downloadSubtitle(item: MediaItem, sub: RemoteSubtitle) {
        send("PUT", "/library/metadata/${enc(item.id)}/subtitles?" + q("key" to sub.id, "codec" to (sub.format ?: "srt"), "language" to (sub.language ?: "ara"),
            "hearingImpaired" to if (sub.hearingImpaired) "1" else "0", "forced" to if (sub.forced) "1" else "0", "providerTitle" to sub.provider))
    }
}
