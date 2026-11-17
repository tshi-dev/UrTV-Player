package com.urtv.player.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.urtv.player.R
import com.urtv.player.UrtvApp
import com.urtv.player.core.Assessment
import com.urtv.player.data.Metrics

/** «تقييم الأداء» (beta builds only): what the build measured on this TV, each area good / fair / poor against fixed thresholds. Nothing is sent anywhere from here. */
@Composable
fun AssessmentScreen(app: UrtvApp, onReport: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var rev by remember { mutableStateOf(0) }
    val snap = remember(rev) { Metrics.snapshot() }
    val findings = remember(snap) { Assessment.rate(snap) }
    val overall = remember(findings) { Assessment.overall(findings) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(C.bgBrush).padding(horizontal = 96.dp, vertical = 56.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.assess_title), style = T.display, modifier = Modifier.weight(1f))
            RatingPill(overall)
        }
        Text(stringResource(R.string.assess_sub, com.urtv.player.BuildConfig.VERSION_NAME.removeSuffix("-debug"), android.os.Build.MODEL.orEmpty(), android.os.Build.VERSION.RELEASE.orEmpty()), style = T.small, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(20.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(findings, key = { it.area.name }) { f ->
                Row(Modifier.fillMaxWidth().height(72.dp).background(C.surface, Rd(14)).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(areaName(f.area)), style = T.body)
                        Text(valueText(f), style = T.tiny)
                    }
                    RatingPill(f.rating)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton(stringResource(R.string.assess_send), onReport, primary = true, focusRequester = first)
            TvButton(stringResource(R.string.assess_reset), { Metrics.reset(app.applicationContext); rev++ })
            TvButton(stringResource(R.string.fm_back), onBack)
        }
        Text(stringResource(R.string.assess_privacy), style = T.tiny, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun RatingPill(r: Assessment.Rating) {
    val (label, color) = when (r) {
        Assessment.Rating.Good -> R.string.rate_good to Color(0xFF3FB27F); Assessment.Rating.Fair -> R.string.rate_fair to Color(0xFFE0A93B)
        Assessment.Rating.Poor -> R.string.rate_poor to Color(0xFFE5645B); Assessment.Rating.NoData -> R.string.rate_nodata to Color(0xFF6B7178)
    }
    Box(Modifier.background(color.copy(alpha = 0.22f), Rd(20)).padding(horizontal = 22.dp, vertical = 8.dp)) { Text(stringResource(label), style = T.bodyBold.copy(color = color)) }
}

private fun areaName(a: Assessment.Area) = when (a) {
    Assessment.Area.Startup -> R.string.as_startup; Assessment.Area.TabOpen -> R.string.as_tab; Assessment.Area.PlaylistLoad -> R.string.as_playlist; Assessment.Area.EpgLoad -> R.string.as_epg
    Assessment.Area.Zap -> R.string.as_zap; Assessment.Area.Buffering -> R.string.as_buffering; Assessment.Area.Dropped -> R.string.as_dropped; Assessment.Area.Decoder -> R.string.as_decoder
    Assessment.Area.Failures -> R.string.as_failures; Assessment.Area.Memory -> R.string.as_memory; Assessment.Area.Stability -> R.string.as_stability
}

/** The numbers behind a rating, in the person's units. */
@Composable
private fun valueText(f: Assessment.Finding): String {
    if (f.rating == Assessment.Rating.NoData) return stringResource(R.string.as_nodata)
    return when (f.area) {
        Assessment.Area.Startup, Assessment.Area.TabOpen, Assessment.Area.EpgLoad -> stringResource(R.string.as_ms, f.value)
        Assessment.Area.PlaylistLoad -> stringResource(R.string.as_ms_n, f.value, f.detail)
        Assessment.Area.Zap -> stringResource(R.string.as_zap_v, f.value, f.detail)
        Assessment.Area.Buffering -> stringResource(R.string.as_buf_v, f.value, f.detail / 10)
        Assessment.Area.Dropped -> stringResource(R.string.as_dropped_v, f.value)
        Assessment.Area.Decoder -> stringResource(R.string.as_count, f.value)
        Assessment.Area.Failures -> stringResource(R.string.as_fail_v, f.value, f.detail)
        Assessment.Area.Memory -> stringResource(R.string.as_mem_v, f.value, f.detail)
        Assessment.Area.Stability -> stringResource(R.string.as_stab_v, f.value, f.detail)
    }
}
