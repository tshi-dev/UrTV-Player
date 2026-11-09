package com.urtv.player.server

import android.view.KeyEvent
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.ui.C
import com.urtv.player.player.PlayStatus
import com.urtv.player.ui.ProgressBar
import com.urtv.player.ui.Spinner
import com.urtv.player.ui.T
import com.urtv.player.ui.Tv
import com.urtv.player.ui.TvButton
import com.urtv.player.ui.accentInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

private enum class Panel { None, Main, Audio, Subs, Style, Offset, Online, Results }

@Composable
private fun streamLabel(s: StreamInfo): String {
    val lang = Lang.norm(s.language)?.let { Locale(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } } ?: "?"
    val extra = listOfNotNull(s.title?.takeIf { it.isNotBlank() && !it.equals(lang, true) && it.length < 40 }, s.codec?.uppercase(), if (s.isForced) stringResource(R.string.srv_forced) else null, if (s.isHearingImpaired) "SDH" else null)
    return lang + if (extra.isEmpty()) "" else "  " + extra.distinct().joinToString("  ")
}

/** Plays a movie or episode from the server: direct play first, server transcoding when needed, progress synced back. */
@Composable
fun ServerPlayerScreen(app: UrtvApp, backend: MediaBackend, first: MediaItem, startMs: Long, queueIn: List<MediaItem>, onExit: () -> Unit) {
    val mgr = app.servers
    val ctx = LocalContext.current
    val ctrl = app.player      // the one shared player: same tuning, buffers, stats overlay and reconnect logic as IPTV
    val scope = rememberCoroutineScope()
    val prefs by mgr.langPrefs.collectAsState(); val mode by mgr.subtitleMode.collectAsState(); val style by mgr.subtitleStyle.collectAsState()

    var item by remember { mutableStateOf(first) }
    var queue by remember { mutableStateOf(queueIn) }
    var plan by remember { mutableStateOf<PlaybackPlan?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var audioSel by remember { mutableStateOf<StreamInfo?>(null) }
    var subSel by remember { mutableStateOf<StreamInfo?>(null) }
    var subAuto by remember { mutableStateOf(true) }
    var track by remember { mutableStateOf<CueTrack?>(null) }
    var markers by remember { mutableStateOf<List<Marker>>(emptyList()) }
    var panel by remember { mutableStateOf(Panel.None) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var bar by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    var offset by remember { mutableLongStateOf(mgr.subOffset(first.id)) }
    var results by remember { mutableStateOf<Res<List<RemoteSubtitle>>>(Res.Loading) }
    var resultsLang by remember { mutableStateOf("") }
    var streak by remember { mutableStateOf(0) }
    val root = remember { FocusRequester() }
    val status by ctrl.status.collectAsState(); val paused by ctrl.paused.collectAsState(); val ended by ctrl.ended.collectAsState()
    val buffering = status == PlayStatus.Loading || status == PlayStatus.Reconnecting
    val perr = if (status == PlayStatus.Failed) "failed" else null
    fun curPos() = ctrl.player?.currentPosition ?: 0L
    fun curDur() = ctrl.player?.duration?.takeIf { it > 0 } ?: 0L

    /** (Re)starts playback of [it] at [at]. Picks audio and subtitles by the user's preferences unless overridden. */
    fun begin(it: MediaItem, at: Long, keepChoices: Boolean) {
        scope.launch {
            failed = null; bar = true; tick++
            try {
                val full = withContext(Dispatchers.IO) { if (it.streams.isEmpty()) backend.item(it.id) ?: it else it }
                item = full
                val audio = (if (keepChoices) audioSel else null) ?: SubtitleChoice.pickAudio(full.audio, prefs)
                val sub = if (keepChoices && !subAuto) subSel else SubtitleChoice.pickSubtitle(full.subtitles, audio, prefs, mode)
                audioSel = audio; subSel = sub
                val burn = sub?.takeIf { s -> SubtitleChoice.needsBurnIn(s) }
                val p = withContext(Dispatchers.IO) { backend.playback(full, mgr.capsNow(ctrl.bandwidthEstimate()), PlayOptions(0, audio?.index, burn?.index)) }
                plan?.let { old -> launch(Dispatchers.IO) { backend.reportStop(old, curPos()) } }
                plan = p
                ctrl.playServer(full.cardTitle(), p.url, at, p.headers, prefs.audio.flatMap { l -> listOf(l, Lang.toIso3(l)) }.distinct())
                offset = mgr.subOffset(full.id)
                track = null
                if (sub != null && burn == null) {
                    val txt = withContext(Dispatchers.IO) { backend.subtitleUrl(p, sub)?.let { u -> runCatching { mgr.transport(backend.config.pinnedCert).send(Req("GET", u, p.headers)).takeIf { r -> r.code in 200..299 }?.body }.getOrNull() } }
                    track = txt?.let { SubtitleParser.parse(it) }?.takeIf { l -> l.isNotEmpty() }?.let { l -> CueTrack(l) }
                }
                launch(Dispatchers.IO) { backend.reportStart(p, at) }
                launch { markers = withContext(Dispatchers.IO) { runCatching { backend.markers(full) }.getOrDefault(emptyList()) } }
                if (full.type == ItemType.Episode && queue.isEmpty() && full.seriesId != null && full.seasonId != null)
                    launch { queue = withContext(Dispatchers.IO) { runCatching { backend.episodes(full.seriesId!!, full.seasonId!!).dropWhile { e -> e.id != full.id }.drop(1) }.getOrDefault(emptyList()) } }
            } catch (e: ServerException) { failed = e.message ?: "error" }
            catch (e: Exception) { failed = "error" }
        }
    }

    LaunchedEffect(Unit) { begin(first, startMs, false) }
    LaunchedEffect(Unit) { while (true) { delay(250); pos = curPos(); dur = curDur().takeIf { it > 0 } ?: item.runtimeMs } }
    LaunchedEffect(plan) { // progress sync every 10 s, plus on pause changes
        val p = plan ?: return@LaunchedEffect
        while (true) { delay(10_000); withContext(Dispatchers.IO) { backend.reportProgress(p, curPos(), !(ctrl.player?.playWhenReady ?: false)) } }
    }
    LaunchedEffect(tick, bar, paused, panel) { if (bar && !paused && panel == Panel.None) { delay(4000); bar = false } }
    LaunchedEffect(panel) { if (panel == Panel.None) runCatching { root.requestFocus() } }
    LaunchedEffect(ended) {
        if (ended) { val nx = queue.firstOrNull(); if (nx != null) { queue = queue.drop(1); begin(nx, 0, false) } else { onExit() } }
    }
    fun finish() {
        val p = plan; val at = curPos()
        if (p != null) scope.launch(kotlinx.coroutines.NonCancellable) { withContext(Dispatchers.IO) { backend.reportStop(p, at) } }
        ctrl.stop(); onExit()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { val p = plan; if (p != null) { val at = curPos(); scope.launch(kotlinx.coroutines.NonCancellable) { withContext(Dispatchers.IO) { backend.reportStop(p, at) } } }; ctrl.rememberPosition(); ctrl.release() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { ctrl.resume() }
    DisposableEffect(Unit) { onDispose { ctrl.stop() } }
    BackHandler { if (panel != Panel.None) panel = if (panel == Panel.Main) Panel.None else Panel.Main else if (bar && !paused) bar = false else finish() }

    val activeMarker = markers.firstOrNull { pos in it.startMs until it.endMs && (it.kind == MarkerKind.Intro || queue.isEmpty()) }
    val nextUp = queue.firstOrNull()?.takeIf { dur > 0 && pos > dur - 25_000 }
    val cues = if (panel == Panel.Style) listOf(Cue(0, 1, stringResource(R.string.srv_sample))) else track?.at(pos, offset).orEmpty()

    fun jump(delta: Long) { ctrl.player?.let { p -> val d = if (p.duration > 0) p.duration - 1000 else Long.MAX_VALUE; p.seekTo((p.currentPosition + delta).coerceIn(0, d)) }; bar = true; tick++ }
    fun setSub(s: StreamInfo?) { subAuto = false; subSel = s; panel = Panel.None; begin(item, curPos(), true) }
    fun setAudio(s: StreamInfo) { audioSel = s; panel = Panel.None
        val ord = item.audio.indexOfFirst { it.index == s.index }
        if (plan?.mode == PlayMode.DirectPlay && ord >= 0) ctrl.tracks.value.audio.getOrNull(ord)?.let { o -> ctrl.selectAudio(o) } else begin(item, curPos(), true) }
    fun shift(d: Long) { offset = (offset + d).coerceIn(-30_000, 30_000); mgr.setSubOffset(item.id, offset) }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(root).focusable().onPreviewKeyEvent { e ->
            if (panel != Panel.None) return@onPreviewKeyEvent false
            if (e.type != KeyEventType.KeyDown) { if (e.type == KeyEventType.KeyUp) streak = 0; return@onPreviewKeyEvent false }
            when (e.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { streak++; jump(-(if (streak > 12) 60_000L else if (streak > 4) 30_000L else 10_000L)); true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { streak++; jump(if (streak > 12) 60_000L else if (streak > 4) 30_000L else 10_000L); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val m = activeMarker
                    if (nextUp != null) { queue = queue.drop(1); begin(nextUp, 0, false) }
                    else if (m != null) { ctrl.player?.seekTo(m.endMs); bar = true; tick++ }
                    else { ctrl.togglePause(); bar = true; tick++ }
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { ctrl.togglePause(); bar = true; tick++; true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> { bar = true; tick++; true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { panel = Panel.Main; true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { queue.firstOrNull()?.let { n -> queue = queue.drop(1); begin(n, 0, false) }; true }
                else -> false
            }
        },
    ) {
        com.urtv.player.ui.VideoSurface(ctrl)
        SubtitleOverlay(cues, style)
        if (app.store.statsOverlay.collectAsState().value) com.urtv.player.ui.StatsOverlay(ctrl, Modifier.align(Alignment.TopStart).padding(40.dp))
        Box(Modifier.align(Alignment.Center)) {
            when {
                failed != null || perr != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.play_failed), style = T.title); TvButton(stringResource(R.string.retry), { begin(item, pos, true) }, primary = true) }
                buffering || plan == null -> Spinner()
                paused -> Text("❚❚", style = T.display)
            }
        }
        // Skip intro / credits and the next-episode prompt (OK acts on them).
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = 150.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (nextUp != null) Column(Modifier.background(Color(0xE005070B), com.urtv.player.ui.Rd(14)).padding(18.dp)) {
                Text(stringResource(R.string.srv_next_episode), style = T.small); Text(episodeLabel(nextUp.seasonNumber, nextUp.episodeNumber) + "  " + nextUp.name, style = T.bodyBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.srv_ok_to_play), style = T.tiny)
            } else if (activeMarker != null) Box(Modifier.background(Color(0xE005070B), com.urtv.player.ui.Rd(14)).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(stringResource(if (activeMarker.kind == MarkerKind.Intro) R.string.srv_skip_intro else R.string.srv_skip_credits) + "  (OK)", style = T.bodyBold)
            }
        }
        androidx.compose.animation.AnimatedVisibility(bar || paused, Modifier.align(Alignment.BottomCenter), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE05070B)))).padding(horizontal = 64.dp).padding(top = 60.dp, bottom = 36.dp)) {
                Text(item.cardTitle(), style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.type == ItemType.Episode) Text(episodeLabel(item.seasonNumber, item.episodeNumber) + "  " + item.name, style = T.small, maxLines = 1)
                Spacer(Modifier.height(12.dp))
                ProgressBar(if (dur > 0) pos / dur.toFloat() else 0f)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(clock(pos), style = T.small); Spacer(Modifier.weight(1f))
                    plan?.let { Text(stringResource(if (it.mode == PlayMode.DirectPlay) R.string.srv_direct else R.string.srv_transcoding), style = T.tiny) }
                    Spacer(Modifier.weight(1f)); Text(if (dur > 0) clock(dur) else "", style = T.small)
                }
            }
        }
        if (panel != Panel.None) Box(Modifier.align(Alignment.CenterEnd)) {
            OptionsPanel(panel, item, plan, audioSel, subSel, prefs, style, offset, results, resultsLang,
                go = { panel = it }, onAudio = ::setAudio, onSub = ::setSub, onShift = ::shift, onResetOffset = { offset = 0; mgr.setSubOffset(item.id, 0) },
                onStyle = { mgr.setSubtitleStyle(it) },
                onSearch = { lang ->
                    resultsLang = lang; results = Res.Loading; panel = Panel.Results
                    scope.launch { results = withContext(Dispatchers.IO) { try { Res.Ok(backend.searchSubtitles(item, lang)) } catch (e: ServerException) { Res.Fail(e) } catch (e: Exception) { Res.Fail(ServerException(ServerException.Reason.Network, "")) } } }
                },
                onDownload = { r ->
                    panel = Panel.None
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { backend.downloadSubtitle(item, r); true }.getOrDefault(false) }
                        if (ok) { val fresh = withContext(Dispatchers.IO) { runCatching { backend.item(item.id) }.getOrNull() }
                            if (fresh != null) { item = fresh; subAuto = false; subSel = fresh.subtitles.lastOrNull { Lang.norm(it.language) == Lang.norm(r.language ?: resultsLang) } ?: subSel; begin(fresh, curPos(), true) } }
                    }
                })
        }
    }
}

