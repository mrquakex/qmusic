package com.qmusic.ui.videos

import com.qmusic.engine.video.VideoCaption
import com.qmusic.engine.video.VideoQuality
import com.qmusic.ui.chat.MovieSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val DESKTOP_SCRAPER_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

private val desktopScraperClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
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

actual suspend fun fetchMovieCatalog(
    source: MovieSource,
    query: String,
    pageUrlOverride: String?,
): Result<MovieCatalogPage> = withContext(Dispatchers.IO) {
    runCatching {
        val target = pageUrlOverride?.takeIf { it.isNotBlank() } ?: MovieHtmlParser.buildSourceUrl(source, query)
        val customHeaders = MovieHtmlParser.parseHeaders(source.headers)
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
            return@runCatching ResolvedMovieStream(
                streams = listOf(VideoQuality(height = 1080, fps = 30, videoUrl = trimmed, audioUrl = null, isLive = false, headers = headers)),
            )
        }
        val (finalUrl, html, ct) = desktopFetch(trimmed, headers, referer = MovieHtmlParser.originOf(trimmed) + "/")
            ?: error("Sayfa açılamadı")
        if (ct.startsWith("video/") || ct.contains("mpegurl") || MovieHtmlParser.isDirectMediaUrl(finalUrl)) {
            return@runCatching ResolvedMovieStream(
                streams = listOf(VideoQuality(height = 1080, fps = 30, videoUrl = finalUrl, audioUrl = null, isLive = false, headers = headers)),
            )
        }
        val streams = ArrayList(MovieHtmlParser.extractStreams(html, finalUrl, headers))
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
                                val streamHeaders = headers + ("Referer" to (MovieHtmlParser.originOf(embedUrl) + "/"))
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

        if (streams.isEmpty()) {
            val embeds = MovieHtmlParser.extractEmbedIframes(html, finalUrl).take(4)
            val found = supervisorScope {
                embeds.map { embedUrl ->
                    async(Dispatchers.IO) {
                        val res = runCatching { desktopFetch(embedUrl, headers, referer = finalUrl) }.getOrNull() ?: return@async emptyList<VideoQuality>()
                        MovieHtmlParser.extractStreams(res.second, res.first, headers)
                    }
                }.awaitAll()
            }
            found.forEach { streams += it }
        }
        val unique = streams.distinctBy { it.videoUrl }
        if (unique.isEmpty()) error("Bu sayfada doğrudan oynatılabilir video akışı bulunamadı")
        ResolvedMovieStream(
            title = MovieHtmlParser.extractTitle(html),
            poster = MovieHtmlParser.extractPoster(html, finalUrl),
            synopsis = MovieHtmlParser.extractSynopsis(html),
            year = MovieHtmlParser.extractYear(html),
            rating = MovieHtmlParser.extractRating(html),
            streams = unique,
            captions = captions.distinctBy { it.url },
        )
    }
}
