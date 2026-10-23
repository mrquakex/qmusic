package com.qmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.qmusic.engine.models.SongItem
import com.qmusic.ui.theme.BgSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.QMusicLogo

/**
 * The mini player, laid out like YouTube Music's: a flat bar flush with the navigation bar under it, square cover on
 * the left, title and artist stacked, plain play/next icons on the right and a hairline of progress along the top.
 *
 * Drag it up to open the full player (the caller turns the drag into the sheet position); swipe it sideways to
 * skip, with a tick of haptic feedback.
 */
@Composable
fun MiniPlayerBar(
    currentTrack: SongItem?,
    isPlaying: Boolean,
    positionMs: () -> Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClick: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** The cover is drawn by the sheet's morphing cover while the player is being dragged open. */
    coverAlpha: () -> Float = { 1f },
    /** Text and buttons fade out as the full player rises over the bar. */
    contentAlpha: () -> Float = { 1f },
    onCoverPositioned: (Rect) -> Unit = {},
) {
    if (currentTrack == null) return

    // Read here, not in the caller: the position ticks every 250 ms and must only redraw this bar.
    val position = positionMs()
    val progress = if (durationMs > 0) (position.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val haptics = LocalHapticFeedback.current
    var sideways by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(BgSurface)
            .draggable(
                state = rememberDraggableState(onDragDelta),
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> onDragStopped(velocity) },
            )
            .pointerInput(currentTrack.id) {
                detectHorizontalDragGestures(
                    onDragStart = { sideways = 0f },
                    onDragEnd = {
                        when {
                            sideways < -SWIPE_SKIP_PX -> {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNext()
                            }

                            sideways > SWIPE_SKIP_PX -> {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPrevious()
                            }
                        }
                        sideways = 0f
                    },
                    onDragCancel = { sideways = 0f },
                ) { _, delta -> sideways += delta }
            }
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .onGloballyPositioned { onCoverPositioned(it.boundsInRoot()) }
                    .graphicsLayer { alpha = coverAlpha() }
                    .clip(RoundedCornerShape(4.dp))
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = currentTrack.thumbnail ?: currentTrack.thumbnails.firstOrNull()?.url
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = currentTrack.title,
                        modifier = Modifier.size(48.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    QMusicLogo(size = 26.dp, tint = Color.White.copy(alpha = 0.85f))
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = contentAlpha() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentTrack.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                )
                Text(
                    text = currentTrack.artists.joinToString { it.name }.ifEmpty { "Unknown artist" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = if (isPlaying) QMusicIcons.Pause else QMusicIcons.Play,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = QMusicIcons.Next,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            }
        }

        // Progress: a hairline along the top edge
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(progress)
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.9f)),
        )
    }
}

private const val SWIPE_SKIP_PX = 140f
