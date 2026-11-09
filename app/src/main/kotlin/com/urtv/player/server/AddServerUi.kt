package com.urtv.player.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.ui.C
import com.urtv.player.ui.FocusField
import com.urtv.player.ui.Rd
import com.urtv.player.ui.Spinner
import com.urtv.player.ui.T
import com.urtv.player.ui.Tv
import com.urtv.player.ui.TvButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Step {
    data object Choose : Step
    data object Address : Step
    data class Login(val base: String, val kind: ServerKind, val name: String, val pinned: String?, val qc: Boolean) : Step
    data class Quick(val base: String, val kind: ServerKind, val name: String, val pinned: String?) : Step
    data class Cert(val base: String, val fingerprint: String) : Step
    data object PlexPin : Step
    data class PlexPick(val token: String, val servers: List<PlexTv.PlexServer>) : Step
}

/** Add a Jellyfin, Emby or Plex server: find it on the LAN or type its address, then sign in. */
@Composable
fun AddServerScreen(app: UrtvApp, onDone: () -> Unit) {
    val mgr = app.servers
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Choose) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler { if (step is Step.Choose) onDone() else { step = Step.Choose; error = null; busy = false } }

    fun fail(e: Throwable) { error = (e as? ServerException)?.let { if (it.reason == ServerException.Reason.Auth) "auth" else if (it.reason == ServerException.Reason.Parse) "notserver" else "net" } ?: "net"; busy = false }

    /** Identify the server at [base]; on a certificate error ask the user instead of trusting it. */
    fun probe(bases: List<String>, pinned: String?) {
        busy = true; error = null
        scope.launch {
            var lastCert: ServerException? = null; var certBase = ""; var sawNet = false
            for (b in bases) {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val a = JellyfinAuth(ServerKind.Jellyfin, b, mgr.deviceId, mgr.transport(pinned))
                        val info = a.info()
                        info to (info != null && a.quickConnectEnabled())
                    }
                }
                val ok = r.getOrNull()
                val info = ok?.first
                if (info != null) { busy = false; step = Step.Login(b, info.kind, info.name, pinned, ok.second); return@launch }
                (r.exceptionOrNull() as? ServerException)?.let {
                    if (it.reason == ServerException.Reason.Certificate) { lastCert = it; certBase = b }
                    if (it.reason == ServerException.Reason.Network) sawNet = true
                }
            }
            val fp = lastCert?.fingerprint
            busy = false
            if (fp != null && pinned == null) step = Step.Cert(certBase, fp)
            else error = if (sawNet) "net" else "notserver"
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.srv_add_server), style = T.display)
        error?.let { Text(stringResource(when (it) { "auth" -> R.string.srv_auth_failed; "notserver" -> R.string.srv_not_server; else -> R.string.srv_unreachable }), style = T.body.copy(color = C.danger)) }
        if (busy) Spinner()
        when (val s = step) {
            Step.Choose -> ChooseStep(
                onPick = { probe(listOf(it.baseUrl), null) },
                onAddress = { step = Step.Address },
                onPlex = { step = Step.PlexPin },
            )
            Step.Address -> {
                var addr by remember { mutableStateOf("") }
                val fr = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                FocusField(addr, { addr = it; error = null }, stringResource(R.string.srv_address), Modifier.fillMaxWidth().widthIn(max = 760.dp), ltr = true, focusRequester = fr, imeAction = ImeAction.Go)
                TvButton(stringResource(R.string.srv_continue_btn), { val c = NetSafety.candidates(addr); if (c.isEmpty()) error = "notserver" else probe(c, null) }, primary = true)
            }
            is Step.Cert -> {
                Text(stringResource(R.string.srv_cert_title), style = T.title.copy(color = C.danger))
                Text(stringResource(R.string.srv_cert_body), style = T.body)
                Text(s.fingerprint.chunked(48).joinToString("\n"), style = T.small.copy(fontSize = 18.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(stringResource(R.string.srv_cert_trust), { probe(listOf(s.base), s.fingerprint) })
                    TvButton(stringResource(R.string.srv_cancel), { step = Step.Choose })
                }
            }
            is Step.Login -> {
                var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
                val fr = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                Text(s.name, style = T.title)
                FocusField(user, { user = it; error = null }, stringResource(R.string.srv_user), Modifier.fillMaxWidth().widthIn(max = 760.dp), ltr = true, focusRequester = fr)
                FocusField(pass, { pass = it; error = null }, stringResource(R.string.srv_pass), Modifier.fillMaxWidth().widthIn(max = 760.dp), password = true, ltr = true, imeAction = ImeAction.Done)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(stringResource(R.string.srv_sign_in), {
                        busy = true; error = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { JellyfinAuth(s.kind, s.base, mgr.deviceId, mgr.transport(s.pinned)).login(user.trim(), pass) } }
                            r.onSuccess { mgr.add(ServerConfig(mgr.newId(), s.kind, s.name, s.base, it.userId, it.userName, it.token, s.pinned)); onDone() }.onFailure(::fail)
                        }
                    }, primary = true)
                    if (s.qc) TvButton(stringResource(R.string.srv_quick_connect), { step = Step.Quick(s.base, s.kind, s.name, s.pinned) })
                }
            }
            is Step.Quick -> QuickStep(app, s, onDone, onFail = { fail(it); step = Step.Choose })
            Step.PlexPin -> PlexPinStep(app, onToken = { token, servers -> step = Step.PlexPick(token, servers) }, onFail = { fail(it); step = Step.Choose })
            is Step.PlexPick -> {
                if (s.servers.isEmpty()) Text(stringResource(R.string.srv_plex_none), style = T.body)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(s.servers, key = { it.machineId }) { ps ->
                        Tv({
                            busy = true; error = null
                            scope.launch {
                                val working = withContext(Dispatchers.IO) {
                                    ps.candidates.firstOrNull { base ->
                                        val cfg = ServerConfig("t", ServerKind.Plex, ps.name, base, "", "", ps.token)
                                        runCatching { PlexBackend(cfg, mgr.transport(null), mgr.deviceId).libraries() }.isSuccess
                                    }
                                }
                                if (working == null) { fail(ServerException(ServerException.Reason.Network, "")) }
                                else { mgr.add(ServerConfig(mgr.newId(), ServerKind.Plex, ps.name, working, "", "plex", ps.token)); onDone() }
                            }
                        }, Modifier.fillMaxWidth().widthIn(max = 760.dp).height(72.dp)) {
                            Text(ps.name, style = T.bodyBold, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChooseStep(onPick: (DiscoveredServer) -> Unit, onAddress: () -> Unit, onPlex: () -> Unit) {
    val found by load("scan") { Discover.scan() }
    val fr = remember { FocusRequester() }
    Text(stringResource(R.string.srv_found_on_lan), style = T.small)
    when (val r = found) {
        is Res.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { Spinner(36); Text(stringResource(R.string.srv_scanning), style = T.body) }
        is Res.Fail -> {}
        is Res.Ok -> LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(r.v, key = { it.id }) { d ->
                Tv({ onPick(d) }, Modifier.fillMaxWidth().widthIn(max = 760.dp).height(72.dp)) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(d.name, style = T.bodyBold, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(d.baseUrl.substringAfter("://"), style = T.tiny)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton(stringResource(R.string.srv_enter_address), onAddress, primary = true, focusRequester = fr)
        TvButton(stringResource(R.string.srv_plex_link), onPlex)
    }
}

@Composable
private fun QuickStep(app: UrtvApp, s: Step.Quick, onDone: () -> Unit, onFail: (Throwable) -> Unit) {
    val mgr = app.servers
    var code by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val auth = JellyfinAuth(s.kind, s.base, mgr.deviceId, mgr.transport(s.pinned))
        try {
            val qc = withContext(Dispatchers.IO) { auth.quickConnectStart() }
            code = qc.code
            val end = System.currentTimeMillis() + 5 * 60_000
            while (isActive && System.currentTimeMillis() < end) {
                delay(2000)
                if (withContext(Dispatchers.IO) { auth.quickConnectApproved(qc.secret) }) {
                    val ses = withContext(Dispatchers.IO) { auth.quickConnectFinish(qc.secret) }
                    mgr.add(ServerConfig(mgr.newId(), s.kind, s.name, s.base, ses.userId, ses.userName, ses.token, s.pinned)); onDone(); return@LaunchedEffect
                }
            }
            onFail(ServerException(ServerException.Reason.Auth, "expired"))
        } catch (e: Exception) { onFail(e) }
    }
    Text(stringResource(R.string.srv_qc_hint), style = T.body)
    if (code == null) Spinner() else Text(code!!.chunked(3).joinToString(" "), style = T.display.copy(fontSize = 84.sp, letterSpacing = 8.sp))
}

@Composable
private fun PlexPinStep(app: UrtvApp, onToken: (String, List<PlexTv.PlexServer>) -> Unit, onFail: (Throwable) -> Unit) {
    val mgr = app.servers
    var code by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val tv = PlexTv(mgr.deviceId, mgr.transport(null))
        try {
            val pin = withContext(Dispatchers.IO) { tv.startPin() }
            code = pin.code
            val end = System.currentTimeMillis() + 10 * 60_000
            while (isActive && System.currentTimeMillis() < end) {
                delay(2000)
                val token = withContext(Dispatchers.IO) { tv.pollPin(pin.id) }
                if (token != null) { val servers = withContext(Dispatchers.IO) { tv.servers(token) }; onToken(token, servers); return@LaunchedEffect }
            }
            onFail(ServerException(ServerException.Reason.Auth, "expired"))
        } catch (e: Exception) { onFail(e) }
    }
    Text(stringResource(R.string.srv_plex_hint), style = T.body)
    if (code == null) Spinner() else Text(code!!, style = T.display.copy(fontSize = 84.sp, letterSpacing = 10.sp))
}
