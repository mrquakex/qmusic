package com.qmusic.ui.videos

import com.qmusic.engine.video.VideoCaption
import com.qmusic.engine.video.VideoQuality
import com.qmusic.ui.chat.MovieSource

/** Prefix used to identify external film/stream URLs inside VideoItem.id and SharedItem.id (syncs over TogetherController). */
internal const val EXTERNAL_VIDEO_PREFIX = "url:"

internal fun externalVideoId(url: String): String = EXTERNAL_VIDEO_PREFIX + url.trim()

internal fun externalUrlOf(id: String): String? =
    if (id.startsWith(EXTERNAL_VIDEO_PREFIX)) id.removePrefix(EXTERNAL_VIDEO_PREFIX).trim().takeIf { it.isNotEmpty() } else null

internal fun isExternalVideoId(id: String): Boolean = id.startsWith(EXTERNAL_VIDEO_PREFIX)

/** One movie/video entry scraped from an admin-configured source catalog or search page. */
data class MovieCard(
    val title: String,
    val pageUrl: String,
    val poster: String? = null,
    val subtitle: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val qualityBadge: String? = null,
    val sourceName: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/** One page of a scraped movie catalog, including optional pagination and genre filters. */
data class MovieCatalogPage(
    val items: List<MovieCard> = emptyList(),
    val nextPageUrl: String? = null,
    val genres: List<Pair<String, String>> = emptyList(),
)

/** Resolved playable stream(s), metadata, and optional subtitles extracted purely via HTTP scraping (no WebView). */
data class ResolvedMovieStream(
    val title: String? = null,
    val poster: String? = null,
    val synopsis: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val streams: List<VideoQuality> = emptyList(),
    val captions: List<VideoCaption> = emptyList(),
)

/**
 * High-performance pure-Kotlin HTML/JS/HLS scraper utilities (zero WebView, 100% unit-testable):
 * - Direct media stream detection (.m3u8, .mpd, .mp4, .mkv, .webm)
 * - Dean Edwards `eval(function(p,a,c,k,e,d){...})` pure-Kotlin JS unpacker
 * - Base64 / `atob("...")` stream & embed decoder
 * - JSON-LD `Movie` / `VideoObject` metadata (synopsis, year, rating, contentUrl)
 * - HLS master playlist (`#EXT-X-STREAM-INF`) quality variant parser
 * - Nested iframe embed discovery, catalog card scraping, genre filters & pagination
 */
internal object MovieHtmlParser {

    private val DIRECT_MEDIA_REGEX = Regex(
        """(?i)^https?://[^\s"'<>]+?\.(m3u8|mpd|mp4|mkv|webm|mov|m4v)(\?[^\s"'<>]*)?$""",
    )
    private val STREAM_IN_TEXT_REGEX = Regex(
        """(?i)(https?:(?://|\\\/\\\/)[^\s"'<>\\`]+?\.(?:m3u8|mpd|mp4|mkv|webm)(?:[^\s"'<>\\`]*))""",
    )
    private val VIDEO_TAG_SRC = Regex("""(?i)<(?:video|source)\b[^>]*\b(?:src|data-src)\s*=\s*["']([^"']+)["']""")
    private val META_VIDEO = Regex("""(?i)<meta\b[^>]*\b(?:property|name)\s*=\s*["'](?:og:video(?::url|:secure_url)?|twitter:player:stream)["'][^>]*\bcontent\s*=\s*["']([^"']+)["']""")
    private val META_VIDEO_REV = Regex("""(?i)<meta\b[^>]*\bcontent\s*=\s*["']([^"']+)["'][^>]*\b(?:property|name)\s*=\s*["'](?:og:video(?::url|:secure_url)?|twitter:player:stream)["']""")
    private val JSON_FILE_URL = Regex("""(?i)(?:"(?:contentUrl|file|hlsUrl|hls|mp4|streamUrl|videoUrl|src|url)"|file\s*:|src\s*:|hls\s*:)\s*["'](https?:(?:\\/|/)[^"']+\.(?:m3u8|mpd|mp4|webm|mkv)[^"']*)["']""")
    private val ATOB_REGEX = Regex("""(?i)atob\s*\(\s*["']([A-Za-z0-9+/=]{16,})["']\s*\)""")
    private val DATA_EMBED_B64 = Regex("""(?i)\bdata-(?:src|video|url|embed|iframe|player|file)\s*=\s*["']([A-Za-z0-9+/=]{20,})["']""")
    private val DATA_EMBED_HTTP = Regex("""(?i)\bdata-(?:embed|iframe|player|video-url|stream|url)\s*=\s*["']((?:https?:|//)[^"']+)["']""")
    private val PACKER_REGEX = Regex(
        """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)\s*\{.*?\}\s*\(\s*['"](.+?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.+?)['"]\s*\.split\s*\(\s*['"]\|['"]\s*\)""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val IFRAME_SRC = Regex("""(?i)<iframe\b[^>]*\b(?:src|data-src|data-lazy-src)\s*=\s*["']([^"']+)["']""")
    private val TRACK_TAG = Regex("""(?i)<track\b([^>]+)>""")
    private val ATTR_SRC = Regex("""(?i)\bsrc\s*=\s*["']([^"']+)["']""")
    private val ATTR_LABEL = Regex("""(?i)\blabel\s*=\s*["']([^"']+)["']""")
    private val ATTR_SRCLANG = Regex("""(?i)\bsrclang\s*=\s*["']([^"']+)["']""")
    private val META_TITLE = Regex("""(?i)<meta\b[^>]*\bproperty\s*=\s*["']og:title["'][^>]*\bcontent\s*=\s*["']([^"']+)["']""")
    private val META_TITLE_REV = Regex("""(?i)<meta\b[^>]*\bcontent\s*=\s*["']([^"']+)["'][^>]*\bproperty\s*=\s*["']og:title["']""")
    private val META_DESC = Regex("""(?i)<meta\b[^>]*\b(?:property|name)\s*=\s*["'](?:og:description|description)["'][^>]*\bcontent\s*=\s*["']([^"']+)["']""")
    private val META_DESC_REV = Regex("""(?i)<meta\b[^>]*\bcontent\s*=\s*["']([^"']+)["'][^>]*\b(?:property|name)\s*=\s*["'](?:og:description|description)["']""")
    private val HTML_TITLE = Regex("""(?is)<title[^>]*>(.*?)</title>""")
    private val META_IMAGE = Regex("""(?i)<meta\b[^>]*\bproperty\s*=\s*["']og:image["'][^>]*\bcontent\s*=\s*["']([^"']+)["']""")
    private val META_IMAGE_REV = Regex("""(?i)<meta\b[^>]*\bcontent\s*=\s*["']([^"']+)["'][^>]*\bproperty\s*=\s*["']og:image["']""")
    private val JSON_LD_BLOCK = Regex("""(?is)<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""")
    private val JSON_LD_DESC = Regex("""(?i)"description"\s*:\s*"([^"]{15,800})"""")
    private val JSON_LD_YEAR = Regex("""(?i)"(?:datePublished|copyrightYear)"\s*:\s*"?((?:19|20)\d{2})""")
    private val JSON_LD_RATING = Regex("""(?i)"ratingValue"\s*:\s*"?(\d(?:[.,]\d)?)"?""")
    private val ARTICLE_BLOCK = Regex("""(?is)<article\b[^>]*>(.*?)</article>""")
    private val FIRST_ANCHOR = Regex("""(?is)<a\b([^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*)>(.*?)</a>""")
    private val ANCHOR_WITH_IMG = Regex("""(?is)<a\b([^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*)>(.*?)</a>""")
    private val IMG_TAG = Regex("""(?is)<img\b([^>]+)>""")
    private val LAZY_SRC_ATTR = Regex("""(?i)\b(?:data-src|data-lazy-src|data-original|data-poster)\s*=\s*["']([^"']+)["']""")
    private val IMG_SRC_ATTR = Regex("""(?i)\b(?:data-src|data-lazy-src|data-original|data-poster|src)\s*=\s*["']([^"']+)["']""")
    private val IMG_SRCSET_ATTR = Regex("""(?i)\b(?:data-srcset|srcset)\s*=\s*["']([^"']+)["']""")
    private val DATA_BG_ATTR = Regex("""(?i)\b(?:data-bg|data-background|data-background-image)\s*=\s*["']([^"']+)["']""")
    private val CSS_BG_URL = Regex("""(?i)background(?:-image)?\s*:\s*url\(\s*["']?([^"')]+)["']?\s*\)""")
    private val IMG_ALT_ATTR = Regex("""(?i)\balt\s*=\s*["']([^"']+)["']""")
    private val ATTR_TITLE = Regex("""(?i)\btitle\s*=\s*["']([^"']+)["']""")
    private val HEADING_TEXT = Regex("""(?is)<h[1-6][^>]*>(.*?)</h[1-6]>""")
    private val STRIP_TAGS = Regex("""<[^>]+>""")
    private val QUALITY_HINT = Regex("""(?i)(2160|1440|1080|720|480|360)p""")
    private val YEAR_REGEX = Regex("""\b((?:19[5-9]\d|20[0-3]\d))\b""")
    private val RATING_REGEX = Regex("""(?i)(?:imdb|puan|rating|★|⭐)?\s*\b([1-9][.,]\d)\b""")
    private val BADGE_REGEX = Regex("""(?i)\b(4K|UHD|1080p|720p|HD|Dublaj|Altyazı|Dual)\b""")
    private val NEXT_LINK_REL = Regex("""(?i)<(?:a|link)\b[^>]*\brel\s*=\s*["']next["'][^>]*\bhref\s*=\s*["']([^"']+)["']""")
    private val NEXT_LINK_REL_REV = Regex("""(?i)<(?:a|link)\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*\brel\s*=\s*["']next["']""")
    private val NEXT_LINK_CLASS = Regex("""(?i)<a\b[^>]*\bclass\s*=\s*["'][^"']*\b(?:next|nextpostslink|pagination-next)\b[^"']*["'][^>]*\bhref\s*=\s*["']([^"']+)["']""")
    private val GENRE_LINK = Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["']([^"']*(?:/tur/|/genre/|/kategori/|/category/|/films/|/movies/)[^"']*)["'][^>]*>(.*?)</a>""")
    private val HLS_STREAM_INF = Regex("""#EXT-X-STREAM-INF:([^\r\n]+)[\r\n]+([^\r\n#]+)""")
    private val HLS_RES = Regex("""RESOLUTION=\d+x(\d+)""")
    private val FILM_DATA_K = Regex("""(?i)\bdata-k\s*=\s*["']([^"']+)["']""")
    private val CSRF_TOKEN = Regex("""(?i)<meta\b[^>]*\bname\s*=\s*["']csrf-token["'][^>]*\bcontent\s*=\s*["']([^"']+)["']""")
    private val BEPLAYER_REGEX = Regex("""(?is)bePlayer\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*,\s*['"](\{.+?\})['"]""")
    private val PEACEMAKER_IFRAME_REGEX = Regex("""(?i)peacemakerst\.com/tv/video/([a-zA-Z0-9]+)""")
    private val DIZIDAD_EPISODE_LINK = Regex("""(?i)href\s*=\s*["'](https?://(?:www\.)?dizidad\.com/[^"']+(?:sezon|bolum)-izle/)["']""")
    private val PEACEMAKER_STREAM_FILE = Regex("""(?i)"file"\s*:\s*"([^"]+\.m3u8[^"]*)"""")

    /** Extracts Filmmodu `(i, s)` pair from `data-k` attribute. */
    fun extractFilmmoduDataK(html: String): Pair<Int, String>? {
        val raw = FILM_DATA_K.find(html)?.groupValues?.getOrNull(1) ?: return null
        val unescaped = decodeEntities(raw)
        val i = Regex(""""i"\s*:\s*(\d+)""").find(unescaped)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val s = Regex(""""s"\s*:\s*"([^"]+)"""").find(unescaped)?.groupValues?.getOrNull(1) ?: return null
        return i to s
    }

    /** Extracts `<meta name="csrf-token" content="...">`. */
    fun extractCsrfToken(html: String): String? =
        CSRF_TOKEN.find(html)?.groupValues?.getOrNull(1)

    /** Extracts `bePlayer(hash, jsonPayload)` arguments from player embed HTML. */
    fun extractBePlayerArgs(html: String): Pair<String, String>? {
        val m = BEPLAYER_REGEX.find(html) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /** Extracts Peacemaker player hash for Dizimom/Dizidad episodes. */
    fun extractPeacemakerHash(html: String, pageUrl: String): String? =
        PEACEMAKER_IFRAME_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: PEACEMAKER_IFRAME_REGEX.find(pageUrl)?.groupValues?.getOrNull(1)

    /** Extracts first episode URL from a Dizidad TV series page. */
    fun extractDizidadFirstEpisode(html: String): String? =
        DIZIDAD_EPISODE_LINK.find(html)?.groupValues?.getOrNull(1)

    /** Extracts the direct .m3u8 URL from Peacemaker `?do=getVideo` response JSON. */
    fun extractPeacemakerStreamUrl(json: String): String? =
        PEACEMAKER_STREAM_FILE.find(json)?.groupValues?.getOrNull(1)?.replace("\\/", "/")

    /** True when [url] points straight to a playable video/HLS/DASH resource with no HTML scraping needed. */
    fun isDirectMediaUrl(url: String): Boolean {
        val trimmed = url.trim()
        return DIRECT_MEDIA_REGEX.matches(trimmed) ||
            trimmed.contains(".m3u8?", ignoreCase = true) ||
            trimmed.contains(".mpd?", ignoreCase = true) ||
            trimmed.contains(".mp4?", ignoreCase = true)
    }

    /** Parses `Key: Value` lines from [MovieSource.headers] into a clean HTTP header map. */
    fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
        }
        return out
    }

    /** Extracts origin (`https://host`) from a full URL for Referer headers and relative path resolution. */
    fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.trimEnd('/')
        val slash = url.indexOf('/', schemeEnd + 3)
        return if (slash > 0) url.substring(0, slash) else url.trimEnd('/')
    }

    /** Resolves a possibly relative [href] against [baseUrl]. */
    fun resolveUrl(baseUrl: String, href: String): String? {
        val raw = decodeEntities(href).replace("\\/", "/").trim()
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("javascript:", true) || raw.startsWith("data:", true)) return null
        return when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> originOf(baseUrl) + raw
            else -> {
                val baseDir = baseUrl.substringBefore('?').substringBeforeLast('/', originOf(baseUrl))
                "$baseDir/$raw"
            }
        }
    }

    /** Builds the catalog or search URL for an admin-configured [source]. */
    fun buildSourceUrl(source: MovieSource, query: String): String {
        val base = source.normalizedBaseUrl
        val q = query.trim()
        if (q.isEmpty()) {
            val path = source.catalogPath.trim().ifEmpty { "/" }
            return if (path.startsWith("http://", true) || path.startsWith("https://", true)) path
            else base + if (path.startsWith("/")) path else "/$path"
        }
        val encoded = percentEncode(q)
        val template = source.searchPath.trim().ifEmpty { "/?s={query}" }
        val replaced = if ("{query}" in template) template.replace("{query}", encoded) else "$template$encoded"
        return if (replaced.startsWith("http://", true) || replaced.startsWith("https://", true)) replaced
        else base + if (replaced.startsWith("/")) replaced else "/$replaced"
    }

    /**
     * Pure-Kotlin Dean Edwards JS Packer unpacker (`eval(function(p,a,c,k,e,d){...})`).
     * Unpacks obfuscated player configs in < 2 ms without any JavaScript engine or WebView.
     */
    fun unpackPackerJs(html: String): List<String> {
        val out = ArrayList<String>()
        PACKER_REGEX.findAll(html).forEach { m ->
            val payload = m.groupValues[1].replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
            val radix = m.groupValues[2].toIntOrNull()?.coerceIn(2, 62) ?: return@forEach
            val count = m.groupValues[3].toIntOrNull()?.coerceIn(0, 10_000) ?: return@forEach
            val symtab = m.groupValues[4].split('|')
            val unpacked = Regex("""\b[0-9a-zA-Z]+\b""").replace(payload) { tok ->
                val idx = decodeBaseN(tok.value, radix)
                if (idx != null && idx in 0 until count && idx < symtab.size && symtab[idx].isNotEmpty()) symtab[idx] else tok.value
            }
            if (unpacked.isNotBlank()) out += unpacked
        }
        return out
    }

    private fun decodeBaseN(token: String, radix: Int): Int? {
        var result = 0
        for (ch in token) {
            val digit = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'z' -> 10 + (ch - 'a')
                in 'A'..'Z' -> if (radix > 36) 36 + (ch - 'A') else 10 + (ch - 'A')
                else -> return null
            }
            if (digit >= radix) return null
            result = result * radix + digit
            if (result > 100_000) return null
        }
        return result
    }

    /** Decodes standard Base64 ASCII/UTF-8 strings in pure Kotlin (for `atob("...")` obfuscated stream URLs). */
    fun decodeBase64String(input: String): String? {
        val clean = input.trim().filter { !it.isWhitespace() }
        if (clean.length < 4 || clean.length % 4 == 1) return null
        val table = IntArray(128) { -1 }
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { idx, c -> table[c.code] = idx }
        val bytes = ArrayList<Byte>(clean.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val code = ch.code
            val v = if (code < 128) table[code] else -1
            if (v < 0) return null
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes += ((buf shr bits) and 0xFF).toByte()
            }
        }
        return runCatching { bytes.toByteArray().decodeToString() }.getOrNull()
    }

    /** Extracts playable stream URLs + quality guesses from raw HTML, unpacked JS blocks, and Base64 tokens. */
    fun extractStreams(html: String, pageUrl: String, headers: Map<String, String> = emptyMap()): List<VideoQuality> {
        val found = LinkedHashSet<String>()
        fun addCandidate(raw: String) {
            val resolved = resolveUrl(pageUrl, raw) ?: return
            if (isLikelyStream(resolved)) found += resolved
        }
        fun scanText(blob: String) {
            VIDEO_TAG_SRC.findAll(blob).forEach { addCandidate(it.groupValues[1]) }
            META_VIDEO.findAll(blob).forEach { addCandidate(it.groupValues[1]) }
            META_VIDEO_REV.findAll(blob).forEach { addCandidate(it.groupValues[1]) }
            JSON_FILE_URL.findAll(blob).forEach { addCandidate(it.groupValues[1]) }
            STREAM_IN_TEXT_REGEX.findAll(blob).forEach { addCandidate(it.groupValues[1]) }
            ATOB_REGEX.findAll(blob).forEach { m ->
                decodeBase64String(m.groupValues[1])?.let { decoded ->
                    if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true) || decoded.startsWith("//")) {
                        addCandidate(decoded)
                    } else {
                        STREAM_IN_TEXT_REGEX.findAll(decoded).forEach { addCandidate(it.groupValues[1]) }
                    }
                }
            }
            DATA_EMBED_B64.findAll(blob).forEach { m ->
                decodeBase64String(m.groupValues[1])?.let { decoded ->
                    if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) addCandidate(decoded)
                }
            }
        }
        scanText(html)
        unpackPackerJs(html).forEach(::scanText)

        val playbackHeaders = LinkedHashMap<String, String>(headers).apply {
            if (keys.none { it.equals("Referer", ignoreCase = true) }) {
                put("Referer", originOf(pageUrl) + "/")
            }
        }
        return found.map { url ->
            val h = QUALITY_HINT.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: if (url.contains(".m3u8", true) || url.contains(".mpd", true)) 1080 else 720
            VideoQuality(
                height = h,
                fps = 30,
                videoUrl = url,
                audioUrl = null,
                isLive = false,
                headers = playbackHeaders,
            )
        }.sortedWith(
            compareByDescending<VideoQuality> { it.videoUrl.contains(".m3u8", true) || it.videoUrl.contains(".mpd", true) }
                .thenByDescending { it.height },
        )
    }

    /** Parses an HLS master playlist (`#EXT-X-STREAM-INF`) into individual quality options + Auto master. */
    fun parseHlsVariants(playlistText: String, masterUrl: String, headers: Map<String, String>): List<VideoQuality> {
        if (!playlistText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return emptyList()
        val variants = ArrayList<VideoQuality>()
        HLS_STREAM_INF.findAll(playlistText).forEach { m ->
            val attrs = m.groupValues[1]
            val relUrl = m.groupValues[2].trim()
            val resolved = resolveUrl(masterUrl, relUrl) ?: return@forEach
            val height = HLS_RES.find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
            variants += VideoQuality(height = height, fps = 30, videoUrl = resolved, audioUrl = null, isLive = false, headers = headers)
        }
        val distinct = variants.distinctBy { it.height }.sortedByDescending { it.height }
        if (distinct.isEmpty()) return emptyList()
        // Keep Auto master at top so adaptive bitrate works, plus explicit height choices.
        return listOf(VideoQuality(height = 0, fps = 30, videoUrl = masterUrl, audioUrl = null, isLive = false, headers = headers)) + distinct
    }

    /** Extracts embedded player iframe URLs (including Base64-encoded embeds) for parallel HTTP resolution. */
    fun extractEmbedIframes(html: String, pageUrl: String): List<String> {
        val urls = LinkedHashSet<String>()
        fun maybeAdd(raw: String) {
            val resolved = resolveUrl(pageUrl, raw) ?: return
            val lower = resolved.lowercase()
            if (lower.contains("googletagmanager") || lower.contains("doubleclick") || lower.contains("googlesyndication") ||
                lower.contains("facebook.com/plugins") || lower.contains("disqus.com") || lower.contains("recaptcha") ||
                lower.contains("cloudflare") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".svg")
            ) return
            urls += resolved
        }
        IFRAME_SRC.findAll(html).forEach { maybeAdd(it.groupValues[1]) }
        DATA_EMBED_HTTP.findAll(html).forEach { m ->
            val raw = m.groupValues[1]
            if (!isLikelyStream(raw)) maybeAdd(raw)
        }
        DATA_EMBED_B64.findAll(html).forEach { m ->
            decodeBase64String(m.groupValues[1])?.let { decoded ->
                if ((decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) && !isLikelyStream(decoded)) {
                    maybeAdd(decoded)
                }
            }
        }
        return urls.take(6)
    }

    /** Extracts `<track>` WebVTT/SRT subtitle entries from HTML. */
    fun extractCaptions(html: String, pageUrl: String): List<VideoCaption> =
        TRACK_TAG.findAll(html).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val src = ATTR_SRC.find(attrs)?.groupValues?.getOrNull(1)?.let { resolveUrl(pageUrl, it) } ?: return@mapNotNull null
            val lang = ATTR_SRCLANG.find(attrs)?.groupValues?.getOrNull(1)?.ifBlank { "tr" } ?: "tr"
            val label = decodeEntities(ATTR_LABEL.find(attrs)?.groupValues?.getOrNull(1).orEmpty()).ifBlank { lang.uppercase() }
            val mime = if (src.contains(".srt", true)) "application/x-subrip" else "text/vtt"
            VideoCaption(language = lang, label = label, url = src, auto = false, mimeType = mime)
        }.distinctBy { it.url }.toList()

    fun extractTitle(html: String): String? {
        val og = META_TITLE.find(html)?.groupValues?.getOrNull(1)
            ?: META_TITLE_REV.find(html)?.groupValues?.getOrNull(1)
            ?: HTML_TITLE.find(html)?.groupValues?.getOrNull(1)
        return og?.let(::decodeEntities)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun extractPoster(html: String, pageUrl: String): String? {
        val img = META_IMAGE.find(html)?.groupValues?.getOrNull(1)
            ?: META_IMAGE_REV.find(html)?.groupValues?.getOrNull(1)
        return img?.let { resolveUrl(pageUrl, it) }
    }

    fun extractSynopsis(html: String): String? {
        val ld = JSON_LD_BLOCK.findAll(html).firstNotNullOfOrNull { b ->
            JSON_LD_DESC.find(b.groupValues[1])?.groupValues?.getOrNull(1)
        }
        val meta = META_DESC.find(html)?.groupValues?.getOrNull(1)
            ?: META_DESC_REV.find(html)?.groupValues?.getOrNull(1)
        return (ld ?: meta)?.let(::decodeEntities)?.replace("\\n", " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(420)?.takeIf { it.length >= 12 }
    }

    fun extractYear(html: String): String? =
        JSON_LD_BLOCK.findAll(html).firstNotNullOfOrNull { b ->
            JSON_LD_YEAR.find(b.groupValues[1])?.groupValues?.getOrNull(1)
        }

    fun extractRating(html: String): String? =
        JSON_LD_BLOCK.findAll(html).firstNotNullOfOrNull { b ->
            JSON_LD_RATING.find(b.groupValues[1])?.groupValues?.getOrNull(1)?.replace(',', '.')
        }

    /** Extracts a full cinema catalog page: movie cards with year/rating/badge, pagination link, and genre pills. */
    fun extractCatalogPage(html: String, pageUrl: String, sourceName: String, headers: Map<String, String> = emptyMap()): MovieCatalogPage {
        val items = extractCatalog(html, pageUrl, sourceName, headers)
        val nextUrl = (NEXT_LINK_REL.find(html)?.groupValues?.getOrNull(1)
            ?: NEXT_LINK_REL_REV.find(html)?.groupValues?.getOrNull(1)
            ?: NEXT_LINK_CLASS.find(html)?.groupValues?.getOrNull(1))
            ?.let { resolveUrl(pageUrl, it) }
            ?.takeIf { !it.equals(pageUrl, ignoreCase = true) }

        val seenGenres = HashSet<String>()
        val genres = ArrayList<Pair<String, String>>()
        GENRE_LINK.findAll(html).forEach { m ->
            val href = resolveUrl(pageUrl, m.groupValues[1]) ?: return@forEach
            val label = decodeEntities(STRIP_TAGS.replace(m.groupValues[2], " ")).replace(Regex("\\s+"), " ").trim()
            if (label.length in 3..26 && seenGenres.add(label.lowercase())) {
                genres += label to href
            }
            if (genres.size >= 14) return@forEach
        }
        return MovieCatalogPage(items = items, nextPageUrl = nextUrl, genres = genres)
    }

    /** Extracts the best poster URL from an HTML snippet (img data-src/src/srcset, data-bg, or CSS background-image). */
    private fun extractPosterFromBlock(block: String, pageUrl: String): Pair<String?, String?> {
        val imgAttrs = IMG_TAG.find(block)?.groupValues?.getOrNull(1)
        val alt = imgAttrs?.let { IMG_ALT_ATTR.find(it)?.groupValues?.getOrNull(1) }
        val fromImg = imgAttrs?.let { attrs ->
            LAZY_SRC_ATTR.find(attrs)?.groupValues?.getOrNull(1)
                ?: IMG_SRCSET_ATTR.find(attrs)?.groupValues?.getOrNull(1)?.let(::pickBestSrcsetUrl)
                ?: IMG_SRC_ATTR.find(attrs)?.groupValues?.getOrNull(1)?.takeUnless { it.startsWith("data:", true) }
        }
        val rawPoster = fromImg
            ?: DATA_BG_ATTR.find(block)?.groupValues?.getOrNull(1)
            ?: CSS_BG_URL.find(block)?.groupValues?.getOrNull(1)
        val resolved = rawPoster?.let { resolveUrl(pageUrl, it) }
        if (resolved != null && (resolved.contains("logo", true) || resolved.contains("avatar", true) || resolved.endsWith(".svg", true))) {
            return null to alt
        }
        return resolved to alt
    }

    private fun pickBestSrcsetUrl(srcset: String): String? =
        srcset.split(',')
            .mapNotNull { entry ->
                val parts = entry.trim().split(Regex("\\s+"))
                val url = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val weight = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                url to weight
            }
            .maxByOrNull { it.second }
            ?.first

    /**
     * Generic catalog/search scraper: extracts poster + title + year/rating/quality + link cards
     * from both `<article>` WordPress/CMS blocks AND `<a href="..."><img ...></a>` grids.
     */
    fun extractCatalog(html: String, pageUrl: String, sourceName: String, headers: Map<String, String> = emptyMap()): List<MovieCard> {
        val seen = HashSet<String>()
        val cards = ArrayList<MovieCard>()
        val pageOrigin = originOf(pageUrl).lowercase()

        fun addCard(href: String, anchorAttrs: String, body: String, fullBlock: String) {
            if (cards.size >= 48) return
            if (href.lowercase() == pageUrl.lowercase() || href.lowercase() == "$pageOrigin/" || href.lowercase() == pageOrigin) return
            val (poster, alt) = extractPosterFromBlock(fullBlock, pageUrl)
            if (poster == null) return

            val plainBlock = decodeEntities(STRIP_TAGS.replace(fullBlock, " ")).replace(Regex("\\s+"), " ").trim()
            val titleRaw = HEADING_TEXT.find(fullBlock)?.groupValues?.getOrNull(1)
                ?: ATTR_TITLE.find(anchorAttrs)?.groupValues?.getOrNull(1)
                ?: alt
                ?: STRIP_TAGS.replace(body, " ")
            val title = decodeEntities(STRIP_TAGS.replace(titleRaw, " ")).replace(Regex("\\s+"), " ").trim()
            if (title.length < 2 || title.length > 140) return
            if (!seen.add(href)) return

            val year = YEAR_REGEX.find(plainBlock)?.groupValues?.getOrNull(1)
            val rating = RATING_REGEX.find(plainBlock)?.groupValues?.getOrNull(1)?.replace(',', '.')
            val badge = BADGE_REGEX.find(plainBlock)?.groupValues?.getOrNull(1)?.uppercase()
            cards += MovieCard(
                title = title,
                pageUrl = href,
                poster = poster,
                year = year,
                rating = rating,
                qualityBadge = badge,
                sourceName = sourceName,
                headers = headers,
            )
        }

        // Pass 1: <article> cards (handles WordPress / CMS themes where <img> and <a> are siblings).
        ARTICLE_BLOCK.findAll(html).forEach { art ->
            val block = art.groupValues[1]
            val anchor = FIRST_ANCHOR.find(block) ?: return@forEach
            val href = resolveUrl(pageUrl, anchor.groupValues[2]) ?: return@forEach
            addCard(href, anchor.groupValues[1], anchor.groupValues[3], block)
        }

        // Pass 2: Direct <a href="..."><img ...></a> or <a data-bg="..."> cards.
        ANCHOR_WITH_IMG.findAll(html).forEach { match ->
            val anchorAttrs = match.groupValues[1]
            val href = resolveUrl(pageUrl, match.groupValues[2]) ?: return@forEach
            val body = match.groupValues[3]
            addCard(href, anchorAttrs, body, anchorAttrs + " " + body)
        }
        return cards
    }

    private fun isLikelyStream(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".css") || lower.endsWith(".js")) {
            return false
        }
        if (lower.contains("wepama") || lower.contains("wepam") || lower.contains("dizifilmizle") || lower.contains("preroll") || lower.contains("/ad/") || lower.contains("ad_") || lower.contains("banner") || lower.contains("kumar") || lower.contains("bet")) {
            return false
        }
        return lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".webm")
    }

    fun decodeEntities(s: String): String {
        var res = s
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        if (res.contains("&#")) {
            res = Regex("""&#(\d+);""").replace(res) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }
            res = Regex("""&#x([0-9a-fA-F]+);""").replace(res) { m ->
                m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
            }
        }
        return res
    }

    private fun percentEncode(s: String): String = buildString {
        s.encodeToByteArray().forEach { b ->
            val c = b.toInt() and 0xFF
            when {
                c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '_'.code || c == '.'.code -> append(c.toChar())
                c == ' '.code -> append('+')
                else -> append('%').append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

/** Fetches and parses a catalog/search/genre/next page from an admin-configured [source] via pure HTTP (no WebView). */
expect suspend fun fetchMovieCatalog(source: MovieSource, query: String = "", pageUrlOverride: String? = null): Result<MovieCatalogPage>

/** Resolves a direct stream URL or HTML film page URL into playable Media3 streams via fast parallel HTTP scraping (no WebView). */
expect suspend fun resolveExternalMovie(url: String, headers: Map<String, String> = emptyMap()): Result<ResolvedMovieStream>

/** Diagnostic report returned by the Admin Panel's 1-tap "Test source" button. */
data class MovieSourceTestResult(
    val ok: Boolean,
    val catalogCount: Int,
    val streamCount: Int,
    val elapsedMs: Long,
    val summary: String,
)

/**
 * Live diagnostic check for an admin-configured [MovieSource]:
 * fetches its catalog page, probes the first movie's stream via pure HTTP, and reports latency + status.
 */
suspend fun testMovieSource(source: MovieSource, english: Boolean = false): MovieSourceTestResult {
    val start = kotlin.time.TimeSource.Monotonic.markNow()
    val catalogRes = fetchMovieCatalog(source)
    val catalog = catalogRes.getOrElse { err ->
        val ms = start.elapsedNow().inWholeMilliseconds
        val msg = err.message ?: if (english) "No response" else "Yanıt alınamadı"
        return MovieSourceTestResult(
            ok = false,
            catalogCount = 0,
            streamCount = 0,
            elapsedMs = ms,
            summary = if (english) "Failed ($ms ms): $msg" else "Erişilemedi ($ms ms): $msg",
        )
    }
    if (catalog.items.isEmpty()) {
        val ms = start.elapsedNow().inWholeMilliseconds
        return MovieSourceTestResult(
            ok = false,
            catalogCount = 0,
            streamCount = 0,
            elapsedMs = ms,
            summary = if (english) "Page opened ($ms ms) but 0 movie cards found (check catalog path or JS protection)"
            else "Sayfa açıldı ($ms ms) ama film kartı bulunamadı (katalog yolunu veya JS korumasını kontrol et)",
        )
    }
    val first = catalog.items.first()
    val streamRes = resolveExternalMovie(first.pageUrl, first.headers)
    val ms = start.elapsedNow().inWholeMilliseconds
    return streamRes.fold(
        onSuccess = { resolved ->
            val q = resolved.streams.map { it.label }.distinct().take(3).joinToString("/")
            MovieSourceTestResult(
                ok = resolved.streams.isNotEmpty(),
                catalogCount = catalog.items.size,
                streamCount = resolved.streams.size,
                elapsedMs = ms,
                summary = if (english) "${catalog.items.size} movies · First playable ($q) · $ms ms"
                else "${catalog.items.size} film bulundu · İlk film oynatılabilir ($q) · $ms ms",
            )
        },
        onFailure = { err ->
            MovieSourceTestResult(
                ok = false,
                catalogCount = catalog.items.size,
                streamCount = 0,
                elapsedMs = ms,
                summary = if (english) "${catalog.items.size} movies found, but no direct stream on first item (${err.message ?: "protected"}) · $ms ms"
                else "${catalog.items.size} film bulundu, fakat ilk filmde doğrudan akış çıkmadı (${err.message ?: "korumalı"}) · $ms ms",
            )
        },
    )
}
