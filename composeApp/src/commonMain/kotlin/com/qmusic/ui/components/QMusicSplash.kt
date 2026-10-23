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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.theme.Q_GLYPH_PATH
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SplashBackground = Color(0xFF08080A)
private val LaserEase = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val LiquidEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val PopEase = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
private val SlideEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/**
 * 5-stage monochrome splash: laser contour trace, liquid fill, equalizer bars pop,
 * wordmark slide-up, thin loader. Calls [onFinished] once the choreography completes.
 */
@Composable
fun QMusicSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trace = remember { Animatable(0f) }
    val fill = remember { Animatable(0f) }
    val bars = remember { Animatable(0f) }
    val letters = remember { List(6) { Animatable(0f) } }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { delay(100); trace.animateTo(1f, tween(1150, easing = LaserEase)) }
            launch { delay(800); fill.animateTo(1f, tween(850, easing = LiquidEase)) }
            launch { delay(1050); bars.animateTo(1f, tween(550, easing = PopEase)) }
            letters.forEachIndexed { index, anim ->
                launch { delay(1120L + index * 60L); anim.animateTo(1f, tween(600, easing = SlideEase)) }
            }
        }
        delay(700)
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
    val loaderShift by pulse.animateFloat(
        initialValue = -1.1f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(1350, easing = LaserEase)),
        label = "loader",
    )

    val glyph = remember { PathParser().parsePathString(Q_GLYPH_PATH).toPath() }
    val outline = remember(glyph) {
        // Measure only the first (outer) contour for the laser trace.
        PathMeasure().apply { setPath(glyph, false) }
    }

    Box(
        modifier = modifier.fillMaxSize().background(SplashBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(148.dp)) {
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
                "qmusic".forEachIndexed { index, ch ->
                    val p = letters[index].value
                    Text(
                        text = ch.toString(),
                        fontSize = 28.sp,
                        letterSpacing = (-1).sp,
                        fontWeight = if (index == 0) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = Color.White.copy(alpha = if (index == 0) p else 0.75f * p),
                        modifier = Modifier.graphicsLayer { translationY = (1f - p) * 40.dp.toPx() },
                    )
                }
            }

            // Stage 5: thin loader.
            Box(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .width(92.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
            ) {
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(2.dp)
                        .graphicsLayer { translationX = loaderShift * 38.dp.toPx() }
                        .background(Color.White),
                )
            }
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
