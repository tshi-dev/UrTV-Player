package com.urtv.player.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun stripAuthParams(u: String): String {
    val i = u.indexOf('?'); if (i < 0) return u
    val kept = u.substring(i + 1).split('&').filter { p -> p.isNotEmpty() && p.substringBefore('=').lowercase() !in setOf("api_key", "apikey", "x-plex-token", "x-emby-token", "token") }
    return u.substring(0, i) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
}

internal fun safeId(s: String) = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)

/** Pre-login calls: identify the server, sign in with a password, or run Quick Connect (Jellyfin only). */
class JellyfinAuth(private val kind: ServerKind, private val base: String, private val deviceId: String, private val http: Transport) {
    private val prefix = if (kind == ServerKind.Emby) "/emby" else ""
    private fun hdr(token: String? = null): Map<String, String> {
        val v = "MediaBrowser Client=\"UrTV\", Device=\"Android TV\", DeviceId=\"${safeId(deviceId)}\", Version=\"0.1.0\"" + (token?.let { ", Token=\"${it.filter { c -> c.isLetterOrDigit() }}\"" } ?: "")
        return mapOf(if (kind == ServerKind.Emby) "X-Emby-Authorization" to v else "Authorization" to v, "Accept" to "application/json")
    }
    private fun call(method: String, path: String, body: String? = null): Resp {
        val r = http.send(Req(method, base + prefix + path, hdr() + if (body != null) mapOf("Content-Type" to "application/json") else emptyMap(), body))
        if (r.code == 401 || r.code == 403) throw ServerException(ServerException.Reason.Auth, "Sign-in refused")
        if (r.code !in 200..299) throw ServerException(ServerException.Reason.Http, "HTTP ${r.code}")
        return r
    }

    data class Info(val id: String, val name: String, val kind: ServerKind, val version: String?)

    /** Identifies a Jellyfin or Emby server; returns null for anything else (so a random web server is not accepted). */
    fun info(): Info? {
        val o = parseObj(call("GET", "/System/Info/Public").body) ?: return null
        val id = o.s("Id") ?: return null
        val product = o.s("ProductName") ?: ""
        val k = if (product.contains("Emby", true) && !product.contains("Jellyfin", true)) ServerKind.Emby else if (kind == ServerKind.Emby && !product.contains("Jellyfin", true)) ServerKind.Emby else ServerKind.Jellyfin
        return Info(id, o.s("ServerName") ?: "Server", k, o.s("Version"))
    }

    data class Session(val token: String, val userId: String, val userName: String)

    private fun session(body: String): Session {
        val o = parseObj(body) ?: throw ServerException(ServerException.Reason.Parse, "Unexpected reply")
        val token = o.s("AccessToken"); val user = o.o("User")
        val uid = user?.s("Id")
        if (token.isNullOrBlank() || uid.isNullOrBlank()) throw ServerException(ServerException.Reason.Auth, "Sign-in refused")
        return Session(token, uid, user.s("Name") ?: "")
    }

    fun login(user: String, password: String): Session {
        val body = buildJsonObject { put("Username", user); put("Pw", password) }.toString()
        return session(call("POST", "/Users/AuthenticateByName", body).body)
    }

    // Quick Connect: show the code on the TV, the user approves it in another signed-in Jellyfin client.
    data class QuickConnect(val code: String, val secret: String)
    fun quickConnectEnabled(): Boolean = kind == ServerKind.Jellyfin && runCatching { call("GET", "/QuickConnect/Enabled").body.trim().equals("true", true) }.getOrDefault(false)
    fun quickConnectStart(): QuickConnect {
        val o = parseObj(call("POST", "/QuickConnect/Initiate").body) ?: throw ServerException(ServerException.Reason.Parse, "Unexpected reply")
        val code = o.s("Code"); val secret = o.s("Secret")
        if (code.isNullOrBlank() || secret.isNullOrBlank()) throw ServerException(ServerException.Reason.Parse, "Unexpected reply")
        return QuickConnect(code.take(12), secret)
    }
    fun quickConnectApproved(secret: String): Boolean = parseObj(call("GET", "/QuickConnect/Connect?secret=${enc(secret)}").body)?.b("Authenticated") == true
    fun quickConnectFinish(secret: String): Session =
        session(call("POST", "/Users/AuthenticateWithQuickConnect", buildJsonObject { put("Secret", secret) }.toString()).body)
}

