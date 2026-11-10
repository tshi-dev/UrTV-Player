package com.urtv.player.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.data.PlaylistDef
import com.urtv.player.phone.PhoneInput
import com.urtv.player.phone.PhoneServer

private enum class AddMode { Phone, M3u, Xtream }

@Composable
fun AddPlaylistScreen(app: UrtvApp, demo: Triple<String, String, String>? = null, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    var mode by remember { mutableStateOf(AddMode.Phone) }
    val first = remember { FocusRequester() }
    val tabRequesters = remember { AddMode.entries.associateWith { if (it == AddMode.Phone) first else FocusRequester() } }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun save(p: PhoneInput) {
        val xt = p.server.isNotEmpty() && p.user.isNotEmpty()
        val host = (if (xt) p.server else p.url).substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { "Playlist" }
        val def = PlaylistDef(app.store.newId(), p.name.ifBlank { host }, if (xt) PlaylistDef.KIND_XTREAM else PlaylistDef.KIND_M3U, p.url, p.server, p.user, p.pass, p.epg)
        app.store.addPlaylist(def); app.catalog.open(def.id); onDone()
    }

    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 128.dp, vertical = 72.dp)) {
        Text(stringResource(R.string.add_playlist), style = T.display)
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            for (m in AddMode.entries) {
                Tv({ mode = m }, Modifier.height(104.dp), Rd(52), selected = m == mode, onFocus = { if (it) mode = m }, focusRequester = tabRequesters[m]) {
                    Text(stringResource(when (m) { AddMode.Phone -> R.string.add_phone; AddMode.M3u -> R.string.kind_m3u; AddMode.Xtream -> R.string.kind_xtream }),
                        style = if (m == mode) T.bodyBold else T.body.copy(color = C.muted), modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp))
                }
            }
        }
        Spacer(Modifier.height(48.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                AddMode.Phone -> if (demo == null && app.integrity == com.urtv.player.data.Integrity.Status.Unofficial) UnofficialNote() else PhonePane(demo, ::save)
                AddMode.M3u -> FormPane(false, ::save) { runCatching { tabRequesters[AddMode.M3u]!!.requestFocus() } }
                AddMode.Xtream -> FormPane(true, ::save) { runCatching { tabRequesters[AddMode.Xtream]!!.requestFocus() } }
            }
        }
    }
}

@Composable
private fun PhonePane(demo: Triple<String, String, String>?, onInput: (PhoneInput) -> Unit) {
    val main = remember { Handler(Looper.getMainLooper()) }
    var info by remember { mutableStateOf<Triple<String, String, String>?>(null) } // url, code, host:port
    var failed by remember { mutableStateOf(false) }
    var closed by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf(0) }
    DisposableEffect(round) {
        if (demo != null) { info = demo; return@DisposableEffect onDispose { } }
        closed = false; failed = false
        val ip = PhoneServer.lanAddress()
        val srv = ip?.let { PhoneServer(it, onInput = { i -> main.post { onInput(i) } }, onClosed = { main.post { closed = true } }) }
        if (srv == null || !srv.start()) { failed = true; info = null } else info = Triple("http://${ip.hostAddress}:${srv.port}/?c=${srv.code}", srv.code, "${ip.hostAddress}:${srv.port}")
        onDispose { srv?.close() }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(96.dp), verticalAlignment = Alignment.CenterVertically) {
        val i = info
        if (i != null && !closed) Qr(i.first, Modifier.size(600.dp))
        else Box(Modifier.size(600.dp), contentAlignment = Alignment.Center) { if (!failed) Spinner() }
        Column(Modifier.weight(1f)) {
            when {
                failed -> { Text(stringResource(R.string.phone_no_lan), style = T.title); Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.phone_no_lan_hint), style = T.body.copy(color = C.muted)) }
                closed -> { Text(stringResource(R.string.phone_closed), style = T.title); Spacer(Modifier.height(40.dp)); TvButton(stringResource(R.string.retry), { round++ }, primary = true) }
                i != null -> {
                    Text(stringResource(R.string.phone_step1), style = T.title)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.phone_step2), style = T.body.copy(color = C.muted))
                    Spacer(Modifier.height(40.dp))
                    Text(i.third, style = T.title.copy(color = C.accent2), modifier = Modifier.padding(bottom = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.phone_code), style = T.body.copy(color = C.muted)); Spacer(Modifier.width(24.dp))
                        Text(i.second.chunked(3).joinToString(" "), style = T.display.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr, letterSpacing = androidx.compose.ui.unit.TextUnit(4f, androidx.compose.ui.unit.TextUnitType.Sp)))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.phone_safe), style = T.small)
                }
            }
        }
    }
}

@Composable
fun Qr(text: String, modifier: Modifier) {
    val m = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Canvas(modifier.background(Color.White, Rd(40))) {
        val quiet = size.minDimension * 0.07f
        val cell = (size.minDimension - 2 * quiet) / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) drawRect(Color.Black, Offset(quiet + x * cell, quiet + y * cell), Size(cell + 0.6f, cell + 0.6f))
    }
}

@Composable
private fun FormPane(xtream: Boolean, onSave: (PhoneInput) -> Unit, toTabs: () -> Unit) {
    var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }; var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        FocusField(name, { name = it }, stringResource(R.string.f_name), Modifier.fillMaxWidth(), onUp = toTabs)
        if (!xtream) FocusField(url, { url = it; err = false }, stringResource(R.string.f_url), Modifier.fillMaxWidth(), ltr = true)
        else {
            FocusField(server, { server = it; err = false }, stringResource(R.string.f_server), Modifier.fillMaxWidth(), ltr = true)
            FocusField(user, { user = it; err = false }, stringResource(R.string.f_user), Modifier.fillMaxWidth(), ltr = true)
            FocusField(pass, { pass = it; err = false }, stringResource(R.string.f_pass), Modifier.fillMaxWidth(), password = true, ltr = true)
        }
        FocusField(epg, { epg = it }, stringResource(R.string.f_epg), Modifier.fillMaxWidth(), ltr = true, imeAction = androidx.compose.ui.text.input.ImeAction.Done)
        if (err) Text(stringResource(R.string.f_invalid), style = T.body.copy(color = C.danger))
        TvButton(stringResource(R.string.save), {
            val ok = if (xtream) server.isNotBlank() && user.isNotBlank() else url.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }
            if (!ok) err = true else onSave(PhoneInput(name.trim(), if (xtream) "" else url.trim(), if (xtream) server.trim() else "", user.trim(), pass, epg.trim()))
        }, primary = true)
    }
}

/** Shown instead of the LAN pairing page in an unofficial (repackaged) build. */
@Composable
fun UnofficialNote() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.unofficial_build), style = T.title.copy(color = C.danger))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.unofficial_blocked), style = T.body.copy(color = C.muted), modifier = Modifier.width(900.dp))
    }
}
