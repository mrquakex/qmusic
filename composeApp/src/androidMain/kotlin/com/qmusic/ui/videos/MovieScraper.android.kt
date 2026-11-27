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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val SCRAPER_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

private const val CATALOG_TTL_MS = 5 * 60_000L
private const val STREAM_TTL_MS = 15 * 60_000L

private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

private val scraperCookieJar = object : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val current = cookieStore.getOrPut(host) { CopyOnWriteArrayList() }
        cookies.forEach { newCookie ->
            current.removeAll { it.name == newCookie.name }
            current.add(newCookie)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        return cookieStore[host].orEmpty()
    }
}

private val scraperClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .cookieJar(scraperCookieJar)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

private data class TimedEntry<T>(val at: Long, val value: T)

private val catalogCache = ConcurrentHashMap<String, TimedEntry<MovieCatalogPage>>()
private val streamCache = ConcurrentHashMap<String, TimedEntry<ResolvedMovieStream>>()

private data class HttpFetchResult(val finalUrl: String, val body: String, val contentType: String)

private fun fetchTextSync(url: String, headers: Map<String, String>, referer: String? = null): HttpFetchResult? {
    val req = Request.Builder()
        .url(url)
        .header("User-Agent", headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: SCRAPER_UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
        .apply {
            referer?.let { header("Referer", it) }
            headers.forEach { (k, v) ->
                if (!k.equals("User-Agent", true)) header(k, v)
            }
        }
        .build()
    scraperClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val ct = resp.header("Content-Type").orEmpty().lowercase()
        val finalUrl = resp.request.url.toString()
        if (ct.startsWith("video/") || ct.contains("octet-stream")) {
            return HttpFetchResult(finalUrl, "", ct)
        }
        val body = resp.body?.string().orEmpty()
        return HttpFetchResult(finalUrl, body, ct)
    }
}

