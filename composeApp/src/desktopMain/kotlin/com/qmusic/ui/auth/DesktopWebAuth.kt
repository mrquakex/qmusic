package com.qmusic.ui.auth

import com.qmusic.engine.auth.YouTubeAuth
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

object DesktopWebAuth {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun findChromiumExecutable(): File? {
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val programFiles = System.getenv("ProgramFiles").orEmpty()
        val programFilesX86 = System.getenv("ProgramFiles(x86)").orEmpty()

        val candidates = listOf(
            File(programFilesX86, "Microsoft\\Edge\\Application\\msedge.exe"),
            File(programFiles, "Microsoft\\Edge\\Application\\msedge.exe"),
            File(localAppData, "Microsoft\\Edge\\Application\\msedge.exe"),
            File(programFiles, "Google\\Chrome\\Application\\chrome.exe"),
            File(programFilesX86, "Google\\Chrome\\Application\\chrome.exe"),
            File(localAppData, "Google\\Chrome\\Application\\chrome.exe"),
            File(programFiles, "BraveSoftware\\Brave-Browser\\Application\\brave.exe"),
            File(localAppData, "BraveSoftware\\Brave-Browser\\Application\\brave.exe"),
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Opens a standalone app-mode Chromium login window for YouTube Music (`accounts.google.com` -> `music.youtube.com`),
     * automatically captures the `.youtube.com` session cookies once the user signs in, and closes the login window.
     */
    suspend fun signInYouTubeMusic(
        onWindowClosedByUser: () -> Unit,
        onError: (String) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val browserExe = findChromiumExecutable()
        if (browserExe == null) {
            onError("Bilgisayarınızda Microsoft Edge veya Google Chrome bulunamadı.")
            return@withContext null
        }

        val port = findFreePort()
        val profileDir = File(System.getProperty("user.home"), ".qmusic\\auth_profile_yt").apply { mkdirs() }
        val loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"

        val process = ProcessBuilder(
            browserExe.absolutePath,
            "--app=$loginUrl",
            "--user-data-dir=${profileDir.absolutePath}",
            "--remote-debugging-port=$port",
            "--remote-allow-origins=*",
            "--window-size=520,700",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
        ).redirectErrorStream(true).start()

        try {
            var settledCount = 0
            while (isActive) {
                delay(550)
                if (!process.isAlive) {
                    onWindowClosedByUser()
                    return@withContext null
                }

                val targets = fetchTargets(port) ?: continue
                val ytTarget = targets.firstOrNull { t ->
                    t.type == "page" && t.url.startsWith("https://music.youtube.com")
                } ?: continue

                val wsUrl = ytTarget.webSocketDebuggerUrl ?: continue
                val cookies = fetchCookiesViaCdp(wsUrl, domainFilter = "youtube.com") ?: continue
                val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val sapisid = YouTubeAuth.parseSapisid(cookieString)
                val hasSessionCookie = cookies.containsKey("LOGIN_INFO") ||
                    cookies.containsKey("SID") ||
                    cookies.containsKey("__Secure-1PSID") ||
                    cookies.containsKey("__Secure-3PSID")

                if (sapisid != null && hasSessionCookie) {
                    settledCount++
                    // Wait one extra tick on music.youtube.com so all post-login cookies settle
                    if (settledCount >= 2) {
                        closeBrowserViaCdp(wsUrl)
                        runCatching { process.destroy() }
                        return@withContext cookieString
                    }
                }
            }
            null
        } catch (e: CancellationException) {
            runCatching { process.destroy() }
            throw e
        } catch (e: Throwable) {
            runCatching { process.destroy() }
            onError(e.message ?: "Oturum açma sırasında bir hata oluştu.")
            null
        }
    }

    /**
     * Opens a standalone app-mode Chromium login window for Spotify (`accounts.spotify.com` -> `open.spotify.com`),
     * waits for `sp_dc` cookie + web player token, and closes the login window.
     */
    suspend fun signInSpotify(
        onWindowClosedByUser: () -> Unit,
        onError: (String) -> Unit,
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val browserExe = findChromiumExecutable()
        if (browserExe == null) {
            onError("Bilgisayarınızda Microsoft Edge veya Google Chrome bulunamadı.")
            return@withContext null
        }

        val port = findFreePort()
        val profileDir = File(System.getProperty("user.home"), ".qmusic\\auth_profile_spotify").apply { mkdirs() }
        val loginUrl = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

        val process = ProcessBuilder(
            browserExe.absolutePath,
            "--app=$loginUrl",
            "--user-data-dir=${profileDir.absolutePath}",
            "--remote-debugging-port=$port",
            "--remote-allow-origins=*",
            "--window-size=520,700",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
        ).redirectErrorStream(true).start()

        try {
            var settledCount = 0
            while (isActive) {
                delay(550)
                if (!process.isAlive) {
                    onWindowClosedByUser()
                    return@withContext null
                }

                val targets = fetchTargets(port) ?: continue
                val pageTarget = targets.firstOrNull { t ->
                    t.type == "page" && t.url.startsWith("https://open.spotify.com")
                } ?: targets.firstOrNull { it.type == "page" } ?: continue

                val wsUrl = pageTarget.webSocketDebuggerUrl ?: continue
                val cookies = fetchCookiesViaCdp(wsUrl, domainFilter = "spotify.com") ?: continue
                if (!cookies.containsKey("sp_dc")) continue

                settledCount++
                if (settledCount >= 2) {
                    closeBrowserViaCdp(wsUrl)
                    runCatching { process.destroy() }
                    return@withContext cookies
                }
            }
            null
        } catch (e: CancellationException) {
            runCatching { process.destroy() }
            throw e
        } catch (e: Throwable) {
            runCatching { process.destroy() }
            onError(e.message ?: "Spotify oturumu açılırken bir hata oluştu.")
            null
        }
    }

    /**
     * Fetches the Spotify Web Player access token (`Bearer ...`) in the background using the signed-in
     * `~/.qmusic/auth_profile_spotify` profile in headless mode.
     */
    suspend fun fetchSpotifyWebPlayerToken(): Pair<String, Long>? = withContext(Dispatchers.IO) {
        val browserExe = findChromiumExecutable() ?: return@withContext null
        val profileDir = File(System.getProperty("user.home"), ".qmusic\\auth_profile_spotify")
        if (!profileDir.exists()) return@withContext null

        val port = findFreePort()
        val process = ProcessBuilder(
            browserExe.absolutePath,
            "--headless=new",
            "--disable-gpu",
            "--user-data-dir=${profileDir.absolutePath}",
            "--remote-debugging-port=$port",
            "--remote-allow-origins=*",
            "--no-first-run",
            "--no-default-browser-check",
            "https://open.spotify.com/",
        ).redirectErrorStream(true).start()

        try {
            withTimeoutOrNull(20_000L) {
                while (isActive) {
                    delay(400)
                    val targets = fetchTargets(port) ?: continue
                    val pageTarget = targets.firstOrNull { it.type == "page" && it.url.contains("spotify.com") } ?: continue
                    val wsUrl = pageTarget.webSocketDebuggerUrl ?: continue
                    val tokenPair = interceptSpotifyTokenViaCdp(wsUrl)
                    if (tokenPair != null) {
                        closeBrowserViaCdp(wsUrl)
                        runCatching { process.destroy() }
                        return@withTimeoutOrNull tokenPair
                    }
                }
                null
            }
        } finally {
            runCatching { process.destroy() }
        }
    }

    private suspend fun interceptSpotifyTokenViaCdp(wsUrl: String): Pair<String, Long>? =
        withTimeoutOrNull(12_000L) {
            suspendCancellableCoroutine { cont ->
                val req = Request.Builder().url(wsUrl).build()
                val ws = httpClient.newWebSocket(
                    req,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"id":1,"method":"Network.enable"}""")
                            webSocket.send("""{"id":2,"method":"Page.reload"}""")
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            runCatching {
                                val root = json.parseToJsonElement(text).jsonObject
                                val method = root["method"]?.jsonPrimitive?.content
                                if (method == "Network.requestWillBeSent") {
                                    val params = root["params"]?.jsonObject
                                    val reqObj = params?.get("request")?.jsonObject
                                    val url = reqObj?.get("url")?.jsonPrimitive?.content.orEmpty()
                                    val headers = reqObj?.get("headers")?.jsonObject
                                    val auth = headers?.entries?.firstOrNull {
                                        it.key.equals("Authorization", ignoreCase = true)
                                    }?.value?.jsonPrimitive?.content
                                    if (auth != null && auth.startsWith("Bearer ") && url.contains("spotify.com")) {
                                        val token = auth.removePrefix("Bearer ").trim()
                                        if (token.length > 40) {
                                            webSocket.close(1000, null)
                                            if (cont.isActive) {
                                                cont.resume(token to (System.currentTimeMillis() + 50 * 60_000L))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
                cont.invokeOnCancellation { runCatching { ws.cancel() } }
            }
        }

    private data class CdpTarget(
        val type: String,
        val url: String,
        val webSocketDebuggerUrl: String?,
    )

    private fun fetchTargets(port: Int): List<CdpTarget>? = runCatching {
        val req = Request.Builder().url("http://127.0.0.1:$port/json").get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val arr = json.parseToJsonElement(body).jsonArray
            arr.map { el ->
                val obj = el.jsonObject
                CdpTarget(
                    type = obj["type"]?.jsonPrimitive?.content.orEmpty(),
                    url = obj["url"]?.jsonPrimitive?.content.orEmpty(),
                    webSocketDebuggerUrl = obj["webSocketDebuggerUrl"]?.jsonPrimitive?.content,
                )
            }
        }
    }.getOrNull()

    private suspend fun fetchCookiesViaCdp(wsUrl: String, domainFilter: String): Map<String, String>? =
        withTimeoutOrNull(2500L) {
            suspendCancellableCoroutine { cont ->
                val req = Request.Builder().url(wsUrl).build()
                val ws = httpClient.newWebSocket(
                    req,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"id":1,"method":"Network.getAllCookies"}""")
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            runCatching {
                                val root = json.parseToJsonElement(text).jsonObject
                                if (root["id"]?.jsonPrimitive?.content == "1") {
                                    val cookiesArr = root["result"]?.jsonObject?.get("cookies")?.jsonArray
                                    val map = LinkedHashMap<String, String>()
                                    cookiesArr?.forEach { item ->
                                        val c = item.jsonObject
                                        val domain = c["domain"]?.jsonPrimitive?.content.orEmpty()
                                        val name = c["name"]?.jsonPrimitive?.content.orEmpty()
                                        val value = c["value"]?.jsonPrimitive?.content.orEmpty()
                                        if (domain.contains(domainFilter, ignoreCase = true) && name.isNotBlank()) {
                                            map[name] = value
                                        }
                                    }
                                    webSocket.close(1000, null)
                                    if (cont.isActive) cont.resume(map)
                                }
                            }.onFailure {
                                webSocket.close(1000, null)
                                if (cont.isActive) cont.resume(null)
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
                cont.invokeOnCancellation { runCatching { ws.cancel() } }
            }
        }

    private suspend fun closeBrowserViaCdp(wsUrl: String) {
        withTimeoutOrNull(1000L) {
            suspendCancellableCoroutine { cont ->
                val req = Request.Builder().url(wsUrl).build()
                val ws = httpClient.newWebSocket(
                    req,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"id":99,"method":"Browser.close"}""")
                            webSocket.close(1000, null)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    },
                )
                cont.invokeOnCancellation { runCatching { ws.cancel() } }
            }
        }
    }
}
