package com.urtv.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text as TvText

/**
 * App-wide Text. Inside a focused [Tv] the light tones of the style are swapped for dark ones, so every label stays
 * readable on the light focus surface without each call site knowing about focus.
 */
@Composable
fun Text(
    text: String, modifier: Modifier = Modifier, style: TextStyle = T.body, maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, textAlign: TextAlign? = null,
) {
    val focused = LocalFocused.current
    val c = style.color
    val color: Color = if (!focused) c else when (c) {
        C.text -> C.onFocus
        C.muted -> C.onFocus2
        C.faint -> C.onFocus3
        C.accent -> LocalTheme.current.onFocusSolid
        else -> c
    }
    TvText(text, modifier, style = style.copy(color = color), maxLines = maxLines, overflow = overflow, softWrap = softWrap, textAlign = textAlign ?: TextAlign.Unspecified)
}
