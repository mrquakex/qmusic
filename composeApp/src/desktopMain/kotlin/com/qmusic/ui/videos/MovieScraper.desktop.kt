package com.qmusic.ui.videos

import com.qmusic.engine.video.VideoAudioTrack
import com.qmusic.engine.video.VideoCaption
import com.qmusic.engine.video.VideoQuality
import com.qmusic.ui.chat.MovieSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val DESKTOP_SCRAPER_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

private val desktopCookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

private val desktopCookieJar = object : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val current = desktopCookieStore.getOrPut(host) { CopyOnWriteArrayList() }
        cookies.forEach { newCookie ->
            current.removeAll { it.name == newCookie.name }
            current.add(newCookie)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        return desktopCookieStore[host].orEmpty()
    }
}

private val desktopScraperClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .cookieJar(desktopCookieJar)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()
}

private fun desktopFetch(url: String, headers: Map<String, String>, referer: String? = null): Triple<String, String, String>? {
    val req = Request.Builder()
        .url(url)
        .header("User-Agent", headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: DESKTOP_SCRAPER_UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .apply {
            referer?.let { header("Referer", it) }
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
        }
        .build()
    desktopScraperClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val ct = resp.header("Content-Type").orEmpty().lowercase()
        val finalUrl = resp.request.url.toString()
        val body = if (ct.startsWith("video/") || ct.contains("octet-stream")) "" else resp.body?.string().orEmpty()
        return Triple(finalUrl, body, ct)
    }
}

private fun desktopPostForm(url: String, formParams: Map<String, String>, headers: Map<String, String>): Triple<String, String, String>? {
    val form = FormBody.Builder().apply {
        formParams.forEach { (k, v) -> add(k, v) }
    }.build()
    val req = Request.Builder()
        .url(url)
        .post(form)
        .header("User-Agent", headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: DESKTOP_SCRAPER_UA)
        .apply {
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
        }
        .build()
    return runCatching {
        desktopScraperClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val ct = resp.header("Content-Type").orEmpty().lowercase()
            val finalUrl = resp.request.url.toString()
            val body = resp.body?.string().orEmpty()
            Triple(finalUrl, body, ct)
        }
    }.getOrNull()
}

private fun parseHexBytes(s: String): ByteArray {
    val len = s.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        val h = Character.digit(s[i], 16)
        val l = Character.digit(s[i + 1], 16)
        data[i / 2] = ((h shl 4) or l).toByte()
        i += 2
    }
    return data
}

