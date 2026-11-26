package com.qmusic.ui.chat.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.lightTheme

/** Vibrant, theme-aware color tokens crafted specifically for Nuvelo's 12 chat games. */
object GameColors {
    val Emerald: Color get() = if (lightTheme) Color(0xFF16A34A) else Color(0xFF22C55E)
    val Amber: Color get() = if (lightTheme) Color(0xFFD97706) else Color(0xFFF59E0B)
    val Gold: Color get() = if (lightTheme) Color(0xFFCA8A04) else Color(0xFFFACC15)
    val Coral: Color get() = if (lightTheme) Color(0xFFE11D48) else Color(0xFFF43F5E)
    val Crimson: Color get() = if (lightTheme) Color(0xFFDC2626) else Color(0xFFEF4444)
    val Sky: Color get() = if (lightTheme) Color(0xFF0284C7) else Color(0xFF38BDF8)
    val Cobalt: Color get() = if (lightTheme) Color(0xFF1D4ED8) else Color(0xFF2563EB)
    val Cyan: Color get() = if (lightTheme) Color(0xFF0891B2) else Color(0xFF06B6D4)
    val Teal: Color get() = if (lightTheme) Color(0xFF0D9488) else Color(0xFF14B8A6)
    val Indigo: Color get() = if (lightTheme) Color(0xFF4F46E5) else Color(0xFF6366F1)
    val Violet: Color get() = if (lightTheme) Color(0xFF9333EA) else Color(0xFFA855F7)
    val Magenta: Color get() = if (lightTheme) Color(0xFFDB2777) else Color(0xFFEC4899)
    val Orange: Color get() = if (lightTheme) Color(0xFFEA580C) else Color(0xFFF97316)
    val Lime: Color get() = if (lightTheme) Color(0xFF65A30D) else Color(0xFF84CC16)
    val Mint: Color get() = if (lightTheme) Color(0xFF15803D) else Color(0xFF1DB954)

    /** Deep ocean cell surface for Battleship (adapts cleanly to Light vs Dark). */
    val OceanDeep: Color get() = if (lightTheme) Color(0xFFE0F2FE) else Color(0xFF0B2239)
    val OceanCell: Color get() = if (lightTheme) Color(0xFFBAE6FD) else Color(0xFF103252)
    val OceanBorder: Color get() = if (lightTheme) Color(0xFF38BDF8) else Color(0xFF1E507B)

    /** Classic royal-blue board frame for Connect Four. */
    val Connect4Frame: Color get() = if (lightTheme) Color(0xFF1D4ED8) else Color(0xFF1E3A8A)
    val Connect4Slot: Color get() = if (lightTheme) Color(0xFFDBEAFE) else Color(0xFF0F172A)

    /** High-contrast foreground text placed directly on top of a saturated accent fill. */
    fun onAccent(accent: Color): Color {
        val luma = 0.299f * accent.red + 0.587f * accent.green + 0.114f * accent.blue
        return if (luma > 0.62f) Color(0xFF0A0A0C) else Color.White
    }
}

/** Distinct visual theme (primary accent, secondary highlight, soft tint) for each of the 12 games. */
data class GamePalette(
    val primary: Color,
    val secondary: Color,
    val onPrimary: Color = GameColors.onAccent(primary),
) {
    val softBg: Color get() = primary.copy(alpha = if (lightTheme) 0.11f else 0.16f)
    val softBorder: Color get() = primary.copy(alpha = if (lightTheme) 0.35f else 0.42f)
}

