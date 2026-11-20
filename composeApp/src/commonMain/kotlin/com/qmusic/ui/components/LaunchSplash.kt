package com.qmusic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import com.qmusic.ui.theme.N_MARK_PATH
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The app says here that what the first screen needs is ready (the splash then opens onto it). */
object SplashGate {
    val ready = MutableStateFlow(false)
}

private val Ground = Color(0xFF050507)
private val MarkColor = Color(0xFFC4C5CD)
private val Settle = CubicBezierEasing(0.16f, 0.8f, 0.2f, 1f)

/** The most the splash waits for the app, whatever happens (a phone so slow, or an answer that never comes). */
private const val MAX_WAIT_MS = 3500L

/**
 * The opening: the n is drawn in the middle (its outline, then filled) and a light keeps passing over it while the app gets ready; the moment
 * the app is ready the n grows through the screen (around the gap between its legs, so the legs leave past the sides
 * and the arch past the top) and the app is there. Fast phone: hardly any wait. Slow phone: it waits for the app.
 * Plays once per process; coming back to a running app goes straight in.
 */
@Composable
fun LaunchSplash(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var running by remember { mutableStateOf(!SplashState.shown) }
    // The app settles from a little larger as the splash opens (hidden under it until then).
    val appScale = remember { Animatable(if (running) 1.16f else 1f) }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = appScale.value; scaleY = appScale.value }) { content() }
        if (running) {
            LaunchOverlay(appScale) {
                SplashState.shown = true
                running = false
            }
        }
    }
}

@Composable
private fun LaunchOverlay(appScale: Animatable<Float, *>, onDone: () -> Unit) {
    val glyph = remember { PathParser().parsePathString(N_MARK_PATH).toPath() }
    // A slanted band of light, in the mark's own 100 units.
    val band = remember {
        Path().apply {
            moveTo(0f, 0f); lineTo(46f, 0f); lineTo(13.5f, 100f); lineTo(-32.5f, 100f); close()
        }
    }
    val glyphAlpha = remember { Animatable(1f) }
    // The outline is drawn (0..1 of its length), then the inside fills and the line fades.
    val draw = remember { Animatable(0f) }
    val fillAlpha = remember { Animatable(0f) }
    val lineAlpha = remember { Animatable(1f) }
    val outline = remember(glyph) { androidx.compose.ui.graphics.PathMeasure().apply { setPath(glyph, false) } }
    val grow = remember { Animatable(1f) }
    val cover = remember { Animatable(1f) }
    var introDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Phase 1: the outline of the n is drawn, then it fills (no bounce).
        coroutineScope {
            launch { draw.animateTo(1f, tween(700, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f))) }
            launch { delay(500); fillAlpha.animateTo(1f, tween(400, easing = LinearEasing)) }
            launch { delay(600); lineAlpha.animateTo(0f, tween(300, easing = LinearEasing)) }
        }
        introDone = true
        // Waits for the app (never longer than the cap).
        withTimeoutOrNull(MAX_WAIT_MS) { SplashGate.ready.first { it } }
        // Phase 2: ready. The mark grows (faster and faster, as moving toward it does), the ground clears, the app settles.
        coroutineScope {
            launch {
                grow.animateTo(
                    34f,
                    keyframes {
                        durationMillis = 1100
                        1f at 0 using LinearEasing
                        1.6f at 165 using LinearEasing
                        3.2f at 330 using LinearEasing
                        7f at 495 using LinearEasing
                        15f at 660 using LinearEasing
                        34f at 825 using LinearEasing
                    },
                )
            }
            launch {
                cover.animateTo(
                    0f,
                    keyframes {
                        durationMillis = 880
                        1f at 0 using LinearEasing
                        1f at 495 using LinearEasing
                        0f at 880 using LinearEasing
                    },
                )
            }
            launch {
                delay(330)
                appScale.animateTo(1f, tween(770, easing = Settle))
            }
            launch {
                delay(825)
                glyphAlpha.snapTo(0f)
            }
        }
        onDone()
    }

    val sweep by rememberInfiniteTransition(label = "launchSweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "sweep",
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Ground, alpha = cover.value) }
            // It holds the touches while it is on top (the app is under it).
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val side = minOf(maxWidth, maxHeight) * 0.46f
        Canvas(
            Modifier
                .align(Alignment.Center)
                // The letter's own middle (46.5, 55 of 100) goes to the middle of the screen.
                .offset(x = side * 0.035f, y = -(side * 0.05f))
                .size(side)
                .graphicsLayer {
                    // It grows around the gap between the legs.
                    transformOrigin = TransformOrigin(0.51f, 0.62f)
                    scaleX = grow.value
                    scaleY = grow.value
                    this.alpha = glyphAlpha.value
                },
        ) {
            val u = size.minDimension / 100f
            withTransform({ scale(u, u, pivot = Offset.Zero) }) {
                if (fillAlpha.value > 0f) drawPath(glyph, MarkColor.copy(alpha = fillAlpha.value))
                if (lineAlpha.value > 0f && draw.value > 0f) {
                    val segment = Path()
                    outline.getSegment(0f, outline.length * draw.value, segment, true)
                    drawPath(
                        segment,
                        MarkColor.copy(alpha = lineAlpha.value),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 1.1f,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                    )
                }
                if (introDone) {
                    // The light passes, rests, and passes again.
                    val bx = if (sweep < 0.3f) -90f else -90f + 240f * ((sweep - 0.3f) / 0.7f)
                    clipPath(glyph) {
                        withTransform({ translate(left = bx, top = 0f) }) {
                            drawPath(
                                band,
                                Brush.horizontalGradient(
                                    listOf(Color.White.copy(alpha = 0f), Color.White, Color.White.copy(alpha = 0f)),
                                    startX = -32.5f,
                                    endX = 46f,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