private fun decryptCryptoJsAes(password: String, jsonStr: String): String? = runCatching {
    val ct = Regex(""""ct"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1) ?: return null
    val ivHex = Regex(""""iv"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1) ?: return null
    val sHex = Regex(""""s"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1) ?: return null
    val salt = parseHexBytes(sHex)
    val iv = parseHexBytes(ivHex)
    val cipherBytes = Base64.getDecoder().decode(ct)

    val md = MessageDigest.getInstance("MD5")
    var d = ByteArray(0)
    var keyAndIv = ByteArray(0)
    val passBytes = password.toByteArray(Charsets.UTF_8)
    while (keyAndIv.size < 48) {
        md.reset()
        md.update(d)
        md.update(passBytes)
        md.update(salt)
        d = md.digest()
        val next = ByteArray(keyAndIv.size + d.size)
        System.arraycopy(keyAndIv, 0, next, 0, keyAndIv.size)
        System.arraycopy(d, 0, next, keyAndIv.size, d.size)
        keyAndIv = next
    }
    val key = ByteArray(32)
    System.arraycopy(keyAndIv, 0, key, 0, 32)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    String(cipher.doFinal(cipherBytes), Charsets.UTF_8).replace("\\/", "/")
}.getOrNull()

private data class DesktopHlsExpansion(
    val streams: List<VideoQuality>,
    val audioTracks: List<VideoAudioTrack> = emptyList(),
    val defaultAudioTrackId: String? = null,
)

/** Generic resolver's HTTP hook, backed by the desktop OkHttp client. */
private val desktopScraperFetch: ScraperFetcher = { url, headers, referer ->
    runCatching { desktopFetch(url, headers, referer) }.getOrNull()
        ?.let { (finalUrl, body, ct) -> ScraperResponse(finalUrl, body, ct) }
}

private suspend fun expandHlsMaster(streams: List<VideoQuality>): DesktopHlsExpansion {
    val r = expandGenericHls(streams, desktopScraperFetch)
    return DesktopHlsExpansion(r.streams, r.audioTracks, r.defaultAudioTrackId)
}

actual suspend fun fetchMovieCatalog(
    source: MovieSource,
    query: String,
    pageUrlOverride: String?,
): Result<MovieCatalogPage> = withContext(Dispatchers.IO) {
    runCatching {
        val q = query.trim()
        val customHeaders = MovieHtmlParser.parseHeaders(source.headers)

        // Filmmodu Live Search API (POST /arama/oneri)
        if (q.isNotEmpty() && pageUrlOverride.isNullOrBlank() && (source.baseUrl.contains("filmmodu", ignoreCase = true) || source.searchPath.contains("arama/oneri", ignoreCase = true))) {
            val origin = source.normalizedBaseUrl
            val homeFetched = desktopFetch(origin, customHeaders, referer = "$origin/")
            val csrf = homeFetched?.second?.let { MovieHtmlParser.extractCsrfToken(it) }
            val postHeaders = customHeaders + mapOf(
                "Referer" to "$origin/",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json",
            ) + (csrf?.let { mapOf("X-CSRF-TOKEN" to it) } ?: emptyMap())
            val res = desktopPostForm("$origin/arama/oneri", mapOf("q" to q), postHeaders)
            if (res != null && res.second.contains("films")) {
                val items = MovieHtmlParser.parseFilmmoduSearchJson(res.second, source.name, customHeaders)
                return@runCatching MovieCatalogPage(items = items)
            }
        }

        val target = pageUrlOverride?.takeIf { it.isNotBlank() } ?: MovieHtmlParser.buildSourceUrl(source, query)
        val (finalUrl, html, _) = desktopFetch(target, customHeaders, referer = source.normalizedBaseUrl + "/")
            ?: error("Kaynak yanıt vermedi")
        MovieHtmlParser.extractCatalogPage(html, finalUrl, source.name, customHeaders)
    }
}

actual suspend fun resolveExternalMovie(url: String, headers: Map<String, String>): Result<ResolvedMovieStream> = withContext(Dispatchers.IO) {
    runCatching {
        val trimmed = url.trim()
        require(trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) { "Geçersiz bağlantı" }
        if (MovieHtmlParser.isDirectMediaUrl(trimmed)) {
            val expanded = expandHlsMaster(
                listOf(VideoQuality(height = 1080, fps = 30, videoUrl = trimmed, audioUrl = null, isLive = false, headers = headers)),
            )
            return@runCatching ResolvedMovieStream(
                streams = expanded.streams,
                audioTracks = expanded.audioTracks,
                defaultAudioTrackId = expanded.defaultAudioTrackId,
            )
        }
        val (finalUrl, html, ct) = desktopFetch(trimmed, headers, referer = MovieHtmlParser.originOf(trimmed) + "/")
            ?: error("Sayfa açılamadı")
        if (ct.startsWith("video/") || ct.contains("mpegurl") || MovieHtmlParser.isDirectMediaUrl(finalUrl)) {
            val expanded = expandHlsMaster(
                listOf(VideoQuality(height = 1080, fps = 30, videoUrl = finalUrl, audioUrl = null, isLive = false, headers = headers)),
            )
            return@runCatching ResolvedMovieStream(
                streams = expanded.streams,
                audioTracks = expanded.audioTracks,
                defaultAudioTrackId = expanded.defaultAudioTrackId,
            )
        }
        val streams = ArrayList<VideoQuality>()
        val captions = ArrayList<VideoCaption>(MovieHtmlParser.extractCaptions(html, finalUrl))

        // Layer 1.5: Filmmodu / Vidmixi backend API resolution (sub-second pure HTTP, zero WebView, 1080p master HLS).
        val dataK = MovieHtmlParser.extractFilmmoduDataK(html)
        if (dataK != null) {
            val csrf = MovieHtmlParser.extractCsrfToken(html)
            val origin = MovieHtmlParser.originOf(finalUrl)
            val pxHeaders = headers + mapOf(
                "Referer" to finalUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json",
            ) + (csrf?.let { mapOf("X-CSRF-TOKEN" to it) } ?: emptyMap())
            val pxRes = desktopPostForm("$origin/px", mapOf("i" to dataK.first.toString(), "s" to dataK.second), pxHeaders)
            val embedUrl = pxRes?.second?.let { b ->
                Regex(""""u"\s*:\s*"([^"]+)"""").find(b)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
            }
            if (embedUrl != null) {
                val embedFetched = desktopFetch(embedUrl, headers, referer = "$origin/")
                if (embedFetched != null) {
                    val beArgs = MovieHtmlParser.extractBePlayerArgs(embedFetched.second)
                    if (beArgs != null) {
                        val decrypted = decryptCryptoJsAes(beArgs.first, beArgs.second)
                        if (decrypted != null) {
                            val streamLoc = Regex(""""video_location"\s*:\s*"([^"]+)"""").find(decrypted)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                            if (streamLoc != null) {
                                val embedOrigin = MovieHtmlParser.originOf(embedUrl)
                                val streamHeaders = headers + mapOf(
                                    "Referer" to embedUrl,
                                    "Origin" to embedOrigin,
                                    "User-Agent" to DESKTOP_SCRAPER_UA,
                                )
                                val vidQual = VideoQuality(
                                    height = 1080,
                                    fps = 30,
                                    videoUrl = streamLoc,
                                    audioUrl = null,
                                    isLive = false,
                                    headers = streamHeaders,
                                )
                                streams += vidQual
                            }
                            Regex("""\{"file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)".*?"language"\s*:\s*"([^"]+)"""")
                                .findAll(decrypted).forEach { sm ->
                                    val f = sm.groupValues[1].replace("\\/", "/").trim()
                                    if (f.isNotEmpty()) {
                                        val subUrl = MovieHtmlParser.resolveUrl(embedUrl, f) ?: f
                                        val lbl = sm.groupValues[2].ifBlank { "TR" }
                                        val lng = sm.groupValues[3].ifBlank { "tr" }
                                        captions += VideoCaption(language = lng, label = lbl, url = subUrl, auto = false, mimeType = "text/vtt")
                                    }
                                }
                        }
                    }
                }
            }
        }

        // Layer 1.6: Peacemaker (Dizimom / Dizidad) backend API resolution (direct Twitter/CDN 1080p HLS).
        if (streams.isEmpty()) {
            var peacemakerHash = MovieHtmlParser.extractPeacemakerHash(html, finalUrl)
            if (peacemakerHash == null) {
                val firstEp = MovieHtmlParser.extractDizidadFirstEpisode(html)
                if (firstEp != null) {
                    val epFetched = desktopFetch(firstEp, headers, referer = finalUrl)
                    if (epFetched != null) {
                        peacemakerHash = MovieHtmlParser.extractPeacemakerHash(epFetched.second, epFetched.first)
                    }
                }
            }
            if (peacemakerHash != null) {
                val pmApiUrl = "https://peacemakerst.com/tv/video/$peacemakerHash?do=getVideo"
                val pmHeaders = headers + mapOf(
                    "Referer" to "https://peacemakerst.com/tv/video/$peacemakerHash",
                    "X-Requested-With" to "XMLHttpRequest",
                )
                val pmRes = desktopPostForm(pmApiUrl, mapOf("hash" to peacemakerHash, "r" to "https://www.dizidad.com/", "s" to ""), pmHeaders)
                val streamUrl = pmRes?.second?.let { MovieHtmlParser.extractPeacemakerStreamUrl(it) }
                if (streamUrl != null) {
                    streams += VideoQuality(
                        height = 1080,
                        fps = 30,
                        videoUrl = streamUrl,
                        audioUrl = null,
                        isLive = false,
                        headers = headers,
                    )
                }
            }
        }

        // Generic HLS + iframe resolver (any source): parallel multi-hop embeds, then the page's own streams.
        if (streams.isEmpty()) {
            val generic = resolveGenericStreams(finalUrl, html, headers, desktopScraperFetch, expandHls = false)
            streams += generic.streams
            captions += generic.captions
        }
        val expanded = expandHlsMaster(streams.distinctBy { it.videoUrl })
        if (expanded.streams.isEmpty()) error("Bu sayfada doğrudan oynatılabilir video akışı bulunamadı")
        ResolvedMovieStream(
            title = MovieHtmlParser.extractTitle(html),
            poster = MovieHtmlParser.extractPoster(html, finalUrl),
            synopsis = MovieHtmlParser.extractSynopsis(html),
            year = MovieHtmlParser.extractYear(html),
            rating = MovieHtmlParser.extractRating(html),
            streams = expanded.streams,
            captions = captions.distinctBy { it.url },
            audioTracks = expanded.audioTracks,
            defaultAudioTrackId = expanded.defaultAudioTrackId,
        )
    }
}
