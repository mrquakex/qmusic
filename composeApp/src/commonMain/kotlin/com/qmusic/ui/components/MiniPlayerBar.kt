package com.qmusic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import kotlinx.coroutines.launch

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
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BgSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.QMusicLogo

/**
 * The mini player, laid out like YouTube Music's: a flat bar flush with the navigation bar under it, square cover on
 * the left, title and artist stacked, plain play/next icons on the right and a hairline of progress along the bottom edge.
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
    // Cover and title follow the finger sideways; past the threshold they slide out, the song changes and the new
    // one slides in from the other side, as on YouTube Music.
    val scope = rememberCoroutineScope()
    val slide = remember { Animatable(0f) }
    var barWidth by remember { mutableFloatStateOf(1f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(BgSurface)
            .draggable(
                state = rememberDraggableState(onDragDelta),
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> onDragStopped(velocity) },
            )
            .onSizeChanged { barWidth = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val travelled = slide.value
                        scope.launch {
                            when {
                                travelled < -SWIPE_SKIP_PX -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    slide.animateTo(-barWidth, tween(SLIDE_OUT_MS))
                                    onNext()
                                    slide.snapTo(barWidth * 0.6f)
                                    slide.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 420f))
                                }

                                travelled > SWIPE_SKIP_PX -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    slide.animateTo(barWidth, tween(SLIDE_OUT_MS))
                                    onPrevious()
                                    slide.snapTo(-barWidth * 0.6f)
                                    slide.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 420f))
                                }

                                else -> slide.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { slide.animateTo(0f) } },
                ) { change, delta ->
                    change.consume()
                    scope.launch { slide.snapTo(slide.value + delta) }
                }
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
                    .graphicsLayer {
                        alpha = coverAlpha() * (1f - (abs(slide.value) / barWidth).coerceIn(0f, 1f))
                        translationX = slide.value
                    }
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

            val s = LocalStrings.current
            Row(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = contentAlpha() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        translationX = slide.value
                        alpha = 1f - (abs(slide.value) / barWidth).coerceIn(0f, 1f)
                    },
            ) {
                Text(
                    text = currentTrack.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                )
                Text(
                    text = currentTrack.artists.joinToString { it.name }.ifEmpty { s.unknownArtist },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = if (isPlaying) QMusicIcons.Pause else QMusicIcons.Play,
                    contentDescription = if (isPlaying) s.pause else s.play,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = QMusicIcons.Next,
                    contentDescription = s.next,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            }
        }

        // Progress, as on YouTube Music: a hairline on the seam between the mini player and the navigation bar,
        // over a barely visible track; it fades with the rest as the full player rises.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(PROGRESS_HEIGHT)
                .graphicsLayer { alpha = contentAlpha() }
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
    }
}

private val PROGRESS_HEIGHT = 1.5.dp
private const val SWIPE_SKIP_PX = 140f
private const val SLIDE_OUT_MS = 160
