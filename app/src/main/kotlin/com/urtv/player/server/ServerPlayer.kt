package com.urtv.player.server

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ExoPlayer for server media. The access token is attached as a header, and only to requests that go to the server's own
 * origin, so it never appears in a URL, never reaches a redirect target on another host, and cannot end up in a log.
 */
class ServerPlayer(private val ctx: Context) {
    var player: ExoPlayer? = null; private set
    private val _ended = MutableStateFlow(false); val ended: StateFlow<Boolean> = _ended
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error
    private val _buffering = MutableStateFlow(true); val buffering: StateFlow<Boolean> = _buffering
    private val _aspect = MutableStateFlow(0f); val aspect: StateFlow<Float> = _aspect
    private val _paused = MutableStateFlow(false); val paused: StateFlow<Boolean> = _paused
    /** Bumps whenever a new ExoPlayer is created, so the video surface re-attaches. */
    private val _gen = MutableStateFlow(0); val gen: StateFlow<Int> = _gen

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            _buffering.value = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            if (state == Player.STATE_ENDED) _ended.value = true
        }
        override fun onPlayerError(e: PlaybackException) { _error.value = e.errorCodeName }
        override fun onVideoSizeChanged(v: VideoSize) { _aspect.value = if (v.width > 0 && v.height > 0) v.width * v.pixelWidthHeightRatio / v.height else 0f }
        override fun onIsPlayingChanged(isPlaying: Boolean) { _paused.value = !isPlaying && player?.playWhenReady == false }
    }

    private fun sameOrigin(a: Uri, b: Uri) = a.scheme == b.scheme && a.host.equals(b.host, true) && (if (a.port < 0) defaultPort(a) else a.port) == (if (b.port < 0) defaultPort(b) else b.port)
    private fun defaultPort(u: Uri) = if (u.scheme == "https") 443 else 80

    fun start(plan: PlaybackPlan, startMs: Long, prefs: LangPrefs) {
        release()
        _ended.value = false; _error.value = null; _buffering.value = true
        val origin = Uri.parse(plan.url)
        val http = DefaultHttpDataSource.Factory().setUserAgent("UrTV/0.1").setConnectTimeoutMs(10_000).setReadTimeoutMs(25_000).setAllowCrossProtocolRedirects(false)
        val resolver = object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec =
                if (sameOrigin(dataSpec.uri, origin)) dataSpec.withAdditionalHeaders(plan.headers) else dataSpec
        }
        val factory = ResolvingDataSource.Factory(http, resolver)
        // Large remuxes (tens of Mbit/s) need a byte budget bigger than the default, or the player re-buffers
        // even on a fast LAN; start after 1.5 s of media so playback begins quickly.
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
            .setTargetBufferBytes(96 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(ctx)
            .setLoadControl(load)
            .setRenderersFactory(DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(factory))
            .setHandleAudioBecomingNoisy(true).build()
        // Our own renderer draws text subtitles (style, offset, Arabic direction); Media3's text track stays off.
        val langs = prefs.audio.flatMap { listOf(it, Lang.toIso3(it)) }.distinct().toTypedArray()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).setPreferredAudioLanguages(*langs).build()
        p.addListener(listener)
        val b = ExoItem.Builder().setUri(origin)
        if (plan.hls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(b.build(), startMs.coerceAtLeast(0))
        p.prepare(); p.playWhenReady = true
        player = p
        _gen.value = _gen.value + 1
    }

    fun position(): Long = player?.currentPosition ?: 0L
    fun duration(): Long = player?.duration?.takeIf { it > 0 } ?: 0L
    fun togglePause() { player?.let { it.playWhenReady = !it.playWhenReady; _paused.value = !it.playWhenReady } }
    fun seekBy(deltaMs: Long) { player?.let { p -> val d = if (p.duration > 0) p.duration - 1000 else Long.MAX_VALUE; p.seekTo((p.currentPosition + deltaMs).coerceIn(0, d)) } }
    fun seekTo(ms: Long) { player?.seekTo(ms.coerceAtLeast(0)) }

    /** Selects the n-th audio track group (the server lists streams in the same order) during direct play. */
    fun selectAudioOrdinal(n: Int) {
        val p = player ?: return
        val g = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(n) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
    }

    fun release() { player?.removeListener(listener); player?.release(); player = null }
}
