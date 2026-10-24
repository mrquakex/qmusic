package com.qmusic.ui.auth

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.QMusicIcons

/**
 * Spotify's sign-in page in a web view. Once it lands on the web player with the session cookie (`sp_dc`), the
 * account is signed in: the app then gets the web player's token the same way (see the app's Spotify session).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun SpotifyLoginView(
    onSignedIn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val s = LocalStrings.current
    Box(modifier = modifier.fillMaxSize().background(DarkBackground)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(QMusicIcons.Close, contentDescription = s.close, tint = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(s.signInSpotify, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(s.spotifySignInHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // The web player (and its token) only runs for a desktop browser; the phone page sends to the app store.
                        settings.userAgentString = SPOTIFY_DESKTOP_AGENT
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        var done = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (done || url?.startsWith("https://open.spotify.com") != true) return
                                val cookies = CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
                                if ("sp_dc=" in cookies) {
                                    done = true
                                    CookieManager.getInstance().flush()
                                    onSignedIn()
                                }
                            }
                        }
                        loadUrl("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                    }
                },
            )
        }
    }
}

/** A desktop Chrome, which Spotify's web player runs in. */
const val SPOTIFY_DESKTOP_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