class JellyfinBackend(override val config: ServerConfig, private val http: Transport, private val deviceId: String) : MediaBackend {
    override val kind get() = config.kind
    private val emby = config.kind == ServerKind.Emby
    private val base = config.baseUrl.trimEnd('/')
    private val prefix = if (emby) "/emby" else ""
    private val uid = config.userId

    override fun authHeaders(): Map<String, String> {
        val v = "MediaBrowser Client=\"UrTV\", Device=\"Android TV\", DeviceId=\"${safeId(deviceId)}\", Version=\"0.1.0\", Token=\"${config.token.filter { it.isLetterOrDigit() }}\""
        return if (emby) mapOf("X-Emby-Authorization" to v, "X-Emby-Token" to config.token) else mapOf("Authorization" to v)
    }

    private fun send(method: String, path: String, body: String? = null): Resp {
        val h = authHeaders() + mapOf("Accept" to "application/json") + if (body != null) mapOf("Content-Type" to "application/json") else emptyMap()
        val r = http.send(Req(method, base + prefix + path, h, body))
        when (r.code) {
            in 200..299 -> {}
            401, 403 -> throw ServerException(ServerException.Reason.Auth, "Session expired, sign in again")
            else -> throw ServerException(ServerException.Reason.Http, "HTTP ${r.code}")
        }
        return r
    }
    private fun getObj(path: String) = parseObj(send("GET", path).body)
    private fun q(vararg p: Pair<String, String?>) = p.filter { it.second != null }.joinToString("&") { "${it.first}=${enc(it.second!!)}" }

    private val fields = "Overview,Genres,People,MediaStreams,MediaSources,Taglines,DateCreated,ChildCount,LocalTrailerCount"
    private val images = "Primary,Backdrop,Thumb,Logo"

    // ---------------------------------------------------------------- parsing (public for tests)
    fun parseItem(o: JsonObject): MediaItem? {
        val id = o.s("Id")?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        val type = when (o.s("Type")) { "Movie" -> ItemType.Movie; "Series" -> ItemType.Series; "Season" -> ItemType.Season; "Episode" -> ItemType.Episode; "BoxSet" -> ItemType.BoxSet; else -> ItemType.Other }
        val tags = o.o("ImageTags")
        val img = HashMap<Img, String>()
        fun add(k: Img, owner: String?, kindName: String, tag: String?, idx: String = "") { if (owner != null && tag != null) img[k] = "/Items/$owner/Images/$kindName$idx?tag=${enc(tag)}" }
        val seriesId = o.s("SeriesId")
        if (type == ItemType.Episode) {
            add(Img.Thumb, id, "Primary", tags?.s("Primary"))
            add(Img.Poster, seriesId, "Primary", o.s("SeriesPrimaryImageTag"))
        } else {
            add(Img.Poster, id, "Primary", tags?.s("Primary"))
            add(Img.Thumb, id, "Thumb", tags?.s("Thumb"))
            if (type == ItemType.Season && img[Img.Poster] == null) add(Img.Poster, seriesId, "Primary", o.s("SeriesPrimaryImageTag"))
        }
        add(Img.Backdrop, id, "Backdrop", o.sa("BackdropImageTags").firstOrNull(), "/0")
        if (Img.Backdrop !in img) add(Img.Backdrop, o.s("ParentBackdropItemId"), "Backdrop", o.sa("ParentBackdropImageTags").firstOrNull(), "/0")
        add(Img.Logo, id, "Logo", tags?.s("Logo"))
        if (Img.Logo !in img) add(Img.Logo, o.s("ParentLogoItemId"), "Logo", o.s("ParentLogoImageTag"))
        val ud = o.o("UserData")
        val src = o.a("MediaSources").firstOrNull()
        val streamsJson = src?.a("MediaStreams")?.ifEmpty { null } ?: o.a("MediaStreams")
        return MediaItem(
            id = id, type = type, name = o.s("Name") ?: "", seriesId = seriesId, seriesName = o.s("SeriesName"),
            seasonId = o.s("SeasonId") ?: o.s("ParentId").takeIf { type == ItemType.Episode },
            seasonNumber = o.i("ParentIndexNumber").takeIf { type == ItemType.Episode },
            episodeNumber = o.i("IndexNumber").takeIf { type == ItemType.Episode },
            year = o.i("ProductionYear")?.takeIf { it in 1800..2200 },
            overview = o.s("Overview") ?: "", tagline = o.sa("Taglines").firstOrNull() ?: "",
            officialRating = o.s("OfficialRating"), rating = o.d("CommunityRating")?.takeIf { it in 0.0..10.0 },
            runtimeMs = ticksToMs(o.l("RunTimeTicks")),
            resumeMs = ticksToMs(ud?.l("PlaybackPositionTicks")), played = ud?.b("Played") == true,
            genres = o.sa("Genres").take(12),
            people = o.a("People").mapNotNull { p -> p.s("Name")?.let { Person(it, p.s("Role"), p.s("Type")) } }.take(40),
            streams = streamsJson.mapNotNull(::parseStream).take(200),
            container = src?.s("Container") ?: o.s("Container"),
            childCount = o.i("ChildCount") ?: 0, unplayed = ud?.i("UnplayedItemCount") ?: 0,
            images = img, addedMs = 0,
            trailers = emptyList(),
        ).let { if ((o.i("LocalTrailerCount") ?: 0) > 0) it.copy(trailers = listOf("local")) else it }
    }

