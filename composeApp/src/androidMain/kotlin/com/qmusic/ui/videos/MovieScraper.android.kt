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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val SCRAPER_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

private const val CATALOG_TTL_MS = 5 * 60_000L
private const val STREAM_TTL_MS = 15 * 60_000L

private val scraperClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
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

private fun expandHlsQualitiesIfMaster(streams: List<VideoQuality>): List<VideoQuality> {
    val master = streams.firstOrNull { it.videoUrl.contains(".m3u8", ignoreCase = true) } ?: return streams
    val fetched = runCatching { fetchTextSync(master.videoUrl, master.headers, referer = master.headers["Referer"]) }.getOrNull()
        ?: return streams
    val variants = MovieHtmlParser.parseHlsVariants(fetched.body, fetched.finalUrl, master.headers)
    if (variants.isEmpty()) return streams
    val rest = streams.filterNot { it.videoUrl == master.videoUrl }
    return (variants + rest).distinctBy { it.videoUrl }
}

actual suspend fun fetchMovieCatalog(
    source: MovieSource,
    query: String,
    pageUrlOverride: String?,
): Result<MovieCatalogPage> = withContext(Dispatchers.IO) {
    runCatching {
        val target = pageUrlOverride?.takeIf { it.isNotBlank() } ?: MovieHtmlParser.buildSourceUrl(source, query)
        val now = System.currentTimeMillis()
        catalogCache[target]?.takeIf { now - it.at < CATALOG_TTL_MS }?.let { return@runCatching it.value }

        val customHeaders = MovieHtmlParser.parseHeaders(source.headers)
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
            val resolved = ResolvedMovieStream(streams = expandHlsQualitiesIfMaster(initial))
            streamCache[trimmed] = TimedEntry(now, resolved)
            return@runCatching resolved
        }

        // Layer 2: Pure HTTP HTML + JS Unpacker + Base64 + Parallel Multi-Hop Embed Resolver (zero WebView).
        val fetched = fetchTextSync(trimmed, headers, referer = MovieHtmlParser.originOf(trimmed) + "/")
            ?: error("Sayfa açılamadı")

        // Server redirected straight to a video stream or HLS manifest.
        if (fetched.contentType.startsWith("video/") || fetched.contentType.contains("mpegurl") || fetched.contentType.contains("dash+xml") || MovieHtmlParser.isDirectMediaUrl(fetched.finalUrl)) {
            val initial = listOf(VideoQuality(height = 1080, fps = 30, videoUrl = fetched.finalUrl, audioUrl = null, isLive = false, headers = headers))
            val resolved = ResolvedMovieStream(streams = expandHlsQualitiesIfMaster(initial))
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
        streams += MovieHtmlParser.extractStreams(html, finalUrl, headers)

        // Probe top candidate iframe embeds IN PARALLEL for sub-second resolution.
        if (streams.isEmpty()) {
            val hop1 = MovieHtmlParser.extractEmbedIframes(html, finalUrl).take(4)
            if (hop1.isNotEmpty()) {
                val results = supervisorScope {
                    hop1.map { embedUrl ->
                        async(Dispatchers.IO) {
                            resolveEmbedHop(embedUrl, finalUrl, headers, depth = 0)
                        }
                    }.awaitAll()
                }
                results.forEach { (foundStreams, foundCaps) ->
                    streams += foundStreams
                    captions += foundCaps
                }
            }
        }

        val uniqueStreams = expandHlsQualitiesIfMaster(streams.distinctBy { it.videoUrl })
        if (uniqueStreams.isEmpty()) error("Bu sayfada doğrudan oynatılabilir video akışı bulunamadı")

        val resolved = ResolvedMovieStream(
            title = pageTitle,
            poster = pagePoster,
            synopsis = pageSynopsis,
            year = pageYear,
            rating = pageRating,
            streams = uniqueStreams,
            captions = captions.distinctBy { it.url },
        )
        if (streamCache.size > 100) streamCache.clear()
        streamCache[trimmed] = TimedEntry(now, resolved)
        resolved
    }
}

private suspend fun resolveEmbedHop(
    embedUrl: String,
    parentUrl: String,
    headers: Map<String, String>,
    depth: Int,
): Pair<List<VideoQuality>, List<VideoCaption>> {
    val fetched = runCatching { fetchTextSync(embedUrl, headers, referer = parentUrl) }.getOrNull()
        ?: return emptyList<VideoQuality>() to emptyList()
    if (fetched.contentType.startsWith("video/") || fetched.contentType.contains("mpegurl") || MovieHtmlParser.isDirectMediaUrl(fetched.finalUrl)) {
        val hdrs = headers + ("Referer" to (MovieHtmlParser.originOf(parentUrl) + "/"))
        return listOf(VideoQuality(height = 1080, fps = 30, videoUrl = fetched.finalUrl, audioUrl = null, isLive = false, headers = hdrs)) to emptyList()
    }
    val foundStreams = MovieHtmlParser.extractStreams(fetched.body, fetched.finalUrl, headers)
    val foundCaps = MovieHtmlParser.extractCaptions(fetched.body, fetched.finalUrl)
    if (foundStreams.isNotEmpty() || depth >= 1) return foundStreams to foundCaps

    val nested = MovieHtmlParser.extractEmbedIframes(fetched.body, fetched.finalUrl).take(2)
    if (nested.isEmpty()) return emptyList<VideoQuality>() to foundCaps
    val deeper = supervisorScope {
        nested.map { childUrl ->
            async(Dispatchers.IO) { resolveEmbedHop(childUrl, fetched.finalUrl, headers, depth + 1) }
        }.awaitAll()
    }
    return deeper.flatMap { it.first } to (foundCaps + deeper.flatMap { it.second })
}
