package com.qmusic.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Master "q" glyph outline (512 x 512 viewBox) from the QMusic brand system. */
const val Q_GLYPH_PATH =
    "M232,128 C165.7,128 112,181.7 112,248 C112,314.3 165.7,368 232,368 C261.2,368 288,357.5 308.8,340.1 " +
        "L308.8,404 C308.8,417.3 319.5,428 332.8,428 C346.1,428 356.8,417.3 356.8,404 L356.8,160 " +
        "C356.8,160 398,168 408,212 C410.5,223 422,228 432,224 C442,220 446,208 443,196 " +
        "C428,136 364,116 332.8,116 C319.5,116 308.8,126.7 308.8,140 L308.8,155.9 " +
        "C288,138.5 261.2,128 232,128 Z M232,320 C192.2,320 160,287.8 160,248 C160,208.2 192.2,176 232,176 " +
        "C271.8,176 304,208.2 304,248 C304,287.8 271.8,320 232,320 Z"

/** Left equalizer bar inside the "q" bowl. */
const val Q_BAR_SHORT_PATH = "M206,232 a8,8 0 0 1 16,0 v32 a8,8 0 0 1 -16,0 z"

/** Right equalizer bar inside the "q" bowl. */
const val Q_BAR_TALL_PATH = "M238,216 a8,8 0 0 1 16,0 v64 a8,8 0 0 1 -16,0 z"

val QMusicLogoSymbol: ImageVector = ImageVector.Builder(
    name = "QMusicLogoSymbol",
    defaultWidth = 48.dp,
    defaultHeight = 48.dp,
    viewportWidth = 512f,
    viewportHeight = 512f,
).apply {
    listOf(Q_GLYPH_PATH, Q_BAR_SHORT_PATH, Q_BAR_TALL_PATH).forEach {
        addPath(pathData = addPathNodes(it), fill = SolidColor(Color.White))
    }
}.build()

/** Tintable "q" symbol. */
@Composable
fun QMusicLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        imageVector = QMusicLogoSymbol,
        contentDescription = "QMusic",
        modifier = modifier.size(size),
        tint = tint,
    )
}

/** "q" + "music" wordmark: q weight 800, music weight 600 at 72% alpha. */
@Composable
fun QMusicWordmark(
    modifier: Modifier = Modifier,
    fontSize: Float = 22f,
    color: Color = LocalContentColor.current,
) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = color)) { append("q") }
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color.copy(alpha = 0.72f))) {
                append("music")
            }
        },
        fontSize = fontSize.sp,
        letterSpacing = (-0.8).sp,
        modifier = modifier,
    )
}

/**
 * Header lockup, read as "qMusic": the symbol is the q, so only "Music" follows it (the way YouTube Music puts
 * "Music" after its play mark), in one colour and weight.
 */
@Composable
fun QMusicHeaderLogo(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        QMusicLogo(size = 30.dp, tint = color)
        Spacer(Modifier.width(1.dp))
        Text(
            text = "Music",
            color = color,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
    }
}
