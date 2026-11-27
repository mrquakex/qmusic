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
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
