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
class PlayerController(private val ctx: Context) {
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

    private var current: Channel? = null
    private var forceHls = false
    private var watchdog: Job? = null
    private var retryJob: Job? = null
    private var stallSec = 0; private var frozenSec = 0; private var stableSec = 0; private var lastPos = -1L

    private fun build(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory().setUserAgent(Http.UA).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(12_000)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        val b = buffer
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(b.minMs, b.maxMs, b.startMs, b.rebufMs).build()
        val p = ExoPlayer.Builder(ctx)
            .setRenderersFactory(DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx, extractors).setDataSourceFactory(http))
            .setLoadControl(load)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        p.addListener(listener)
        surface?.let { p.setVideoSurfaceView(it) }
        builtFor = b
        return p
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> if (_status.value != PlayStatus.Reconnecting && _status.value != PlayStatus.Failed) _status.value = PlayStatus.Loading
                Player.STATE_READY -> if (_status.value != PlayStatus.Failed) { _status.value = PlayStatus.Playing }
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

    fun play(ch: Channel) {
        current = ch; forceHls = false; _attempt.value = 0; _paused.value = false
        _tracks.value = TrackState(); _cue.value = ""; _video.value = 0f
        load(ch)
    }

    /** Re-attach after the activity was stopped. */
    fun resume() { current?.let { load(it) } }

    private fun load(ch: Channel) {
        retryJob?.cancel()
        if (player == null || builtFor != buffer) { player?.release(); player = build() }
        val p = player!!
        _status.value = if (_attempt.value > 0) PlayStatus.Reconnecting else PlayStatus.Loading
        val item = MediaItem.Builder().setUri(Uri.parse(ch.url)).apply {
            if (forceHls || ch.url.contains(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (ch.url.contains(".mpd", true)) setMimeType(MimeTypes.APPLICATION_MPD)
        }.build()
        p.setMediaItem(item); p.prepare(); p.playWhenReady = true
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
        val n = _attempt.value + 1
        _attempt.value = n
        if (n > MAX_ATTEMPTS) { _status.value = PlayStatus.Failed; player?.stop(); return }
        _status.value = PlayStatus.Reconnecting
        retryJob?.cancel()
        retryJob = scope.launch { delay(minOf(1000L shl (n - 1), 12_000L)); load(c) }
    }

    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (true) {
                delay(1000)
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
    fun release() {
        watchdog?.cancel(); retryJob?.cancel(); watchdog = null
        player?.release(); player = null; _status.value = PlayStatus.Idle
    }

    fun stop() { release(); current = null }

    companion object { const val MAX_ATTEMPTS = 8; const val STALL_LIMIT_SEC = 15; const val FROZEN_LIMIT_SEC = 8 }
}
