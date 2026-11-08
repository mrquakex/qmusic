package com.qmusic.ui.videos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Watching is on the phone for now: the desktop app lists and searches, and says so where a video would play.
@Composable
actual fun rememberVideoPlayback(): VideoPlayback? = null

@Composable
actual fun VideoPlaybackSurface(playback: VideoPlayback, modifier: Modifier) = Unit
