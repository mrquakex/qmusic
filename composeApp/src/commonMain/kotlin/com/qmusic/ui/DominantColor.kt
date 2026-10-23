package com.qmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The colour of the picture at [url], deepened so white text reads on it (the tint YouTube Music puts behind the
 * top of its home page). Null while it loads, or where pictures cannot be read.
 */
@Composable
expect fun rememberDominantColor(url: String?): Color?
