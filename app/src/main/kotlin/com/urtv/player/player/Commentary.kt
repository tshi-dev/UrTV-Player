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

/** What the panel shows: the second channel (null = the original sound), the delay, and why it failed. */
data class CommentaryState(val audio: Channel? = null, val delayMs: Long = 0L, val failure: Commentary.Failure? = null, val ready: Boolean = false)

/**
 * «دمج التعليق»: the main player keeps the picture and is muted through its own volume; a second, audio-only ExoPlayer (video and text
 * disabled) plays the other channel. The delay holds back whichever stream is ahead (a seek inside the live window), at most 10 s.
 * Any failure restores the original sound. The second player stops when the channel changes, on stop and in the background.
 * Never touches the system volume or mute.
 */
class CommentaryController(private val ctx: Context, private val main: PlayerController) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(CommentaryState())
    val state: StateFlow<CommentaryState> = _state
    private var audio: ExoPlayer? = null
    private var watch: Job? = null
    /** The delay chosen for each audio channel, kept for the session (the process), not on disk. */
    private val delays = HashMap<String, Long>()

    init { main.onSessionEnd = { stop() } }

    fun start(ch: Channel) {
        endAudio(keepFailure = false)
        val keep = delays[ch.url] ?: 0L
        _state.value = CommentaryState(audio = ch, delayMs = 0L)
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Net.client).setUserAgent(Http.UA)
        val p = ExoPlayer.Builder(ctx)
            .setRenderersFactory(DefaultRenderersFactory(ctx))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(http))
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        p.addListener(object : Player.Listener {
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
        })
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
        st.audio?.let { delays[it.url] = new }
        _state.value = st.copy(delayMs = new)
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

    fun stop() { endAudio(keepFailure = false) }

    private fun endAudio(keepFailure: Boolean) {
        watch?.cancel(); watch = null
        val had = audio != null || _state.value.audio != null
        audio?.release(); audio = null
        if (had) { main.setCommentaryMuted(false); if (_state.value.delayMs < 0L) main.backToLive() }
        else main.setCommentaryMuted(false)
        if (!keepFailure) _state.value = CommentaryState()
    }
}
