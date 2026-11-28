package com.qmusic.ui.videos

import com.qmusic.engine.video.VideoAudioTrack
import com.qmusic.engine.video.VideoCaption
import com.qmusic.engine.video.VideoQuality
import com.qmusic.ui.chat.MovieSource
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

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

/** Parsed `#EXT-X-MEDIA:TYPE=AUDIO` rendition from an HLS master playlist. */
data class HlsAudioTrack(
    val groupId: String,
    val name: String,
    val language: String?,
    val uri: String?,
    val isDefault: Boolean = false,
    val isAutoSelect: Boolean = false,
    val channels: String? = null,
) {
    val normalizedLanguage: String?
        get() = MovieHtmlParser.normalizeHlsLanguage(language, name)

    val displayLabel: String
        get() {
            val raw = name.trim()
            return when (normalizedLanguage) {
                "tr" -> if (raw.isNotEmpty() && !raw.equals("tr", true) && !raw.equals("tur", true) && !raw.equals("turkish", true)) raw else "Türkçe"
                "en" -> if (raw.isNotEmpty() && !raw.equals("en", true) && !raw.equals("eng", true) && !raw.equals("english", true)) raw else "İngilizce"
                else -> raw.ifEmpty { language?.uppercase() ?: "Varsayılan" }
            }
        }
}

/** Parsed HLS master playlist containing video variants and synchronized audio renditions. */
data class HlsMasterPlaylist(
    val masterUrl: String,
    val variants: List<VideoQuality>,
    val audioTracks: List<VideoAudioTrack>,
    val rawAudioTracks: List<HlsAudioTrack> = emptyList(),
    val defaultAudioTrackId: String? = null,
    val hasSeparateAudio: Boolean = false,
)

/** One playable episode on a TV series page (e.g. "1. Sezon 1. Bölüm · Pilot"). */
data class MovieEpisode(
    val season: Int,
    val episode: Int,
    val title: String,
    val pageUrl: String,
    val poster: String? = null,
    val runtime: String? = null,
)

