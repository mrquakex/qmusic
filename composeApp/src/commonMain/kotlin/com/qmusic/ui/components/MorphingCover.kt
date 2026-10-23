package com.qmusic.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

/**
 * The cover that travels between the mini player and the full player.
 *
 * Everything is driven from [progress] in the layout and draw phases, so dragging the sheet moves, scales and
 * rounds this one image at the speed of the finger without recomposing anything.
 */
@Composable
fun MorphingCover(
    model: String?,
    from: () -> Rect?,
    to: () -> Rect?,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    if (model == null) return
    Box(modifier = modifier.fillMaxSize()) {
        SharpCover(
            url = model,
            px = 800,
            contentDescription = null,
            modifier = Modifier
                .layout { measurable, _ ->
                    val start = from()
                    val end = to()
                    val p = progress()
                    if (start == null || end == null || p <= 0f || p >= HANDOVER) {
                        layout(0, 0) {}
                    } else {
                        val width = lerp(start.width, end.width, p).roundToInt().coerceAtLeast(1)
                        val height = lerp(start.height, end.height, p).roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, height) {
                            placeable.place(
                                IntOffset(
                                    lerp(start.left, end.left, p).roundToInt(),
                                    lerp(start.top, end.top, p).roundToInt(),
                                ),
                            )
                        }
                    }
                }
                .graphicsLayer {
                    // Square corners of the mini cover grow into the full player's rounded ones.
                    shape = RoundedCornerShape(lerp(4f, 24f, progress()).dp)
                    clip = true
                },
        )
    }
}

private const val HANDOVER = 0.995f

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
