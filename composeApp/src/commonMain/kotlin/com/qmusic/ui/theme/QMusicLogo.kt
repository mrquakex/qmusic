package com.qmusic.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Nuvelo's mark (100 x 100): a lowercase n that is a speech bubble, its left leg ending in the bubble's tail. The
 * name's first letter and talking, in one shape.
 */
const val N_MARK_PATH =
    "M22 24Q22 19 27 19H35Q40 19 40 24V27C44 21 51 18 58 18C71 18 80 27 80 41V77Q80 82 75 82H67Q62 82 62 77V44" +
        "C62 38 58 35 52.5 35C46 35 40 40 40 47V81Q40 86 35 87.5L17 91.5Q13 92.3 14.5 88.8L22 72Z"

/**
 * The n of the written logo (its own units: baseline at y 88, the letter from y 24): the stem goes straight down,
 * and the tail is drawn apart, under the whole word.
 */
private const val N_WORD_PATH =
    "M12 30Q12 25 17 25H25Q30 25 30 30V33C34 27 41 24 48 24C61 24 70 33 70 47V83Q70 88 65 88H57Q52 88 52 83V50" +
        "C52 44 48 41 42.5 41C36 41 30 46 30 53V90H12Z"

val QMusicLogoSymbol: ImageVector = ImageVector.Builder(
    name = "NuveloMark",
    defaultWidth = 48.dp,
    defaultHeight = 48.dp,
    viewportWidth = 100f,
    viewportHeight = 100f,
).apply {
    addPath(pathData = addPathNodes(N_MARK_PATH), fill = SolidColor(Color.White))
}.build()

/** The mark alone (the bubble n), tinted. */
@Composable
fun QMusicLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        imageVector = QMusicLogoSymbol,
        contentDescription = "Nuvelo",
        modifier = modifier.size(size),
        tint = tint,
    )
}

/** The written logo, at a size given by the text size [fontSize] (kept for the places that set one). */
@Composable
fun QMusicWordmark(
    modifier: Modifier = Modifier,
    fontSize: Float = 22f,
    color: Color = LocalContentColor.current,
) = NuveloWordmark(modifier = modifier, letterHeight = (fontSize * 0.62f).dp, color = color)

/** The header logo: the written "nuvelo". */
@Composable
fun QMusicHeaderLogo(
    modifier: Modifier = Modifier,
    color: Color = Ink,
) {
    Box(modifier, contentAlignment = Alignment.CenterStart) { NuveloWordmark(letterHeight = 17.dp, color = color) }
}

/**
 * "nuvelo" as the logo has it: the n, then "uvelo", and the n's tail sweeping under the whole word and lifting at its
 * end (the word sits in a speech bubble). [letterHeight] is the height of the n; the rest follows from it.
 */
@Composable
fun NuveloWordmark(
    modifier: Modifier = Modifier,
    letterHeight: Dp = 17.dp,
    color: Color = Ink,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // In the drawing's units the n is 64 tall (y 24 to its baseline at 88); k turns units into pixels.
    val k = with(density) { letterHeight.toPx() } / 64f
    val style = TextStyle(
        fontSize = with(density) { (letterHeight * 1.55f).toSp() },
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.04).em,
        color = color,
    )
    val word = remember(style, measurer) { measurer.measure("uvelo", style) }
    val n = remember { PathParser().parsePathString(N_WORD_PATH).toPath() }
    val textX = 68f * k
    // The tail ends a little past the word.
    val tipX = textX + word.size.width + 22f * k
    val widthPx = tipX + 6f * k
    val heightPx = 100f * k
    val tail = remember(k, tipX) { tailPath(k, tipX) }
    Canvas(modifier.size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })) {
        // Units: x from 12, y from 24 (the top of the n).
        scale(k, k, pivot = Offset.Zero) {
            translate(-12f, -24f) { drawPath(n, color) }
        }
        drawPath(tail, color)
        drawText(word, topLeft = Offset(textX, 64f * k - word.firstBaseline))
    }
}

/**
 * The tail under the word, in pixels: thick where it leaves the n (as thick as its stem), thinning along the word,
 * then curling up to a rounded tip at [tipX]. Its straight middle stretches with the word's width.
 */
private fun tailPath(k: Float, tipX: Float): Path {
    // The drawing's units: the straight part runs from x 62 to 298, the curl to the tip at 355.
    fun x(u: Float): Float = when {
        u <= 62f -> (u - 12f) * k
        u >= 298f -> tipX + (u - 355f) * k
        else -> {
            val a = (62f - 12f) * k
            val b = tipX + (298f - 355f) * k
            a + (b - a) * (u - 62f) / (298f - 62f)
        }
    }
    fun y(u: Float): Float = (u - 24f) * k
    return Path().apply {
        moveTo(x(30f), y(86f))
        cubicTo(x(30f), y(99f), x(40f), y(105f), x(62f), y(105f))
        lineTo(x(298f), y(103f))
        cubicTo(x(321f), y(102f), x(337f), y(96f), x(349f), y(86f))
        cubicTo(x(352f), y(83.5f), x(357f), y(85.5f), x(355f), y(89.5f))
        cubicTo(x(344f), y(107f), x(325f), y(113.5f), x(300f), y(114.5f))
        lineTo(x(62f), y(123f))
        cubicTo(x(30f), y(124f), x(12f), y(111f), x(12f), y(86f))
        close()
    }
}
