package com.qmusic.ui.components

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.qmusic.player.PlayerHolder
import kotlinx.coroutines.delay

@Composable
actual fun VideoSurface(trackKey: String, modifier: Modifier) {
    // The active player changes on a crossfade and on a picture <-> audio hand-off: follow it.
    val flow = remember { PlayerHolder.peek()?.activePlayerFlow }
    val player = flow?.collectAsState()?.value

    // The surface is black until the first frame decodes; stay see-through (the cover shows) until then.
    var ready by remember(trackKey, player) { mutableStateOf(false) }
    DisposableEffect(player, trackKey) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                ready = true
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    // Safety net: never leave the picture hidden if the first-frame event was missed.
    LaunchedEffect(trackKey, player) {
        delay(FIRST_FRAME_TIMEOUT_MS)
        ready = true
    }

    AndroidView(
        modifier = modifier.graphicsLayer { alpha = if (ready) 1f else 0f },
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
                this.player = player
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
    )
}

private const val FIRST_FRAME_TIMEOUT_MS = 6_000L
