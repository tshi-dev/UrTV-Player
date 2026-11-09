package com.urtv.player.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.urtv.player.core.Channel
import com.urtv.player.data.AspectMode
import com.urtv.player.data.BufferMode
import com.urtv.player.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class PlayStatus { Idle, Loading, Playing, Reconnecting, Failed }

data class TrackOption(val label: String, val selected: Boolean, val group: Tracks.Group?, val index: Int)

data class TrackState(val audio: List<TrackOption> = emptyList(), val text: List<TrackOption> = emptyList(), val textOn: Boolean = false)

/**
 * Wraps ExoPlayer for live TV: HLS/DASH/TS, a stall watchdog with exponential back-off reconnect,
 * track selection, subtitles as plain text and aspect modes.
 */
class PlayerController(private val ctx: Context, private val store: com.urtv.player.data.Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var player: ExoPlayer? = null; private set
    private var builtFor: BufferMode? = null
    var buffer: BufferMode = BufferMode.Normal

    private val _status = MutableStateFlow(PlayStatus.Idle)
    val status: StateFlow<PlayStatus> = _status
    private val _attempt = MutableStateFlow(0)
    val attempt: StateFlow<Int> = _attempt
    private val _tracks = MutableStateFlow(TrackState())
    val tracks: StateFlow<TrackState> = _tracks
    private val _cue = MutableStateFlow("")
    val cue: StateFlow<String> = _cue
    private val _video = MutableStateFlow(0f)
    /** Display aspect ratio of the video (width/height), 0 when unknown. */
    val videoAspect: StateFlow<Float> = _video
    private val _aspect = MutableStateFlow(AspectMode.Fit)
    val aspect: StateFlow<AspectMode> = _aspect
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended
    /** Where to start (movies/catch-up). Updated while playing so a reconnect resumes in place. */
    private var startAtMs = 0L
    private var vod = false
    /** Muted live preview inside the channel list (one shared decoder). */
    private var preview = false

    private var current: Channel? = null
    private var forceHls = false
    private var watchdog: Job? = null
    private var retryJob: Job? = null
    private var stallSec = 0; private var frozenSec = 0; private var stableSec = 0; private var lastPos = -1L

    private val lowRam = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
    private val prefs = ctx.getSharedPreferences("urtv", Context.MODE_PRIVATE)
    private var meter: androidx.media3.exoplayer.upstream.DefaultBandwidthMeter? = null
    private var builtLive: Boolean? = null
    private val rebuffers = PlaybackTuning.RebufferTracker()
    private var readySince = false
    private var loadStartMs = 0L
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats
    private var ttffAvg = 0L; private var ttffN = 0

    data class Stats(val width: Int = 0, val height: Int = 0, val videoKbps: Int = 0, val estimateKbps: Int = 0, val bufferedMs: Long = 0,
                     val dropped: Int = 0, val codec: String = "", val fps: Float = 0f, val ttffMs: Long = 0, val ttffAvgMs: Long = 0, val capped: Boolean = false)

    /** Display size in pixels (physical mode), used to cap the video resolution. */
    private fun displaySize(): Pair<Int, Int> = runCatching {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val m = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY).mode; m.physicalWidth to m.physicalHeight
    }.getOrDefault(0 to 0)

    // ---- server media (Jellyfin/Emby/Plex): same player, same tuning; headers only for the server's own origin
    private var serverMode = false
    private var serverHeaders: Map<String, String> = emptyMap()
    private var serverOrigin: Uri? = null
    private var audioLangs: List<String> = emptyList()
    private var builtServer = false

    private fun sameOrigin(a: Uri, b: Uri?): Boolean {
        if (b == null) return false
        fun port(u: Uri) = if (u.port >= 0) u.port else if (u.scheme == "https") 443 else 80
        return a.scheme == b.scheme && a.host.equals(b.host, true) && port(a) == port(b)
    }

    /** Last measured throughput of the shared bandwidth meter (bit/s), or the saved seed before any measurement. */
    fun bandwidthEstimate(): Long = meter?.bitrateEstimate?.takeIf { it > 0 } ?: PlaybackTuning.seedBitrate(prefs.getLong("bw", 0))

    private fun build(live: Boolean): ExoPlayer {
        val okhttp = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Net.client).setUserAgent(Http.UA)
        val origin = serverOrigin; val hdrs = serverHeaders
        val http: androidx.media3.datasource.DataSource.Factory = if (!serverMode) okhttp else androidx.media3.datasource.ResolvingDataSource.Factory(okhttp,
            object : androidx.media3.datasource.ResolvingDataSource.Resolver {
                override fun resolveDataSpec(dataSpec: androidx.media3.datasource.DataSpec) =
                    if (sameOrigin(dataSpec.uri, origin)) dataSpec.withAdditionalHeaders(hdrs) else dataSpec
            })
        dataFactory = http
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        val buf = if (serverMode) PlaybackTuning.serverBuffers(lowRam) else PlaybackTuning.buffers(buffer, live, lowRam)
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(buf.minMs, buf.maxMs, buf.startMs, buf.rebufMs)
            .setPrioritizeTimeOverSizeThresholds(!serverMode).setBackBuffer(if (live) 0 else 30_000, false).apply { if (buf.targetBytes > 0) setTargetBufferBytes(buf.targetBytes) }.build()
        val bm = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(ctx)
            .setInitialBitrateEstimate(PlaybackTuning.seedBitrate(prefs.getLong("bw", 0))).build()
        meter = bm
        val (mw, mh) = PlaybackTuning.maxVideoSize(displaySize().first, displaySize().second)
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(ctx).apply {
            setParameters(buildUponParameters().setMaxVideoSize(mw, mh).setViewportSizeToPhysicalDisplaySize(ctx, true).setTunnelingEnabled(store.tunneling.value).setAllowVideoMixedMimeTypeAdaptiveness(true))
        }
        val p = ExoPlayer.Builder(ctx)
            .setRenderersFactory(DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))   // hardware decoder first, software if it fails
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx, extractors).setDataSourceFactory(http))
            .setLoadControl(load).setBandwidthMeter(bm).setTrackSelector(selector)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS      // 24/25/50/60 matching where the TV can switch without a black screen
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        p.addListener(listener)
        surface?.let { p.setVideoSurfaceView(it) }
        if (serverMode && audioLangs.isNotEmpty()) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredAudioLanguages(*audioLangs.toTypedArray()).build()
        builtFor = buffer; builtLive = live; builtServer = serverMode
        return p
    }
    private var dataFactory: androidx.media3.datasource.DataSource.Factory? = null

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            val t = System.currentTimeMillis() - loadStartMs
            if (loadStartMs > 0 && t in 1..60_000) { ttffAvg = (ttffAvg * ttffN + t) / (ttffN + 1); ttffN++; _stats.value = _stats.value.copy(ttffMs = t, ttffAvgMs = ttffAvg) }
        }
        override fun onPlaybackStateChanged(state: Int) {
            // a stall after playback had started: after 3 in a minute, switch to a lower variant
            if (state == Player.STATE_BUFFERING && readySince && !vod) {
                if (rebuffers.record(System.currentTimeMillis())) downgrade()
            }
            if (state == Player.STATE_READY) readySince = true
            when (state) {
                Player.STATE_BUFFERING -> if (_status.value != PlayStatus.Reconnecting && _status.value != PlayStatus.Failed) _status.value = PlayStatus.Loading
                Player.STATE_READY -> if (_status.value != PlayStatus.Failed) { _status.value = PlayStatus.Playing }
                Player.STATE_ENDED -> if (vod) _ended.value = true
                else -> Unit
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) _status.value = PlayStatus.Playing }
        override fun onTracksChanged(tracks: Tracks) { _tracks.value = readTracks(tracks) }
        override fun onCues(cueGroup: CueGroup) { _cue.value = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n") }
        override fun onVideoSizeChanged(v: VideoSize) { _video.value = if (v.width > 0 && v.height > 0) v.width * v.pixelWidthHeightRatio / v.height else 0f }
        override fun onPlayerError(e: PlaybackException) {
            // Playlists often serve HLS from extension-less URLs: retry once as HLS before treating it as an outage.
            val c = current
            if (c != null && !forceHls && e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) { forceHls = true; load(c); return }
            scheduleReconnect()
        }
    }

    private fun downgrade() {
        val p = player ?: return
        val cur = p.videoFormat?.bitrate?.toLong() ?: return
        if (cur <= 0) return
        rebuffers.reset()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoBitrate(PlaybackTuning.downgradeCap(cur).toInt()).build()
        _stats.value = _stats.value.copy(capped = true)
    }

    /** Refreshes [stats] from the player (called about once a second by the overlay and the watchdog). */
    fun sampleStats() {
        val p = player ?: return
        val f = p.videoFormat
        _stats.value = _stats.value.copy(width = f?.width ?: 0, height = f?.height ?: 0, videoKbps = ((f?.bitrate ?: 0) / 1000).coerceAtLeast(0),
            estimateKbps = ((meter?.bitrateEstimate ?: 0L) / 1000).toInt(), bufferedMs = p.totalBufferedDuration, dropped = p.videoDecoderCounters?.droppedBufferCount ?: 0,
            codec = (f?.sampleMimeType ?: "").removePrefix("video/") + (f?.codecs?.let { " $it" } ?: ""), fps = f?.frameRate?.takeIf { it > 0 } ?: 0f)
    }

    private fun readTracks(t: Tracks): TrackState {
        fun opts(type: Int): List<TrackOption> {
            val out = ArrayList<TrackOption>()
            for (g in t.groups) if (g.type == type && g.isSupported) for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val lang = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }
                val label = f.label ?: lang ?: "#${out.size + 1}"
                out += TrackOption(label + (if (f.channelCount > 2 && type == C.TRACK_TYPE_AUDIO) " ${f.channelCount - 1}.1" else ""), g.isTrackSelected(i), g, i)
            }
            return out
        }
        val text = opts(C.TRACK_TYPE_TEXT)
        return TrackState(opts(C.TRACK_TYPE_AUDIO), text, text.any { it.selected })
    }

    fun selectAudio(o: TrackOption) {
        val p = player ?: return; val g = o.group ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, o.index)).build()
    }

    fun selectText(o: TrackOption?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (o?.group == null) { b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true); _cue.value = "" }
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
        p.trackSelectionParameters = b.build()
    }

    fun cycleAspect() { _aspect.value = AspectMode.entries[(_aspect.value.ordinal + 1) % AspectMode.entries.size] }

    /** Seekable on-demand playback (movie, episode, catch-up) starting at [startMs]. */
    fun playVod(title: String, url: String, startMs: Long) { play(Channel(url, title, url), startMs, true) }

    val isPreview get() = preview

    fun playPreview(ch: Channel) { preview = true; serverMode = false; serverHeaders = emptyMap(); serverOrigin = null; startPlay(ch, 0L, false) }

    fun play(ch: Channel, startMs: Long = 0L, isVod: Boolean = false) { preview = false; serverMode = false; serverHeaders = emptyMap(); serverOrigin = null; startPlay(ch, startMs, isVod) }

    /**
     * Media from a home server. [headers] (the access token) go only to requests for [url]'s own origin; [langs] are the
     * preferred audio languages (2- and 3-letter codes). Seekable, resumes at [startMs], reconnects in place like any VOD.
     */
    fun playServer(title: String, url: String, startMs: Long, headers: Map<String, String>, langs: List<String>) {
        preview = false; serverMode = true; serverHeaders = headers; serverOrigin = Uri.parse(url); audioLangs = langs
        startPlay(Channel(url, title, url), startMs, true)
    }

    /** Remember where we are so [resume] (after the activity was stopped) continues in place. */
    fun rememberPosition() { if (vod) player?.currentPosition?.takeIf { it > 0 }?.let { startAtMs = it } }

    private fun startPlay(ch: Channel, startMs: Long, isVod: Boolean) {
        vod = isVod; startAtMs = startMs; _ended.value = false
        current = ch; forceHls = false; _attempt.value = 0; _paused.value = false
        _tracks.value = TrackState(); _cue.value = ""; _video.value = 0f
        load(ch)
    }

    /** Re-attach after the activity was stopped. */
    fun resume() { current?.let { load(it) } }

    private fun load(ch: Channel) {
        retryJob?.cancel()
        if (player == null || builtFor != buffer || builtLive != !vod || builtServer != serverMode) { saveBandwidth(); player?.release(); player = build(!vod) }
        val p = player!!
        loadStartMs = System.currentTimeMillis(); readySince = false
        if (_attempt.value == 0) { rebuffers.reset(); p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoBitrate(Int.MAX_VALUE).build(); _stats.value = _stats.value.copy(capped = false) }
        p.volume = if (preview) 0f else 1f
        _status.value = if (_attempt.value > 0) PlayStatus.Reconnecting else PlayStatus.Loading
        val isHls = forceHls || ch.url.contains(".m3u8", true)
        val item = MediaItem.Builder().setUri(Uri.parse(ch.url)).apply {
            if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) else if (ch.url.contains(".mpd", true)) setMimeType(MimeTypes.APPLICATION_MPD)
            // live: gently speed up (max 1.04x) to stay close to the live edge after a stall
            if (!vod) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.04f).build())
        }.build()
        val df = dataFactory
        if (isHls && df != null) {
            // chunkless preparation: start from the master playlist alone (fewer round trips before the first frame)
            val src = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(df).setAllowChunklessPreparation(true).createMediaSource(item)
            if (vod && startAtMs > 0) p.setMediaSource(src, startAtMs) else p.setMediaSource(src)
        } else if (vod && startAtMs > 0) p.setMediaItem(item, startAtMs) else p.setMediaItem(item)
        p.prepare(); p.playWhenReady = true
        stallSec = 0; frozenSec = 0; stableSec = 0; lastPos = -1
        startWatchdog()
    }

    fun togglePause() {
        val p = player ?: return
        if (p.playWhenReady) { p.playWhenReady = false; _paused.value = true }
        else { _paused.value = false; if (p.playbackState == Player.STATE_IDLE || _status.value == PlayStatus.Failed) { _attempt.value = 0; current?.let { load(it) } } else p.playWhenReady = true }
    }

    fun retryNow() { _attempt.value = 0; current?.let { load(it) } }

    private fun scheduleReconnect() {
        val c = current ?: return
        if (vod) player?.currentPosition?.takeIf { it > 0 }?.let { startAtMs = it }
        val n = _attempt.value + 1
        _attempt.value = n
        if (n > (if (preview) 1 else MAX_ATTEMPTS)) { _status.value = PlayStatus.Failed; player?.stop(); return }
        _status.value = PlayStatus.Reconnecting
        retryJob?.cancel()
        retryJob = scope.launch { delay(minOf(1000L shl (n - 1), 12_000L)); load(c) }
    }

    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (true) {
                delay(1000)
                if (store.statsOverlay.value) sampleStats()
                val p = player ?: continue
                if (current == null || _paused.value || _status.value == PlayStatus.Failed || retryJob?.isActive == true) continue
                when (p.playbackState) {
                    Player.STATE_BUFFERING -> { frozenSec = 0; stableSec = 0; if (++stallSec >= STALL_LIMIT_SEC) { stallSec = 0; scheduleReconnect() } }
                    Player.STATE_READY -> {
                        stallSec = 0
                        val pos = p.currentPosition
                        if (p.isPlaying && pos == lastPos) { if (++frozenSec >= FROZEN_LIMIT_SEC) { frozenSec = 0; scheduleReconnect() } }
                        else { frozenSec = 0; if (p.isPlaying && ++stableSec >= 15) _attempt.value = 0 }
                        lastPos = pos
                    }
                    else -> Unit
                }
            }
        }
    }

    private var surface: android.view.SurfaceView? = null
    fun attach(sv: android.view.SurfaceView) { surface = sv; player?.setVideoSurfaceView(sv) }
    fun detach(sv: android.view.SurfaceView) { if (surface === sv) { player?.clearVideoSurfaceView(sv); surface = null } }

    /** Frees the decoder (activity stopped). The current channel is kept so [resume] can restart it. */
    private fun saveBandwidth() { meter?.bitrateEstimate?.takeIf { it > 0 }?.let { prefs.edit().putLong("bw", it).apply() } }

    fun release() {
        saveBandwidth(); watchdog?.cancel(); retryJob?.cancel(); watchdog = null
        player?.release(); player = null; _status.value = PlayStatus.Idle
    }

    fun stop() { release(); current = null }

    companion object { const val MAX_ATTEMPTS = 8; const val STALL_LIMIT_SEC = 15; const val FROZEN_LIMIT_SEC = 8 }
}
