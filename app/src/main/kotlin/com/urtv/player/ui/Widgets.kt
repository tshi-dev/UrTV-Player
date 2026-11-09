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
import androidx.compose.foundation.combinedClickable
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
 * The one focusable building block. No outline at rest; focus = solid light surface with dark text, a 1.03 lift and
 * (for image cards, [ring]) a white ring. A subtle fill marks the selected item (e.g. the current group).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tv(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    restColor: Color = Color.Transparent,
    ring: Boolean = false,
    lift: Float = 1.03f,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) lift else 1f, tween(180), label = "lift")
    val fill by animateColorAsState(if (focused && !ring) C.focusFill else if (selected) C.glass2 else restColor, tween(180), label = "fill")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = if (focused) 24f else 0f; this.shape = shape; clip = false }
            .clip(shape)
            .background(fill)
            .let { if (focused && ring) it.border(3.dp, C.ring, shape) else it }
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick),
    ) { androidx.compose.runtime.CompositionLocalProvider(LocalFocused provides (focused && !ring)) { content(focused) } }
}

/** Action button. Primary = light surface even at rest; secondary = quiet layer that turns light on focus. */
@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, focusRequester: FocusRequester? = null) {
    Tv(onClick, modifier.height(60.dp), RoundedCornerShape(14.dp), focusRequester = focusRequester, restColor = if (primary) C.text else C.surfaceHi) {
        // primary at rest is already light, so its text is dark whether or not it is focused
        val tone = if (primary) T.bodyBold.copy(color = C.onFocus) else T.bodyBold
        Text(text, style = tone, maxLines = 1, modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp))
    }
}

@Composable
fun Spinner(size: Int = 64) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.minDimension * 0.1f
        drawArc(C.ring.copy(alpha = 0.15f), 0f, 360f, false, style = Stroke(w), topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w))
        drawArc(C.accent2, a, 100f, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w))
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val focused = LocalFocused.current
    Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(if (focused) Color(0x26000000) else Color(0x29FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(C.accent))
    }
}

/** Fallback tile for channels without a logo: the first letter on a tint derived from the name (stable per channel). */
@Composable
fun Monogram(name: String, size: Int = 52) {
    val t = name.trim()
    val h = (t.fold(0) { a, c -> (a * 31 + c.code) and 0xFFFF } % 360).toFloat()
    val letter = Regex("""\d{1,2}$""").find(t)?.value ?: (t.firstOrNull() ?: '?').toString()
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4.5f).dp))
        .background(Brush.linearGradient(listOf(Color.hsv(h, 0.38f, 0.40f), Color.hsv((h + 30) % 360, 0.42f, 0.26f)))), contentAlignment = Alignment.Center) {
        androidx.tv.material3.Text(letter.uppercase(), style = T.bodyBold.copy(color = Color.White, fontSize = (size * 0.42f).sp))
    }
}

/** Small label chip (genre, year, rating, S/E badge): separates facts without a bullet character. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(C.glass2).padding(horizontal = 10.dp, vertical = 2.dp)) {
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

/** Text input that is itself the focus target (D-pad OK opens the TV keyboard); ring shows focus. */
@Composable
fun FocusField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    password: Boolean = false, ltr: Boolean = false, leading: @Composable (() -> Unit)? = null,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Next,
    focusRequester: FocusRequester? = null, compact: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
    Row(
        modifier.height(if (compact) 52.dp else 60.dp).clip(shape).background(if (focused) C.focusFill else C.surface)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp)) }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(label, style = T.body.copy(color = if (focused) C.onFocus3 else C.faint), maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true,
                textStyle = T.body.copy(color = if (focused) C.onFocus else C.text, textAlign = if (ltr) androidx.compose.ui.text.style.TextAlign.Left else androidx.compose.ui.text.style.TextAlign.Start,
                    textDirection = if (ltr) androidx.compose.ui.text.style.TextDirection.Ltr else androidx.compose.ui.text.style.TextDirection.Content),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(C.accent2),
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = imeAction, autoCorrectEnabled = false,
                    keyboardType = if (ltr) androidx.compose.ui.text.input.KeyboardType.Uri else androidx.compose.ui.text.input.KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().let { if (focusRequester != null) it.focusRequester(focusRequester) else it }.onFocusChanged { focused = it.isFocused },
            )
        }
    }
}