private fun postFormSync(url: String, formParams: Map<String, String>, headers: Map<String, String>): HttpFetchResult? {
    val form = FormBody.Builder().apply {
        formParams.forEach { (k, v) -> add(k, v) }
    }.build()
    val req = Request.Builder()
        .url(url)
        .post(form)
        .header("User-Agent", headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: SCRAPER_UA)
        .apply {
            headers.forEach { (k, v) ->
                if (!k.equals("User-Agent", true)) header(k, v)
            }
        }
        .build()
    return runCatching {
        scraperClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val ct = resp.header("Content-Type").orEmpty().lowercase()
            val finalUrl = resp.request.url.toString()
            val body = resp.body?.string().orEmpty()
            HttpFetchResult(finalUrl, body, ct)
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
    decryptWithKey(password, jsonStr)
        ?: runCatching {
            val decoded = String(android.util.Base64.decode(password, android.util.Base64.DEFAULT), Charsets.UTF_8)
            decryptWithKey(decoded, jsonStr)
        }.getOrNull()
}.getOrNull()

private fun decryptWithKey(password: String, jsonStr: String): String? = runCatching {
    val ct = Regex(""""ct"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1) ?: return null
    val ivHex = Regex(""""iv"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1) ?: return null
    val sHex = Regex(""""s"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1) ?: return null
    val salt = parseHexBytes(sHex)
    val iv = parseHexBytes(ivHex)
    val cipherBytes = android.util.Base64.decode(ct, android.util.Base64.DEFAULT)

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

private data class HlsExpansion(
    val streams: List<VideoQuality>,
    val audioTracks: List<VideoAudioTrack> = emptyList(),
    val defaultAudioTrackId: String? = null,
)

/** Generic resolver's HTTP hook, backed by the shared OkHttp client (cookies, UA, Accept-Language). */
private val scraperFetch: ScraperFetcher = { url, headers, referer ->
    runCatching { fetchTextSync(url, headers, referer) }.getOrNull()
        ?.let { ScraperResponse(it.finalUrl, it.body, it.contentType) }
}

private suspend fun expandHlsMaster(streams: List<VideoQuality>): HlsExpansion {
    val r = expandGenericHls(streams, scraperFetch)
    return HlsExpansion(r.streams, r.audioTracks, r.defaultAudioTrackId)
}

actual suspend fun fetchMovieCatalog(
    source: MovieSource,
    query: String,
    pageUrlOverride: String?,
): Result<MovieCatalogPage> = withContext(Dispatchers.IO) {
    runCatching {
        val q = query.trim()
        val customHeaders = MovieHtmlParser.parseHeaders(source.headers)
        val now = System.currentTimeMillis()

        // Filmmodu Live Search API (POST /arama/oneri)
        if (q.isNotEmpty() && pageUrlOverride.isNullOrBlank() && (source.baseUrl.contains("filmmodu", ignoreCase = true) || source.searchPath.contains("arama/oneri", ignoreCase = true))) {
            val origin = source.normalizedBaseUrl
            val homeFetched = fetchTextSync(origin, customHeaders, referer = "$origin/")
            val csrf = homeFetched?.body?.let { MovieHtmlParser.extractCsrfToken(it) }
            val postHeaders = customHeaders + mapOf(
                "Referer" to "$origin/",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json",
            ) + (csrf?.let { mapOf("X-CSRF-TOKEN" to it) } ?: emptyMap())
            val res = postFormSync("$origin/arama/oneri", mapOf("q" to q), postHeaders)
            if (res != null && res.body.contains("films")) {
                val items = MovieHtmlParser.parseFilmmoduSearchJson(res.body, source.name, customHeaders)
                return@runCatching MovieCatalogPage(items = items)
            }
        }

        val target = pageUrlOverride?.takeIf { it.isNotBlank() } ?: MovieHtmlParser.buildSourceUrl(source, query)
        catalogCache[target]?.takeIf { now - it.at < CATALOG_TTL_MS }?.let { return@runCatching it.value }

        val fetched = fetchTextSync(target, customHeaders, referer = source.normalizedBaseUrl + "/")
            ?: error("Kaynak yanıt vermedi")
        val page = MovieHtmlParser.extractCatalogPage(fetched.body, fetched.finalUrl, source.name, customHeaders)
        if (catalogCache.size > 60) catalogCache.clear()
        catalogCache[target] = TimedEntry(now, page)
        page
    }
}

actual suspend fun resolveExternalMovie(url: String, headers: Map<String, String>): Result<ResolvedMovieStream> = withContext(Dispatchers.IO) {
    runCatching {
        val trimmed = url.trim()
        require(trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) { "Geçersiz bağlantı" }

        val now = System.currentTimeMillis()
        streamCache[trimmed]?.takeIf { now - it.at < STREAM_TTL_MS }?.let { return@runCatching it.value }

        // Layer 1: Direct playable media stream (.m3u8, .mpd, .mp4, .mkv, .webm).
        if (MovieHtmlParser.isDirectMediaUrl(trimmed)) {
            val h = Regex("""(?i)(2160|1440|1080|720|480|360)p""").find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: if (trimmed.contains(".m3u8", true) || trimmed.contains(".mpd", true)) 1080 else 720
            val initial = listOf(VideoQuality(height = h, fps = 30, videoUrl = trimmed, audioUrl = null, isLive = false, headers = headers))
            val expanded = expandHlsMaster(initial)
            val resolved = ResolvedMovieStream(
                streams = expanded.streams,
                audioTracks = expanded.audioTracks,
                defaultAudioTrackId = expanded.defaultAudioTrackId,
            )
            streamCache[trimmed] = TimedEntry(now, resolved)
            return@runCatching resolved
        }

        // Layer 2: Pure HTTP HTML + JS Unpacker + Base64 + Parallel Multi-Hop Embed Resolver (zero WebView).
        val fetched = fetchTextSync(trimmed, headers, referer = MovieHtmlParser.originOf(trimmed) + "/")
            ?: error("Sayfa açılamadı")

        // Server redirected straight to a video stream or HLS manifest.
        if (fetched.contentType.startsWith("video/") || fetched.contentType.contains("mpegurl") || fetched.contentType.contains("dash+xml") || MovieHtmlParser.isDirectMediaUrl(fetched.finalUrl)) {
            val initial = listOf(VideoQuality(height = 1080, fps = 30, videoUrl = fetched.finalUrl, audioUrl = null, isLive = false, headers = headers))
            val expanded = expandHlsMaster(initial)
            val resolved = ResolvedMovieStream(
                streams = expanded.streams,
                audioTracks = expanded.audioTracks,
                defaultAudioTrackId = expanded.defaultAudioTrackId,
            )
            streamCache[trimmed] = TimedEntry(now, resolved)
            return@runCatching resolved
        }

        val html = fetched.body
        val finalUrl = fetched.finalUrl
        val pageTitle = MovieHtmlParser.extractTitle(html)
        val pagePoster = MovieHtmlParser.extractPoster(html, finalUrl)
        val pageSynopsis = MovieHtmlParser.extractSynopsis(html)
        val pageYear = MovieHtmlParser.extractYear(html)
        val pageRating = MovieHtmlParser.extractRating(html)

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
            val pxRes = postFormSync("$origin/px", mapOf("i" to dataK.first.toString(), "s" to dataK.second), pxHeaders)
            val embedUrl = pxRes?.body?.let { b ->
                Regex(""""u"\s*:\s*"([^"]+)"""").find(b)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
            }
            if (embedUrl != null) {
                val embedFetched = fetchTextSync(embedUrl, headers, referer = "$origin/")
                if (embedFetched != null) {
                    val beArgs = MovieHtmlParser.extractBePlayerArgs(embedFetched.body)
                    if (beArgs != null) {
                        val decrypted = decryptCryptoJsAes(beArgs.first, beArgs.second)
                        if (decrypted != null) {
                            val streamLoc = Regex(""""video_location"\s*:\s*"([^"]+)"""").find(decrypted)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                            if (streamLoc != null) {
                                val embedOrigin = MovieHtmlParser.originOf(embedUrl)
                                val streamHeaders = headers + mapOf(
                                    "Referer" to embedUrl,
                                    "Origin" to embedOrigin,
                                    "User-Agent" to SCRAPER_UA,
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
                    val epFetched = fetchTextSync(firstEp, headers, referer = finalUrl)
                    if (epFetched != null) {
                        peacemakerHash = MovieHtmlParser.extractPeacemakerHash(epFetched.body, epFetched.finalUrl)
                    }
                }
            }
            if (peacemakerHash != null) {
                val pmApiUrl = "https://peacemakerst.com/tv/video/$peacemakerHash?do=getVideo"
                val pmHeaders = headers + mapOf(
                    "Referer" to "https://peacemakerst.com/tv/video/$peacemakerHash",
                    "X-Requested-With" to "XMLHttpRequest",
                )
                val pmRes = postFormSync(pmApiUrl, mapOf("hash" to peacemakerHash, "r" to "https://www.dizidad.com/", "s" to ""), pmHeaders)
                val streamUrl = pmRes?.body?.let { MovieHtmlParser.extractPeacemakerStreamUrl(it) }
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
            val generic = resolveGenericStreams(finalUrl, html, headers, scraperFetch, expandHls = false)
            streams += generic.streams
            captions += generic.captions
        }

        val expanded = expandHlsMaster(streams.distinctBy { it.videoUrl })
        if (expanded.streams.isEmpty()) error("Bu sayfada doğrudan oynatılabilir video akışı bulunamadı")

        val resolved = ResolvedMovieStream(
            title = pageTitle,
            poster = pagePoster,
            synopsis = pageSynopsis,
            year = pageYear,
            rating = pageRating,
            streams = expanded.streams,
            captions = captions.distinctBy { it.url },
            audioTracks = expanded.audioTracks,
            defaultAudioTrackId = expanded.defaultAudioTrackId,
        )
        if (streamCache.size > 100) streamCache.clear()
        streamCache[trimmed] = TimedEntry(now, resolved)
        resolved
    }
}
