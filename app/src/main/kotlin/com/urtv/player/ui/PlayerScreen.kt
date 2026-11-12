package com.urtv.player.ui

import android.view.KeyEvent
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.Channel
import com.urtv.player.data.AspectMode
import com.urtv.player.data.Browse
import com.urtv.player.player.PlayStatus
import com.urtv.player.player.PlayerController
import kotlinx.coroutines.delay

internal val SLEEP_STEPS = listOf(0, 15, 30, 60, 90, 120)

private enum class Overlay { None, Info, Guide, Options, Lists }

@Composable
fun PlayerScreen(app: UrtvApp, queue: List<Channel>, start: Channel, onExit: (Channel) -> Unit) {
    val store = app.store
    val ctrl = app.player
    val cat by app.catalog.state.collectAsState()
    val epg by app.catalog.epg.collectAsState()
    val favs by store.favs.collectAsState()
    val locked by store.lockedGroups.collectAsState()
    val pid = cat.playlistId.orEmpty()
    val all = cat.channels
    val nowMs by rememberNow()

    var shown by remember { mutableStateOf(start) }
    androidx.compose.runtime.DisposableEffect(all) { ctrl.alternates = { c -> com.urtv.player.core.Alternates.find(c, all) }; onDispose { ctrl.alternates = null } }
    val cause by ctrl.cause.collectAsState(); val altNote by ctrl.altNote.collectAsState()
    var previous by remember { mutableStateOf<Channel?>(null) }
    var overlay by remember { mutableStateOf(Overlay.Info) }
    var infoTick by remember { mutableStateOf(0) }
    var digits by remember { mutableStateOf("") }
    var blocked by remember { mutableStateOf(false) }
    var pinReq by remember { mutableStateOf<PinRequest?>(null) }
    var firstPlay by remember { mutableStateOf(true) }
    val root = remember { FocusRequester() }
    var sleepMin by remember { mutableStateOf(0) }
    LaunchedEffect(sleepMin) { if (sleepMin > 0) { delay(sleepMin * 60_000L); onExit(shown) } }

    fun showInfo() { overlay = Overlay.Info; infoTick++ }
    fun goTo(c: Channel) { if (c.url != shown.url) previous = shown; shown = c; blocked = false; showInfo() }
    fun step(d: Int) {
        val q = if (queue.any { it.url == shown.url }) queue else all
        if (q.isEmpty()) return
        val i = q.indexOfFirst { it.url == shown.url }
        goTo(q[((if (i < 0) 0 else i) + d + q.size) % q.size])
    }

    // Debounced start so holding Up/Down never thrashes the decoder; parental lock is checked here.
    LaunchedEffect(shown.url) {
        if (!firstPlay) delay(180)     // holding a key never starts a stream per channel; short enough not to be felt
        firstPlay = false
        val gk = Browse.groupsOf(shown).map { groupKey(pid, it) }.firstOrNull { it in locked && it !in app.unlocked } ?: groupKey(pid, Browse.groupOf(shown))
        if (gk in locked && gk !in app.unlocked) {
            blocked = true; ctrl.stop()
            pinReq = PinRequest.Verify { app.unlocked = app.unlocked + gk; blocked = false; ctrl.play(shown); store.pushRecent(chKey(pid, shown)) }
        } else { blocked = false; ctrl.play(shown); store.pushRecent(chKey(pid, shown)) }
    }
    // Zap ahead: resolve the host names of the next two channels each way (cheap), and, unless the TV is low on memory or the link is
    // metered, fetch the manifests of the two nearest neighbours (at most 2 requests) so the next zap skips DNS, connect and manifest.
    val lowRamTv = remember { (app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice }
    LaunchedEffect(shown.url) {
        delay(700)
        val q = if (queue.any { it.url == shown.url }) queue else all
        val i = q.indexOfFirst { it.url == shown.url }
        if (i < 0 || q.size < 2) return@LaunchedEffect
        fun at(d: Int) = q[((i + d) % q.size + q.size) % q.size]
        for (d in intArrayOf(1, -1, 2, -2)) com.urtv.player.player.Net.preResolve(at(d).url)
        val metered = runCatching { (app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered }.getOrDefault(false)
        if (store.prefetchNeighbours.value && !lowRamTv && !metered) { com.urtv.player.player.Net.prefetchManifest(at(1).url); com.urtv.player.player.Net.prefetchManifest(at(-1).url) }
    }
    LaunchedEffect(infoTick, overlay) { if (overlay == Overlay.Info) { delay(4500); if (overlay == Overlay.Info) overlay = Overlay.None } }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1800)
            val n = digits.toIntOrNull(); digits = ""
            if (n == 0) previous?.let { goTo(it) } else if (n != null) Browse.byNumber(all, n)?.let { goTo(it) }
        }
    }
    LaunchedEffect(overlay) { if (overlay == Overlay.None || overlay == Overlay.Info) runCatching { root.requestFocus() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { ctrl.release() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (!blocked) ctrl.resume() }
    DisposableEffect(Unit) { onDispose { ctrl.stop() } }
    BackHandler { if (overlay == Overlay.Guide || overlay == Overlay.Options || overlay == Overlay.Lists) overlay = Overlay.None else if (overlay == Overlay.Info) overlay = Overlay.None else onExit(shown) }

    val status by ctrl.status.collectAsState()
    val attempt by ctrl.attempt.collectAsState()

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(root).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val code = e.nativeKeyEvent.keyCode
            if (overlay == Overlay.Guide || overlay == Overlay.Options || overlay == Overlay.Lists) {
                if (code == KeyEvent.KEYCODE_MENU) { overlay = Overlay.None; true } else false
            } else when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(-1); true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { step(1); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (digits.isNotEmpty()) { val n = digits.toIntOrNull(); digits = ""; if (n != null) Browse.byNumber(all, n)?.let { goTo(it) } }
                    else if (blocked) pinReq = PinRequest.Verify { app.unlocked = app.unlocked + Browse.groupsOf(shown).map { groupKey(pid, it) }; blocked = false; ctrl.play(shown) }
                    else if (status == PlayStatus.Failed) ctrl.retryNow()
                    else overlay = Overlay.Guide
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> { overlay = Overlay.Options; true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { showInfo(); true }
                KeyEvent.KEYCODE_LAST_CHANNEL -> { previous?.let { goTo(it) }; true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { ctrl.togglePause(); true }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { if (digits.length < 4) digits += (code - KeyEvent.KEYCODE_0); true }
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { if (digits.length < 4) digits += (code - KeyEvent.KEYCODE_NUMPAD_0); true }
                else -> false
            }
        },
    ) {
        if (!blocked) VideoSurface(ctrl)
        val sub by ctrl.cue.collectAsState()
        if (sub.isNotBlank()) Text(sub, style = T.title.copy(color = Color.White), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 200.dp, start = 240.dp, end = 240.dp).background(Color(0x99000000), Rd(8)).padding(horizontal = 14.dp, vertical = 4.dp))

        if (store.statsOverlay.collectAsState().value) StatsOverlay(ctrl, Modifier.align(Alignment.TopStart).padding(40.dp), extra = app.catalog.timings.let { "list ${it.channels} ch: fetch+parse ${it.fetchParseMs} ms, cache ${it.cacheWriteMs} ms" })
        // status
        val paused by ctrl.paused.collectAsState()
        val firstFrame by ctrl.firstFrame.collectAsState()
        // A new channel (or a reconnect) must never look like the old picture froze: black it out at once; the banner below names the new channel.
        val opening = !blocked && (status == PlayStatus.Reconnecting || (status == PlayStatus.Loading && !firstFrame))
        if (opening) Box(Modifier.fillMaxSize().background(Color.Black))
        Box(Modifier.align(Alignment.Center)) {
            when {
                blocked -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Glyph.Lock, C.muted, 72); Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.group_locked), style = T.title); Text(stringResource(R.string.press_ok_unlock), style = T.small) }
                status == PlayStatus.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(stringResource(R.string.play_failed), style = T.title); WhyLine(cause, ctrl.lastHttpStatus); Text(stringResource(R.string.press_ok_retry), style = T.small) }
                status == PlayStatus.Reconnecting -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Spinner(); Spacer(Modifier.height(10.dp)); WhyLine(cause, ctrl.lastHttpStatus) }
                status == PlayStatus.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Spinner(); WhyLine(cause, ctrl.lastHttpStatus) }
                paused -> Text("❚❚", style = T.display)
            }
        }

        // number entry
        AnimatedVisibility(digits.isNotEmpty(), Modifier.align(Alignment.TopEnd).padding(80.dp), enter = fadeIn(), exit = fadeOut()) {
            Text(digits, style = T.display.copy(fontSize = androidx.compose.ui.unit.TextUnit(72f, androidx.compose.ui.unit.TextUnitType.Sp)), modifier = Modifier.background(Color(0xAA000000), Rd(20)).padding(horizontal = 28.dp, vertical = 8.dp))
        }

        // info banner: bottom scrim, logo + number + name + live pill, programme title, time bar, next
        AnimatedVisibility(overlay == Overlay.Info || opening, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it / 4 }, exit = fadeOut() + slideOutVertically { it / 4 }) {
            val (now, next) = epg.nowNext(shown.epgId, nowMs)
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0000000)))).padding(horizontal = 84.dp).padding(top = 90.dp, bottom = 56.dp), verticalAlignment = Alignment.Bottom) {
                Logo(shown, 96)
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("${Browse.numberOf(all, shown)}", style = T.row.copy(color = C.text))
                        Text(shown.name, style = T.row.copy(color = C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Row(Modifier.clip(RoundedCornerShape(50)).background(C.glass2).padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(C.accent)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.live), style = T.tiny.copy(color = C.text, fontSize = 15.sp))
                        }
                        if (chKey(pid, shown) in favs) Icon(Glyph.Heart, C.accent, 24, filled = true)
                    }
                    if (opening) Text(stringResource(if (status == PlayStatus.Reconnecting) R.string.reconnecting else R.string.opening_channel, *(if (status == PlayStatus.Reconnecting) arrayOf<Any>(attempt, PlayerController.MAX_ATTEMPTS) else emptyArray())), style = T.body.copy(color = Color.White), modifier = Modifier.padding(top = 4.dp))
                    if (now != null) {
                        Text(now.title, style = T.display.copy(fontSize = 40.sp, lineHeight = 54.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { Text("${fmtTime(now.startMs)} – ${fmtTime(now.stopMs)}", style = T.small); Text(leftText(now, nowMs), style = T.small) }
                        Spacer(Modifier.height(12.dp))
                        ProgressBar((nowMs - now.startMs).toFloat() / (now.stopMs - now.startMs).coerceAtLeast(1))
                    } else Text(shown.name, style = T.display.copy(fontSize = 40.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    altNote?.let { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.alt_playing, it), style = T.small.copy(color = C.accent), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (cause != com.urtv.player.core.Diagnosis.Cause.None) { Spacer(Modifier.height(8.dp)); Row { Text(stringResource(R.string.why_title) + ": ", style = T.small.copy(color = C.text)); WhyLine(cause, ctrl.lastHttpStatus, inline = true) } }
                    if (next != null) { Spacer(Modifier.height(12.dp)); Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Text(stringResource(R.string.next), style = T.small.copy(color = C.text)); Text("${fmtTime(next.startMs)}  ${next.title}", style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                }
            }
        }

        // mini guide
        AnimatedVisibility(overlay == Overlay.Guide, Modifier.align(Alignment.CenterStart), enter = fadeIn() + slideInVertically { 0 }, exit = fadeOut()) {
            val list = if (queue.any { it.url == shown.url }) queue else all
            MiniGuide(list, shown, epg, nowMs, all, fav = chKey(pid, shown) in favs, onFav = { store.toggleFav(chKey(pid, shown)) }, statsOn = store.statsOverlay.collectAsState().value, onStats = { store.setStatsOverlay(!store.statsOverlay.value) }, cause = cause, httpStatus = ctrl.lastHttpStatus) { goTo(it); overlay = Overlay.None }
        }
        // my lists picker
        AnimatedVisibility(overlay == Overlay.Lists, Modifier.align(Alignment.CenterEnd), enter = fadeIn(), exit = fadeOut()) {
            ListsPicker(app, pid, chKey(pid, shown)) { overlay = Overlay.Options }
        }
        // options
        AnimatedVisibility(overlay == Overlay.Options, Modifier.align(Alignment.CenterEnd), enter = fadeIn(), exit = fadeOut()) {
            OptionsPanel(app, shown.name, chKey(pid, shown) in favs, onFav = { store.toggleFav(chKey(pid, shown)) }, onGuide = { overlay = Overlay.Guide }, onLists = { overlay = Overlay.Lists }, onReload = { ctrl.retryNow() }, onClose = { overlay = Overlay.None }, sleepMin = sleepMin, onSleep = { sleepMin = SLEEP_STEPS[(SLEEP_STEPS.indexOf(sleepMin) + 1) % SLEEP_STEPS.size] })
        }
    }
    PinHost(pinReq, store) { pinReq = null }
}

@Composable
internal fun VideoSurface(ctrl: PlayerController) {
    val ar by ctrl.videoAspect.collectAsState()
    val mode by ctrl.aspect.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val box = maxWidth / maxHeight
        val v = if (ar > 0f) ar else 16f / 9f
        fun fit(a: Float) = if (a > box) maxWidth to maxWidth / a else maxHeight * a to maxHeight
        val (w, h) = when (mode) {
            AspectMode.Fit -> fit(v)
            AspectMode.Fill -> if (v > box) maxHeight * v to maxHeight else maxWidth to maxWidth / v
            AspectMode.Stretch -> maxWidth to maxHeight
            AspectMode.R16x9 -> fit(16f / 9f)
            AspectMode.R4x3 -> fit(4f / 3f)
        }
        AndroidView({ SurfaceView(it).also(ctrl::attach) }, Modifier.requiredSize(w, h).align(Alignment.Center), onRelease = { ctrl.detach(it) })
    }
}

