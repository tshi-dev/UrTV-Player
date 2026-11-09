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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.shadow
import com.urtv.player.data.FocusStyle
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
import androidx.tv.material3.Text

/**
 * The one focusable building block. Focus is shown by a white ring, a lift and a lighter fill, so it is
 * visible on any background and never relies on colour alone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tv(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Rd(16),
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    restColor: Color = C.surface,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val th = LocalTheme.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(140), label = "lift")
    val fill by animateColorAsState(if (focused) th.focusFill else if (selected) th.surfaceHi else restColor, tween(140), label = "fill")
    // Solid: the fill itself is the indicator. Ring: white ring. Glow: accent border + accent shadow.
    val border = when {
        focused && th.focus == FocusStyle.Ring -> BorderStroke(3.dp, th.ring)
        focused && th.focus == FocusStyle.Glow -> BorderStroke(2.dp, th.accent)
        focused && th.outlineAtRest -> BorderStroke(3.dp, th.ring)
        selected -> BorderStroke(1.5.dp, th.accent)
        th.outlineAtRest -> BorderStroke(1.dp, th.text.copy(alpha = 0.5f))
        else -> null
    }
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .let { if (focused && th.focus == FocusStyle.Glow) it.shadow(18.dp, shape, ambientColor = th.accent, spotColor = th.accent) else it }
            .clip(shape)
            .background(fill)
            .let { if (border != null) it.border(border, shape) else it }
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick),
    ) { CompositionLocalProvider(LocalFocused provides focused) { content(focused) } }
}

/** Text button used for actions: primary ones get the accent gradient. */
@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, focusRequester: FocusRequester? = null) {
    Tv(onClick, modifier.height(60.dp), Rd(30), focusRequester = focusRequester, restColor = if (primary) C.accent else C.surfaceHi) { f ->
        Text(text, style = T.bodyBold.copy(color = if (primary && !f) C.onAccent else ink()), modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp))
    }
}

@Composable
fun Spinner(size: Int = 64) {
    val accent = C.accent
    val track = C.text.copy(alpha = 0.15f)
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.minDimension * 0.1f
        drawArc(track, 0f, 360f, false, style = Stroke(w), topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w))
        drawArc(accent, a, 100f, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w))
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(C.text.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(C.accentBrush))
    }
}

/** Letter avatar fallback for channels without a logo. */
@Composable
fun Monogram(name: String, size: Int = 52) {
    Box(Modifier.size(size.dp).clip(Rd(14)).background(C.surfaceHi), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), style = T.bodyBold.copy(color = mutedInk()))
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
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = Rd(18)
    Row(
        modifier.height(60.dp).clip(shape).background(if (focused) C.surfaceHi else C.surface)
            .border(if (focused) 3.dp else 1.dp, if (focused) C.ring else C.text.copy(alpha = 0.18f), shape).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp)) }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(label, style = T.body.copy(color = mutedInk()), maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true,
                textStyle = T.body.copy(textAlign = if (ltr) androidx.compose.ui.text.style.TextAlign.Left else androidx.compose.ui.text.style.TextAlign.Start,
                    textDirection = if (ltr) androidx.compose.ui.text.style.TextDirection.Ltr else androidx.compose.ui.text.style.TextDirection.Content),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(C.accent),
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = imeAction, autoCorrectEnabled = false,
                    keyboardType = if (ltr) androidx.compose.ui.text.input.KeyboardType.Uri else androidx.compose.ui.text.input.KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().let { if (focusRequester != null) it.focusRequester(focusRequester) else it }.onFocusChanged { focused = it.isFocused },
            )
        }
    }
}
