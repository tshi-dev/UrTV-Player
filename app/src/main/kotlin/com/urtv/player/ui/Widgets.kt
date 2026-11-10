package com.urtv.player.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.shadow
import com.urtv.player.data.FocusStyle
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush

/**
 * The one focusable building block. No outline at rest (except the high-contrast theme). Focus follows the chosen style:
 * Solid = light surface with dark text, Ring = white ring, Glow = accent border and glow; all lift slightly. Image cards
 * ([ring]) always use a ring so the artwork stays visible. A subtle fill marks the selected item.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tv(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Rd(12),
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    restColor: Color = Color.Transparent,
    ring: Boolean = false,
    lift: Float = 1.03f,
    /** No shadow: for long, fast-scrolling lists. */
    flat: Boolean = false,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val th = LocalTheme.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) lift else 1f, tween(170, easing = androidx.compose.animation.core.LinearOutSlowInEasing), label = "lift")
    val solid = th.solidFocus && !ring
    val fill by animateColorAsState(if (focused && solid) th.focusFill else if (focused && !ring) th.focusSoft else if (selected) C.glass2 else restColor, tween(170, easing = androidx.compose.animation.core.LinearOutSlowInEasing), label = "fill")
    val border = when {
        focused && (ring || th.focus == FocusStyle.Ring) -> BorderStroke(3.dp, th.ring)
        focused && th.focus == FocusStyle.Glow -> BorderStroke(2.dp, th.accent)
        focused && th.outlineAtRest -> BorderStroke(3.dp, th.ring)
        th.outlineAtRest -> BorderStroke(1.dp, th.text.copy(alpha = 0.5f))
        else -> null
    }
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .let { if (flat) it else if (focused && th.focus == FocusStyle.Glow && !ring) it.shadow(18.dp, shape, ambientColor = th.accent, spotColor = th.accent) else if (focused) it.shadow(14.dp, shape) else it }
            .clip(shape)
            .background(fill)
            .let { if (border != null) it.border(border, shape) else it }
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick),
    ) { CompositionLocalProvider(LocalFocused provides (focused && solid)) { content(focused) } }
}

/** Action button. Primary = solid light surface (theme text colour); secondary = quiet layer that lights up on focus. */
@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, focusRequester: FocusRequester? = null) {
    val th = LocalTheme.current
    Tv(onClick, modifier.height(60.dp), Rd(14), focusRequester = focusRequester, restColor = if (primary) th.text else C.surfaceHi) {
        // primary at rest is already the light surface, so its label is the dark colour whether or not it is focused
        val tone = if (primary) T.bodyBold.copy(color = th.bg) else T.bodyBold
        Text(text, style = tone, maxLines = 1, modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp))
    }
}

@Composable
fun Spinner(size: Int = 64) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.minDimension * 0.1f
        drawArc(C.text.copy(alpha = 0.15f), 0f, 360f, false, style = Stroke(w), topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w))
        drawArc(C.accent, a, 100f, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w))
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val focused = LocalFocused.current
    Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(if (focused) C.onFocus.copy(alpha = 0.15f) else C.text.copy(alpha = 0.16f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(C.accent))
    }
}

/** Fallback tile for channels without a logo: the first letter on a tint derived from the name (stable per channel). */
@Composable
fun Monogram(name: String, size: Int = 52) {
    val t = name.trim()
    val h = (t.fold(0) { a, c -> (a * 31 + c.code) and 0xFFFF } % 360).toFloat()
    val letter = Regex("""\d{1,2}$""").find(t)?.value ?: (t.firstOrNull() ?: '?').toString()
    Box(Modifier.size(size.dp).clip(Rd((size / 4.5f).toInt()))
        .background(Brush.linearGradient(listOf(Color.hsv(h, 0.38f, 0.40f), Color.hsv((h + 30) % 360, 0.42f, 0.26f)))), contentAlignment = Alignment.Center) {
        androidx.tv.material3.Text(letter.uppercase(), style = T.bodyBold.copy(color = Color.White, fontSize = (size * 0.42f).sp))
    }
}

/** Small label chip (genre, year, rating, S/E badge): separates facts without a bullet character. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(Rd(8)).background(C.glass2).padding(horizontal = 10.dp, vertical = 2.dp)) {
        Text(text, style = T.small.copy(color = C.text, fontSize = 17.sp), maxLines = 1)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = T.small, modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp))
}

@Composable
fun Row2(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    Row(modifier, verticalAlignment = Alignment.CenterVertically, content = content)

/**
 * Text input for a TV remote. Focus alone never opens the keyboard (D-pad users would type stray letters): the field is a plain focus
 * target until OK is pressed, then the real editor and the keyboard open; Back, or the keyboard's own action key, closes them again.
 * Up/Down never trap: they move to the neighbour, or Up goes to [onUp] when given. Link and login fields ask for a Latin keyboard.
 */
