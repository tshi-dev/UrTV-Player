package com.urtv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.urtv.player.data.BgStyle

/** Poster of the focused item, shown blurred behind the screen when the background style is "poster". */
object Backdrop {
    var url by mutableStateOf<String?>(null)
}

@Composable
fun PosterBackdrop() {
    val th = LocalTheme.current
    val u = Backdrop.url
    if (th.background != BgStyle.Poster || u.isNullOrBlank()) return
    Box(Modifier.fillMaxSize().clipToBounds()) {
        AsyncImage(u, null, Modifier.fillMaxSize().blur(36.dp), contentScale = ContentScale.Crop, alpha = 0.55f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(th.bg.copy(alpha = 0.6f), th.bg.copy(alpha = 0.92f)))))
    }
}
