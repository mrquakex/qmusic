package com.qmusic.ui

import androidx.compose.runtime.Composable

/** Starts the system's speech input; [onResult] gets what was said. Null where there is none. */
@Composable
expect fun rememberVoiceSearch(onResult: (String) -> Unit): (() -> Unit)?

/** Hands over to a song-recognition app (Google's "What's this song?", or Shazam). Null when neither is there. */
@Composable
expect fun rememberSongRecognition(): (() -> Unit)?
