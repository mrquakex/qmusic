package com.qmusic.ui.auth

import android.annotation.SuppressLint
import com.qmusic.ui.theme.QMusicIcons
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.DarkSurface

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun YouTubeLoginView(
    onCookiesCaptured: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val s = LocalStrings.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = QMusicIcons.Close,
                        contentDescription = s.close,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = s.signInYouTubeMusic,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val currentUrl = url ?: return
                                // Only the music.youtube.com session is usable by the API calls; accounts.google.com
                                // also has a SAPISID, but its cookie jar is the wrong domain.
                                if (!currentUrl.startsWith("https://music.youtube.com")) return
                                val cookieManager = CookieManager.getInstance()
                                val cookies = cookieManager.getCookie("https://music.youtube.com") ?: return

                                val sapisid = YouTubeAuth.parseSapisid(cookies)
                                if (sapisid != null) {
                                    onCookiesCaptured(cookies)
                                }
                            }
                        }

                        loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F")
                    }
                },
            )
        }
    }
}