@Composable
fun FocusField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    password: Boolean = false, ltr: Boolean = false, leading: @Composable (() -> Unit)? = null,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Next,
    focusRequester: FocusRequester? = null, compact: Boolean = false, onUp: (() -> Unit)? = null, hintLocale: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val outer = focusRequester ?: remember { FocusRequester() }
    val inner = remember { FocusRequester() }
    val latin = ltr || hintLocale == "en"
    fun finish(moveDown: Boolean = false) {
        editing = false; kb?.hide()
        runCatching { outer.requestFocus() }
        if (moveDown) fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
    }
    androidx.activity.compose.BackHandler(enabled = editing) { finish() }
    androidx.compose.runtime.LaunchedEffect(editing) { if (editing) { kotlinx.coroutines.delay(30); runCatching { inner.requestFocus() }; kb?.show() } }
    val shape = Rd(if (compact) 12 else 16)
    val solid = LocalTheme.current.solidFocus
    val ink = if (focused && solid) C.onFocus else C.text
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier.height(if (compact) 52.dp else 60.dp).clip(shape).background(if (focused) LocalTheme.current.focusFill else C.surface)
            .let { if (focused && LocalTheme.current.focus != FocusStyle.Solid) it.border(3.dp, C.ring, shape) else it }
            .focusRequester(outer).onFocusChanged { focused = it.hasFocus }
            .onPreviewKeyEvent { e: androidx.compose.ui.input.key.KeyEvent ->
                if (editing || e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
                else if (e.key == androidx.compose.ui.input.key.Key.DirectionUp && onUp != null) { onUp(); true } else false
            }
            .androidx_clickable(interaction) { editing = true }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp)) }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && !editing) Text(label, style = T.body.copy(color = if (focused && solid) C.onFocus.copy(alpha = 0.55f) else C.faint), maxLines = 1)
            if (!editing) {
                if (value.isNotEmpty()) Text(if (password) "•".repeat(value.length.coerceAtMost(40)) else value, style = T.body.copy(color = ink, textAlign = if (latin) androidx.compose.ui.text.style.TextAlign.Left else androidx.compose.ui.text.style.TextAlign.Start,
                    textDirection = if (latin) androidx.compose.ui.text.style.TextDirection.Ltr else androidx.compose.ui.text.style.TextDirection.Content), maxLines = 1, softWrap = false, modifier = Modifier.fillMaxWidth())
            } else androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true,
                textStyle = T.body.copy(color = ink, textAlign = if (latin) androidx.compose.ui.text.style.TextAlign.Left else androidx.compose.ui.text.style.TextAlign.Start,
                    textDirection = if (latin) androidx.compose.ui.text.style.TextDirection.Ltr else androidx.compose.ui.text.style.TextDirection.Content),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(C.accent2),
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = imeAction, autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                    keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password else if (ltr) androidx.compose.ui.text.input.KeyboardType.Uri else androidx.compose.ui.text.input.KeyboardType.Text,
                    // links and logins are Latin: ask for an English keyboard (an Arabic one turns Latin keys into Arabic); no suggestions, no auto-correct
                    hintLocales = if (latin) androidx.compose.ui.text.intl.LocaleList("en") else if (hintLocale != null) androidx.compose.ui.text.intl.LocaleList(hintLocale) else null),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onNext = { finish(moveDown = true) }, onDone = { finish() }, onSearch = { finish() }, onGo = { finish() }),
                modifier = Modifier.fillMaxWidth().focusRequester(inner),
            )
        }
    }
}

/** clickable without ripple; D-pad centre / Enter also click it. */
private fun Modifier.androidx_clickable(source: androidx.compose.foundation.interaction.MutableInteractionSource, onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = source, indication = null, onClick = onClick)


/** Quiet placeholder block with a slow pulse; uses theme tokens so it fits every theme. */
@Composable
fun SkeletonBlock(modifier: Modifier, radius: Int = 10) {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Box(modifier.graphicsLayer { alpha = a }.clip(Rd(radius)).background(C.glass2))
}

/** Rows shaped like the real channel rows (number, logo, two text lines): shown instead of a spinner or an empty state while loading. */
@Composable
fun SkeletonRows(count: Int = 10, rowHeight: Int = 76) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        repeat(count) {
            Row(Modifier.fillMaxWidth().height(rowHeight.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(Modifier.size(width = 30.dp, height = 18.dp), 6); androidx.compose.foundation.layout.Spacer(Modifier.size(22.dp))
                SkeletonBlock(Modifier.size(44.dp), 10); androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    SkeletonBlock(Modifier.size(width = (240 + (it * 37) % 160).dp, height = 18.dp), 6)
                    SkeletonBlock(Modifier.size(width = (160 + (it * 53) % 140).dp, height = 14.dp), 6)
                }
            }
        }
    }
}

/** Card-shaped placeholders for Home rows and poster grids. */
@Composable
fun SkeletonCards(count: Int = 5, width: Int = 300, height: Int = 88) {
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) { repeat(count) { SkeletonBlock(Modifier.size(width.dp, height.dp), 14) } }
}
