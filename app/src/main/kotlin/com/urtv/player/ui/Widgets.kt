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
    shape: Shape = RoundedCornerShape(16.dp),
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    restColor: Color = C.surface,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(140), label = "lift")
    val fill by animateColorAsState(if (focused) C.focusFill else if (selected) C.surfaceHi else restColor, tween(140), label = "fill")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(fill)
            .border(if (focused) 3.dp else if (selected) 1.5.dp else 0.dp, if (focused) C.ring else C.accent, shape)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick),
    ) { content(focused) }
}

/** Text button used for actions: primary ones get the accent gradient. */
@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, focusRequester: FocusRequester? = null) {
    Tv(onClick, modifier.height(60.dp), RoundedCornerShape(30.dp), focusRequester = focusRequester, restColor = if (primary) C.accent else C.surfaceHi) {
        Text(text, style = T.bodyBold, modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp))
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
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(C.ring.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(C.accentBrush))
    }
}

/** Letter avatar fallback for channels without a logo. */
@Composable
fun Monogram(name: String, size: Int = 52) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(C.surfaceHi), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), style = T.bodyBold.copy(color = C.muted))
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
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.height(60.dp).clip(shape).background(if (focused) C.focusFill else C.surface)
            .border(if (focused) 3.dp else 1.dp, if (focused) C.ring else C.surfaceHi, shape).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp)) }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(label, style = T.body.copy(color = C.muted), maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true,
                textStyle = T.body.copy(textAlign = if (ltr) androidx.compose.ui.text.style.TextAlign.Left else androidx.compose.ui.text.style.TextAlign.Start,
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
