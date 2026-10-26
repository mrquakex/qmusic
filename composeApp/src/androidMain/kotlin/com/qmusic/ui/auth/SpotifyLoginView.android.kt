package com.qmusic.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
 * Spotify's sign-in page in a web view. Once it sets the session cookie (`sp_dc`), the
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(android.graphics.Color.parseColor("#121212"))
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        // The web player (and its token) only runs for a desktop browser; the phone page sends to the app store.
                        settings.userAgentString = SPOTIFY_DESKTOP_AGENT
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        var done = false

                        fun checkSignedIn(view: WebView?): Boolean {
                            if (done) return true
                            val cm = CookieManager.getInstance()
                            val cookies = listOfNotNull(
                                cm.getCookie("https://open.spotify.com"),
                                cm.getCookie("https://accounts.spotify.com"),
                                cm.getCookie("https://spotify.com"),
                            ).joinToString("; ")
                            if ("sp_dc=" in cookies) {
                                done = true
                                cm.flush()
                                view?.stopLoading()
                                onSignedIn()
                                return true
                            }
                            return false
                        }

                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                if (checkSignedIn(view)) return true
                                val scheme = request?.url?.scheme?.lowercase() ?: return false
                                if (scheme != "https" && scheme != "http") return true
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                checkSignedIn(view)
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                checkSignedIn(view)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                checkSignedIn(view)
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
