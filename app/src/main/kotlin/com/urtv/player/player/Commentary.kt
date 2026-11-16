package com.urtv.player.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.urtv.player.core.Channel
import com.urtv.player.core.Commentary
import com.urtv.player.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Zoom of the picture while syncing (to read the score and the match clock): ×1..×3 from one of six anchor points (top/bottom × left/centre/right of the screen). */
data class Zoom(val level: Int = 1, val pos: Int = 0) {
    val scale get() = level.toFloat()
    val originX get() = (pos % 3) * 0.5f
    val originY get() = if (pos / 3 == 0) 0f else 1f
}

/** What the panel shows: the second channel (null = the original sound), the delay, and why it failed. */
data class CommentaryState(val audio: Channel? = null, val delayMs: Long = 0L, val failure: Commentary.Failure? = null, val ready: Boolean = false)

/**
 * «دمج التعليق»: the main player keeps the picture and is muted through its own volume; a second, audio-only ExoPlayer (video and text
 * disabled) plays the other channel. The delay holds back whichever stream is ahead (a seek inside the live window), at most 10 s.
 * Any failure restores the original sound. The second player stops when the channel changes, on stop and in the background.
 * Never touches the system volume or mute.
 */
class CommentaryController(private val ctx: Context, private val main: PlayerController, private val lightest: Boolean = false, private val store: com.urtv.player.data.Store? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(CommentaryState())
    val state: StateFlow<CommentaryState> = _state
    private var audio: ExoPlayer? = null
    private var audioListener: Player.Listener? = null
    /** True only while a second player exists: it lives exactly as long as the merge is on, nothing is kept warm. */
    val audioPlayerAlive: Boolean get() = audio != null
    private var watch: Job? = null
    private fun pairKey(audio: Channel) = Integer.toHexString(((main.currentChannel?.url ?: "") + "|" + audio.url).hashCode())
    /** The sound channels used so far in this run (newest first, at most three), offered again as source cards. */
    private val _recent = MutableStateFlow<List<Channel>>(emptyList())
    val recent: StateFlow<List<Channel>> = _recent
    private val _zoom = MutableStateFlow(Zoom())
    val zoom: StateFlow<Zoom> = _zoom
    fun cycleZoom() { _zoom.value = _zoom.value.let { it.copy(level = if (it.level >= 3) 1 else it.level + 1) } }
    fun cycleZoomPos() { _zoom.value = _zoom.value.let { it.copy(pos = (it.pos + 1) % 6) } }
    fun resetZoom() { _zoom.value = Zoom() }
    /** Takes a channel out of the list of sound sources; if it is the one playing, the original sound comes back. */
    fun forget(ch: Channel) { _recent.value = _recent.value.filter { it.url != ch.url }; if (_state.value.audio?.url == ch.url) original() }

    init {
        main.onSessionEnd = { stop() }
        // the state in one line for `adb logcat -s UrTVCommentary` (channel name only, never a URL): audio channel, delay, which stream waits, failure
        scope.launch { _state.collect { st ->
            val lag = Commentary.lagFor(st.delayMs)
            val held = when { lag.audioMs > 0 -> "audio ${lag.audioMs} ms"; lag.videoMs > 0 -> "video ${lag.videoMs} ms"; else -> "none" }
            android.util.Log.i("UrTVCommentary", "audio=${st.audio?.name ?: "original"} ready=${st.ready} delayMs=${st.delayMs} held=$held failure=${st.failure ?: "none"} mainMuted=${main.commentaryMuted}")
        } }
    }

    fun start(ch: Channel, initialDelayMs: Long? = null) {
        endAudio(keepFailure = false, keepMuted = true)       // replacing the source: the original sound must not blink back in between
        _recent.value = (listOf(ch) + _recent.value.filter { it.url != ch.url }).take(3)
        val keep = initialDelayMs ?: store?.commentaryDelay(pairKey(ch)) ?: 0L
        _state.value = CommentaryState(audio = ch, delayMs = 0L)
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Net.client).setUserAgent(Http.UA)
        // small devices: the smallest buffers that still play (a few seconds, a couple of MB)
        val load = if (lightest) androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(3000, 5000, 1000, 2000).setTargetBufferBytes(2 * 1024 * 1024).build() else androidx.media3.exoplayer.DefaultLoadControl()
        val p = ExoPlayer.Builder(ctx)
            .setLoadControl(load)
            .setRenderersFactory(DefaultRenderersFactory(ctx))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(http))
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !_state.value.ready) {
                    _state.value = _state.value.copy(ready = true)
                    if (keep != 0L) setDelay(keep)           // this channel's delay from earlier in the session
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                fail(Commentary.failureFor(http, originalFailedSoon = false))
            }
        }
        audioListener = l; p.addListener(l)
        val item = MediaItem.Builder().setUri(Uri.parse(ch.url)).apply { if (ch.url.contains(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        p.volume = 1f; p.setMediaItem(item); p.prepare(); p.playWhenReady = true
        audio = p
        main.setCommentaryMuted(true)
        // the first channel dropping right after the second one starts, or nothing playing within 15 s, is an honest failure
        watch = scope.launch {
            val startedAt = System.currentTimeMillis()
            var seenOriginalFail = false
            val job = launch { main.status.collect { st -> if (st == PlayStatus.Failed && System.currentTimeMillis() - startedAt < 20_000) { seenOriginalFail = true; fail(Commentary.Failure.OneConnection) } } }
            delay(15_000)
            job.cancel()
            if (!_state.value.ready && _state.value.failure == null && !seenOriginalFail) fail(Commentary.Failure.Unreachable)
        }
    }

    /** One press of the delay row (live: it takes effect at once). */
    fun press(step: Long) = setDelay(Commentary.press(_state.value.delayMs, step))

    private fun setDelay(new: Long) {
        val st = _state.value; val a = audio ?: return
        val m = Commentary.plan(st.delayMs, new)
        val okA = m.audioBackMs == 0L || (a.isCurrentMediaItemSeekable.also { if (it) a.seekTo((a.currentPosition - m.audioBackMs).coerceAtLeast(0L)) })
        val okV = main.seekBackBy(m.videoBackMs)
        if (!okA || !okV) { fail(Commentary.Failure.NotSeekable); return }
        _state.value = st.copy(delayMs = new)
    }

    /** Keeps what is playing now: the delay is remembered for this picture + sound pairing. */
    fun applyCurrent() { val st = _state.value; st.audio?.let { store?.setCommentaryDelay(pairKey(it), st.delayMs) } }

    /** Puts back the source and delay that were active when the panel was opened (the panel's «cancel» and Back). */
    fun cancelTo(before: CommentaryState) {
        val now = _state.value
        when {
            before.audio == null -> stop()
            now.audio?.url != before.audio.url -> start(before.audio, before.delayMs)
            now.delayMs != before.delayMs -> setDelay(before.delayMs)
        }
        if (_state.value.failure != null) clearFailure()
    }

    /** Back to the original sound; the second channel is dropped. */
    fun original() = stop()

    fun clearFailure() { _state.value = _state.value.copy(failure = null) }

    private fun fail(f: Commentary.Failure) {
        val wasOriginalDown = main.status.value == PlayStatus.Failed
        endAudio(keepFailure = true)
        _state.value = CommentaryState(failure = f)
        if (wasOriginalDown) main.retryNow()          // the provider cut the first channel: bring it back with its own sound
    }

    fun stop() { endAudio(keepFailure = false); resetZoom() }

    private fun endAudio(keepFailure: Boolean, keepMuted: Boolean = false) {
        watch?.cancel(); watch = null
        val had = audio != null || _state.value.audio != null
        audio?.let { a -> audioListener?.let(a::removeListener); a.stop(); a.clearMediaItems(); a.release() }; audio = null; audioListener = null     // gone completely: player, buffers, listener
        if (had) { if (!keepMuted) main.setCommentaryMuted(false); if (_state.value.delayMs < 0L) main.backToLive() }
        else if (!keepMuted) main.setCommentaryMuted(false)
        if (!keepFailure) _state.value = CommentaryState()
    }
}