fun GameKind.palette(): GamePalette = when (this) {
    GameKind.WORD_DUEL -> GamePalette(GameColors.Emerald, GameColors.Amber)
    GameKind.WORD_CHAIN -> GamePalette(GameColors.Cyan, GameColors.Indigo)
    GameKind.XOX -> GamePalette(GameColors.Coral, GameColors.Sky)
    GameKind.HANGMAN -> GamePalette(GameColors.Amber, GameColors.Crimson)
    GameKind.CONNECT4 -> GamePalette(GameColors.Cobalt, GameColors.Gold)
    GameKind.RPS -> GamePalette(GameColors.Violet, GameColors.Magenta)
    GameKind.NAME_CITY -> GamePalette(GameColors.Teal, GameColors.Amber)
    GameKind.MASTERMIND -> GamePalette(GameColors.Lime, GameColors.Emerald)
    GameKind.SONG_QUIZ -> GamePalette(GameColors.Mint, GameColors.Magenta)
    GameKind.BATTLESHIP -> GamePalette(GameColors.Sky, GameColors.Orange)
    GameKind.TABOO -> GamePalette(GameColors.Magenta, GameColors.Crimson)
    GameKind.TRIVIA -> GamePalette(GameColors.Indigo, GameColors.Gold)
}

/**
 * Custom Canvas emblem unique to each of the 12 games — gives every game card, banner,
 * picker tile, slash command row, and arena header its own unmistakable silhouette & color identity.
 */