    private fun parseStream(s: JsonObject): StreamInfo? {
        val type = when (s.s("Type")) { "Video" -> StreamType.Video; "Audio" -> StreamType.Audio; "Subtitle" -> StreamType.Subtitle; else -> return null }
        return StreamInfo(
            index = s.i("Index") ?: return null, type = type, codec = s.s("Codec")?.lowercase(), language = s.s("Language"),
            title = s.s("DisplayTitle") ?: s.s("Title"), isDefault = s.b("IsDefault") == true, isForced = s.b("IsForced") == true,
            isHearingImpaired = s.b("IsHearingImpaired") == true, isExternal = s.b("IsExternal") == true,
            channels = s.i("Channels"), width = s.i("Width"), height = s.i("Height"),
            videoRange = s.s("VideoRangeType") ?: s.s("VideoRange"), profile = s.s("Profile"),
            deliveryPath = s.s("DeliveryUrl")?.takeIf { it.startsWith("/") },
        )
    }

    private fun items(body: String): List<MediaItem> = parseObj(body)?.a("Items")?.mapNotNull(::parseItem) ?: emptyList()

    // ---------------------------------------------------------------- library
    override fun libraries(): List<Library> = (getObj("/Users/$uid/Views")?.a("Items") ?: emptyList()).mapNotNull { v ->
        val id = v.s("Id") ?: return@mapNotNull null
        val kind = when (v.s("CollectionType")?.lowercase()) { "movies" -> LibraryKind.Movies; "tvshows" -> LibraryKind.Shows; "boxsets" -> LibraryKind.Collections; else -> return@mapNotNull null }
        Library(id, v.s("Name") ?: "", kind)
    }

    override fun continueWatching(limit: Int) = items(send("GET", "/Users/$uid/Items/Resume?" + q("Limit" to "$limit", "MediaTypes" to "Video", "Fields" to fields, "EnableImageTypes" to images, "EnableUserData" to "true")).body)
    override fun nextUp(limit: Int) = items(send("GET", "/Shows/NextUp?" + q("UserId" to uid, "Limit" to "$limit", "Fields" to fields, "EnableImageTypes" to images)).body)

