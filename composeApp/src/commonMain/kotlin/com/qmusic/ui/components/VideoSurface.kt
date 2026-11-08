package com.qmusic.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Draws the picture of the music video that the player is playing. [trackKey] changes with the song so the view
 * re-attaches to whichever player is rendering it. With [fill] the picture fills the whole area, cropped at the
 * edges (the full-screen clips of "For you"); otherwise all of it shows, with bars if the shapes differ.
 */
@Composable
expect fun VideoSurface(trackKey: String, modifier: Modifier = Modifier, fill: Boolean = false)
