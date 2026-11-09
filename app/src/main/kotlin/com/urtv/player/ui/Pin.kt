package com.urtv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester

import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.urtv.player.R
import com.urtv.player.data.Store

sealed class PinRequest {
    /** Ask for the existing PIN, then run [onOk]. */
    class Verify(val onOk: () -> Unit) : PinRequest()
    /** Ask for a new PIN twice, then hand it to [onSet]. */
    class SetNew(val onSet: (String) -> Unit) : PinRequest()
}

/** Run [action] now when there is no PIN, otherwise after the PIN is entered. */
fun requestPin(store: Store, action: () -> Unit): PinRequest? =
    if (store.hasPin.value) PinRequest.Verify(action) else { action(); null }

@Composable
fun PinHost(req: PinRequest?, store: Store, onDismiss: () -> Unit) {
    if (req == null) return
    var step by remember(req) { mutableStateOf(0) }
    var first by remember(req) { mutableStateOf("") }
    var error by remember(req) { mutableStateOf(false) }
    var attempt by remember(req) { mutableStateOf(0) }
    val title = when {
        req is PinRequest.Verify -> stringResource(R.string.pin_enter)
        step == 0 -> stringResource(R.string.pin_new)
        else -> stringResource(R.string.pin_confirm)
    }
    val lockSec = if (req is PinRequest.Verify && error) store.pinLockedSeconds() else 0
    PinPad(title, if (lockSec > 0) stringResource(R.string.pin_locked, lockSec) else if (error) stringResource(if (req is PinRequest.Verify) R.string.pin_wrong else R.string.pin_mismatch) else null, attempt, onCancel = onDismiss) { pin ->
        error = false
        when (req) {
            is PinRequest.Verify -> if (store.checkPin(pin)) { onDismiss(); req.onOk() } else { error = true; attempt++ }
            is PinRequest.SetNew -> if (step == 0) { first = pin; step = 1; attempt++ } else if (pin == first) { onDismiss(); req.onSet(pin) } else { error = true; step = 0; attempt++ }
        }
    }
}

@Composable
fun PinPad(title: String, error: String?, resetKey: Int, onCancel: () -> Unit, onComplete: (String) -> Unit) {
    var pin by remember(resetKey) { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler(onBack = onCancel)
    fun add(d: Int) { if (pin.length < 4) { pin += d; if (pin.length == 4) onComplete(pin) } }
    Box(
        Modifier.fillMaxSize().background(C.scrim).onPreviewKeyEvent { e ->
            val code = e.nativeKeyEvent.keyCode
            if (e.type == KeyEventType.KeyDown && (code in 7..16 || code in 144..153)) { add(if (code in 7..16) code - 7 else code - 144); true } else false
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(840.dp).background(C.surface, Rd(56)).padding(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = T.title)
            Row(Modifier.padding(vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                repeat(4) { Box(Modifier.size(40.dp).background(if (it < pin.length) C.accent2 else C.surfaceHi, CircleShape)) }
            }
            Box(Modifier.height(64.dp)) { if (error != null) Text(error, style = T.small.copy(color = C.danger)) }
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                for (r in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    for (c in 1..3) { val d = r * 3 + c; PadKey(d.toString(), Modifier, if (d == 5) first else null) { add(d) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PadKey("⌫", Modifier) { pin = pin.dropLast(1) }
                    PadKey("0", Modifier) { add(0) }
                    PadKey("✕", Modifier) { onCancel() }
                }
            }
        }
    }
}

@Composable
private fun PadKey(label: String, modifier: Modifier, fr: FocusRequester? = null, onClick: () -> Unit) {
    Tv(onClick, modifier.size(width = 216.dp, height = 144.dp), Rd(40), focusRequester = fr, restColor = C.surfaceHi) {
        Text(label, style = T.title, modifier = Modifier.align(Alignment.Center))
    }
}
