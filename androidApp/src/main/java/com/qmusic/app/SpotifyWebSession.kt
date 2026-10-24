package com.qmusic.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.qmusic.settings.SpotifySession
import com.qmusic.ui.auth.SPOTIFY_DESKTOP_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * The Spotify account signed in on Spotify's web page. Its session lives in the web view's cookies; the access
 * token is the one Spotify's own web player gets: the player is opened in a hidden web view, and its token request
 * (whatever proof Spotify asks for in it) is answered for it and read on the way. A token lasts about an hour.
 */
class SpotifyWebSession(private val context: Context) : SpotifySession {
    override val available: Boolean = true

    private val _signedIn = MutableStateFlow(hasCookie())
    override val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val lock = Mutex()
    @Volatile private var token: String? = null
    @Volatile private var expiresAt = 0L

    override suspend fun token(refresh: Boolean): String? = lock.withLock {
        if (!hasCookie()) {
            _signedIn.value = false
            return@withLock null
        }
        val now = System.currentTimeMillis()
        if (!refresh && token != null && now < expiresAt - EARLY_MS) return@withLock token
        val fresh = withContext(Dispatchers.Main) { fetchFromWebPlayer() }
        if (fresh == null) {
            // No token although the cookie is there: the session was ended on Spotify's side.
            if (refresh) signOutLocally()
            return@withLock null
        }
        token = fresh.first
        expiresAt = fresh.second
        _signedIn.value = true
        fresh.first
    }

    override fun onSignedIn() {
        token = null
        expiresAt = 0L
        _signedIn.value = hasCookie()
    }

    override fun signOut() = signOutLocally()

    private fun signOutLocally() {
        val cookies = CookieManager.getInstance()
        for (name in listOf("sp_dc", "sp_key", "sp_t", "sp_landing", "sp_gaid")) {
            for (host in listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://spotify.com")) {
                cookies.setCookie(host, "$name=; Max-Age=0; Path=/; Domain=.spotify.com")
            }
        }
        cookies.flush()
        token = null
        expiresAt = 0L
        _signedIn.value = false
    }

    private fun hasCookie(): Boolean =
        runCatching { CookieManager.getInstance().getCookie("https://open.spotify.com")?.contains("sp_dc=") == true }.getOrDefault(false)

    /** Opens the web player out of sight and catches its token: (token, expiry in epoch ms), or null. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchFromWebPlayer(): Pair<String, Long>? = withTimeoutOrNull(TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            val web = WebView(context.applicationContext)
            fun finish(result: Pair<String, Long>?) {
                main.post {
                    if (cont.isActive) cont.resume(result)
                    runCatching {
                        web.stopLoading()
                        web.destroy()
                    }
                }
            }
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.userAgentString = SPOTIFY_DESKTOP_AGENT
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    request ?: return null
                    val url = request.url.toString()
                    // The web player's token request: made here with the same address and cookies, its answer read and passed on.
                    if (request.method == "GET" && url.startsWith("https://open.spotify.com/") &&
                        (url.contains("/api/token") || url.contains("/get_access_token"))
                    ) {
                        val body = runCatching { fetch(url, request.requestHeaders) }.getOrNull() ?: return null
                        runCatching { JSONObject(body) }.getOrNull()?.let { json ->
                            val access = json.optString("accessToken")
                            if (access.isNotEmpty() && !json.optBoolean("isAnonymous", false)) {
                                val expiry = json.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + DEFAULT_LIFETIME_MS)
                                finish(access to expiry)
                            } else if (json.optBoolean("isAnonymous", false)) {
                                finish(null)
                            }
                        }
                        return WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(body.toByteArray()))
                    }
                    // A call the player makes with its token is as good (if the token request was missed).
                    val auth = request.requestHeaders.entries.firstOrNull { it.key.equals("authorization", ignoreCase = true) }?.value
                    if (auth != null && auth.startsWith("Bearer ") && request.url.host?.endsWith("spotify.com") == true) {
                        finish(auth.removePrefix("Bearer ") to System.currentTimeMillis() + DEFAULT_LIFETIME_MS)
                    }
                    return null
                }
            }
            cont.invokeOnCancellation { main.post { runCatching { web.destroy() } } }
            web.loadUrl("https://open.spotify.com/")
        }
    }

    private fun fetch(url: String, headers: Map<String, String>): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.setRequestProperty("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
            connection.setRequestProperty("User-Agent", SPOTIFY_DESKTOP_AGENT)
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            if (connection.responseCode in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 25_000L
        const val EARLY_MS = 120_000L
        const val DEFAULT_LIFETIME_MS = 50 * 60_000L
    }
}
