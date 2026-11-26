package com.qmusic.ui.videos

import androidx.compose.runtime.Composable

@Composable
actual fun FullscreenEffect(enabled: Boolean, upright: Boolean) = Unit

@Composable
actual fun PictureInPictureEffect(armed: Boolean) = Unit

@Composable
actual fun rememberInPictureInPicture(): Boolean = false

@Composable
actual fun VideoBackgroundEffect(
    active: Boolean,
    title: String?,
    onToggle: () -> Unit,
    artist: String?,
    artworkUrl: String?,
    playing: Boolean,
    onSeekBy: ((Long) -> Unit)?,
    onNext: (() -> Unit)?,
) = Unit

@Composable
actual fun rememberDeviceControls(): DeviceControls? = null
