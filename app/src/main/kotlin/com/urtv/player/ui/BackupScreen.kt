package com.urtv.player.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.data.Backup
import com.urtv.player.phone.PhoneMode
import com.urtv.player.phone.PhoneServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { Passphrase, Working, Serving, Done, Failed }

/**
 * Back up to the phone / restore from the phone. The file on the wire is already encrypted with a passphrase typed on the TV;
 * the passphrase itself never travels over the network and is never stored.
 */
@Composable
fun BackupScreen(app: UrtvApp, restore: Boolean, demo: Triple<String, String, String>? = null, onDone: (restored: Boolean) -> Unit) {
    BackHandler { onDone(false) }
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    var step by remember { mutableStateOf(if (restore) Step.Serving else Step.Passphrase) }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var blob by remember { mutableStateOf<ByteArray?>(null) }       // restore: the uploaded encrypted file
    var info by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var failedLan by remember { mutableStateOf(false) }
    var closedEarly by remember { mutableStateOf(false) }
    var serverBlob by remember { mutableStateOf<ByteArray?>(null) }  // backup: the encrypted file to serve
    val first = remember { FocusRequester() }
    var gotBlob by remember { mutableStateOf(false) }

    // start the LAN page when it is time to serve (backup: after the passphrase; restore: immediately)
    DisposableEffect(step == Step.Serving, serverBlob) {
        if (step != Step.Serving || (!restore && serverBlob == null)) return@DisposableEffect onDispose { }
        if (demo != null) { info = demo; return@DisposableEffect onDispose { } }
        val ip = PhoneServer.lanAddress()
        val srv = ip?.let {
            PhoneServer(it, onInput = {}, onClosed = { main.post { if (restore) { if (!gotBlob) closedEarly = true } else if (step == Step.Serving) step = Step.Done } },
                mode = if (restore) PhoneMode.Restore else PhoneMode.Backup, backupBlob = serverBlob,
                onBlob = { b -> main.post { gotBlob = true; blob = b; step = Step.Passphrase } })
        }
        if (srv == null || !srv.start()) failedLan = true else info = Triple("http://${ip.hostAddress}:${srv.port}/?c=${srv.code}", srv.code, "${ip.hostAddress}:${srv.port}")
        onDispose { srv?.close() }
    }

    fun submitBackup() {
        step = Step.Working
        scope.launch {
            val enc = withContext(Dispatchers.Default) { Backup.encrypt(app.store.exportJson().toByteArray(), pass.toCharArray()) }
            serverBlob = enc; pass = ""; pass2 = ""; step = Step.Serving
        }
    }
    fun submitRestore() {
        val b = blob ?: return
        step = Step.Working
        scope.launch {
            val plain = withContext(Dispatchers.Default) { Backup.decrypt(b, pass.toCharArray()) }
            val ok = plain != null && app.store.importJson(plain.toString(Charsets.UTF_8))
            pass = ""
            if (ok) { app.catalog.open(app.store.activeId.value); step = Step.Done } else { wrong = true; step = Step.Passphrase }
        }
    }

    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 120.dp, vertical = 80.dp)) {
        Text(stringResource(if (restore) R.string.restore_from_phone else R.string.backup_to_phone), style = T.display)
        Spacer(Modifier.height(32.dp))
        when (step) {
            Step.Passphrase -> {
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                val fresh = !restore
                Text(stringResource(if (fresh) R.string.pass_new else R.string.pass_enter), style = T.title)
                Text(stringResource(if (fresh) R.string.pass_new_hint else R.string.pass_enter_hint), style = T.small, modifier = Modifier.width(900.dp))
                Spacer(Modifier.height(20.dp))
                FocusField(pass, { pass = it; wrong = false }, stringResource(R.string.f_pass), Modifier.width(720.dp), password = true, ltr = true, focusRequester = first)
                if (fresh) { Spacer(Modifier.height(14.dp)); FocusField(pass2, { pass2 = it }, stringResource(R.string.pass_confirm), Modifier.width(720.dp), password = true, ltr = true) }
                if (wrong) { Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.restore_wrong), style = T.body.copy(color = C.danger)) }
                if (fresh && pass.isNotEmpty() && pass.length < Backup.MIN_PASS) { Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.pass_short, Backup.MIN_PASS), style = T.small.copy(color = C.danger)) }
                if (fresh && pass2.isNotEmpty() && pass != pass2) { Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.pin_mismatch), style = T.small.copy(color = C.danger)) }
                Spacer(Modifier.height(24.dp))
                val ready = if (fresh) pass.length >= Backup.MIN_PASS && pass == pass2 else pass.isNotEmpty()
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (ready) TvButton(stringResource(R.string.continue_), { if (fresh) submitBackup() else submitRestore() }, primary = true)
                    TvButton(stringResource(R.string.back), { onDone(false) })
                }
            }
            Step.Working -> Row(verticalAlignment = Alignment.CenterVertically) { Spinner(); Spacer(Modifier.width(20.dp)); Text(stringResource(R.string.working), style = T.title) }
            Step.Serving -> {
                val i = info
                Row(horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (i != null && !closedEarly) Qr(i.first, Modifier.size(420.dp)) else Box(Modifier.size(420.dp), contentAlignment = Alignment.Center) { if (!failedLan) Spinner() }
                    Column {
                        when {
                            failedLan -> Text(stringResource(R.string.phone_no_lan), style = T.title)
                            closedEarly -> Text(stringResource(R.string.phone_closed), style = T.title)
                            i != null -> {
                                Text(stringResource(R.string.phone_step1), style = T.title)
                                Text(stringResource(if (restore) R.string.restore_step2 else R.string.backup_step2), style = T.body.copy(color = C.muted), modifier = Modifier.width(760.dp))
                                Spacer(Modifier.height(20.dp))
                                Text(i.third, style = T.title.copy(color = C.accent, textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.phone_code), style = T.body.copy(color = C.muted)); Spacer(Modifier.width(12.dp))
                                    Text(i.second.chunked(3).joinToString(" "), style = T.display.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
                                }
                            }
                        }
                    }
                }
            }
            Step.Done -> {
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                Text(stringResource(if (restore) R.string.restore_done else R.string.backup_done), style = T.title)
                Spacer(Modifier.height(24.dp))
                TvButton(stringResource(R.string.back), { onDone(restore) }, primary = true, focusRequester = first)
            }
            Step.Failed -> TvButton(stringResource(R.string.back), { onDone(false) })
        }
    }
}
