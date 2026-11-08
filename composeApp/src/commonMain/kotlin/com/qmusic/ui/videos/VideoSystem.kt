package com.qmusic.ui.videos

import androidx.compose.runtime.Composable

/** Landscape with the system bars hidden while [enabled] (full-screen video); everything is put back after. */
@Composable
expect fun FullscreenEffect(enabled: Boolean)

/** While [armed] (a video is playing on screen), leaving the app shrinks the video into a floating window. */
@Composable
expect fun PictureInPictureEffect(armed: Boolean)

/** Whether the app is in that floating window now. */
@Composable
expect fun rememberInPictureInPicture(): Boolean
