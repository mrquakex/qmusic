package com.qmusic.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.QMusicLogo

@Composable
actual fun YouTubeLoginView(
    onCookiesCaptured: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val s = LocalStrings.current
    var attempt by remember { mutableIntStateOf(0) }
    var isWaiting by remember { mutableStateOf(true) }
    var statusError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        isWaiting = true
        statusError = null
        val cookies = DesktopWebAuth.signInYouTubeMusic(
            onWindowClosedByUser = {
                isWaiting = false
            },
            onError = { err ->
                isWaiting = false
                statusError = err
            },
        )
        if (cookies != null) {
            onCookiesCaptured(cookies)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkSurface)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QMusicLogo(size = 26.dp, tint = Color.White)
                    Text(
                        text = s.signInYouTubeMusic,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = QMusicIcons.Close,
                        contentDescription = s.close,
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (isWaiting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(32.dp),
                    )
                } else {
                    Icon(
                        imageVector = QMusicIcons.Account,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = if (isWaiting) {
                    "Google Oturum Açma Penceresi Açıldı"
                } else {
                    "Oturum Açma Tamamlanmadı"
                },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = statusError
                    ?: if (isWaiting) {
                        "Açılan güvenli Google penceresinden e-posta ve şifrenizle giriş yapın. Giriş yaptığınız anda hesabınız otomatik olarak bağlanacaktır."
                    } else {
                        "Oturum açma penceresi kapatıldı. Tekrar denemek için aşağıdaki butona tıklayın."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = if (statusError != null) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.68f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            if (!isWaiting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable { attempt++ },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Oturum Açma Penceresini Tekrar Aç",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = BgRoot,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            TextButton(onClick = onDismiss) {
                Text(s.cancel, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}
