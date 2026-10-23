package com.qmusic.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberVoiceSearch(onResult: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun rememberSongRecognition(): (() -> Unit)? = null