@Composable
internal fun MiniGuide(list: List<Channel>, current: Channel, epg: com.urtv.player.data.EpgIndex, nowMs: Long, all: List<Channel>, fav: Boolean, onFav: () -> Unit, statsOn: Boolean, onStats: () -> Unit, cause: com.urtv.player.core.Diagnosis.Cause, httpStatus: Int, onPick: (Channel) -> Unit) {
    val idx = list.indexOfFirst { it.url == current.url }.coerceAtLeast(0)
    val state = rememberLazyListState()
    // one focus requester per row, created as rows appear: the actions column sends the focus back to the row you LEFT (not the nearest one)
    val listKeys = remember(list) { uniqueKeys(list.map { it.url }) }     // a playlist may repeat a stream URL: every row needs its own key
    val rowRq = remember { HashMap<Int, FocusRequester>() }
    val lastRow = remember { intArrayOf(idx) }
    val fr = rowRq.getOrPut(idx) { FocusRequester() }
    // open on the CURRENT channel: scroll it into the composed range first, then ask for the focus until its row exists (the first try can land before it is laid out)
    LaunchedEffect(Unit) {
        state.scrollToItem((idx - 2).coerceAtLeast(0))
        repeat(12) { if (runCatching { fr.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect; delay(30) }
    }
    // scrim fades toward the video from the start side. The list is on the start side; the quick actions are a FIXED column on the end side,
    // one D-pad move (toward the video) from wherever the list is, however far down the current channel is.
    Row(Modifier.fillMaxHeight().width(940.dp).background(Brush.horizontalGradient(listOf(Color(0xF2080909), Color(0xE6080909), Color.Transparent)))) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state, contentPadding = PaddingValues(start = 40.dp, end = 12.dp, top = 48.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(list, key = { i, _ -> listKeys[i] }) { i, c ->
                val (now, _) = epg.nowNext(c.epgId, nowMs)
                Tv({ onPick(c) }, Modifier.fillMaxWidth().height(72.dp), Rd(12), selected = i == idx, focusRequester = rowRq.getOrPut(i) { FocusRequester() }, onFocus = { if (it) lastRow[0] = i }, lift = 1.02f, flat = true) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${Browse.numberOf(all, c)}", style = T.small.copy(color = C.faint), modifier = Modifier.width(52.dp))
                        Logo(c, 44); Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = T.row.copy(fontSize = 23.sp, lineHeight = 30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (now != null) Text(now.title, style = T.small.copy(fontSize = 18.sp, lineHeight = 24.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Column(Modifier.width(280.dp).fillMaxHeight().padding(top = 48.dp, bottom = 48.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)) {
            val back = Modifier.focusProperties { start = rowRq[lastRow[0]] ?: FocusRequester.Default }     // toward the list: the row you left
            Tv(onFav, Modifier.fillMaxWidth().height(60.dp).then(back), Rd(14), selected = fav, lift = 1f) {
                Row(Modifier.padding(horizontal = 18.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyph.Heart, if (fav) C.accent else C.muted, 24, filled = fav); Spacer(Modifier.width(10.dp))
                    Text(stringResource(if (fav) R.string.menu_unfav else R.string.menu_fav), style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Tv(onStats, Modifier.fillMaxWidth().height(60.dp).then(back), Rd(14), selected = statsOn, lift = 1f) {
                Text(stringResource(R.string.set_stats), style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp))
            }
            // «why is it choppy?» is always answered here, also when nothing is wrong
            Column(Modifier.padding(start = 6.dp, top = 8.dp)) {
                Text(stringResource(R.string.why_title), style = T.small.copy(color = C.text))
                if (cause == com.urtv.player.core.Diagnosis.Cause.None) Text(stringResource(R.string.why_ok), style = T.small.copy(color = C.accent), maxLines = 2)
                else WhyLine(cause, httpStatus, inline = true)
            }
        }
    }
}

@Composable
internal fun OptionsPanel(app: UrtvApp, title: String, fav: Boolean?, onFav: () -> Unit, onGuide: (() -> Unit)?, onReload: () -> Unit, onClose: () -> Unit, sleepMin: Int = 0, onSleep: () -> Unit = {}, onLists: (() -> Unit)? = null) {
    val ctrl = app.player
    val tracks by ctrl.tracks.collectAsState()
    val aspect by ctrl.aspect.collectAsState()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val audio = tracks.audio.firstOrNull { it.selected }
    val text = tracks.text.firstOrNull { it.selected }
    Column(Modifier.fillMaxHeight().width(620.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xE0080909), Color(0xF2080909)))).padding(start = 110.dp, end = 60.dp, top = 60.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Text(title, style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OptRow(stringResource(R.string.opt_audio), audio?.label ?: stringResource(R.string.none), fr) {
            if (tracks.audio.size > 1) ctrl.selectAudio(tracks.audio[(tracks.audio.indexOf(audio) + 1) % tracks.audio.size])
        }
        OptRow(stringResource(R.string.opt_subs), if (tracks.textOn && text != null) text.label else stringResource(if (tracks.text.isEmpty()) R.string.none else R.string.off)) {
            val l = tracks.text
            if (l.isNotEmpty()) { val i = if (text == null) -1 else l.indexOf(text); ctrl.selectText(if (i + 1 >= l.size) null else l[i + 1]) }
        }
        OptRow(stringResource(R.string.opt_aspect), stringResource(when (aspect) { AspectMode.Fit -> R.string.aspect_fit; AspectMode.Fill -> R.string.aspect_fill; AspectMode.Stretch -> R.string.aspect_stretch; AspectMode.R16x9 -> R.string.aspect_169; AspectMode.R4x3 -> R.string.aspect_43 })) { ctrl.cycleAspect() }
        if (fav != null) OptRow(stringResource(R.string.opt_fav), stringResource(if (fav) R.string.on else R.string.off)) { onFav() }
        if (onGuide != null) OptRow(stringResource(R.string.opt_list), "") { onGuide() }
        if (onLists != null) OptRow(stringResource(R.string.opt_my_lists), "") { onLists() }
        OptRow(stringResource(R.string.opt_sleep), if (sleepMin == 0) stringResource(R.string.off) else stringResource(R.string.min_n, sleepMin)) { onSleep() }
        OptRow(stringResource(R.string.opt_reload), "") { onReload(); onClose() }
    }
}

@Composable
private fun OptRow(label: String, value: String, fr: FocusRequester? = null, onClick: () -> Unit) {
    Tv(onClick, Modifier.fillMaxWidth().height(60.dp), Rd(12), focusRequester = fr) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = T.body, modifier = Modifier.weight(1f))
            Text(value, style = T.bodyBold.copy(color = C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Add or remove the current channel in the user's own lists; "New list" creates one named "List N". */
@Composable
private fun ListsPicker(app: UrtvApp, pid: String, key: String, onBack: () -> Unit) {
    val store = app.store
    val lists by store.customLists.collectAsState()
    val names = lists.keys.filter { it.startsWith("$pid|") }.map { it.substringAfter('|') }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = onBack)
    val base = stringResource(R.string.list_default_name)
    Column(Modifier.fillMaxHeight().width(620.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xE0080909), Color(0xF2080909)))).padding(start = 110.dp, end = 60.dp, top = 60.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Text(stringResource(R.string.opt_my_lists), style = T.title)
        names.forEachIndexed { i, n ->
            val on = key in (lists["$pid|$n"] ?: emptySet())
            OptRow(n, if (on) "✓" else "", if (i == 0) fr else null) { store.toggleInList(pid, n, key) }
        }
        OptRow(stringResource(R.string.new_list), "+", if (names.isEmpty()) fr else null) {
            var i = names.size + 1; while ("$base $i" in names) i++
            val n = store.createList(pid, "$base $i"); store.toggleInList(pid, n, key)
        }
    }
}

/** One careful sentence naming the likely cause of choppy playback (nothing when there is none). */
@Composable
fun WhyLine(cause: com.urtv.player.core.Diagnosis.Cause, httpStatus: Int, inline: Boolean = false) {
    val t = when (cause) {
        com.urtv.player.core.Diagnosis.Cause.Source -> stringResource(R.string.why_source); com.urtv.player.core.Diagnosis.Cause.HomeNetwork -> stringResource(R.string.why_network); com.urtv.player.core.Diagnosis.Cause.Decoder -> stringResource(R.string.why_decoder)
        com.urtv.player.core.Diagnosis.Cause.DeadChannel -> stringResource(R.string.why_dead, if (httpStatus > 0) "HTTP $httpStatus" else "—"); com.urtv.player.core.Diagnosis.Cause.Unknown -> stringResource(R.string.why_unknown); com.urtv.player.core.Diagnosis.Cause.None -> return
    }
    Text(t, Modifier.let { if (inline) it else it.padding(top = 14.dp, start = 120.dp, end = 120.dp) }, style = T.small.copy(color = if (inline) C.muted else C.text), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
}
