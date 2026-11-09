package com.urtv.player.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.tv.material3.Text
import com.urtv.player.R
import com.urtv.player.data.AccentId
import com.urtv.player.data.Appearance
import com.urtv.player.data.BgStyle
import com.urtv.player.data.CornerStyle
import com.urtv.player.data.FocusStyle
import com.urtv.player.data.Store
import com.urtv.player.data.ThemeId

@StringRes fun themeName(t: ThemeId) = when (t) {
    ThemeId.Default -> R.string.theme_dark; ThemeId.Oled -> R.string.theme_oled; ThemeId.Midnight -> R.string.theme_midnight
    ThemeId.Sand -> R.string.theme_sand; ThemeId.Light -> R.string.theme_light; ThemeId.HighContrast -> R.string.theme_hc
}
@StringRes fun accentName(a: AccentId) = when (a) {
    AccentId.Blue -> R.string.accent_blue; AccentId.Teal -> R.string.accent_teal; AccentId.Green -> R.string.accent_green
    AccentId.Gold -> R.string.accent_gold; AccentId.Amber -> R.string.accent_amber; AccentId.Coral -> R.string.accent_coral
    AccentId.Rose -> R.string.accent_rose; AccentId.Violet -> R.string.accent_violet
}
@StringRes private fun focusName(f: FocusStyle) = when (f) { FocusStyle.Solid -> R.string.focus_solid; FocusStyle.Ring -> R.string.focus_ring; FocusStyle.Glow -> R.string.focus_glow }
@StringRes private fun cornerName(c: CornerStyle) = if (c == CornerStyle.Rounded) R.string.corner_rounded else R.string.corner_square
@StringRes private fun bgName(b: BgStyle) = when (b) { BgStyle.Solid -> R.string.bg_solid; BgStyle.Gradient -> R.string.bg_gradient; BgStyle.Poster -> R.string.bg_poster }

/** Settings → Appearance. Every choice applies instantly (the theme above is recomposed) and is saved by the store. */
@Composable
fun AppearanceScreen(store: Store, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val look by store.appearance.collectAsState()
    val textScale by store.textScale.collectAsState()
    fun set(a: Appearance) = store.setAppearance(a)
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.set_theme), style = T.title, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            TvButton(stringResource(R.string.back), onBack)
        } }
        item { LivePreview(LocalTheme.current) }

        item { SectionLabel(stringResource(R.string.ap_theme)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (t in ThemeId.entries) {
                val spec = remember(t, look) { resolveSpec(look.copy(theme = t)) }
                Option(stringResource(themeName(t)), look.theme == t, { set(look.copy(theme = t)) }, Modifier.weight(1f), if (t == look.theme) first else null) { ThemeMock(spec, Modifier.fillMaxSize()) }
            }
        } }

        item { SectionLabel(stringResource(R.string.ap_accent)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (a in AccentId.entries) {
                val spec = remember(a, look) { resolveSpec(look.copy(accent = a)) }
                Option(stringResource(accentName(a)), look.accent == a, { set(look.copy(accent = a)) }, Modifier.weight(1f)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(spec.accent))
                    }
                }
            }
        } }

        item { SectionLabel(stringResource(R.string.ap_focus)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (f in FocusStyle.entries) {
                val spec = remember(f, look) { resolveSpec(look.copy(focus = f)) }
                Option(stringResource(focusName(f)), look.focus == f, { set(look.copy(focus = f)) }, Modifier.weight(1f)) { FocusMock(spec, Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.weight(3f))
        } }

        item { SectionLabel(stringResource(R.string.ap_corner)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (c in CornerStyle.entries) {
                val spec = remember(c, look) { resolveSpec(look.copy(corner = c)) }
                Option(stringResource(cornerName(c)), look.corner == c, { set(look.copy(corner = c)) }, Modifier.weight(1f)) { CornerMock(spec, Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.weight(4f))
        } }

        item { SectionLabel(stringResource(R.string.ap_background)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (b in BgStyle.entries) {
                val spec = remember(b, look) { resolveSpec(look.copy(background = b)) }
                Option(stringResource(bgName(b)), look.background == b, { set(look.copy(background = b)) }, Modifier.weight(1f)) { BackgroundMock(spec, Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.weight(3f))
        } }
        if (look.background == BgStyle.Poster) item { Text(stringResource(R.string.ap_poster_hint), style = T.tiny, modifier = Modifier.padding(horizontal = 8.dp)) }

        item { SectionLabel(stringResource(R.string.ap_text)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val names = listOf(R.string.size_normal, R.string.size_large, R.string.size_xl)
            for (i in 0..2) Option(stringResource(names[i]), textScale == i, { store.setTextScale(i) }, Modifier.weight(1f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Aa أب", style = androidx.compose.ui.text.TextStyle(fontSize = (18 + i * 5).sp, color = ink()))
                }
            }
            Spacer(Modifier.weight(3f))
        } }
    }
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/** A selectable tile: a preview on top, its name below, a check when chosen. */
@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, focusRequester: androidx.compose.ui.focus.FocusRequester? = null, preview: @Composable () -> Unit) {
    Tv(onClick, modifier.height(132.dp), Rd(16), selected = selected, focusRequester = focusRequester) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(Rd(10))) { preview() }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = T.small.copy(color = ink()), maxLines = 1, modifier = Modifier.weight(1f))
                if (selected) Icon(Glyph.Check, accentInk(), 20)
            }
        }
    }
}

private fun DrawScope.rr(c: Color, x: Float, y: Float, w: Float, h: Float, r: Float) =
    drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(r, r))