@Composable
fun GameEmblem(
    kind: GameKind,
    modifier: Modifier = Modifier,
) {
    val pal = kind.palette()
    val p = pal.primary
    val s = pal.secondary
    val crimson = GameColors.Crimson
    val gold = GameColors.Gold
    val sky = GameColors.Sky

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = (w * 0.10f).coerceIn(1.8.dp.toPx(), 3.2.dp.toPx())
        when (kind) {
            GameKind.WORD_DUEL -> {
                // 2x2 classic green/amber Wordle letter tiles
                val gap = w * 0.10f
                val cell = (w - gap * 3f) / 2f
                val r = CornerRadius(cell * 0.24f, cell * 0.24f)
                drawRoundRect(p, Offset(gap, gap), Size(cell, cell), r)
                drawRoundRect(s, Offset(gap * 2 + cell, gap), Size(cell, cell), r)
                drawRoundRect(p.copy(alpha = 0.28f), Offset(gap, gap * 2 + cell), Size(cell, cell), r)
                drawRoundRect(p, Offset(gap * 2 + cell, gap * 2 + cell), Size(cell, cell), r)
            }
            GameKind.WORD_CHAIN -> {
                // Two interlocking glowing chain links
                drawRoundRect(
                    color = p,
                    topLeft = Offset(w * 0.10f, h * 0.30f),
                    size = Size(w * 0.50f, h * 0.40f),
                    cornerRadius = CornerRadius(h * 0.20f, h * 0.20f),
                    style = Stroke(sw),
                )
                drawRoundRect(
                    color = s,
                    topLeft = Offset(w * 0.40f, h * 0.30f),
                    size = Size(w * 0.50f, h * 0.40f),
                    cornerRadius = CornerRadius(h * 0.20f, h * 0.20f),
                    style = Stroke(sw),
                )
            }
            GameKind.XOX -> {
                // Coral X on top-left + Sky O on bottom-right
                drawLine(p, Offset(w * 0.16f, h * 0.16f), Offset(w * 0.46f, h * 0.46f), sw, StrokeCap.Round)
                drawLine(p, Offset(w * 0.46f, h * 0.16f), Offset(w * 0.16f, h * 0.46f), sw, StrokeCap.Round)
                drawCircle(s, radius = w * 0.17f, center = Offset(w * 0.70f, h * 0.70f), style = Stroke(sw))
                drawLine(p.copy(alpha = 0.32f), Offset(w * 0.52f, h * 0.12f), Offset(w * 0.52f, h * 0.88f), sw * 0.5f, StrokeCap.Round)
                drawLine(s.copy(alpha = 0.32f), Offset(w * 0.12f, h * 0.52f), Offset(w * 0.88f, h * 0.52f), sw * 0.5f, StrokeCap.Round)
            }
            GameKind.HANGMAN -> {
                // Minimalist amber gallows + crimson head
                drawLine(p, Offset(w * 0.16f, h * 0.86f), Offset(w * 0.56f, h * 0.86f), sw, StrokeCap.Round)
                drawLine(p, Offset(w * 0.30f, h * 0.86f), Offset(w * 0.30f, h * 0.16f), sw, StrokeCap.Round)
                drawLine(p, Offset(w * 0.30f, h * 0.16f), Offset(w * 0.72f, h * 0.16f), sw, StrokeCap.Round)
                drawLine(p, Offset(w * 0.72f, h * 0.16f), Offset(w * 0.72f, h * 0.30f), sw * 0.85f, StrokeCap.Round)
                drawCircle(s, radius = w * 0.13f, center = Offset(w * 0.72f, h * 0.44f), style = Stroke(sw))
                drawLine(s, Offset(w * 0.72f, h * 0.57f), Offset(w * 0.72f, h * 0.78f), sw * 0.9f, StrokeCap.Round)
            }
            GameKind.CONNECT4 -> {
                // Cobalt board with red & yellow discs
                drawRoundRect(p, Offset(w * 0.08f, h * 0.14f), Size(w * 0.84f, h * 0.72f), CornerRadius(w * 0.14f, w * 0.14f))
                val r = w * 0.11f
                drawCircle(crimson, r, Offset(w * 0.31f, h * 0.66f))
                drawCircle(gold, r, Offset(w * 0.50f, h * 0.66f))
                drawCircle(crimson, r, Offset(w * 0.69f, h * 0.66f))
                drawCircle(gold, r, Offset(w * 0.31f, h * 0.38f))
                drawCircle(crimson, r, Offset(w * 0.50f, h * 0.38f))
                drawCircle(Color.White.copy(alpha = 0.25f), r, Offset(w * 0.69f, h * 0.38f))
            }
            GameKind.RPS -> {
                // Three clash shapes: stone circle, paper rect, scissors X
                drawCircle(GameColors.Amber, radius = w * 0.16f, center = Offset(w * 0.28f, h * 0.34f))
                drawRoundRect(sky, Offset(w * 0.54f, h * 0.16f), Size(w * 0.30f, h * 0.34f), CornerRadius(w * 0.06f, w * 0.06f))
                drawLine(s, Offset(w * 0.34f, h * 0.60f), Offset(w * 0.68f, h * 0.88f), sw, StrokeCap.Round)
                drawLine(s, Offset(w * 0.68f, h * 0.60f), Offset(w * 0.34f, h * 0.88f), sw, StrokeCap.Round)
            }
            GameKind.NAME_CITY -> {
                // Notebook sheet with golden stamp badge
                drawRoundRect(p, Offset(w * 0.14f, h * 0.12f), Size(w * 0.72f, h * 0.76f), CornerRadius(w * 0.12f, w * 0.12f), style = Stroke(sw))
                drawCircle(s, radius = w * 0.14f, center = Offset(w * 0.34f, h * 0.36f))
                drawLine(p, Offset(w * 0.54f, h * 0.36f), Offset(w * 0.74f, h * 0.36f), sw * 0.85f, StrokeCap.Round)
                drawLine(p, Offset(w * 0.26f, h * 0.60f), Offset(w * 0.74f, h * 0.60f), sw * 0.85f, StrokeCap.Round)
                drawLine(s, Offset(w * 0.26f, h * 0.74f), Offset(w * 0.60f, h * 0.74f), sw * 0.85f, StrokeCap.Round)
            }
            GameKind.MASTERMIND -> {
                // Vault code dials + exact/misplaced pins
                drawRoundRect(p, Offset(w * 0.10f, h * 0.20f), Size(w * 0.80f, h * 0.60f), CornerRadius(w * 0.14f, w * 0.14f), style = Stroke(sw))
                val r = w * 0.085f
                drawCircle(s, r, Offset(w * 0.28f, h * 0.50f))
                drawCircle(GameColors.Amber, r, Offset(w * 0.43f, h * 0.50f))
                drawCircle(s, r, Offset(w * 0.58f, h * 0.50f))
                drawCircle(p, r, Offset(w * 0.73f, h * 0.50f))
            }
            GameKind.SONG_QUIZ -> {
                // Vivid pulsing equalizer bars
                val barW = w * 0.13f
                val heights = listOf(0.40f, 0.78f, 0.56f, 0.86f)
                val colors = listOf(p, s, GameColors.Cyan, p)
                heights.forEachIndexed { idx, frac ->
                    val x = w * (0.14f + idx * 0.20f)
                    val bh = h * frac
                    drawRoundRect(
                        color = colors[idx],
                        topLeft = Offset(x, h * 0.88f - bh),
                        size = Size(barW, bh),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                    )
                }
            }
            GameKind.BATTLESHIP -> {
                // Radar crosshair + orange target hit dot
                val center = Offset(w * 0.50f, h * 0.50f)
                drawCircle(p, radius = w * 0.36f, center = center, style = Stroke(sw * 0.85f))
                drawCircle(p.copy(alpha = 0.45f), radius = w * 0.19f, center = center, style = Stroke(sw * 0.7f))
                drawLine(p, Offset(w * 0.50f, h * 0.08f), Offset(w * 0.50f, h * 0.92f), sw * 0.6f, StrokeCap.Round)
                drawLine(p, Offset(w * 0.08f, h * 0.50f), Offset(w * 0.92f, h * 0.50f), sw * 0.6f, StrokeCap.Round)
                drawCircle(s, radius = w * 0.10f, center = Offset(w * 0.64f, h * 0.36f))
            }
            GameKind.TABOO -> {
                // Stacked Taboo card with magenta header & crimson forbidden mark
                drawRoundRect(p, Offset(w * 0.16f, h * 0.10f), Size(w * 0.68f, h * 0.26f), CornerRadius(w * 0.10f, w * 0.10f))
                drawRoundRect(p, Offset(w * 0.16f, h * 0.10f), Size(w * 0.68f, h * 0.80f), CornerRadius(w * 0.12f, w * 0.12f), style = Stroke(sw * 0.85f))
                drawCircle(s, radius = w * 0.16f, center = Offset(w * 0.50f, h * 0.62f), style = Stroke(sw * 0.9f))
                drawLine(s, Offset(w * 0.39f, h * 0.51f), Offset(w * 0.61f, h * 0.73f), sw * 0.9f, StrokeCap.Round)
            }
            GameKind.TRIVIA -> {
                // Quiz show golden 4-point star inside indigo diamond/circle
                drawCircle(p, radius = w * 0.40f, center = Offset(w * 0.50f, h * 0.50f))
                val star = Path().apply {
                    moveTo(w * 0.50f, h * 0.20f)
                    lineTo(w * 0.58f, h * 0.42f)
                    lineTo(w * 0.80f, h * 0.50f)
                    lineTo(w * 0.58f, h * 0.58f)
                    lineTo(w * 0.50f, h * 0.80f)
                    lineTo(w * 0.42f, h * 0.58f)
                    lineTo(w * 0.20f, h * 0.50f)
                    lineTo(w * 0.42f, h * 0.42f)
                    close()
                }
                drawPath(star, color = s)
            }
        }
    }
}

