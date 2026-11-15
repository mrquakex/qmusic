package com.qmusic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.theme.Q_GLYPH_PATH
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SplashBackground = Color(0xFF050507)

/** The name, written the way the logo is: a bold "q", the rest lighter. */
private const val WORD = "qeyf"

/** A warm light behind the logo: keyif. */
private val Glow = Color(0xFFFF8A3D)
private val Glow2 = Color(0xFFFF3D7F)

/** What it is, under the name, in the language the phone speaks (the app's own setting is not read yet here). */
private fun tagline(): String =
    if (androidx.compose.ui.text.intl.Locale.current.language == "tr") "MÜZİK · VİDEO · SOHBET" else "MUSIC · VIDEO · CHAT"
private val LaserEase = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val LiquidEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val PopEase = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
private val SlideEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** The splash plays once per process: coming back to an app that is still running goes straight in. */
object SplashState {
    var shown: Boolean = false
}

/**
 * 4-stage monochrome splash: laser contour trace, liquid fill, equalizer bars pop,
 * wordmark slide-up. Calls [onFinished] once the choreography completes.
 */
@Composable
fun QMusicSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trace = remember { Animatable(0f) }
    val fill = remember { Animatable(0f) }
    val bars = remember { Animatable(0f) }
    val letters = remember { List(WORD.length) { Animatable(0f) } }
    val tagline = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    val skip = remember { kotlinx.coroutines.CompletableDeferred<Unit>() }

    LaunchedEffect(Unit) {
        // About 1.4 s in all (the app loads underneath meanwhile); a tap skips to the end.
        val play = launch {
            coroutineScope {
                launch { glow.animateTo(1f, tween(900, easing = LiquidEase)) }
                launch { delay(50); trace.animateTo(1f, tween(550, easing = LaserEase)) }
                launch { delay(380); fill.animateTo(1f, tween(450, easing = LiquidEase)) }
                launch { delay(520); bars.animateTo(1f, tween(350, easing = PopEase)) }
                letters.forEachIndexed { index, anim ->
                    launch { delay(560L + index * 45L); anim.animateTo(1f, tween(420, easing = SlideEase)) }
                }
                launch { delay(820); tagline.animateTo(1f, tween(360, easing = SlideEase)) }
            }
            delay(120)
        }
        kotlinx.coroutines.selects.select<Unit> {
            play.onJoin { }
            skip.onAwait { }
        }
        play.cancel()
        SplashState.shown = true
        exit.animateTo(0f, tween(280, easing = LinearEasing))
        onFinished()
    }

    val pulse = rememberInfiniteTransition(label = "splashPulse")
    val eq1 by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.32f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "eq1",
    )
    val eq2 by pulse.animateFloat(
        initialValue = 1.18f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse),
        label = "eq2",
    )

    val glyph = remember { PathParser().parsePathString(Q_GLYPH_PATH).toPath() }
    val outline = remember(glyph) {
        // Measure only the first (outer) contour for the laser trace.
        PathMeasure().apply { setPath(glyph, false) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exit.value }
            .background(SplashBackground)
            // Holds the touches while it is on top (the app is underneath), and a tap skips it.
            .pointerInput(Unit) { detectTapGestures { skip.complete(Unit) } }
            .background(
                Brush.radialGradient(
                    listOf(Glow.copy(alpha = 0.22f * glow.value), Glow2.copy(alpha = 0.08f * glow.value), Color.Transparent),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(148.dp).graphicsLayer { val k = 1f + (1f - exit.value) * 0.08f; scaleX = k; scaleY = k }) {
                val s = size.minDimension / 512f
                withTransform({
                    translate(0f, 0f)
                    scale(s, s, pivot = Offset.Zero)
                }) {
                    // Stage 1: laser contour trace (fades once the fill takes over).
                    val traceAlpha = (1f - ((fill.value - 0.1f) / 0.6f)).coerceIn(0f, 1f)
                    if (trace.value > 0f && traceAlpha > 0f) {
                        val segment = Path()
                        outline.getSegment(0f, outline.length * trace.value, segment, true)
                        drawPath(
                            path = segment,
                            color = Color.White.copy(alpha = traceAlpha),
                            style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }

                    // Stage 2: liquid circle fill clipped to the glyph.
                    if (fill.value > 0f) {
                        val radius = 240f * fill.value
                        val circle = Path().apply {
                            addOval(Rect(Offset(232f - radius, 248f - radius), Size(radius * 2, radius * 2)))
                        }
                        clipPath(circle) {
                            drawPath(glyph, Color.White)
                        }
                    }

                    // Stage 3: equalizer bars pop in, then keep pulsing.
                    if (bars.value > 0f) {
                        drawBar(214f, 48f, bars.value * eq1)
                        drawBar(246f, 80f, bars.value * eq2)
                    }
                }
            }

            // Stage 4: wordmark characters slide up.
            Row(modifier = Modifier.padding(top = 16.dp)) {
                WORD.forEachIndexed { index, ch ->
                    val p = letters[index].value
                    Text(
                        text = ch.toString(),
                        fontSize = 34.sp,
                        letterSpacing = (-1).sp,
                        fontWeight = if (index == 0) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = Color.White.copy(alpha = if (index == 0) p else 0.75f * p),
                        modifier = Modifier.graphicsLayer { translationY = (1f - p) * 40.dp.toPx() },
                    )
                }
            }
            Text(
                text = tagline(),
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.55f * tagline.value),
                modifier = Modifier.padding(top = 10.dp).graphicsLayer { translationY = (1f - tagline.value) * 12.dp.toPx() },
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(
    centerX: Float,
    height: Float,
    scaleY: Float,
) {
    val h = height * scaleY
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(centerX - 8f, 248f - h / 2f),
        size = Size(16f, h),
        cornerRadius = CornerRadius(8f, 8f),
    )
}