/** Miniature of a screen in [s]: background, one focused row, two resting rows, an accent progress line. */
@Composable
fun ThemeMock(s: ThemeSpec, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(s.bgTop, s.bg)))
        val u = size.height / 10f; val r = u * 0.7f * s.cornerScale.coerceAtLeast(0.3f)
        val x = size.width * 0.08f; val w = size.width * 0.84f
        rr(s.focusFill, x, u * 1.2f, w, u * 2.2f, r)
        rr(s.onFocus.copy(alpha = 0.8f), x + u, u * 2f, w * 0.45f, u * 0.6f, u * 0.3f)
        rr(s.surface, x, u * 3.8f, w, u * 2.2f, r); rr(s.text.copy(alpha = 0.8f), x + u, u * 4.6f, w * 0.35f, u * 0.6f, u * 0.3f)
        rr(s.surfaceHi, x, u * 6.4f, w, u * 2.2f, r); rr(s.text.copy(alpha = s.mutedAlpha), x + u, u * 7.2f, w * 0.5f, u * 0.6f, u * 0.3f)
        rr(s.accent, x + w * 0.62f, u * 4.65f, w * 0.3f, u * 0.5f, u * 0.25f)
    }
}

@Composable private fun FocusMock(s: ThemeSpec, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(s.bg)
        val u = size.height / 10f; val r = u * s.cornerScale.coerceAtLeast(0.3f) * 1.2f
        val x = size.width * 0.12f; val w = size.width * 0.76f
        rr(s.surface, x, u * 0.8f, w, u * 2.4f, r)
        rr(s.surfaceHi, x, u * 6.8f, w, u * 2.4f, r)
        val y = u * 3.7f; val h = u * 2.6f
        when (s.focus) {
            FocusStyle.Solid -> rr(s.focusSolid, x - u * 0.3f, y, w + u * 0.6f, h, r)
            FocusStyle.Ring -> { rr(s.ring, x - u * 0.3f, y, w + u * 0.6f, h, r); rr(s.focusSoft, x - u * 0.1f + u * 0.2f, y + u * 0.3f, w + u * 0.2f - u * 0.4f, h - u * 0.6f, r * 0.7f) }
            FocusStyle.Glow -> {
                for (k in 4 downTo 1) rr(s.accent.copy(alpha = 0.10f * (5 - k)), x - u * 0.3f - k * u * 0.25f, y - k * u * 0.25f, w + u * 0.6f + k * u * 0.5f, h + k * u * 0.5f, r + k * u * 0.2f)
                rr(s.accent, x - u * 0.3f, y, w + u * 0.6f, h, r); rr(s.focusSoft, x - u * 0.3f + u * 0.2f, y + u * 0.2f, w + u * 0.2f, h - u * 0.4f, r * 0.8f)
            }
        }
        rr(s.onFocus.copy(alpha = 0.85f), x + u * 0.8f, y + h * 0.4f, w * 0.4f, h * 0.2f, u * 0.2f)
    }
}

@Composable private fun CornerMock(s: ThemeSpec, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(s.bg)
        val u = size.height / 10f
        rr(s.surfaceHi, size.width * 0.12f, u * 1.5f, size.width * 0.76f, u * 7f, u * 2.5f * s.cornerScale)
        rr(s.accent, size.width * 0.2f, u * 5.2f, size.width * 0.3f, u * 2f, u * 1f * s.cornerScale)
    }
}

@Composable private fun BackgroundMock(s: ThemeSpec, modifier: Modifier) {
    Canvas(modifier) {
        when (s.background) {
            BgStyle.Solid -> drawRect(s.bg)
            BgStyle.Gradient -> drawRect(Brush.verticalGradient(listOf(s.bgTop, s.bg)))
            BgStyle.Poster -> {
                drawRect(s.bg)
                drawRect(Brush.linearGradient(listOf(s.accent.copy(alpha = 0.55f), s.surfaceHi, s.bg.copy(alpha = 0.9f))))
            }
        }
        val u = size.height / 10f
        rr(s.surface.copy(alpha = 0.9f), size.width * 0.12f, u * 6f, size.width * 0.3f, u * 3f, u * 0.6f * s.cornerScale)
        rr(s.surface.copy(alpha = 0.9f), size.width * 0.5f, u * 6f, size.width * 0.3f, u * 3f, u * 0.6f * s.cornerScale)
    }
}

/** Larger preview with real text, drawn only from [s] (not from the surrounding theme). */
@Composable
fun LivePreview(s: ThemeSpec) {
    Box(Modifier.fillMaxWidth().height(150.dp).clip(Rd(20)).background(Brush.verticalGradient(listOf(s.bgTop, s.bg))).padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewRow(s, focused = true, title = stringResource(R.string.ap_preview_title) + " 1")
            PreviewRow(s, focused = false, title = stringResource(R.string.ap_preview_title) + " 2")
        }
    }
}

@Composable
private fun PreviewRow(s: ThemeSpec, focused: Boolean, title: String) {
    val fill = if (focused) s.focusFill else s.surface
    val ink = if (focused) s.onFocus else s.text
    val shape = androidx.compose.foundation.shape.RoundedCornerShape((14 * s.cornerScale).dp)
    Row(
        Modifier.fillMaxWidth().height(50.dp).clip(shape).background(fill).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.ui.text.TextStyle(fontSize = 18.sp0, color = ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1)
            Text(stringResource(R.string.ap_preview_sub), style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp0, color = ink.copy(alpha = s.mutedAlpha)), maxLines = 1)
        }
        Box(Modifier.width(80.dp).height(4.dp).clip(CircleShape).background(ink.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.6f).background(if (focused && s.solidFocus) ink else s.accent))
        }
    }
}
private val Int.sp0 get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
