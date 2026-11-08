package com.qmusic.ui.videos

import androidx.compose.runtime.Composable

/** Landscape with the system bars hidden while [enabled] (full-screen video); everything is put back after. */
@Composable
expect fun FullscreenEffect(enabled: Boolean)

/** While [armed] (a video is playing on screen), leaving the app shrinks the video into a floating window. */
@Composable
expect fun PictureInPictureEffect(armed: Boolean)

/**
 * Keeps the sound of the video going when the screen is turned off or the app is left, while [active]: a notification
 * (with a play / pause button) and a service hold the app alive. [onToggle] is that button.
 */
@Composable
expect fun VideoBackgroundEffect(active: Boolean, title: String?, onToggle: () -> Unit)

/** The device's own brightness and volume, each 0..1. */
interface DeviceControls {
    var brightness: Float
    var volume: Float
}

/** Null where the device's brightness and volume cannot be set from here. */
@Composable
expect fun rememberDeviceControls(): DeviceControls?

/** Whether the app is in that floating window now. */
@Composable
expect fun rememberInPictureInPicture(): Boolean
