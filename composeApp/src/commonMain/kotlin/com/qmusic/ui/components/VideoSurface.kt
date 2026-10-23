package com.qmusic.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Draws the picture of the music video that the player is playing. [trackKey] changes with the song so the view
 * re-attaches to whichever player is rendering it.
 */
@Composable
expect fun VideoSurface(trackKey: String, modifier: Modifier = Modifier)