/** Resolved playable stream(s), metadata, audio tracks, and optional subtitles extracted purely via HTTP scraping (no WebView). */
data class ResolvedMovieStream(
    val title: String? = null,
    val poster: String? = null,
    val synopsis: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val streams: List<VideoQuality> = emptyList(),
    val captions: List<VideoCaption> = emptyList(),
    val audioTracks: List<VideoAudioTrack> = emptyList(),
    val defaultAudioTrackId: String? = null,
    val seasons: List<Int> = emptyList(),
    val tvshowId: String? = null,
    val seriesOrigin: String? = null,
    val seriesUrl: String? = null,
    val episodes: List<MovieEpisode> = emptyList(),
    val activeEpisodeUrl: String? = null,
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
        """(?i)(https?:(?://|\\\/\\\/)[^\s"'<>\\`]+?(?:\.(?:m3u8|mpd|mp4|mkv|webm)|/master\.txt)(?:[^\s"'<>\\`]*))""",
    )
    private val VIDEO_TAG_SRC = Regex("""(?i)<(?:video|source)\b[^>]*\b(?:src|data-src)\s*=\s*["']([^"']+)["']""")
    private val META_VIDEO = Regex("""(?i)<meta\b[^>]*\b(?:property|name)\s*=\s*["'](?:og:video(?::url|:secure_url)?|twitter:player:stream)["'][^>]*\bcontent\s*=\s*["']([^"']+)["']""")
    private val META_VIDEO_REV = Regex("""(?i)<meta\b[^>]*\bcontent\s*=\s*["']([^"']+)["'][^>]*\b(?:property|name)\s*=\s*["'](?:og:video(?::url|:secure_url)?|twitter:player:stream)["']""")
    private val JSON_FILE_URL = Regex("""(?i)(?:"(?:contentUrl|file|hlsUrl|hls|mp4|streamUrl|videoUrl|src|url)"|file\s*:|src\s*:|hls\s*:)\s*["'](https?:(?:\\/|/)[^"']+(?:\.(?:m3u8|mpd|mp4|webm|mkv)|/master\.txt)[^"']*)["']""")
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
    private val SOURCE_SRCSET_ATTR = Regex("""(?is)<source\b[^>]*\b(?:data-srcset|srcset)\s*=\s*["']([^"']+)["']""")
    private val DATA_BG_ATTR = Regex("""(?i)\b(?:data-bg|data-background|data-background-image)\s*=\s*["']([^"']+)["']""")
    private val CSS_BG_URL = Regex("""(?i)background(?:-image)?\s*:\s*url\(\s*["']?([^"')]+)["']?\s*\)""")
    private val IMG_ALT_ATTR = Regex("""(?i)\balt\s*=\s*["']([^"']+)["']""")
    private val ATTR_TITLE = Regex("""(?i)\btitle\s*=\s*["']([^"']+)["']""")
    private val HEADING_TEXT = Regex("""(?is)<h[1-6][^>]*>(.*?)</h[1-6]>""")
    private val POSTER_TITLE_REGEX = Regex("""(?is)<strong\b[^>]*\bclass\s*=\s*["'][^"']*\bposter-title\b[^"']*["'][^>]*>(.*?)</strong>""")
    private val STRIP_TAGS = Regex("""<[^>]+>""")
    private val QUALITY_HINT = Regex("""(?i)(2160|1440|1080|720|480|360)p""")
    private val YEAR_REGEX = Regex("""\b((?:19[5-9]\d|20[0-3]\d))\b""")
    private val RATING_REGEX = Regex("""(?i)(?:imdb|puan|rating|★|⭐)?\s*\b([1-9][.,]\d)\b""")
    private val BADGE_REGEX = Regex("""(?i)\b(4K|UHD|1080p|720p|HD|Dublaj|Altyazı|Dual)\b""")
    private val NEXT_LINK_REL = Regex("""(?i)<(?:a|link)\b[^>]*\brel\s*=\s*["']next["'][^>]*\bhref\s*=\s*["']([^"']+)["']""")
    private val NEXT_LINK_REL_REV = Regex("""(?i)<(?:a|link)\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*\brel\s*=\s*["']next["']""")
    private val NEXT_LINK_CLASS = Regex("""(?i)<a\b[^>]*\bclass\s*=\s*["'][^"']*\b(?:next|nextpostslink|pagination-next)\b[^"']*["'][^>]*\bhref\s*=\s*["']([^"']+)["']""")
    private val GENRE_LINK = Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["']([^"']*(?:/tur/|/genre/|/kategori/|/category/|/films/|/movies/)[^"']*)["'][^>]*>(.*?)</a>""")
    private val FILM_DATA_K = Regex("""(?i)\bdata-k\s*=\s*["']([^"']+)["']""")
    private val CSRF_TOKEN = Regex("""(?i)<meta\b[^>]*\bname\s*=\s*["']csrf-token["'][^>]*\bcontent\s*=\s*["']([^"']+)["']""")
    private val BEPLAYER_REGEX = Regex("""(?is)bePlayer\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*,\s*['"](\{.+?\})['"]""")
    private val PEACEMAKER_IFRAME_REGEX = Regex("""(?i)(https?://[^"'<>\s]+?)/tv/video/([a-zA-Z0-9]+)""")
    private val DIZIDAD_EPISODE_LINK = Regex("""(?i)href\s*=\s*["'](https?://(?:www\.)?(?:dizidad|dizimom)[^"']+(?:sezon|bolum)[^"']*)["']""")
    private val PEACEMAKER_STREAM_FILE = Regex("""(?i)"file"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
    private val DIZIDAD_BOLUMUST_BLOCK = Regex("""(?is)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bbolumust\b[^"']*["'][^>]*>(.*?)</a>""")
    private val DIZIDAD_SEASON_EP = Regex("""(?i)(\d+)\s*\.\s*Sezon\s*(\d+)\s*\.\s*B[öo]l[üu]m""")
    private val DIZIDAD_EP_ONLY = Regex("""(?i)(?:^|\b)(\d+)\s*\.\s*B[öo]l[üu]m""")
    private val DIZIDAD_URL_SEASON_EP = Regex("""(?i)-(\d+)-sezon-(\d+)-bolum""")
    private val DIZIDAD_URL_EP_ONLY = Regex("""(?i)-(\d+)-bolum""")
    private val DIZIDAD_CAT_IMG_BLOCK = Regex("""(?is)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bcat-img\b[^"']*["'][^>]*>(.*?)</div>""")
    private val DIZIDAD_CATEGORY_TITLE = Regex("""(?is)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bcategorytitle\b[^"']*["'][^>]*>\s*<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""")
    private val DIZIDAD_IMDB = Regex("""(?i)\bIMDb\s*:\s*(\d(?:[.,]\d)?)""")
    private val DIZIDAD_YEAR = Regex("""(?is)Yap[ıi]m\s*Y[ıi]l[ıi]\s*:\s*(?:</span>\s*)?((?:19|20)\d{2})""")
    private val DIZIDAD_GENRES_META = Regex("""(?is)T[üu]r\s*:\s*(?:</span>\s*)?([^<\r\n]+)""")
    private val DIZIDAD_GENRE_NAV = Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["'](https?://[^"']*(?:yerli-dizi-izle|yabanci-dizi-izle|netflix-dizileri-izle|turkce-dublaj-diziler-hd|kore-dizileri-izle-hd|netflix-filmleri|yabanci-filmler-izle)[^"']*)["'][^>]*>(.*?)</a>""")
    private val HDFC_ALT_GROUP = Regex("""(?is)<div\b[^>]*\bclass\s*=\s*["'][^"']*\balternative-links\b[^"']*["']([^>]*)>(.*?)</div>""")
    private val HDFC_LANG_BTN = Regex("""(?is)<(?:button|a)\b[^>]*\bclass\s*=\s*["'][^"']*\blanguage-link\b[^"']*["']([^>]*)>(.*?)</(?:button|a)>""")
    private val HDFC_DATA_VIDEO_BTN = Regex("""(?is)<(?:button|a|div)\b([^>]*\bdata-video\s*=\s*["']([^"']+)["'][^>]*)>(.*?)</(?:button|a|div)>""")
    private val HDFC_SEASONS_BLOCK = Regex("""(?is)<div\b[^>]*\b(?:class|id)\s*=\s*["'][^"']*(?:seasons-tab-content|seasonsTabs-tabContent)[^"']*["'][^>]*>(.*?)</div>""")
    private val HDFC_EPISODE_LINK = Regex("""(?i)<a\b[^>]*\bhref\s*=\s*["']([^"']*(?:/bolum-|/sezon-\d+/bolum-|-\d+-sezon-\d+-bolum)[^"']*)["']""")
    private val HDFC_LOAD_PAGE_URL = Regex("""(?i)^(.+?)/load/page/(\d+)/([^?#]*)(.*)$""")
    private val HDFC_LOAD_PAGE_IN_HTML = Regex("""(?i)["']([^"']*?/load/page/\d+/[^"'<>\s]*)["']""")
    private val HDFC_NEXT_PAGE_LINK = Regex("""(?i)<a\b[^>]*\bhref\s*=\s*["']([^"']*(?:/page/\d+|[?&]page=\d+)[^"']*)["']""")
    private val HDFC_FILE_LINK = Regex("""(?i)\bfile_link\s*=\s*["']([^"']+)["']""")
    private val HDFC_FILE_LINK_CALL = Regex("""(?i)\bfile_link\s*=\s*(?:atob|dc_hello)\s*\(\s*["']([^"']+)["']\s*\)""")
    private val DC_HELLO_REGEX = Regex("""(?i)\bdc_hello\s*\(\s*["']([^"']+)["']\s*\)""")
    private val HDFC_SOURCES_BLOCK = Regex("""(?is)["']?sources["']?\s*:\s*\[(.*?)\]""")
    private val HDFC_TRACKS_BLOCK = Regex("""(?is)["']?tracks["']?\s*:\s*\[(.*?)\]""")
    private val HDFC_SOURCE_FILE = Regex("""(?is)(?:["']?(?:file|src|url)["']?\s*:\s*(?:(?:atob|dc_hello)\s*\(\s*["']([^"']+)["']\s*\)|["']([^"']+)["']))""")
    private val SCRIPT_INLINE = Regex("""(?is)<script\b([^>]*)>(.*?)</script\s*>""")
    private val TEMPLATE_BLOCK = Regex("""(?is)<template\b[^>]*>.*?</template\s*>""")
    private val WSTREAM_OVERVIEW = Regex("""(?is)<p\b[^>]*\bclass\s*=\s*["'][^"']*\bhro__overview\b[^"']*["'][^>]*>(.*?)</p>""")
    private val NP_CONFIG_REGEX = Regex("""(?is)window\.NP_CONFIG\s*=\s*(\{.*?\});""")
    private val DIZIYOU_SEASON_BTN = Regex("""(?i)data-season-number\s*=\s*["'](\d+)["']""")
    private val DIZIYOU_TVSHOW_ID = Regex("""(?i)data-tvshow-id\s*=\s*["'](\d+)["']""")
    private val DIZIYOU_PARENT_DIZI_LINK = Regex("""(?i)\bhref\s*=\s*["'](https?://[^"'#?\s]+/dizi/[^"'#?\s]+/?)["']""")
    private val DIZIYOU_EP_META = Regex("""(?is)<span\b[^>]*\bclass\s*=\s*["']eps["'][^>]*>.*?<span>\s*Sezon\s*(\d+)\s*</span>\s*B[öo]l[üu]m\s*(\d+)""", )
    private val DIZIYOU_EP_URL_NUMS = Regex("""(?i)(\d+)-sezon-(\d+)-bolum""")
    private val DIZIYOU_RUNTIME = Regex("""(?is)<span\b[^>]*\bclass\s*=\s*["']runtime["'][^>]*>.*?</i>\s*([^<\s][^<]{0,16})</span>""")
    private val HDFC_VIP_PLAYER_DATA = Regex("""(?is)videoPlayerData\s*\(\s*JSON\.parse\(\s*['"](.+?)['"]\s*\)""")
    private val HDFC_VIP_EPISODE_ANCHOR = Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["']([^"']*?/dizi/([^/"'?#]+)/sezon-(\d+)/bolum-(\d+)[^"']*)["'][^>]*>(.*?)</a>""")
    private val HDFC_VIP_SERIES_LINK = Regex("""(?i)\bhref\s*=\s*["'](https?://[^"'#?\s]+/dizi/[^/"'?#]+/?)["']""")

    /** Strips SEO boilerplate suffixes (`| Diziyou`, `- Türkçe Dublaj...`, `izle`) from scraped titles. */
    fun cleanMovieTitle(raw: String?): String? {
        val t = raw?.let(::decodeEntities)?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        val cleaned = t
            .replace(Regex("""(?i)\s*[-–|]\s*T[üu]rk[çc]e\s+Dublaj(?:\s+ve|\s*&)?\s*Altyaz[ıi]l[ıi]?\s*[İi]zle.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*(?:Diziyou|DiziDAD|HD\s*film\s*cehennemi|Filmmodu).*$"""), "")
            .replace(Regex("""(?i),\s*[^,]{2,60}\(\d{4}\)$"""), "")
            .replace(Regex("""(?i)\s+(?:full\s*hd\s*)?[İi]zle(?:\s+(?:hd\d*|full))?$"""), "")
            .trim()
        return cleaned.ifBlank { t }
    }

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

    /** Extracts `(playerOrigin, hash)` for `/tv/video/{hash}` player mirrors (peacemakerst, hdstreamable, hdmomplayer). */
    fun extractTvVideoTarget(html: String, pageUrl: String): Pair<String, String>? {
        val m = PEACEMAKER_IFRAME_REGEX.find(html) ?: PEACEMAKER_IFRAME_REGEX.find(pageUrl) ?: return null
        return m.groupValues[1].trimEnd('/') to m.groupValues[2]
    }

    /** Extracts Peacemaker player hash for Dizimom/Dizidad episodes. */
    fun extractPeacemakerHash(html: String, pageUrl: String): String? =
        extractTvVideoTarget(html, pageUrl)?.second

    /** Extracts first episode URL from a Dizidad TV series page. */
    fun extractDizidadFirstEpisode(html: String): String? =
        DIZIDAD_EPISODE_LINK.find(html)?.groupValues?.getOrNull(1)

    /** Extracts the direct .m3u8 URL from `/tv/video/{hash}?do=getVideo` response JSON. */
    fun extractPeacemakerStreamUrl(json: String): String? =
        PEACEMAKER_STREAM_FILE.find(json)?.groupValues?.getOrNull(1)?.replace("\\/", "/")

    fun isDizidadPage(pageUrl: String, html: String = ""): Boolean =
        pageUrl.contains("dizidad", ignoreCase = true) ||
            pageUrl.contains("dizimom", ignoreCase = true) ||
            html.contains("DiziDAD", ignoreCase = true) ||
            html.contains("bolumust", ignoreCase = true) ||
            PEACEMAKER_IFRAME_REGEX.containsMatchIn(html)

    private fun cleanDizidadTitle(raw: String): String =
        decodeEntities(STRIP_TAGS.replace(raw, " "))
            .replace(Regex("\\s+"), " ")
            .replace(Regex("""(?i)\s+(?:full\s*hd\s*)?(?:son\s*b[öo]l[üu]m\s*)?izle(?:\s+(?:hd\d*|full))*$"""), "")
            .trim()

    /** Parses Dizidad `<div class="single-item">` series archive / search pages cleanly (without episode spam). */
    fun extractDizidadCatalogPage(
        html: String,
        pageUrl: String,
        sourceName: String,
        headers: Map<String, String> = emptyMap(),
    ): MovieCatalogPage? {
        if (!html.contains("single-item", ignoreCase = true)) return null
        val blocks = html.split(Regex("""(?i)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bsingle-item\b[^"']*["'][^>]*>"""))
            .drop(1)
        if (blocks.isEmpty()) return null

        val seen = HashSet<String>()
        val items = ArrayList<MovieCard>()
        blocks.forEach { block ->
            if (items.size >= 48) return@forEach
            val catTitleMatch = DIZIDAD_CATEGORY_TITLE.find(block) ?: return@forEach
            val href = resolveUrl(pageUrl, catTitleMatch.groupValues[1]) ?: return@forEach
            if (!seen.add(href)) return@forEach
            val title = cleanDizidadTitle(catTitleMatch.groupValues[2]).ifBlank { return@forEach }

            val imgBlock = DIZIDAD_CAT_IMG_BLOCK.find(block)?.groupValues?.getOrNull(1) ?: block
            val (poster, _) = extractPosterFromBlock(imgBlock, pageUrl)
            val rating = DIZIDAD_IMDB.find(block)?.groupValues?.getOrNull(1)?.replace(',', '.')
            val year = DIZIDAD_YEAR.find(block)?.groupValues?.getOrNull(1)
            val genresText = DIZIDAD_GENRES_META.find(block)?.groupValues?.getOrNull(1)
                ?.let(::decodeEntities)
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val badge = when {
                block.contains("Yerli Dizi", ignoreCase = true) -> "YERLİ"
                block.contains("Türkçe Dublaj", ignoreCase = true) -> "DUBLAJ"
                block.contains("Türkçe Altyazı", ignoreCase = true) -> "ALTYAZI"
                else -> null
            }
            items += MovieCard(
                title = title,
                pageUrl = href,
                poster = poster,
                subtitle = genresText,
                year = year,
                rating = rating,
                qualityBadge = badge,
                sourceName = sourceName,
                headers = headers,
            )
        }
        if (items.isEmpty()) return null

        val nextUrl = (NEXT_LINK_CLASS.find(html)?.groupValues?.getOrNull(1)
            ?: NEXT_LINK_REL.find(html)?.groupValues?.getOrNull(1)
            ?: NEXT_LINK_REL_REV.find(html)?.groupValues?.getOrNull(1))
            ?.let { resolveUrl(pageUrl, it) }
            ?.takeIf { !it.equals(pageUrl, ignoreCase = true) }

        val seenGenres = HashSet<String>()
        val genres = ArrayList<Pair<String, String>>()
        DIZIDAD_GENRE_NAV.findAll(html).forEach { m ->
            val href = resolveUrl(pageUrl, m.groupValues[1]) ?: return@forEach
            val label = decodeEntities(STRIP_TAGS.replace(m.groupValues[2], " ")).replace(Regex("\\s+"), " ").trim()
            if (label.isNotEmpty() && seenGenres.add(label.lowercase())) {
                genres += label to href
            }
        }
        return MovieCatalogPage(items = items, nextPageUrl = nextUrl, genres = genres)
    }

    /** Extracts all seasons and episodes from a Dizidad `/diziler/...` series page in ascending episode order. */
    fun extractDizidadEpisodes(html: String, pageUrl: String): List<MovieEpisode> {
        val out = ArrayList<MovieEpisode>()
        val seen = HashSet<String>()
        DIZIDAD_BOLUMUST_BLOCK.findAll(html).forEach { m ->
            val block = m.groupValues[1]
            val href = FIRST_ANCHOR.find(block)?.groupValues?.getOrNull(2)?.let { resolveUrl(pageUrl, it) }
                ?: Regex("""(?i)\bhref\s*=\s*["']([^"']+)["']""").find(block)?.groupValues?.getOrNull(1)?.let { resolveUrl(pageUrl, it) }
                ?: return@forEach
            if (!seen.add(href)) return@forEach
            val rawLabel = decodeEntities(STRIP_TAGS.replace(block, " ")).replace(Regex("\\s+"), " ").trim()
            val seasonEp = DIZIDAD_SEASON_EP.find(rawLabel) ?: DIZIDAD_URL_SEASON_EP.find(href)
            val (season, ep) = if (seasonEp != null) {
                (seasonEp.groupValues[1].toIntOrNull() ?: 1) to (seasonEp.groupValues[2].toIntOrNull() ?: return@forEach)
            } else {
                val epOnly = DIZIDAD_EP_ONLY.find(rawLabel) ?: DIZIDAD_URL_EP_ONLY.find(href) ?: return@forEach
                1 to (epOnly.groupValues[1].toIntOrNull() ?: return@forEach)
            }
            val cleanTitle = rawLabel
                .replace(Regex("""(?i)\bİzledim\b"""), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "${season}. Sezon ${ep}. Bölüm" }
            out += MovieEpisode(
                season = season,
                episode = ep,
                title = cleanTitle,
                pageUrl = href,
            )
        }
        return out.sortedWith(compareBy<MovieEpisode> { it.season }.thenBy { it.episode })
    }

    internal data class HdFilmCehennemiVideoOption(
        val videoIdOrUrl: String,
        val label: String,
        val language: String? = null,
    )

    /** True when [pageUrl] or [html] belongs to HDFilmCehennemi (regardless of active mirror domain). */
    fun isHdFilmCehennemiPage(pageUrl: String, html: String = ""): Boolean =
        pageUrl.contains("hdfilmcehennemi", ignoreCase = true) ||
            pageUrl.contains("vidload.top", ignoreCase = true) ||
            pageUrl.contains("/load/page/", ignoreCase = true) ||
            html.contains("videoPlayerData", ignoreCase = true) ||
            html.contains("poster-title", ignoreCase = true) ||
            html.contains("alternative-links", ignoreCase = true) ||
            html.contains("seasonsTabs-tabContent", ignoreCase = true) ||
            html.contains("seasons-tab-content", ignoreCase = true)

    /** Extracts a JSON-escaped HTML string field (`"html"`, `"content"`, or `"data"`) without backtracking issues. */
    fun unwrapHdFilmCehennemiLoadResponse(body: String): String {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) return body
        for (key in listOf("\"html\"", "\"content\"", "\"data\"")) {
            val extracted = extractJsonStringField(trimmed, key) ?: continue
            if (extracted.contains("<a", ignoreCase = true) ||
                extracted.contains("<iframe", ignoreCase = true) ||
                extracted.contains("poster-title", ignoreCase = true)
            ) {
                return extracted
            }
        }
        return body
    }

    private fun extractJsonStringField(json: String, quotedKey: String): String? {
        var searchFrom = 0
        while (searchFrom < json.length) {
            val keyIdx = json.indexOf(quotedKey, searchFrom)
            if (keyIdx < 0) return null
            var i = keyIdx + quotedKey.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') {
                searchFrom = keyIdx + quotedKey.length
                continue
            }
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '"') {
                searchFrom = i
                continue
            }
            i++
            val sb = StringBuilder()
            while (i < json.length) {
                val c = json[i++]
                if (c == '"') return sb.toString()
                if (c == '\\' && i < json.length) {
                    when (val esc = json[i++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> if (i + 4 <= json.length) {
                            val code = json.substring(i, i + 4).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 4
                            } else {
                                sb.append("\\u")
                            }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return null
        }
        return null
    }

    /** Extracts the next pagination URL for HDFilmCehennemi catalog, genre, or `/load/page/{n}/` endpoints. */
    fun extractHdFilmCehennemiNextPage(html: String, pageUrl: String): String? {
        HDFC_LOAD_PAGE_URL.find(pageUrl)?.let { m ->
            val prefix = m.groupValues[1]
            val currentPage = m.groupValues[2].toIntOrNull() ?: 1
            val suffix = m.groupValues[3]
            val query = m.groupValues[4]
            return "$prefix/load/page/${currentPage + 1}/$suffix$query"
        }

        HDFC_LOAD_PAGE_IN_HTML.findAll(html).forEach { m ->
            val candidate = resolveUrl(pageUrl, m.groupValues[1]) ?: return@forEach
            if (!candidate.equals(pageUrl, ignoreCase = true)) return candidate
        }

        val currentPage = Regex("""(?i)(?:/page/|[?&]page=)(\d+)""").find(pageUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        var bestPage = Int.MAX_VALUE
        var bestUrl: String? = null
        HDFC_NEXT_PAGE_LINK.findAll(html).forEach { m ->
            val candidate = resolveUrl(pageUrl, m.groupValues[1]) ?: return@forEach
            val p = Regex("""(?i)(?:/page/|[?&]page=)(\d+)""").find(candidate)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (p != null && p > currentPage && p < bestPage) {
                bestPage = p
                bestUrl = candidate
            }
        }
        if (bestUrl != null) return bestUrl

        if (pageUrl.contains("hdfilmcehennemi", ignoreCase = true)) {
            val origin = originOf(pageUrl)
            val pathAndQuery = pageUrl.removePrefix(origin)
            val pathOnly = pathAndQuery.substringBefore('?').substringBefore('#').trimEnd('/')
            if (pathOnly.isEmpty() && !pathAndQuery.contains("q=", ignoreCase = true)) {
                return "$origin/load/page/2/movies/"
            }
        }
        return null
    }

    /** Parses HDFilmCehennemi JSON search/suggestion payload (`results`, `result`, `data`, or `html`) into cards. */
    fun parseHdFilmCehennemiSearchJson(
        json: String,
        pageUrl: String,
        sourceName: String,
        headers: Map<String, String>,
    ): List<MovieCard> {
        val unwrapped = unwrapHdFilmCehennemiLoadResponse(json)
        if (unwrapped !== json) {
            val htmlCards = extractCatalog(unwrapped, pageUrl, sourceName, headers)
            if (htmlCards.isNotEmpty()) return htmlCards
        }
        val items = ArrayList<MovieCard>()
        val objRegex = Regex("""\{[^{}]*\}""")
        objRegex.findAll(json).forEach { m ->
            val obj = m.value
            val title = (Regex(""""(?:title|name)"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1))
                ?.let(::decodeEntities)?.trim() ?: return@forEach
            val rawLink = Regex(""""(?:url|link|slug)"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")?.trim() ?: return@forEach
            val href = if (rawLink.startsWith("http", true) || rawLink.startsWith("/")) {
                resolveUrl(pageUrl, rawLink)
            } else {
                "${originOf(pageUrl)}/${rawLink.trimStart('/')}"
            } ?: return@forEach
            val rawPoster = Regex(""""(?:posterUrl|poster|image|img|thumb)"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")?.trim()
            val poster = rawPoster?.let { resolveUrl(pageUrl, it) }
            val year = Regex(""""(?:releaseYear|year|date)"\s*:\s*"?((?:19|20)\d{2})""").find(obj)?.groupValues?.getOrNull(1)
            val rating = Regex(""""(?:imdbRating|imdb|rating)"\s*:\s*"?(\d(?:[.,]\d)?)"?""").find(obj)?.groupValues?.getOrNull(1)?.replace(',', '.')
            items += MovieCard(
                title = title,
                pageUrl = href,
                poster = poster,
                year = year,
                rating = rating,
                sourceName = sourceName,
                headers = headers,
            )
        }
        return items.distinctBy { it.pageUrl }
    }

    /** Extracts the first episode URL from an HDFilmCehennemi TV series overview page. */
    fun extractHdFilmCehennemiFirstEpisode(html: String, pageUrl: String): String? {
        if (HDFC_DATA_VIDEO_BTN.containsMatchIn(html) || HDFC_VIP_PLAYER_DATA.containsMatchIn(html)) return null
        val vipFirst = extractHdFilmCehennemiVipSeriesInfo(html, pageUrl).second.firstOrNull()?.pageUrl
        if (vipFirst != null && !vipFirst.equals(pageUrl, ignoreCase = true)) return vipFirst
        val fromSeasonBlock = HDFC_SEASONS_BLOCK.findAll(html).firstNotNullOfOrNull { block ->
            HDFC_EPISODE_LINK.find(block.groupValues[1])?.groupValues?.getOrNull(1)
                ?.let { resolveUrl(pageUrl, it) }
                ?.takeIf { !it.equals(pageUrl, ignoreCase = true) }
        }
        if (fromSeasonBlock != null) return fromSeasonBlock
        return HDFC_EPISODE_LINK.findAll(html).firstNotNullOfOrNull { m ->
            resolveUrl(pageUrl, m.groupValues[1])?.takeIf { !it.equals(pageUrl, ignoreCase = true) }
        }
    }

    /** Extracts ordered player options (`data-video` IDs/URLs + language hints) from an HDFilmCehennemi page. */
    fun extractHdFilmCehennemiVideoOptions(html: String, pageUrl: String): List<HdFilmCehennemiVideoOption> {
        val seen = HashSet<String>()
        val options = ArrayList<HdFilmCehennemiVideoOption>()

        val langHints = ArrayList<Pair<String, String>>()
        HDFC_LANG_BTN.findAll(html).forEach { m ->
            val attrs = m.groupValues[1]
            val text = decodeEntities(STRIP_TAGS.replace(m.groupValues[2], " ")).trim()
            val langAttr = Regex("""(?i)\bdata-(?:lang|language|target)\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.getOrNull(1)
            val normalized = normalizeHlsLanguage(langAttr, text) ?: if (text.contains("altyaz", true)) "en" else null
            if (langAttr != null && normalized != null) {
                langHints += langAttr.lowercase() to normalized
            }
        }

        fun inferLang(attrs: String): String? {
            val dataLang = Regex("""(?i)\bdata-(?:lang|language)\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.getOrNull(1)
            val idOrClass = Regex("""(?i)\b(?:id|class)\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.getOrNull(1).orEmpty()
            normalizeHlsLanguage(dataLang, idOrClass)?.let { return it }
            val lower = (attrs + " " + idOrClass).lowercase()
            return langHints.firstOrNull { (k, _) -> lower.contains(k) }?.second
        }

        fun addButton(attrs: String, rawVideo: String, body: String, groupLang: String?) {
            val video = decodeEntities(rawVideo).trim()
            if (video.isEmpty()) return
            val label = decodeEntities(STRIP_TAGS.replace(body, " ")).replace(Regex("\\s+"), " ").trim()
            if (label.contains("fragman", ignoreCase = true) || label.contains("trailer", ignoreCase = true)) return
            if (!seen.add(video)) return
            val lang = groupLang ?: inferLang(attrs)
            options += HdFilmCehennemiVideoOption(videoIdOrUrl = video, label = label.ifEmpty { "Player" }, language = lang)
        }

        HDFC_ALT_GROUP.findAll(html).forEach { group ->
            val groupLang = inferLang(group.groupValues[1])
            HDFC_DATA_VIDEO_BTN.findAll(group.groupValues[2]).forEach { btn ->
                addButton(btn.groupValues[1], btn.groupValues[2], btn.groupValues[3], groupLang)
            }
        }

        HDFC_DATA_VIDEO_BTN.findAll(html).forEach { btn ->
            addButton(btn.groupValues[1], btn.groupValues[2], btn.groupValues[3], null)
        }

        HDFC_VIP_PLAYER_DATA.findAll(html).forEach { m ->
            val rawJson = decodeEntities(m.groupValues[1]).replace("\\\\/", "/")
            Regex("""\{[^{}]*"link"\s*:\s*"[^"]+"[^{}]*"template"\s*:\s*"[^"]+"[^{}]*\}""").findAll(rawJson).forEach { objM ->
                val obj = objM.value
                val link = extractJsonStringField(obj, "\"link\"")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                val tplB64 = extractJsonStringField(obj, "\"template\"")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                val tplHtml = decodeBase64String(tplB64) ?: return@forEach
                val replacedTpl = tplHtml.replace("{url}", link)
                val rawSrc = IFRAME_SRC.find(replacedTpl)?.groupValues?.getOrNull(1) ?: return@forEach
                val resolvedEmbed = resolveUrl(pageUrl, rawSrc) ?: return@forEach
                val slug = extractJsonStringField(obj, "\"service_name\"")
                    ?: extractJsonStringField(obj, "\"service_slug\"")
                    ?: "VIP"
                if (seen.add(resolvedEmbed)) {
                    options += HdFilmCehennemiVideoOption(videoIdOrUrl = resolvedEmbed, label = slug)
                }
            }
        }

        return options.sortedBy { opt ->
            val l = opt.label.lowercase()
            when {
                l.contains("vip") || l.contains("vidload") || l.contains("rapidrame") || l.contains("rplayer") -> 0
                l.contains("vidmoly") || l.contains("close") -> 1
                else -> 2
            }
        }
    }

    /** Extracts `(seriesOverviewUrl, sortedEpisodesAscending)` from an HDFilmCehennemi `.vip` TV series or episode page. */
    fun extractHdFilmCehennemiVipSeriesInfo(html: String, pageUrl: String): Pair<String?, List<MovieEpisode>> {
        val seen = HashSet<String>()
        val out = ArrayList<MovieEpisode>()
        HDFC_VIP_EPISODE_ANCHOR.findAll(html).forEach { m ->
            val href = resolveUrl(pageUrl, m.groupValues[1]) ?: return@forEach
            val cleanHref = href.substringBefore('#').substringBefore('?')
            if (!seen.add(cleanHref)) return@forEach
            val season = m.groupValues[3].toIntOrNull() ?: return@forEach
            val ep = m.groupValues[4].toIntOrNull() ?: return@forEach
            val rawLabel = decodeEntities(STRIP_TAGS.replace(m.groupValues[5], " "))
                .replace(Regex("\\s+"), " ")
                .trim()
            val cleanTitle = rawLabel.ifBlank { "$season. Sezon $ep. Bölüm" }
            out += MovieEpisode(
                season = season,
                episode = ep,
                title = cleanTitle,
                pageUrl = cleanHref,
            )
        }
        val seriesLink = HDFC_VIP_SERIES_LINK.findAll(html)
            .mapNotNull { resolveUrl(pageUrl, it.groupValues[1])?.trimEnd('/') }
            .firstOrNull { !it.contains("/sezon-", ignoreCase = true) }
        val sorted = out.sortedWith(compareBy<MovieEpisode> { it.season }.thenBy { it.episode })
        return seriesLink to sorted
    }

    /** Builds the HTTP GET URL for an HDFilmCehennemi `data-video` identifier or relative path. */
    fun buildHdFilmCehennemiVideoRequestUrl(videoIdOrUrl: String, pageUrl: String): String? {
        val raw = videoIdOrUrl.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true) || raw.startsWith("//") || raw.startsWith("/")) {
            return resolveUrl(pageUrl, raw)
        }
        return "${originOf(pageUrl)}/video/${raw.trim('/')}/"
    }

    /** Extracts the embed iframe URL returned by HDFilmCehennemi `/video/{id}/` JSON or HTML. */
    fun extractHdFilmCehennemiEmbedUrl(body: String, requestUrl: String): String? {
        val unwrapped = unwrapHdFilmCehennemiLoadResponse(body)
        IFRAME_SRC.find(unwrapped)?.groupValues?.getOrNull(1)?.let { src ->
            resolveUrl(requestUrl, src)?.let { return it }
        }
        val jsonUrl = Regex("""(?i)"(?:url|src|iframe|embed)"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)
        if (jsonUrl != null) {
            resolveUrl(requestUrl, jsonUrl)?.let { return it }
        }
        return extractEmbedIframes(unwrapped, requestUrl).firstOrNull()
    }

    private fun rot13(s: String): String = buildString(s.length) {
        for (ch in s) {
            append(
                when (ch) {
                    in 'a'..'z' -> 'a' + ((ch - 'a' + 13) % 26)
                    in 'A'..'Z' -> 'A' + ((ch - 'A' + 13) % 26)
                    else -> ch
                },
            )
        }
    }

    /** Decodes Base64 / reversed Base64 / ROT13 / double-Base64 player tokens (`atob`, `dc_hello`). */
    fun decodeHdFilmCehennemiToken(raw: String): String? {
        val clean = raw.trim().trim('"', '\'')
        if (clean.isEmpty()) return null
        fun looksLikeStreamUrl(s: String): Boolean {
            val t = s.trim()
            return t.startsWith("http://", true) ||
                t.startsWith("https://", true) ||
                t.startsWith("//") ||
                (t.startsWith("/") && (t.contains(".m3u8", true) || t.contains("/master.txt", true) || t.contains(".mp4", true)))
        }
        if (looksLikeStreamUrl(clean)) return clean
        val d1 = decodeBase64String(clean)
        if (d1 != null) {
            if (looksLikeStreamUrl(d1)) return d1.trim()
            val d1Rev = d1.reversed()
            if (looksLikeStreamUrl(d1Rev)) return d1Rev.trim()
            val d1Rot = rot13(d1)
            if (looksLikeStreamUrl(d1Rot)) return d1Rot.trim()
            val d2 = decodeBase64String(d1)
            if (d2 != null && looksLikeStreamUrl(d2)) return d2.trim()
            val d2Rev = decodeBase64String(d1Rev)
            if (d2Rev != null && looksLikeStreamUrl(d2Rev)) return d2Rev.trim()
        }
        val dRevB64 = decodeBase64String(clean.reversed())
        if (dRevB64 != null && looksLikeStreamUrl(dRevB64)) return dRevB64.trim()
        return null
    }

    /**
     * Extracts streams (`file_link`, `sources: [...]`, `dc_hello(...)`, `atob(...)`, packed JS) and
     * WebVTT/SRT subtitles (`tracks: [...]`, `<track>`) from an HDFilmCehennemi / Rapidrame player page.
     */
    fun extractHdFilmCehennemiPlayerStreams(
        html: String,
        playerUrl: String,
        headers: Map<String, String> = emptyMap(),
    ): GenericStreamResult {
        val playerOrigin = originOf(playerUrl)
        val playbackHeaders = LinkedHashMap<String, String>(headers).apply {
            if (keys.none { it.equals("Referer", ignoreCase = true) }) put("Referer", "$playerOrigin/")
            if (keys.none { it.equals("Origin", ignoreCase = true) }) put("Origin", playerOrigin)
        }

        val foundStreams = LinkedHashSet<String>()
        val foundCaptions = ArrayList<VideoCaption>()

        fun addStream(raw: String) {
            val resolved = resolveUrl(playerUrl, raw) ?: return
            if (isLikelyStream(resolved) || isHlsCandidateUrl(resolved)) {
                val path = resolved.substringBefore('?').substringBefore('#').lowercase()
                if (!path.endsWith(".html") && !path.endsWith(".htm") && !path.endsWith(".php")) {
                    foundStreams += resolved
                }
            }
        }

        fun scanPlayerBlob(blob: String) {
            HDFC_FILE_LINK.findAll(blob).forEach { addStream(it.groupValues[1]) }
            HDFC_FILE_LINK_CALL.findAll(blob).forEach { m ->
                decodeHdFilmCehennemiToken(m.groupValues[1])?.let(::addStream)
            }
            DC_HELLO_REGEX.findAll(blob).forEach { m ->
                decodeHdFilmCehennemiToken(m.groupValues[1])?.let(::addStream)
            }
            HDFC_SOURCES_BLOCK.findAll(blob).forEach { block ->
                HDFC_SOURCE_FILE.findAll(block.groupValues[1]).forEach { fm ->
                    val b64 = fm.groupValues[1]
                    val plain = fm.groupValues[2]
                    if (b64.isNotEmpty()) decodeHdFilmCehennemiToken(b64)?.let(::addStream)
                    if (plain.isNotEmpty()) addStream(plain)
                }
            }
            HDFC_TRACKS_BLOCK.findAll(blob).forEach { block ->
                Regex("""\{([^{}]+)\}""").findAll(block.groupValues[1]).forEach { obj ->
                    val body = obj.groupValues[1]
                    val kind = Regex("""(?i)["']?kind["']?\s*:\s*["']([^"']+)["']""").find(body)?.groupValues?.getOrNull(1).orEmpty()
                    if (kind.equals("thumbnails", true) || kind.equals("chapters", true) || kind.equals("metadata", true) || kind.equals("preview", true)) {
                        return@forEach
                    }
                    val srcMatch = HDFC_SOURCE_FILE.find(body)
                    val rawSub = when {
                        srcMatch != null && srcMatch.groupValues[1].isNotEmpty() -> decodeHdFilmCehennemiToken(srcMatch.groupValues[1])
                        srcMatch != null && srcMatch.groupValues[2].isNotEmpty() -> srcMatch.groupValues[2]
                        else -> null
                    } ?: return@forEach
                    val subUrl = resolveUrl(playerUrl, rawSub) ?: return@forEach
                    val label = Regex("""(?i)["']?label["']?\s*:\s*["']([^"']+)["']""").find(body)?.groupValues?.getOrNull(1)
                        ?.let(::decodeEntities)?.trim().orEmpty()
                    val lang = Regex("""(?i)["']?(?:srclang|lang|language)["']?\s*:\s*["']([^"']+)["']""").find(body)?.groupValues?.getOrNull(1)
                        ?: normalizeHlsLanguage(null, label)
                        ?: "tr"
                    val mime = if (subUrl.contains(".srt", true)) "application/x-subrip" else "text/vtt"
                    foundCaptions += VideoCaption(
                        language = lang,
                        label = label.ifEmpty { lang.uppercase() },
                        url = subUrl,
                        auto = false,
                        mimeType = mime,
                    )
                }
            }
        }

        val blobs = ArrayList<String>().apply {
            add(html)
            val decodedHtml = decodeEntities(html).replace("\\\\/", "/")
            if (decodedHtml != html) add(decodedHtml)
            SCRIPT_INLINE.findAll(html).forEach { m ->
                val body = m.groupValues[2]
                if (body.isNotBlank()) {
                    add(body)
                    val decoded = decodeEntities(body).replace("\\\\/", "/")
                    if (decoded != body) add(decoded)
                }
            }
        }
        val unpackedLevel1 = blobs.flatMap { unpackPackerJs(it) }
        val unpackedLevel2 = unpackedLevel1.flatMap { unpackPackerJs(it) }
        (blobs + unpackedLevel1 + unpackedLevel2).distinct().forEach(::scanPlayerBlob)

        val decodedForStreams = decodeEntities(html).replace("\\\\/", "/")
        val genericStreams = (extractStreams(html, playerUrl, playbackHeaders) +
            if (decodedForStreams != html) extractStreams(decodedForStreams, playerUrl, playbackHeaders) else emptyList())
            .distinctBy { it.videoUrl }
        val mergedQualities = (foundStreams.map { url ->
            val h = QUALITY_HINT.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: if (isHlsCandidateUrl(url) || url.contains(".mpd", true)) 1080 else 720
            VideoQuality(height = h, fps = 30, videoUrl = url, audioUrl = null, isLive = false, headers = playbackHeaders)
        } + genericStreams)
            .distinctBy { it.videoUrl }
            .sortedWith(
                compareByDescending<VideoQuality> { isHlsCandidateUrl(it.videoUrl) || it.videoUrl.contains(".mpd", true) }
                    .thenByDescending { it.height },
            )
        val mergedCaptions = (foundCaptions + extractCaptions(html, playerUrl)).distinctBy { it.url }
        return GenericStreamResult(streams = mergedQualities, captions = mergedCaptions)
    }

    /** True when [url] points straight to a playable video/HLS/DASH resource with no HTML scraping needed. */
    fun isDirectMediaUrl(url: String): Boolean {
        val trimmed = url.trim()
        return DIRECT_MEDIA_REGEX.matches(trimmed) ||
            trimmed.contains(".m3u8?", ignoreCase = true) ||
            trimmed.contains(".mpd?", ignoreCase = true) ||
            trimmed.contains(".mp4?", ignoreCase = true) ||
            trimmed.contains("/master.txt", ignoreCase = true)
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

    /** Parses Filmmodu live search JSON (`/arama/oneri`) into catalog items. */
    fun parseFilmmoduSearchJson(json: String, sourceName: String, headers: Map<String, String>): List<MovieCard> {
        val items = ArrayList<MovieCard>()
        val objectRegex = Regex("""\{[^{}]*"title"\s*:\s*"([^"]+)".*?\}""")
        objectRegex.findAll(json).forEach { m ->
            val objStr = m.value
            val title = Regex(""""title"\s*:\s*"([^"]+)"""").find(objStr)?.groupValues?.getOrNull(1) ?: return@forEach
            val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(objStr)?.groupValues?.getOrNull(1)?.replace("\\/", "/") ?: return@forEach
            val rawPoster = Regex(""""poster"\s*:\s*"([^"]+)"""").find(objStr)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
            val year = Regex(""""year"\s*:\s*"?(\d{4})""").find(objStr)?.groupValues?.getOrNull(1)
            val rating = Regex(""""rating"\s*:\s*"?(\d(?:[.,]\d)?)"?""").find(objStr)?.groupValues?.getOrNull(1)

            items += MovieCard(
                title = decodeEntities(title),
                pageUrl = rawUrl,
                poster = rawPoster,
                year = year,
                rating = rating,
                sourceName = sourceName,
                headers = headers,
            )
        }
        return items.distinctBy { it.pageUrl }
    }

    /** Upgrades tiny `w92` TMDB mirror thumbnails (used in live JSON APIs) to crisp `w300` posters. */
    fun upgradeMirrorPosterUrl(url: String?): String? =
        url?.replace("/images/t/p/w92/", "/images/t/p/w300/", ignoreCase = true)

    /** True when [pageUrl] or [html] belongs to Diziyou / Wstream / ksdpictures player embed. */
    fun isDiziyouPage(pageUrl: String, html: String = ""): Boolean =
        pageUrl.contains("diziyou", ignoreCase = true) ||
            pageUrl.contains("ksdpictures", ignoreCase = true) ||
            html.contains("window.NP_CONFIG", ignoreCase = true) ||
            (html.contains("episodes-list", ignoreCase = true) && html.contains("seasons-list", ignoreCase = true))

    /** Extracts playable HLS/MP4 URLs + WebVTT subtitles from `window.NP_CONFIG = {...}` (`dzembed.php`). */
    fun extractNpConfigStreams(html: String, playerUrl: String, headers: Map<String, String> = emptyMap()): GenericStreamResult {
        val json = NP_CONFIG_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return GenericStreamResult()
        val origin = originOf(playerUrl)
        val playbackHeaders = headers + mapOf(
            "Referer" to "$origin/",
            "Origin" to origin,
        )
        val streamUrls = LinkedHashSet<String>()
        Regex("""(?i)"(?:master|file|url)"\s*:\s*"(https?:[^"]+\.(?:m3u8|mpd|mp4)[^"]*)"""")
            .findAll(json)
            .forEach { m ->
                resolveUrl(playerUrl, m.groupValues[1])?.let { if (isLikelyStream(it)) streamUrls += it }
            }
        val streams = streamUrls.map { url -> mediaQuality(url, playbackHeaders) }

        val captions = ArrayList<VideoCaption>()
        val subBlock = Regex("""(?is)"subtitles"\s*:\s*\[(.*?)\]""").find(json)?.groupValues?.getOrNull(1).orEmpty()
        Regex("""\{[^{}]*\}""").findAll(subBlock).forEach { m ->
            val obj = m.value
            val subUrl = extractJsonStringField(obj, "\"url\"")?.let { resolveUrl(playerUrl, it) } ?: return@forEach
            val lang = extractJsonStringField(obj, "\"lang\"").orEmpty().ifBlank { "tr" }
            val label = extractJsonStringField(obj, "\"label\"")?.let(::decodeEntities)?.ifBlank { lang.uppercase() } ?: lang.uppercase()
            val mime = if (subUrl.contains(".srt", true)) "application/x-subrip" else "text/vtt"
            captions += VideoCaption(language = lang, label = label, url = subUrl, auto = false, mimeType = mime)
        }
        return GenericStreamResult(streams = streams, captions = captions.distinctBy { it.url })
    }

    /** Parses Diziyou live search JSON (`/wp-json/wstream/v1/search?s=...`) into [MovieCard] entries. */
    fun parseDiziyouSearchJson(json: String, baseUrl: String, sourceName: String, headers: Map<String, String>): List<MovieCard> {
        val items = ArrayList<MovieCard>()
        val resultsBody = Regex("""(?is)"results"\s*:\s*\[(.*)\]""").find(json)?.groupValues?.getOrNull(1) ?: json
        Regex("""\{[^{}]*\}""").findAll(resultsBody).forEach { m ->
            val obj = m.value
            val rawTitle = extractJsonStringField(obj, "\"title\"") ?: return@forEach
            val rawUrl = extractJsonStringField(obj, "\"url\"") ?: return@forEach
            val pageUrl = resolveUrl(baseUrl, rawUrl) ?: return@forEach
            val rawPoster = extractJsonStringField(obj, "\"poster\"")?.let { resolveUrl(baseUrl, it) }
            val kindLabel = extractJsonStringField(obj, "\"type\"")?.let(::decodeEntities)
            items += MovieCard(
                title = decodeEntities(rawTitle).trim(),
                pageUrl = pageUrl,
                poster = upgradeMirrorPosterUrl(rawPoster),
                subtitle = kindLabel,
                sourceName = sourceName,
                headers = headers,
            )
        }
        return items.distinctBy { it.pageUrl }
    }

    /** Extracts the parent `/dizi/{slug}/` series overview URL from a Diziyou episode page. */
    fun extractDiziyouParentSeriesUrl(html: String, pageUrl: String): String? {
        val raw = DIZIYOU_PARENT_DIZI_LINK.find(html)?.groupValues?.getOrNull(1) ?: return null
        return resolveUrl(pageUrl, raw)?.takeIf { !it.equals(pageUrl, ignoreCase = true) }
    }

    /** Extracts the season number embedded in a Diziyou episode URL or markup (if present). */
    fun extractDiziyouEpisodeSeason(pageUrl: String, html: String): Int? =
        DIZIYOU_EP_URL_NUMS.find(pageUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: DIZIYOU_EP_META.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /** Extracts `(tvshowId, sortedSeasonsAscending)` from a Diziyou series overview page. */
    fun extractDiziyouSeriesMeta(html: String): Pair<String?, List<Int>> {
        val tvshowId = DIZIYOU_TVSHOW_ID.find(html)?.groupValues?.getOrNull(1)
        val seasons = DIZIYOU_SEASON_BTN.findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .sorted()
            .toList()
        return tvshowId to seasons
    }

    /** Parses `/wp-json/wstream/v1/retrieve-episodes/{tvshowId}?season={n}&id={tvshowId}` JSON. */
    fun parseDiziyouEpisodesJson(json: String, baseUrl: String): List<MovieEpisode> {
        val resultsBody = Regex("""(?is)"results"\s*:\s*\[(.*)\]""").find(json)?.groupValues?.getOrNull(1) ?: return emptyList()
        val episodes = ArrayList<MovieEpisode>()
        Regex("""\{[^{}]*\}""").findAll(resultsBody).forEach { m ->
            val obj = m.value
            val rawUrl = extractJsonStringField(obj, "\"url\"") ?: return@forEach
            val pageUrl = resolveUrl(baseUrl, rawUrl) ?: return@forEach
            val epNum = (extractJsonStringField(obj, "\"episode_number\"")
                ?: Regex(""""episode_number"\s*:\s*(\d+)""").find(obj)?.groupValues?.getOrNull(1))
                ?.toIntOrNull() ?: return@forEach
            val seasonNum = (extractJsonStringField(obj, "\"season_number\"")
                ?: Regex(""""season_number"\s*:\s*(\d+)""").find(obj)?.groupValues?.getOrNull(1))
                ?.toIntOrNull() ?: 1
            val epName = extractJsonStringField(obj, "\"name\"")?.let(::decodeEntities)?.trim().orEmpty()
            val fullTitle = extractJsonStringField(obj, "\"title\"")?.let(::decodeEntities)?.trim().orEmpty()
            val cleanTitle = epName.ifBlank { fullTitle.ifBlank { "${seasonNum}. Sezon ${epNum}. Bölüm" } }
            val runtime = extractJsonStringField(obj, "\"runtime\"")?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() }
            val poster = upgradeMirrorPosterUrl(extractJsonStringField(obj, "\"image\"")?.let { resolveUrl(baseUrl, it) })
            episodes += MovieEpisode(
                season = seasonNum,
                episode = epNum,
                title = cleanTitle,
                pageUrl = pageUrl,
                poster = poster,
                runtime = runtime,
            )
        }
        return episodes
            .distinctBy { it.pageUrl }
            .sortedWith(compareBy<MovieEpisode> { it.season }.thenBy { it.episode })
    }

    /** Fallback: extracts `<article class="card card--episode">` blocks directly from a Diziyou series page. */
    fun extractDiziyouHtmlEpisodes(html: String, pageUrl: String): List<MovieEpisode> {
        val cleanHtml = TEMPLATE_BLOCK.replace(html, "")
        val out = ArrayList<MovieEpisode>()
        ARTICLE_BLOCK.findAll(cleanHtml).forEach { art ->
            val block = art.groupValues[1]
            val anchor = FIRST_ANCHOR.find(block) ?: return@forEach
            val href = resolveUrl(pageUrl, anchor.groupValues[2]) ?: return@forEach
            if (!href.contains("/bolum/", ignoreCase = true) || '{' in href) return@forEach
            val nums = DIZIYOU_EP_META.find(block) ?: DIZIYOU_EP_URL_NUMS.find(href) ?: return@forEach
            val season = nums.groupValues[1].toIntOrNull() ?: 1
            val ep = nums.groupValues[2].toIntOrNull() ?: return@forEach
            val runtime = DIZIYOU_RUNTIME.find(block)?.groupValues?.getOrNull(1)?.trim()
            val (poster, _) = extractPosterFromBlock(block, pageUrl)
            out += MovieEpisode(
                season = season,
                episode = ep,
                title = "${season}. Sezon ${ep}. Bölüm",
                pageUrl = href,
                poster = upgradeMirrorPosterUrl(poster),
                runtime = runtime,
            )
        }
        return out.distinctBy { it.pageUrl }.sortedWith(compareBy<MovieEpisode> { it.season }.thenBy { it.episode })
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
        val clean = input.trim().filter { !it.isWhitespace() }.replace('-', '+').replace('_', '/')
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
                ?: if (isHlsCandidateUrl(url) || url.contains(".mpd", true)) 1080 else 720
            VideoQuality(
                height = h,
                fps = 30,
                videoUrl = url,
                audioUrl = null,
                isLive = false,
                headers = playbackHeaders,
            )
        }.sortedWith(
            compareByDescending<VideoQuality> { isHlsCandidateUrl(it.videoUrl) || it.videoUrl.contains(".mpd", true) }
                .thenByDescending { it.height },
        )
    }

    /** True when [url] likely points to an HLS master or media playlist (even without `.m3u8` extension). */
    fun isHlsCandidateUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm")) return false
        return url.contains(".m3u8", ignoreCase = true) ||
            url.contains("/list/", ignoreCase = true) ||
            url.contains("/m3u/", ignoreCase = true) ||
            url.contains("vidmixi.com", ignoreCase = true) ||
            url.contains("peacemakerst.com", ignoreCase = true) ||
            url.contains("twimg.com", ignoreCase = true) ||
            url.contains("akamaized.net", ignoreCase = true) ||
            url.contains("/hls", ignoreCase = true) ||
            url.contains("/master.txt", ignoreCase = true) ||
            path.endsWith("/playlist.txt") ||
            path.endsWith("/index.txt") ||
            url.contains("rapidrame", ignoreCase = true) ||
            url.contains("cdnimages", ignoreCase = true)
    }

    /** Normalizes ISO 639-1/639-2 language codes and track name hints (e.g. `tur` / `Türkçe Dublaj` -> `tr`, `eng` -> `en`). */
    fun normalizeHlsLanguage(rawLang: String?, rawName: String? = null): String? {
        val code = rawLang?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_').orEmpty()
        when (code) {
            "tr", "tur", "turkish", "türkçe", "turkce" -> return "tr"
            "en", "eng", "english", "ingilizce", "ing" -> return "en"
            "de", "deu", "ger", "german" -> return "de"
            "fr", "fra", "fre", "french" -> return "fr"
            "es", "spa", "spanish" -> return "es"
            "ru", "rus", "russian" -> return "ru"
            "ar", "ara", "arabic" -> return "ar"
            "ja", "jpn", "japanese" -> return "ja"
            "ko", "kor", "korean" -> return "ko"
        }
        if (code.length in 2..3 && code != "und") return code

        val name = rawName?.trim()?.lowercase().orEmpty()
        return when {
            name.contains("türk") || name.contains("turk") || (name.contains("dublaj") && !name.contains("ing")) -> "tr"
            name.contains("engl") || name.contains("ingiliz") || name.contains("orijinal") || name.contains("original") -> "en"
            else -> null
        }
    }

    /** Parses RFC 8216 HLS attribute lists (`KEY=VALUE` pairs, preserving commas/equals inside quoted values). */
    fun parseHlsAttributes(attrText: String): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        var i = 0
        val n = attrText.length
        while (i < n) {
            while (i < n && (attrText[i] == ',' || attrText[i].isWhitespace())) i++
            if (i >= n) break
            val eq = attrText.indexOf('=', i)
            if (eq < 0) break
            val key = attrText.substring(i, eq).trim().uppercase()
            var vStart = eq + 1
            while (vStart < n && attrText[vStart].isWhitespace()) vStart++
            val value = if (vStart < n && attrText[vStart] == '"') {
                val close = attrText.indexOf('"', vStart + 1)
                if (close >= 0) {
                    i = close + 1
                    attrText.substring(vStart + 1, close)
                } else {
                    i = n
                    attrText.substring(vStart + 1)
                }
            } else {
                var end = vStart
                while (end < n && attrText[end] != ',') end++
                i = end
                attrText.substring(vStart, end).trim()
            }
            if (key.isNotEmpty()) attrs[key] = value
        }
        return attrs
    }

    /**
     * Parses an HLS master playlist (`#EXT-X-MEDIA:TYPE=AUDIO` + `#EXT-X-STREAM-INF`) into:
     * - An adaptive Auto master entry (`height = 0, videoUrl = masterUrl`)
     * - Per-resolution video variants linked to the default audio rendition URI when separate audio exists
     * - Normalized [VideoAudioTrack] entries (Türkçe / İngilizce / etc.) for synchronized Media3 track selection.
     */
    fun parseHlsMasterPlaylist(playlistText: String, masterUrl: String, headers: Map<String, String>): HlsMasterPlaylist {
        if (!playlistText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
            return HlsMasterPlaylist(masterUrl, emptyList(), emptyList())
        }
        val lines = playlistText.lineSequence().map { it.trim() }.toList()
        val rawAudioTracks = ArrayList<HlsAudioTrack>()
        data class RawVariant(
            val height: Int,
            val fps: Int,
            val bandwidth: Long,
            val url: String,
            val audioGroupId: String?,
        )
        val rawVariants = ArrayList<RawVariant>()

        var idx = 0
        while (idx < lines.size) {
            val line = lines[idx]
            if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) {
                val attrs = parseHlsAttributes(line.substringAfter(':'))
                if (attrs["TYPE"].equals("AUDIO", ignoreCase = true)) {
                    val uri = attrs["URI"]?.takeIf { it.isNotBlank() }?.let { resolveUrl(masterUrl, it) }
                    rawAudioTracks += HlsAudioTrack(
                        groupId = attrs["GROUP-ID"].orEmpty(),
                        name = attrs["NAME"].orEmpty(),
                        language = attrs["LANGUAGE"]?.takeIf { it.isNotBlank() },
                        uri = uri,
                        isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true),
                        isAutoSelect = attrs["AUTOSELECT"].equals("YES", ignoreCase = true),
                        channels = attrs["CHANNELS"]?.takeIf { it.isNotBlank() },
                    )
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val attrs = parseHlsAttributes(line.substringAfter(':'))
                var nextIdx = idx + 1
                while (nextIdx < lines.size && (lines[nextIdx].isEmpty() || lines[nextIdx].startsWith("#"))) {
                    nextIdx++
                }
                val relUrl = lines.getOrNull(nextIdx)
                val resolved = relUrl?.let { resolveUrl(masterUrl, it) }
                if (resolved != null) {
                    val codecs = attrs["CODECS"].orEmpty().lowercase()
                    val resStr = attrs["RESOLUTION"].orEmpty()
                    val resHeight = resStr.lowercase().substringAfter('x', "").trim().toIntOrNull()
                    val isAudioOnly = resHeight == null && codecs.isNotEmpty() &&
                        (codecs.contains("mp4a") || codecs.contains("ac-3") || codecs.contains("ec-3") || codecs.contains("opus")) &&
                        !codecs.contains("avc") && !codecs.contains("hev") && !codecs.contains("hvc") && !codecs.contains("vp0") && !codecs.contains("av01")
                    if (!isAudioOnly) {
                        val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
                        val height = resHeight
                            ?: QUALITY_HINT.find(attrs["NAME"].orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: QUALITY_HINT.find(resolved)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: when {
                                bandwidth >= 3_500_000L -> 1080
                                bandwidth >= 1_800_000L -> 720
                                bandwidth >= 700_000L -> 480
                                else -> 360
                            }
                        val fps = attrs["FRAME-RATE"]?.toFloatOrNull()?.roundToInt()?.takeIf { it > 0 } ?: 30
                        rawVariants += RawVariant(
                            height = height,
                            fps = fps,
                            bandwidth = bandwidth,
                            url = resolved,
                            audioGroupId = attrs["AUDIO"]?.takeIf { it.isNotBlank() },
                        )
                    }
                    idx = nextIdx
                }
            }
            idx++
        }

        val sortedRaw = rawVariants
            .sortedWith(compareByDescending<RawVariant> { it.height }.thenByDescending { it.bandwidth })
            .distinctBy { it.height }
        if (sortedRaw.isEmpty()) {
            return HlsMasterPlaylist(masterUrl, emptyList(), emptyList())
        }

        val byGroup = rawAudioTracks.groupBy { it.groupId }
        val preferredGroupId = sortedRaw.firstNotNullOfOrNull { v ->
            v.audioGroupId?.takeIf { !byGroup[it].isNullOrEmpty() }
        } ?: rawAudioTracks.firstOrNull()?.groupId

        fun pickDefaultAudio(group: List<HlsAudioTrack>): HlsAudioTrack? =
            group.firstOrNull { it.isDefault }
                ?: group.firstOrNull { it.normalizedLanguage == "tr" }
                ?: group.firstOrNull { it.isAutoSelect }
                ?: group.firstOrNull()

        val primaryGroup = preferredGroupId?.let { byGroup[it] }.orEmpty().ifEmpty { rawAudioTracks }
        val distinctAudio = primaryGroup.distinctBy {
            "${it.normalizedLanguage ?: "und"}|${it.displayLabel.lowercase()}|${it.uri.orEmpty()}"
        }
        val defaultAudio = pickDefaultAudio(distinctAudio)
        var defaultAudioId: String? = null
        val audioTracks = distinctAudio.mapIndexed { aIdx, t ->
            val lang = t.normalizedLanguage
            val id = "hls:${lang ?: "und"}:${t.groupId.ifBlank { "default" }}:$aIdx"
            if (t == defaultAudio) defaultAudioId = id
            val isOrig = !t.isDefault && (
                lang == "en" ||
                    t.name.contains("original", ignoreCase = true) ||
                    t.name.contains("orijinal", ignoreCase = true)
                )
            VideoAudioTrack(
                id = id,
                label = t.displayLabel,
                url = t.uri.orEmpty(),
                isOriginal = isOrig,
                language = lang,
            )
        }

        val hasSeparateAudio = rawAudioTracks.isNotEmpty()
        val variants = sortedRaw.map { v ->
            val groupTracks = v.audioGroupId?.let { byGroup[it] }.orEmpty().ifEmpty { primaryGroup }
            val matchedAudio = pickDefaultAudio(groupTracks)
            VideoQuality(
                height = v.height,
                fps = v.fps,
                videoUrl = v.url,
                audioUrl = matchedAudio?.uri,
                isLive = false,
                headers = headers,
            )
        }
        val topFps = sortedRaw.firstOrNull()?.fps ?: 30
        val autoMaster = VideoQuality(
            height = 0,
            fps = topFps,
            videoUrl = masterUrl,
            audioUrl = null,
            isLive = false,
            headers = headers,
        )
        return HlsMasterPlaylist(
            masterUrl = masterUrl,
            variants = listOf(autoMaster) + variants,
            audioTracks = audioTracks,
            rawAudioTracks = rawAudioTracks,
            defaultAudioTrackId = defaultAudioId,
            hasSeparateAudio = hasSeparateAudio,
        )
    }

    /** Parses an HLS master playlist (`#EXT-X-STREAM-INF`) into individual quality options + Auto master. */
    fun parseHlsVariants(playlistText: String, masterUrl: String, headers: Map<String, String>): List<VideoQuality> =
        parseHlsMasterPlaylist(playlistText, masterUrl, headers).variants

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
        val overview = WSTREAM_OVERVIEW.find(html)?.groupValues?.getOrNull(1)
            ?.let { STRIP_TAGS.replace(it, " ") }
        val meta = META_DESC.find(html)?.groupValues?.getOrNull(1)
            ?: META_DESC_REV.find(html)?.groupValues?.getOrNull(1)
        return (ld ?: overview ?: meta)?.let(::decodeEntities)?.replace("\\n", " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(420)?.takeIf { it.length >= 12 }
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
        if (isDizidadPage(pageUrl, html)) {
            extractDizidadCatalogPage(html, pageUrl, sourceName, headers)?.let { return it }
        }
        val rawItems = extractCatalog(html, pageUrl, sourceName, headers)
        val items = if (isDizidadPage(pageUrl, html) && rawItems.any { it.pageUrl.contains("/diziler/", ignoreCase = true) }) {
            rawItems.filter { it.pageUrl.contains("/diziler/", ignoreCase = true) }
        } else {
            rawItems
        }
        val rawNextUrl = (NEXT_LINK_REL.find(html)?.groupValues?.getOrNull(1)
            ?: NEXT_LINK_REL_REV.find(html)?.groupValues?.getOrNull(1)
            ?: NEXT_LINK_CLASS.find(html)?.groupValues?.getOrNull(1))
            ?.let { resolveUrl(pageUrl, it) }
            ?.takeIf { !it.equals(pageUrl, ignoreCase = true) }
        val nextUrl = rawNextUrl ?: if (items.isNotEmpty() && isHdFilmCehennemiPage(pageUrl, html)) {
            extractHdFilmCehennemiNextPage(html, pageUrl)
        } else {
            null
        }

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
            ?: SOURCE_SRCSET_ATTR.find(block)?.groupValues?.getOrNull(1)?.let(::pickBestSrcsetUrl)
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
            if ('{' in href || '}' in href) return
            if (href.lowercase() == pageUrl.lowercase() || href.lowercase() == "$pageOrigin/" || href.lowercase() == pageOrigin) return
            val (poster, alt) = extractPosterFromBlock(fullBlock, pageUrl)
            if (poster == null || '{' in poster || '}' in poster) return

            val plainBlock = decodeEntities(STRIP_TAGS.replace(fullBlock, " ")).replace(Regex("\\s+"), " ").trim()
            val titleRaw = POSTER_TITLE_REGEX.find(fullBlock)?.groupValues?.getOrNull(1)
                ?: HEADING_TEXT.find(fullBlock)?.groupValues?.getOrNull(1)
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
                poster = upgradeMirrorPosterUrl(poster),
                year = year,
                rating = rating,
                qualityBadge = badge,
                sourceName = sourceName,
                headers = headers,
            )
        }

        val cleanHtml = TEMPLATE_BLOCK.replace(html, "")
        // Pass 1: <article> cards (handles WordPress / CMS themes where <img> and <a> are siblings).
        ARTICLE_BLOCK.findAll(cleanHtml).forEach { art ->
            val block = art.groupValues[1]
            val anchor = FIRST_ANCHOR.find(block) ?: return@forEach
            val href = resolveUrl(pageUrl, anchor.groupValues[2]) ?: return@forEach
            addCard(href, anchor.groupValues[1], anchor.groupValues[3], block)
        }

        // Pass 2: Direct <a href="..."><img ...></a> or <a data-bg="..."> cards.
        ANCHOR_WITH_IMG.findAll(cleanHtml).forEach { match ->
            val anchorAttrs = match.groupValues[1]
            val href = resolveUrl(pageUrl, match.groupValues[2]) ?: return@forEach
            val body = match.groupValues[3]
            addCard(href, anchorAttrs, body, anchorAttrs + " " + body)
        }
        return cards
    }

    private fun isLikelyStream(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") ||
            path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".css") || path.endsWith(".js") ||
            path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php")
        ) {
            return false
        }
        if (lower.contains("wepama") || lower.contains("wepam") || lower.contains("dizifilmizle") || lower.contains("preroll") || lower.contains("/ad/") || lower.contains("ad_") || lower.contains("banner") || lower.contains("kumar") || lower.contains("bet")) {
            return false
        }
        return lower.contains(".m3u8") ||
            lower.contains(".mpd") ||
            lower.contains(".mp4") ||
            lower.contains(".mkv") ||
            lower.contains(".webm") ||
            lower.contains("/master.txt") ||
            path.endsWith("/playlist.txt") ||
            path.endsWith("/index.txt")
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
        // Text taken out of JSON keeps its escapes: "Y\u00fcz\u00fck" -> "Yüzük", "\/" -> "/", "\"" -> ".
        if (res.contains("\\u")) {
            res = Regex("""\\u([0-9a-fA-F]{4})""").replace(res) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        }
        if (res.contains('\\')) res = res.replace("\\/", "/").replace("\\\"", "\"")
        return res
    }

    internal fun percentEncode(s: String): String = buildString {
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

/** Plain HTTP response handed to the generic resolver; each platform fills it from its own OkHttp client. */
internal data class ScraperResponse(val finalUrl: String, val body: String, val contentType: String) {
    /** True when the server answered with a video/HLS/DASH resource instead of an HTML page. */
    val isMedia: Boolean
        get() = contentType.startsWith("video/") || contentType.contains("mpegurl") ||
            contentType.contains("dash+xml") || MovieHtmlParser.isDirectMediaUrl(finalUrl)
}

/** GET request used by the generic resolver: `(url, headers, referer) -> response or null`. Must not throw for HTTP errors. */
internal typealias ScraperFetcher = suspend (url: String, headers: Map<String, String>, referer: String?) -> ScraperResponse?

/** POST form request used by backend API resolvers: `(url, formParams, headers) -> response or null`. */
internal typealias ScraperFormPoster = suspend (url: String, formParams: Map<String, String>, headers: Map<String, String>) -> ScraperResponse?

/** Output of [resolveGenericStreams] / [expandGenericHls]. */
internal data class GenericStreamResult(
    val streams: List<VideoQuality> = emptyList(),
    val captions: List<VideoCaption> = emptyList(),
    val audioTracks: List<VideoAudioTrack> = emptyList(),
    val defaultAudioTrackId: String? = null,
)

private class EmbedHop(
    val url: String,
    val streams: List<VideoQuality>,
    val captions: List<VideoCaption>,
    val children: List<String>,
)

private fun mediaQuality(url: String, headers: Map<String, String>, height: Int? = null): VideoQuality {
    val h = height
        ?: Regex("""(?i)(2160|1440|1080|720|480|360)p""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: if (MovieHtmlParser.isHlsCandidateUrl(url) || url.contains(".mpd", true)) 1080 else 720
    return VideoQuality(height = h, fps = 30, videoUrl = url, audioUrl = null, isLive = false, headers = headers)
}

/** Runs [block], turning failures into null but never swallowing coroutine cancellation. */
private suspend inline fun <T> probeOrNull(block: () -> T?): T? = try {
    block()
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
    throw e
} catch (_: Throwable) {
    null
}

/** Opens one embed page and reports its streams, `<track>` captions and nested iframes. */
private suspend fun probeEmbed(
    embedUrl: String,
    parentUrl: String,
    headers: Map<String, String>,
    fetch: ScraperFetcher,
): EmbedHop? {
    val res = probeOrNull { fetch(embedUrl, headers, parentUrl) } ?: return null
    if (res.isMedia) {
        // The embed URL redirected straight to the media file: the CDN expects the parent page as Referer.
        val hdrs = headers + ("Referer" to (MovieHtmlParser.originOf(parentUrl) + "/"))
        return EmbedHop(res.finalUrl, listOf(mediaQuality(res.finalUrl, hdrs)), emptyList(), emptyList())
    }
    val np = MovieHtmlParser.extractNpConfigStreams(res.body, res.finalUrl, headers)
    val found = (np.streams + MovieHtmlParser.extractStreams(res.body, res.finalUrl, headers)).distinctBy { it.videoUrl }
    val caps = (np.captions + MovieHtmlParser.extractCaptions(res.body, res.finalUrl)).distinctBy { it.url }
    val children = if (found.isEmpty()) MovieHtmlParser.extractEmbedIframes(res.body, res.finalUrl) else emptyList()
    return EmbedHop(res.finalUrl, found, caps, children)
}

/**
 * Generic, site-independent resolver for a new film source: follows the page's player iframes
 * (plain, `data-*` and Base64 embeds) level by level, probing each level in parallel, and collects
 * HLS/DASH/MP4 links found via `<video>`/`<source>`, player JSON (`file:`, `hls:`), packed JS and `atob(...)`.
 *
 * - Iframes are tried first (the page itself often carries only trailers or ads); the page's own
 *   streams are used as a fallback.
 * - Stops at the first level that yields a stream; at most [maxPerLevel] embeds per level, [maxDepth] levels.
 * - Each stream keeps the Referer of the page it was found on, so Referer-protected CDNs play in Media3.
 * - With [expandHls], the first HLS master is opened and split into quality variants + audio renditions.
 *
 * The caller supplies [fetch] (platform OkHttp) and the already downloaded [html] of [pageUrl].
 */
internal suspend fun resolveGenericStreams(
    pageUrl: String,
    html: String,
    headers: Map<String, String>,
    fetch: ScraperFetcher,
    maxDepth: Int = 2,
    maxPerLevel: Int = 4,
    expandHls: Boolean = true,
): GenericStreamResult {
    val streams = LinkedHashMap<String, VideoQuality>()
    val captions = ArrayList<VideoCaption>(MovieHtmlParser.extractCaptions(html, pageUrl))
    val visited = HashSet<String>().apply { add(pageUrl) }

    var frontier: List<Pair<String, String>> = MovieHtmlParser.extractEmbedIframes(html, pageUrl)
        .filter { visited.add(it) }
        .take(maxPerLevel)
        .map { it to pageUrl }
    var depth = 0
    while (frontier.isNotEmpty() && streams.isEmpty() && depth < maxDepth) {
        val hops = supervisorScope {
            frontier.map { (embed, parent) -> async { probeEmbed(embed, parent, headers, fetch) } }.awaitAll()
        }
        val next = ArrayList<Pair<String, String>>()
        hops.filterNotNull().forEach { hop ->
            hop.streams.forEach { streams.getOrPut(it.videoUrl) { it } }
            captions += hop.captions
            hop.children.forEach { child -> if (visited.add(child)) next += child to hop.url }
        }
        frontier = next.take(maxPerLevel)
        depth++
    }

    if (streams.isEmpty()) {
        val np = MovieHtmlParser.extractNpConfigStreams(html, pageUrl, headers)
        np.streams.forEach { streams.getOrPut(it.videoUrl) { it } }
        captions += np.captions
        MovieHtmlParser.extractStreams(html, pageUrl, headers).forEach { streams.getOrPut(it.videoUrl) { it } }
    }

    val found = GenericStreamResult(streams = streams.values.toList(), captions = captions.distinctBy { it.url })
    if (!expandHls || found.streams.isEmpty()) return found
    val expanded = expandGenericHls(found.streams, fetch)
    return expanded.copy(captions = found.captions)
}

/**
 * Opens the first HLS candidate in [streams] and, if it is a master playlist, replaces it with its
 * `#EXT-X-STREAM-INF` quality variants and `#EXT-X-MEDIA` audio tracks. Media playlists and failures
 * leave [streams] unchanged.
 */
internal suspend fun expandGenericHls(streams: List<VideoQuality>, fetch: ScraperFetcher): GenericStreamResult {
    val master = streams.firstOrNull { MovieHtmlParser.isHlsCandidateUrl(it.videoUrl) }
        ?: return GenericStreamResult(streams = streams)
    val fetched = probeOrNull { fetch(master.videoUrl, master.headers, master.headers["Referer"]) }
        ?: return GenericStreamResult(streams = streams)
    val parsed = MovieHtmlParser.parseHlsMasterPlaylist(fetched.body, fetched.finalUrl, master.headers)
    if (parsed.variants.isEmpty()) return GenericStreamResult(streams = streams)
    val rest = streams.filterNot { it.videoUrl == master.videoUrl }
    return GenericStreamResult(
        streams = (parsed.variants + rest).distinctBy { it.videoUrl to it.audioUrl },
        audioTracks = parsed.audioTracks,
        defaultAudioTrackId = parsed.defaultAudioTrackId,
    )
}

/**
 * Handles HDFilmCehennemi's `/load/page/{n}/...` AJAX pagination (HTML or JSON-wrapped HTML) and
 * `/search?q=...` (HTML or JSON search payload), returning `null` for standard catalog/genre pages.
 */
internal suspend fun fetchHdFilmCehennemiCatalogOrNull(
    source: MovieSource,
    query: String,
    pageUrlOverride: String?,
    headers: Map<String, String>,
    fetch: ScraperFetcher,
): MovieCatalogPage? {
    val q = query.trim()
    val target = pageUrlOverride?.takeIf { it.isNotBlank() } ?: MovieHtmlParser.buildSourceUrl(source, q)
    val isHdfcSource = source.id.equals("hdfilmcehennemi", ignoreCase = true) ||
        source.baseUrl.contains("hdfilmcehennemi", ignoreCase = true) ||
        target.contains("/load/page/", ignoreCase = true)
    if (!isHdfcSource) return null

    val origin = source.normalizedBaseUrl
    val referer = "$origin/"

    if (target.contains("/load/page/", ignoreCase = true)) {
        val ajaxHeaders = headers + mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, text/html, */*")
        val res = probeOrNull { fetch(target, ajaxHeaders, referer) }
            ?: probeOrNull { fetch(target, headers, referer) }
            ?: return null
        val unwrapped = MovieHtmlParser.unwrapHdFilmCehennemiLoadResponse(res.body)
        return MovieHtmlParser.extractCatalogPage(unwrapped, res.finalUrl, source.name, headers)
    }

    if (q.isNotEmpty() && pageUrlOverride.isNullOrBlank()) {
        val res = probeOrNull { fetch(target, headers, referer) }
        if (res != null) {
            val trimmed = res.body.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                val jsonCards = MovieHtmlParser.parseHdFilmCehennemiSearchJson(res.body, res.finalUrl, source.name, headers)
                if (jsonCards.isNotEmpty()) return MovieCatalogPage(items = jsonCards)
            }
            val unwrapped = MovieHtmlParser.unwrapHdFilmCehennemiLoadResponse(res.body)
            val page = MovieHtmlParser.extractCatalogPage(unwrapped, res.finalUrl, source.name, headers)
            if (page.items.isNotEmpty()) return page
        }

        val ajaxHeaders = headers + mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, text/html, */*")
        val ajaxRes = probeOrNull { fetch(target, ajaxHeaders, referer) } ?: return null
        val jsonCards = MovieHtmlParser.parseHdFilmCehennemiSearchJson(ajaxRes.body, ajaxRes.finalUrl, source.name, headers)
        if (jsonCards.isNotEmpty()) return MovieCatalogPage(items = jsonCards)
        val unwrapped = MovieHtmlParser.unwrapHdFilmCehennemiLoadResponse(ajaxRes.body)
        return MovieHtmlParser.extractCatalogPage(unwrapped, ajaxRes.finalUrl, source.name, headers)
    }

    return null
}

/**
 * Resolves an HDFilmCehennemi movie, TV series overview, or Rapidrame/RPlayer page into playable HLS/MP4
 * streams and WebVTT/SRT captions:
 * 1. Direct player page (`file_link`, `sources`, `dc_hello`, `tracks`, packed JS)
 * 2. Series overview -> first episode jump (`seasons-tab-content` / `seasonsTabs-tabContent`)
 * 3. Parallel `/video/{data-video}/` lookups -> embedded iframe -> player stream + nested iframe fallback.
 */
internal suspend fun resolveHdFilmCehennemiStreams(
    pageUrl: String,
    html: String,
    headers: Map<String, String>,
    fetch: ScraperFetcher,
    expandHls: Boolean = false,
): GenericStreamResult {
    val directPlayer = MovieHtmlParser.extractHdFilmCehennemiPlayerStreams(html, pageUrl, headers)
    val hasExplicitPlayerConfig = html.contains("file_link", ignoreCase = true) ||
        html.contains("dc_hello", ignoreCase = true) ||
        (html.contains("sources", ignoreCase = true) && html.contains("tracks", ignoreCase = true)) ||
        pageUrl.contains("/rplayer/", ignoreCase = true) ||
        pageUrl.contains("rapidrame", ignoreCase = true)
    if (hasExplicitPlayerConfig && directPlayer.streams.isNotEmpty()) {
        if (!expandHls) return directPlayer
        val expanded = expandGenericHls(directPlayer.streams, fetch)
        return expanded.copy(captions = directPlayer.captions)
    }

    if (!MovieHtmlParser.isHdFilmCehennemiPage(pageUrl, html)) {
        return GenericStreamResult()
    }

    var activeHtml = html
    var activeUrl = pageUrl
    var options = MovieHtmlParser.extractHdFilmCehennemiVideoOptions(activeHtml, activeUrl)

    if (options.isEmpty()) {
        val firstEp = MovieHtmlParser.extractHdFilmCehennemiFirstEpisode(activeHtml, activeUrl)
        if (firstEp != null) {
            val epRes = probeOrNull { fetch(firstEp, headers, activeUrl) }
            if (epRes != null) {
                activeHtml = epRes.body
                activeUrl = epRes.finalUrl
                options = MovieHtmlParser.extractHdFilmCehennemiVideoOptions(activeHtml, activeUrl)
            }
        }
    }

    val streams = LinkedHashMap<String, VideoQuality>()
    val captions = ArrayList<VideoCaption>(MovieHtmlParser.extractCaptions(activeHtml, activeUrl))

    if (options.isNotEmpty()) {
        val ajaxHeaders = headers + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/html, */*",
        )
        val probes = supervisorScope {
            options.take(4).map { opt ->
                async {
                    val reqUrl = MovieHtmlParser.buildHdFilmCehennemiVideoRequestUrl(opt.videoIdOrUrl, activeUrl)
                        ?: return@async null
                    val res = if (reqUrl.contains("/video/", ignoreCase = true)) {
                        probeOrNull { fetch(reqUrl, ajaxHeaders, activeUrl) }
                            ?: probeOrNull { fetch(reqUrl, headers, activeUrl) }
                    } else {
                        probeOrNull { fetch(reqUrl, headers, activeUrl) }
                            ?: probeOrNull { fetch(reqUrl, ajaxHeaders, activeUrl) }
                    } ?: return@async null
                    if (res.isMedia) {
                        val hdrs = headers + ("Referer" to (MovieHtmlParser.originOf(activeUrl) + "/"))
                        return@async GenericStreamResult(streams = listOf(mediaQuality(res.finalUrl, hdrs)))
                    }
                    val directFromVideoApi = MovieHtmlParser.extractHdFilmCehennemiPlayerStreams(res.body, res.finalUrl, headers)
                    if (directFromVideoApi.streams.isNotEmpty()) {
                        return@async directFromVideoApi
                    }
                    val embedUrl = MovieHtmlParser.extractHdFilmCehennemiEmbedUrl(res.body, res.finalUrl)
                        ?: return@async null
                    val embedRes = probeOrNull { fetch(embedUrl, headers, activeUrl) }
                        ?: return@async null
                    if (embedRes.isMedia) {
                        val hdrs = headers + ("Referer" to (MovieHtmlParser.originOf(activeUrl) + "/"))
                        return@async GenericStreamResult(streams = listOf(mediaQuality(embedRes.finalUrl, hdrs)))
                    }
                    val fromPlayer = MovieHtmlParser.extractHdFilmCehennemiPlayerStreams(embedRes.body, embedRes.finalUrl, headers)
                    if (fromPlayer.streams.isNotEmpty()) {
                        return@async fromPlayer
                    }
                    resolveGenericStreams(
                        pageUrl = embedRes.finalUrl,
                        html = embedRes.body,
                        headers = headers,
                        fetch = fetch,
                        maxDepth = 2,
                        maxPerLevel = 3,
                        expandHls = false,
                    )
                }
            }.awaitAll()
        }
        probes.filterNotNull().forEach { res ->
            res.streams.forEach { streams.getOrPut(it.videoUrl) { it } }
            captions += res.captions
        }
    }

    if (streams.isEmpty()) {
        val fallback = resolveGenericStreams(activeUrl, activeHtml, headers, fetch, expandHls = false)
        fallback.streams.forEach { streams.getOrPut(it.videoUrl) { it } }
        captions += fallback.captions
    }

    val orderedStreams = streams.values.sortedWith(
        compareByDescending<VideoQuality> { MovieHtmlParser.isHlsCandidateUrl(it.videoUrl) || it.videoUrl.contains(".mpd", true) }
            .thenByDescending { it.height },
    )
    val result = GenericStreamResult(streams = orderedStreams, captions = captions.distinctBy { it.url })
    if (!expandHls || result.streams.isEmpty()) return result
    val expanded = expandGenericHls(result.streams, fetch)
    return expanded.copy(captions = result.captions)
}

/**
 * Resolves an HDFilmCehennemi film, TV series overview, or episode page (including `.vip` `videoPlayerData` +
 * `vidload.top` JWPlayer streams and multi-season episode lists) into a full [ResolvedMovieStream].
 */
internal suspend fun resolveHdFilmCehennemiStreamOrNull(
    pageUrl: String,
    html: String,
    headers: Map<String, String>,
    fetch: ScraperFetcher,
): ResolvedMovieStream? {
    if (!MovieHtmlParser.isHdFilmCehennemiPage(pageUrl, html)) return null

    val pageTitle = MovieHtmlParser.cleanMovieTitle(MovieHtmlParser.extractTitle(html))
    val pagePoster = MovieHtmlParser.extractPoster(html, pageUrl)
    val pageSynopsis = MovieHtmlParser.extractSynopsis(html)
    val pageYear = MovieHtmlParser.extractYear(html)
    val pageRating = MovieHtmlParser.extractRating(html)
    val origin = MovieHtmlParser.originOf(pageUrl)

    val (rawSeriesLink, episodes) = MovieHtmlParser.extractHdFilmCehennemiVipSeriesInfo(html, pageUrl)
    val seasons = episodes.map { it.season }.distinct().sorted()
    val firstEpUrl = MovieHtmlParser.extractHdFilmCehennemiFirstEpisode(html, pageUrl)
    val isEpisodeUrl = pageUrl.contains("/bolum-", ignoreCase = true)
    val resolvedSeriesUrl = when {
        rawSeriesLink != null -> rawSeriesLink
        seasons.isNotEmpty() && !isEpisodeUrl -> pageUrl
        else -> null
    }

    val generic = resolveHdFilmCehennemiStreams(pageUrl, html, headers, fetch, expandHls = true)
    if (generic.streams.isEmpty()) return null
    val activeEp = when {
        isEpisodeUrl -> pageUrl
        firstEpUrl != null -> firstEpUrl
        episodes.isNotEmpty() -> episodes.first().pageUrl
        else -> null
    }
    return ResolvedMovieStream(
        title = pageTitle,
        poster = pagePoster,
        synopsis = pageSynopsis,
        year = pageYear,
        rating = pageRating,
        streams = generic.streams,
        captions = generic.captions,
        audioTracks = generic.audioTracks,
        defaultAudioTrackId = generic.defaultAudioTrackId,
        seasons = seasons,
        seriesOrigin = origin,
        seriesUrl = resolvedSeriesUrl,
        episodes = episodes,
        activeEpisodeUrl = activeEp,
    )
}

/**
 * Fetches Diziyou live search results (`/?s={query}` HTML cards first, falling back to the
 * `/wp-json/wstream/v1/search?s=...` JSON endpoint), returning `null` for non-search or non-Diziyou requests.
 */
internal suspend fun fetchDiziyouCatalogOrNull(
    source: MovieSource,
    query: String,
    pageUrlOverride: String?,
    headers: Map<String, String>,
    fetch: ScraperFetcher,
): MovieCatalogPage? {
    val q = query.trim()
    if (q.isEmpty() || !pageUrlOverride.isNullOrBlank()) return null
    val isDiziyou = source.id.equals("diziyou", ignoreCase = true) ||
        source.baseUrl.contains("diziyou", ignoreCase = true)
    if (!isDiziyou) return null

    val origin = source.normalizedBaseUrl
    val searchUrl = MovieHtmlParser.buildSourceUrl(source, q)
    val htmlRes = probeOrNull { fetch(searchUrl, headers, "$origin/") }
    if (htmlRes != null) {
        val page = MovieHtmlParser.extractCatalogPage(htmlRes.body, htmlRes.finalUrl, source.name, headers)
        if (page.items.isNotEmpty()) return page
    }

    val apiUrl = "$origin/wp-json/wstream/v1/search?s=${MovieHtmlParser.percentEncode(q)}"
    val apiHeaders = headers + mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest")
    val apiRes = probeOrNull { fetch(apiUrl, apiHeaders, "$origin/") } ?: return null
    val jsonItems = MovieHtmlParser.parseDiziyouSearchJson(apiRes.body, origin, source.name, headers)
    return if (jsonItems.isNotEmpty()) MovieCatalogPage(items = jsonItems) else null
}

/**
 * Fetches all episodes for a single [season] of a Diziyou TV series via `/wp-json/wstream/v1/retrieve-episodes`,
 * falling back to HTML episode cards from the series page when available.
 */
internal suspend fun fetchDiziyouSeasonEpisodes(
    seriesUrl: String,
    tvshowId: String,
    season: Int,
    headers: Map<String, String>,
    fetch: ScraperFetcher,
    fallbackHtml: String? = null,
): List<MovieEpisode> {
    val origin = MovieHtmlParser.originOf(seriesUrl)
    val apiUrl = "$origin/wp-json/wstream/v1/retrieve-episodes/$tvshowId?season=$season&id=$tvshowId"
    val apiHeaders = headers + mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest")
    val res = probeOrNull { fetch(apiUrl, apiHeaders, seriesUrl) }
    val fromApi = res?.body?.let { MovieHtmlParser.parseDiziyouEpisodesJson(it, origin) }.orEmpty()
    if (fromApi.isNotEmpty()) return fromApi
    if (!fallbackHtml.isNullOrBlank()) {
        val fromHtml = MovieHtmlParser.extractDiziyouHtmlEpisodes(fallbackHtml, seriesUrl)
            .filter { it.season == season }
        if (fromHtml.isNotEmpty()) return fromHtml
    }
    return emptyList()
}

/**
 * Resolves a Diziyou series overview page (`/dizi/...`) or episode/embed page (`/bolum/...`, `dzembed.php`)
 * into playable `master.m3u8` + multi-audio tracks + `.vtt` subtitles + season/episode metadata.
 * When called on a series overview page, jumps cleanly to Season 1 Episode 1 instead of the latest spoiler episode.
 */
internal suspend fun resolveDiziyouStreamOrNull(
    pageUrl: String,
    html: String,
    headers: Map<String, String>,
    fetch: ScraperFetcher,
): ResolvedMovieStream? {
    if (!MovieHtmlParser.isDiziyouPage(pageUrl, html)) return null

    // Direct NP_CONFIG embed page (`ksdpictures.site/dzembed.php`)
    val directNp = MovieHtmlParser.extractNpConfigStreams(html, pageUrl, headers)
    if (directNp.streams.isNotEmpty()) {
        val expanded = expandGenericHls(directNp.streams, fetch)
        return ResolvedMovieStream(
            title = MovieHtmlParser.extractTitle(html),
            poster = MovieHtmlParser.extractPoster(html, pageUrl),
            synopsis = MovieHtmlParser.extractSynopsis(html),
            year = MovieHtmlParser.extractYear(html),
            rating = MovieHtmlParser.extractRating(html),
            streams = expanded.streams,
            captions = directNp.captions,
            audioTracks = expanded.audioTracks,
            defaultAudioTrackId = expanded.defaultAudioTrackId,
        )
    }

    val pageTitle = MovieHtmlParser.cleanMovieTitle(MovieHtmlParser.extractTitle(html))
    val pagePoster = MovieHtmlParser.extractPoster(html, pageUrl)
    val pageSynopsis = MovieHtmlParser.extractSynopsis(html)
    val pageYear = MovieHtmlParser.extractYear(html)
    val pageRating = MovieHtmlParser.extractRating(html)
    val (tvshowId, seasons) = MovieHtmlParser.extractDiziyouSeriesMeta(html)
    val origin = MovieHtmlParser.originOf(pageUrl)

    val isSeriesOverview = tvshowId != null || seasons.isNotEmpty() || pageUrl.contains("/dizi/", ignoreCase = true)
    if (isSeriesOverview) {
        val firstSeason = seasons.minOrNull() ?: 1
        val episodes = when {
            tvshowId != null -> fetchDiziyouSeasonEpisodes(pageUrl, tvshowId, firstSeason, headers, fetch, fallbackHtml = html)
                .ifEmpty { MovieHtmlParser.extractDiziyouHtmlEpisodes(html, pageUrl) }
            else -> MovieHtmlParser.extractDiziyouHtmlEpisodes(html, pageUrl)
        }
        val slug = pageUrl.substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
        val targetEpisodeUrl = episodes.firstOrNull()?.pageUrl
            ?: slug?.let { "$origin/bolum/$it-$firstSeason-sezon-1-bolum/" }
        if (targetEpisodeUrl != null) {
            val epRes = probeOrNull { fetch(targetEpisodeUrl, headers, pageUrl) }
            if (epRes != null) {
                val resolvedEp = resolveGenericStreams(epRes.finalUrl, epRes.body, headers, fetch, expandHls = true)
                if (resolvedEp.streams.isNotEmpty()) {
                    return ResolvedMovieStream(
                        title = pageTitle,
                        poster = pagePoster,
                        synopsis = pageSynopsis,
                        year = pageYear,
                        rating = pageRating,
                        streams = resolvedEp.streams,
                        captions = resolvedEp.captions,
                        audioTracks = resolvedEp.audioTracks,
                        defaultAudioTrackId = resolvedEp.defaultAudioTrackId,
                        seasons = seasons,
                        tvshowId = tvshowId,
                        seriesOrigin = origin,
                        seriesUrl = pageUrl,
                        episodes = episodes,
                        activeEpisodeUrl = epRes.finalUrl,
                    )
                }
            }
        }
    }

    val parentDiziUrl = MovieHtmlParser.extractDiziyouParentSeriesUrl(html, pageUrl)
    val (generic, parentMeta) = supervisorScope {
        val streamDeferred = async { resolveGenericStreams(pageUrl, html, headers, fetch, expandHls = true) }
        val parentDeferred = async {
            val pUrl = parentDiziUrl ?: return@async null
            val pRes = probeOrNull { fetch(pUrl, headers, pageUrl) } ?: return@async null
            val (pId, pSeasons) = MovieHtmlParser.extractDiziyouSeriesMeta(pRes.body)
            val activeSeason = MovieHtmlParser.extractDiziyouEpisodeSeason(pageUrl, html)
                ?: pSeasons.firstOrNull()
                ?: 1
            val pEpisodes = when {
                pId != null -> fetchDiziyouSeasonEpisodes(pUrl, pId, activeSeason, headers, fetch, fallbackHtml = pRes.body)
                    .ifEmpty { MovieHtmlParser.extractDiziyouHtmlEpisodes(pRes.body, pUrl) }
                else -> MovieHtmlParser.extractDiziyouHtmlEpisodes(pRes.body, pUrl)
            }
            ResolvedMovieStream(
                title = MovieHtmlParser.cleanMovieTitle(MovieHtmlParser.extractTitle(pRes.body)),
                poster = MovieHtmlParser.extractPoster(pRes.body, pUrl),
                synopsis = MovieHtmlParser.extractSynopsis(pRes.body),
                year = MovieHtmlParser.extractYear(pRes.body),
                rating = MovieHtmlParser.extractRating(pRes.body),
                seasons = pSeasons,
                tvshowId = pId,
                seriesUrl = pUrl,
                episodes = pEpisodes,
            )
        }
        streamDeferred.await() to parentDeferred.await()
    }
    if (generic.streams.isEmpty()) return null
    val hasSeriesContext = parentMeta != null && (parentMeta.seasons.isNotEmpty() || parentMeta.episodes.isNotEmpty())
    return ResolvedMovieStream(
        title = parentMeta?.title ?: pageTitle,
        poster = pagePoster ?: parentMeta?.poster,
        synopsis = pageSynopsis ?: parentMeta?.synopsis,
        year = pageYear ?: parentMeta?.year,
        rating = pageRating ?: parentMeta?.rating,
        streams = generic.streams,
        captions = generic.captions,
        audioTracks = generic.audioTracks,
        defaultAudioTrackId = generic.defaultAudioTrackId,
        seasons = parentMeta?.seasons.orEmpty(),
        tvshowId = parentMeta?.tvshowId,
        seriesOrigin = origin,
        seriesUrl = parentMeta?.seriesUrl,
        episodes = parentMeta?.episodes.orEmpty(),
        activeEpisodeUrl = if (hasSeriesContext) pageUrl else null,
    )
}

/**
 * Routes Dizimom/Dizidad catalog requests to the clean `/1-dizi-arsivi/` series archive when `catalogPath` is `/`,
 * preventing individual episode cards from cluttering the main feed or search results.
 */
internal suspend fun fetchDizidadCatalogOrNull(
    source: MovieSource,
    query: String,
    pageUrlOverride: String?,
    headers: Map<String, String>,
    fetch: ScraperFetcher,
): MovieCatalogPage? {
    val isDizidad = source.id.equals("dizimom", ignoreCase = true) ||
        source.baseUrl.contains("dizidad", ignoreCase = true) ||
        source.baseUrl.contains("dizimom", ignoreCase = true)
    if (!isDizidad) return null

    val q = query.trim()
    val origin = source.normalizedBaseUrl
    val target = when {
        !pageUrlOverride.isNullOrBlank() -> pageUrlOverride
        q.isEmpty() && (source.catalogPath.trim().isEmpty() || source.catalogPath.trim() == "/") ->
            "$origin/1-dizi-arsivi/"
        else -> MovieHtmlParser.buildSourceUrl(source, q)
    }
    val res = probeOrNull { fetch(target, headers, "$origin/") } ?: return null
    return MovieHtmlParser.extractCatalogPage(res.body, res.finalUrl, source.name, headers)
}

/**
 * Resolves a Dizimom/Dizidad TV series overview page (`/diziler/prens-izle-hd5-full/`) or individual episode page:
 * - Extracts all seasons and episodes (`<div class="bolumust">`) cleanly into [ResolvedMovieStream.seasons] & [ResolvedMovieStream.episodes]
 * - Starts on Season 1 Episode 1 when opened from a series card
 * - Queries any `/tv/video/{hash}?do=getVideo` mirror (`peacemakerst.com`, `hdstreamable.com`, `hdmomplayer.com`) + falls back to generic embeds.
 */
internal suspend fun resolveDizidadStreamOrNull(
    pageUrl: String,
    html: String,
    headers: Map<String, String>,
    fetch: ScraperFetcher,
    postForm: ScraperFormPoster,
): ResolvedMovieStream? {
    if (!MovieHtmlParser.isDizidadPage(pageUrl, html)) return null

    val pageTitle = MovieHtmlParser.extractTitle(html)
        ?.replace(Regex("""\s*\|\s*DiziDAD.*$""", RegexOption.IGNORE_CASE), "")
        ?.replace(Regex("""(?i)\s+izle$"""), "")
        ?.trim()
    val pagePoster = MovieHtmlParser.extractPoster(html, pageUrl)
    val pageSynopsis = MovieHtmlParser.extractSynopsis(html)
    val pageYear = MovieHtmlParser.extractYear(html)
    val pageRating = MovieHtmlParser.extractRating(html)
    val origin = MovieHtmlParser.originOf(pageUrl)

    val episodes = MovieHtmlParser.extractDizidadEpisodes(html, pageUrl)
    val seasons = episodes.map { it.season }.distinct().sorted()

    var epUrl = pageUrl
    var epHtml = html
    var tvTarget = MovieHtmlParser.extractTvVideoTarget(epHtml, epUrl)
    if (tvTarget == null) {
        val candidateEp = episodes.firstOrNull()?.pageUrl ?: MovieHtmlParser.extractDizidadFirstEpisode(html)
        if (candidateEp != null) {
            val fetched = probeOrNull { fetch(candidateEp, headers, pageUrl) }
            if (fetched != null) {
                epUrl = fetched.finalUrl
                epHtml = fetched.body
                tvTarget = MovieHtmlParser.extractTvVideoTarget(epHtml, epUrl)
            }
        }
    }

    val rawStreams = ArrayList<VideoQuality>()
    val captions = ArrayList<VideoCaption>(MovieHtmlParser.extractCaptions(epHtml, epUrl))

    if (tvTarget != null) {
        val (playerOrigin, hash) = tvTarget
        val pmApiUrl = "$playerOrigin/tv/video/$hash?do=getVideo"
        val pmHeaders = headers + mapOf(
            "Referer" to "$playerOrigin/tv/video/$hash",
            "X-Requested-With" to "XMLHttpRequest",
        )
        val pmRes = probeOrNull {
            postForm(pmApiUrl, mapOf("hash" to hash, "r" to "$origin/", "s" to ""), pmHeaders)
        }
        val streamUrl = pmRes?.body?.let { MovieHtmlParser.extractPeacemakerStreamUrl(it) }
        if (streamUrl != null) {
            rawStreams += VideoQuality(
                height = 1080,
                fps = 30,
                videoUrl = streamUrl,
                audioUrl = null,
                isLive = false,
                headers = headers,
            )
        }
    }

    val expanded = if (rawStreams.isNotEmpty()) {
        expandGenericHls(rawStreams, fetch)
    } else {
        resolveGenericStreams(epUrl, epHtml, headers, fetch, expandHls = true)
    }
    if (expanded.streams.isEmpty()) return null
    val mergedCaptions = (captions + expanded.captions).distinctBy { it.url }

    return ResolvedMovieStream(
        title = pageTitle,
        poster = pagePoster,
        synopsis = pageSynopsis,
        year = pageYear,
        rating = pageRating,
        streams = expanded.streams,
        captions = mergedCaptions,
        audioTracks = expanded.audioTracks,
        defaultAudioTrackId = expanded.defaultAudioTrackId,
        seasons = seasons,
        seriesOrigin = origin,
        seriesUrl = pageUrl.takeIf { it.contains("/diziler/", ignoreCase = true) },
        episodes = episodes,
        activeEpisodeUrl = epUrl.takeIf { episodes.isNotEmpty() || it != pageUrl },
    )
}

/** Fetches episode list for a given [season] of a series via pure HTTP (no WebView). */
expect suspend fun fetchSeriesSeasonEpisodes(
    seriesUrl: String,
    tvshowId: String,
    season: Int,
    headers: Map<String, String> = emptyMap(),
): Result<List<MovieEpisode>>

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