    override fun latest(lib: Library, limit: Int): List<MediaItem> {
        val arr = parseJson(send("GET", "/Users/$uid/Items/Latest?" + q("ParentId" to lib.id, "Limit" to "$limit", "Fields" to fields, "EnableImageTypes" to images)).body) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::parseItem) }
    }

    override fun browse(lib: Library, q: Query): Page<MediaItem> {
        val type = q.type ?: when (lib.kind) { LibraryKind.Movies -> ItemType.Movie; LibraryKind.Shows -> ItemType.Series; LibraryKind.Collections -> ItemType.BoxSet; else -> null }
        val sort = when (q.sort) { Sort.Name -> "SortName"; Sort.Added -> "DateCreated,SortName"; Sort.Year -> "ProductionYear,SortName"; Sort.Rating -> "CommunityRating,SortName" }
        val path = "/Users/$uid/Items?" + q(
            "ParentId" to lib.id, "Recursive" to "true", "IncludeItemTypes" to type?.name, "SortBy" to sort,
            "SortOrder" to if (q.descending) "Descending" else "Ascending", "Genres" to q.genre,
            "Filters" to if (q.unplayedOnly) "IsUnplayed" else null, "StartIndex" to "${q.start.coerceAtLeast(0)}", "Limit" to "${q.limit.coerceIn(1, 200)}",
            "Fields" to "Overview,Genres,ChildCount", "EnableImageTypes" to images, "EnableTotalRecordCount" to "true",
        )
        val o = getObj(path) ?: return Page(emptyList(), 0)
        val list = o.a("Items").mapNotNull(::parseItem)
        return Page(list, o.i("TotalRecordCount") ?: list.size)
    }

    override fun genres(lib: Library): List<String> =
        (getObj("/Genres?" + q("UserId" to uid, "ParentId" to lib.id, "SortBy" to "SortName"))?.a("Items") ?: emptyList()).mapNotNull { it.s("Name") }.take(100)

    override fun item(id: String): MediaItem? {
        val o = getObj("/Users/$uid/Items/${enc(id)}") ?: return null
        return parseItem(o)
    }

    override fun seasons(seriesId: String) = items(send("GET", "/Shows/${enc(seriesId)}/Seasons?" + q("UserId" to uid, "Fields" to "ChildCount", "EnableImageTypes" to images)).body)
    override fun episodes(seriesId: String, seasonId: String) =
        items(send("GET", "/Shows/${enc(seriesId)}/Episodes?" + q("UserId" to uid, "SeasonId" to seasonId, "Fields" to fields, "EnableImageTypes" to images)).body)
    override fun children(id: String) = items(send("GET", "/Users/$uid/Items?" + q("ParentId" to id, "Fields" to "Overview,Genres,ChildCount", "EnableImageTypes" to images, "SortBy" to "ProductionYear,SortName")).body)
    override fun similar(id: String, limit: Int) = items(send("GET", "/Items/${enc(id)}/Similar?" + q("UserId" to uid, "Limit" to "$limit", "EnableImageTypes" to images)).body)
    override fun search(text: String, limit: Int) =
        items(send("GET", "/Users/$uid/Items?" + q("SearchTerm" to text.take(100), "Recursive" to "true", "IncludeItemTypes" to "Movie,Series,BoxSet", "Limit" to "$limit", "EnableImageTypes" to images, "Fields" to "Genres")).body)

    override fun imageUrl(item: MediaItem, kind: Img, width: Int): String? =
        item.images[kind]?.takeIf { it.startsWith("/Items/") }?.let { "$base$prefix$it&maxWidth=${width.coerceIn(64, 3840)}&quality=90" }

    // ---------------------------------------------------------------- playback
    override fun playback(item: MediaItem, caps: DeviceCaps, opts: PlayOptions): PlaybackPlan {
        val burn = opts.subtitleIndex
        val burnStream = burn?.let { i -> item.streams.firstOrNull { it.index == i && it.type == StreamType.Subtitle } }
        val textCodecs = listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa").filter { it != burnStream?.codec }
        val profile = buildJsonObject {
            put("Name", "UrTV"); put("MaxStreamingBitrate", caps.maxBitrate)
            put("DirectPlayProfiles", buildJsonArray { add(buildJsonObject {
                put("Type", "Video"); put("Container", caps.containers.joinToString(",")); put("VideoCodec", caps.videoCodecs.joinToString(",")); put("AudioCodec", caps.audioCodecs.joinToString(","))
            }) })
            put("TranscodingProfiles", buildJsonArray { add(buildJsonObject {
                put("Type", "Video"); put("Container", "ts"); put("Protocol", "hls"); put("Context", "Streaming")
                put("VideoCodec", if ("hevc" in caps.videoCodecs) "hevc,h264" else "h264"); put("AudioCodec", "aac,ac3".takeIf { "ac3" in caps.audioCodecs } ?: "aac")
                put("MaxAudioChannels", "6"); put("BreakOnNonKeyFrames", true)
            }) })
            put("SubtitleProfiles", buildJsonArray { textCodecs.forEach { c -> add(buildJsonObject { put("Format", c); put("Method", "External") }) } })
        }
        val body = buildJsonObject {
            put("UserId", uid); put("MaxStreamingBitrate", opts.maxBitrate ?: caps.maxBitrate)
            if (opts.startMs > 0) put("StartTimeTicks", msToTicks(opts.startMs))
            opts.audioIndex?.let { put("AudioStreamIndex", it) }
            burn?.let { put("SubtitleStreamIndex", it) }
            put("EnableDirectPlay", true); put("EnableDirectStream", true); put("EnableTranscoding", true); put("AutoOpenLiveStream", false)
            put("DeviceProfile", profile)
        }.toString()
        val o = parseObj(send("POST", "/Items/${enc(item.id)}/PlaybackInfo?" + q("UserId" to uid), body).body) ?: throw ServerException(ServerException.Reason.Parse, "Unexpected reply")
        val src = o.a("MediaSources").firstOrNull() ?: throw ServerException(ServerException.Reason.Refused, "The server offered no playable source")
        val sid = src.s("Id") ?: item.id
        val streams = src.a("MediaStreams").mapNotNull(::parseStream).ifEmpty { item.streams }
        val session = o.s("PlaySessionId")
        val audio = streams.firstOrNull { it.type == StreamType.Audio && it.index == (opts.audioIndex ?: src.i("DefaultAudioStreamIndex")) } ?: SubtitleChoice.pickAudio(streams.filter { it.type == StreamType.Audio }, LangPrefs())
        val d = PlaybackDecision.decide(src.s("Container"), streams.firstOrNull { it.type == StreamType.Video }, audio, src.l("Bitrate"), burn != null, caps)
        val transcodePath = src.s("TranscodingUrl")?.let(::stripAuthParams)
        val direct = d.mode == PlayMode.DirectPlay && src.b("SupportsDirectPlay") != false
        val url: String; val mode: PlayMode; val reason: String; val hls: Boolean
        if (direct) {
            url = "$base$prefix/Videos/${enc(item.id)}/stream?" + q("static" to "true", "mediaSourceId" to sid, "PlaySessionId" to session)
            mode = PlayMode.DirectPlay; reason = d.reason; hls = false
        } else {
            if (transcodePath == null || !transcodePath.startsWith("/")) throw ServerException(ServerException.Reason.Refused, "The server cannot transcode this item")
            url = base + (if (emby && !transcodePath.startsWith("/emby/")) "/emby$transcodePath" else transcodePath)
            mode = if (d.mode == PlayMode.DirectPlay) PlayMode.DirectStream else d.mode
            reason = if (d.mode == PlayMode.DirectPlay) "server declined direct play" else d.reason
            hls = true
        }
        return PlaybackPlan(item.id, sid, url, mode, reason, session, hls, streams, src.s("Container"), item.runtimeMs, authHeaders(), audioIndex = audio?.index)
    }
    override fun markers(item: MediaItem): List<Marker> {
        if (!emby) {
            val o = runCatching { getObj("/MediaSegments/${enc(item.id)}") }.getOrNull() ?: return emptyList()
            return o.a("Items").mapNotNull { s ->
                val kind = when (s.s("Type")) { "Intro" -> MarkerKind.Intro; "Outro" -> MarkerKind.Credits; else -> return@mapNotNull null }
                val a = ticksToMs(s.l("StartTicks")); val b = ticksToMs(s.l("EndTicks"))
                if (b > a) Marker(kind, a, b) else null
            }.take(10)
        }
        val o = runCatching { getObj("/Users/$uid/Items/${enc(item.id)}?" + q("Fields" to "Chapters")) }.getOrNull() ?: return emptyList()
        val ch = o.a("Chapters").mapNotNull { c -> c.s("MarkerType")?.let { it to ticksToMs(c.l("StartPositionTicks")) } }
        val out = ArrayList<Marker>()
        val s = ch.firstOrNull { it.first == "IntroStart" }; val e = ch.firstOrNull { it.first == "IntroEnd" }
        if (s != null && e != null && e.second > s.second) out += Marker(MarkerKind.Intro, s.second, e.second)
        ch.firstOrNull { it.first == "CreditsStart" }?.let { if (item.runtimeMs > it.second) out += Marker(MarkerKind.Credits, it.second, item.runtimeMs) }
        return out
    }

    override fun subtitleUrl(plan: PlaybackPlan, s: StreamInfo): String? =
        if (s.type != StreamType.Subtitle) null else "$base$prefix/Videos/${enc(plan.itemId)}/${enc(plan.sourceId)}/Subtitles/${s.index}/Stream.vtt"

    private fun report(path: String, plan: PlaybackPlan, ms: Long, paused: Boolean?) {
        val body = buildJsonObject {
            put("ItemId", plan.itemId); put("MediaSourceId", plan.sourceId); put("PositionTicks", msToTicks(ms)); put("CanSeek", true)
            plan.playSessionId?.let { put("PlaySessionId", it) }
            put("PlayMethod", when (plan.mode) { PlayMode.DirectPlay -> "DirectPlay"; PlayMode.DirectStream -> "DirectStream"; PlayMode.Transcode -> "Transcode" })
            if (paused != null) put("IsPaused", paused)
        }.toString()
        runCatching { send("POST", path, body) }       // reporting must never interrupt playback
    }
    override fun reportStart(plan: PlaybackPlan, positionMs: Long) = report("/Sessions/Playing", plan, positionMs, null)
    override fun reportProgress(plan: PlaybackPlan, positionMs: Long, paused: Boolean) = report("/Sessions/Playing/Progress", plan, positionMs, paused)
    override fun reportStop(plan: PlaybackPlan, positionMs: Long) = report("/Sessions/Playing/Stopped", plan, positionMs, null)

    override fun setPlayed(item: MediaItem, played: Boolean) { send(if (played) "POST" else "DELETE", "/Users/$uid/PlayedItems/${enc(item.id)}") }

    // Subtitle search/download goes through the server's own subtitle plugin; the app holds no provider key.
    override fun searchSubtitles(item: MediaItem, language: String): List<RemoteSubtitle> {
        val arr = parseJson(send("GET", "/Items/${enc(item.id)}/RemoteSearch/Subtitles/${enc(Lang.toIso3(language))}").body) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e -> (e as? JsonObject)?.let { s ->
            val id = s.s("Id") ?: return@let null
            RemoteSubtitle(id, s.s("Name") ?: id, s.s("ThreeLetterISOLanguageName"), s.s("ProviderName"), s.s("Format"), s.i("DownloadCount"), s.d("CommunityRating"),
                forced = s.b("Forced") == true, hearingImpaired = s.b("HearingImpaired") == true)
        } }.take(30)
    }
    override fun downloadSubtitle(item: MediaItem, sub: RemoteSubtitle) { send("POST", "/Items/${enc(item.id)}/RemoteSearch/Subtitles/${enc(sub.id)}") }
}
