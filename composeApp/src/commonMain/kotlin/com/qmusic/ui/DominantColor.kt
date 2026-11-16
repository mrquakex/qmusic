package com.qmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The colour of the picture at [url], deepened so white text reads on it (the tint YouTube Music puts behind the
 * top of its home page). Null while it loads, or where pictures cannot be read.
 */
@Composable
expect fun rememberDominantColor(url: String?): Color?

/**
 * A cover's colour as the backdrop of a page: in the light theme a soft wash of it (the deep colour made for white
 * text looked muddy behind dark text on a white page); in the dark theme the colour itself.
 */
fun Color.asBackdrop(): Color =
    if (com.qmusic.ui.theme.lightTheme) androidx.compose.ui.graphics.lerp(this.copy(alpha = 1f), Color.White, 0.78f).copy(alpha = alpha) else this