@Composable
private fun OptionsPanel(
    panel: Panel, item: MediaItem, plan: PlaybackPlan?, audio: StreamInfo?, sub: StreamInfo?, prefs: LangPrefs, style: SubtitleStyle, offset: Long,
    results: Res<List<RemoteSubtitle>>, resultsLang: String,
    go: (Panel) -> Unit, onAudio: (StreamInfo) -> Unit, onSub: (StreamInfo?) -> Unit, onShift: (Long) -> Unit, onResetOffset: () -> Unit,
    onStyle: (SubtitleStyle) -> Unit, onSearch: (String) -> Unit, onDownload: (RemoteSubtitle) -> Unit,
) {
    val fr = remember(panel) { FocusRequester() }
    LaunchedEffect(panel) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxHeight().width(520.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xE005070B), Color(0xF005070B)))).padding(start = 64.dp, end = 32.dp, top = 40.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        when (panel) {
            Panel.Main -> {
                Text(item.cardTitle(), style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Opt(stringResource(R.string.srv_opt_audio), audio?.let { a -> streamLabel(a) } ?: "-", fr) { go(Panel.Audio) }
                Opt(stringResource(R.string.srv_opt_subs), sub?.let { s -> streamLabel(s) } ?: stringResource(R.string.srv_off)) { go(Panel.Subs) }
                Opt(stringResource(R.string.srv_opt_style), "") { go(Panel.Style) }
                Opt(stringResource(R.string.srv_opt_offset), (if (offset > 0) "+" else "") + "%.1f s".format(Locale.US, offset / 1000f)) { go(Panel.Offset) }
                Opt(stringResource(R.string.srv_opt_online), "") { go(Panel.Online) }
                plan?.let { Text(stringResource(if (it.mode == PlayMode.DirectPlay) R.string.srv_direct else R.string.srv_transcoding) + "  " + it.reason, style = T.tiny, maxLines = 2) }
            }
            Panel.Audio -> { Text(stringResource(R.string.srv_opt_audio), style = T.title)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(item.audio, key = { it.index }) { a -> Opt(streamLabel(a), if (a.channels != null) "${a.channels}ch" else "", if (a.index == audio?.index) fr else null, selected = a.index == audio?.index) { onAudio(a) } } } }
            Panel.Subs -> { Text(stringResource(R.string.srv_opt_subs), style = T.title)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Opt(stringResource(R.string.srv_off), "", if (sub == null) fr else null, selected = sub == null) { onSub(null) } }
                    items(item.subtitles, key = { it.index }) { s -> Opt(streamLabel(s), if (SubtitleChoice.isImage(s)) stringResource(R.string.srv_burned) else "", if (s.index == sub?.index) fr else null, selected = s.index == sub?.index) { onSub(s) } }
                } }
            Panel.Style -> { Text(stringResource(R.string.srv_opt_style), style = T.title)
                styleRows(style).forEachIndexed { i, r -> Opt(stringResource(r.label), if (r.valueRes != 0) stringResource(r.valueRes) else r.valueText, if (i == 0) fr else null) { onStyle(r.next) } } }
            Panel.Offset -> { Text(stringResource(R.string.srv_opt_offset), style = T.title)
                Text((if (offset > 0) "+" else "") + "%.1f s".format(Locale.US, offset / 1000f), style = T.display)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { TvButton("−0.5", { onShift(-500) }, focusRequester = fr); TvButton("−0.1", { onShift(-100) }); TvButton("+0.1", { onShift(100) }); TvButton("+0.5", { onShift(500) }) }
                TvButton(stringResource(R.string.srv_reset), onResetOffset) }
            Panel.Online -> { Text(stringResource(R.string.srv_opt_online), style = T.title); Text(stringResource(R.string.srv_online_hint), style = T.tiny)
                prefs.subtitle.take(3).forEachIndexed { i, l -> Opt(Locale(l).getDisplayLanguage(Locale.getDefault()).ifBlank { l }, "", if (i == 0) fr else null) { onSearch(l) } } }
            Panel.Results -> { Text(stringResource(R.string.srv_opt_online), style = T.title)
                when (results) {
                    is Res.Loading -> Spinner()
                    is Res.Fail -> Text(errorText(results.e), style = T.body)
                    is Res.Ok -> if (results.v.isEmpty()) Text(stringResource(R.string.srv_subs_none), style = T.body)
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(results.v, key = { it.id }) { r -> Opt(r.name, listOfNotNull(r.provider, r.downloads?.let { "↓$it" }).joinToString(" "), if (r == results.v.first()) fr else null) { onDownload(r) } } }
                } }
            Panel.None -> {}
        }
    }
}

@Composable
private fun Opt(label: String, value: String, fr: FocusRequester? = null, selected: Boolean = false, onClick: () -> Unit) {
    Tv(onClick, Modifier.fillMaxWidth().height(60.dp), com.urtv.player.ui.Rd(16), selected = selected, focusRequester = fr, restColor = Color(0x66151A24)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = T.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value.isNotEmpty()) Text(value, style = T.small.copy(color = accentInk()), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
        }
    }
}