/**
 * Horizontal linear roulette / slot-reel animation ("çark gibi ama düz").
 * Rapidly scrolls a strip of Turkish letters behind a fixed center pointer with smooth ease-out deceleration,
 * landing dead-center on [targetLetter] with a celebratory scale lock-in pulse.
 */
@Composable
fun LetterRouletteStrip(
    targetLetter: Char,
    spinKey: Any,
    accent: Color,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    onSettled: () -> Unit = {},
) {
    val target = TurkishText.trUpperChar(targetLetter)
    val reelCount = 44
    val targetIndex = 35
    val reel = remember(spinKey, target) {
        val letters = TurkishText.PLAYABLE_NAME_CITY_LETTERS
        List(reelCount) { idx ->
            if (idx == targetIndex) {
                target
            } else {
                val pick = letters[Sha256.deterministicIndex("reel|$spinKey|$idx|$target", letters.length)]
                if (idx == targetIndex - 1 && pick == target) letters[(letters.indexOf(pick) + 1) % letters.length] else pick
            }
        }
    }

    val tileDp = 52.dp
    val gapDp = 6.dp
    val pitchDp = tileDp + gapDp
    val progressIndex = remember(spinKey, target) { Animatable(if (animate) 2f else targetIndex.toFloat()) }
    val pulseScale = remember(spinKey, target) { Animatable(1f) }
    var settled by remember(spinKey, target) { mutableStateOf(!animate) }

    LaunchedEffect(spinKey, target, animate) {
        if (!animate) {
            progressIndex.snapTo(targetIndex.toFloat())
            settled = true
            onSettled()
            return@LaunchedEffect
        }
        settled = false
        progressIndex.snapTo(2f)
        pulseScale.snapTo(1f)
        // Realistic linear roulette spin: whip launch -> long suspenseful slowdown -> lock onto targetIndex
        progressIndex.animateTo(
            targetValue = targetIndex.toFloat(),
            animationSpec = tween(
                durationMillis = 2_350,
                easing = CubicBezierEasing(0.06f, 0.90f, 0.14f, 1.0f),
            ),
        )
        settled = true
        pulseScale.animateTo(1.13f, tween(130))
        pulseScale.animateTo(1.0f, tween(170))
        onSettled()
    }

    val density = LocalDensity.current
    val surfaceColor = DarkSurface

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(78.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(surfaceColor)
            .border(1.4.dp, accent.copy(alpha = if (settled) 0.75f else 0.35f), RoundedCornerShape(16.dp))
            .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val viewportPx = with(density) { maxWidth.toPx() }
        val tilePx = with(density) { tileDp.toPx() }
        val pitchPx = with(density) { pitchDp.toPx() }

        // Scrolling strip of 44 Turkish letter tiles
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .graphicsLayer {
                    val centerOffset = (viewportPx - tilePx) / 2f
                    translationX = centerOffset - progressIndex.value * pitchPx
                },
            horizontalArrangement = Arrangement.spacedBy(gapDp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            reel.forEachIndexed { idx, ch ->
                val isWinner = settled && idx == targetIndex
                Box(
                    modifier = Modifier
                        .size(tileDp)
                        .graphicsLayer {
                            if (isWinner) {
                                scaleX = pulseScale.value
                                scaleY = pulseScale.value
                            }
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isWinner) accent else DarkSurfaceVariant)
                        .border(1.2.dp, if (isWinner) accent else BorderSubtle, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ch.toString(),
                        color = if (isWinner) GameColors.onAccent(accent) else TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }

        // Left & right soft edge fade
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(44.dp)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(surfaceColor, Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(44.dp)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color.Transparent, surfaceColor))),
        )

        // Centered selection reticle + top/bottom pointers
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(tileDp + 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(2.2.dp, accent, RoundedCornerShape(14.dp)),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val tri = 7.dp.toPx()
            val topPtr = Path().apply {
                moveTo(cx, tri + 1.dp.toPx())
                lineTo(cx - tri, 0f)
                lineTo(cx + tri, 0f)
                close()
            }
            val botPtr = Path().apply {
                moveTo(cx, size.height - tri - 1.dp.toPx())
                lineTo(cx - tri, size.height)
                lineTo(cx + tri, size.height)
                close()
            }
            drawPath(topPtr, color = accent)
            drawPath(botPtr, color = accent)
        }
    }
}
