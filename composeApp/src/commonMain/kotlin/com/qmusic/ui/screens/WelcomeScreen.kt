package com.qmusic.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.QMusicLogo

/**
 * First launch: which account brings the music. Only the chosen one is used from then on (Settings can change it);
 * going on without an account is offered too.
 */
@Composable
fun WelcomeScreen(
    onYouTube: () -> Unit,
    onSpotify: () -> Unit,
    onWithoutAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BgRoot)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QMusicLogo(size = 72.dp, tint = Color.White)
            Spacer(Modifier.height(28.dp))
            Text(
                text = s.welcomeTitle,
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 36.sp),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = s.welcomeSubtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            ServiceButton(
                label = s.continueWithYouTube,
                container = Color.White,
                content = Color(0xFF0B0B0F),
                mark = { YouTubeMusicMark() },
                onClick = onYouTube,
            )
            Spacer(Modifier.height(12.dp))
            ServiceButton(
                label = s.continueWithSpotify,
                container = SPOTIFY_GREEN,
                content = Color(0xFF0B0B0F),
                mark = { SpotifyMark() },
                onClick = onSpotify,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = s.welcomeNote,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onWithoutAccount) {
                Text(s.continueWithoutAccount, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun ServiceButton(label: String, container: Color, content: Color, mark: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { mark() }
        Spacer(Modifier.width(12.dp))
        Text(label, color = content, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** YouTube Music's mark: a red disc, a white ring and a play triangle. */
@Composable
private fun YouTubeMusicMark() {
    Canvas(Modifier.size(26.dp)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0xFFFF0033), r, c)
        drawCircle(Color.White, r * 0.58f, c, style = Stroke(width = r * 0.11f))
        val t = r * 0.3f
        drawPath(
            Path().apply {
                moveTo(c.x - t * 0.75f, c.y - t)
                lineTo(c.x + t, c.y)
                lineTo(c.x - t * 0.75f, c.y + t)
                close()
            },
            Color.White,
        )
    }
}

/** Spotify's mark: three arcs on a disc (dark, to sit on the green button). */
@Composable
private fun SpotifyMark() {
    Canvas(Modifier.size(26.dp)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0xFF0B0B0F), r, c)
        listOf(0.68f to 0.13f, 0.52f to 0.11f, 0.38f to 0.09f).forEachIndexed { i, (w, stroke) ->
            val width = r * 2 * w
            val top = c.y - r * 0.42f + i * r * 0.36f
            drawArc(
                color = SPOTIFY_GREEN,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(c.x - width / 2, top),
                size = Size(width, width * 0.55f),
                style = Stroke(width = r * stroke * 1.6f, cap = StrokeCap.Round),
            )
        }
    }
}

private val SPOTIFY_GREEN = Color(0xFF1ED760)
