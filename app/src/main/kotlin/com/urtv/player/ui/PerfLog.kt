package com.urtv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.urtv.player.R

/**
 * The last lines of the performance and error log (the same lines `adb logcat -s UrTVPerf` prints), kept in memory only, so a tester can
 * read them on the TV itself. Numbers, counts, codec names and error class names only: never a name, an address or a login.
 */
object PerfLogBuffer {
    private val ring = ArrayDeque<String>()
    fun add(line: String) = synchronized(ring) { ring.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + line.take(300)); while (ring.size > 300) ring.removeFirst() }
    fun lines(): List<String> = synchronized(ring) { ring.toList().asReversed() }
}

@Composable
fun PerfLogScreen(onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    var lines by remember { mutableStateOf(PerfLogBuffer.lines()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); lines = PerfLogBuffer.lines() } }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 72.dp, vertical = 48.dp)) {
        Text(stringResource(R.string.perf_log_title), style = T.display.copy(fontSize = 40.sp))
        Text(stringResource(R.string.perf_log_hint), style = T.small, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))
        TvButton(stringResource(R.string.back), onDone, focusRequester = fr)
        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.fillMaxSize()) { itemsIndexed(lines) { _, l -> Text(l, style = T.small.copy(color = C.text), maxLines = 2, modifier = Modifier.padding(vertical = 3.dp)) } }
    }
}
