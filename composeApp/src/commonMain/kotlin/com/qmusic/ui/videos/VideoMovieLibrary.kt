package com.qmusic.ui.videos

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import com.qmusic.settings.AppSettings
import com.qmusic.ui.chat.MovieSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A film kept on the device: in the recently watched row or in the person's own list, with where it was left. */
@Serializable
internal data class SavedMovie(
    val title: String,
    val pageUrl: String,
    val poster: String? = null,
    val sourceName: String = "",
    val year: String? = null,
    val rating: String? = null,
    val subtitle: String? = null,
    val qualityBadge: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** The title was made from the address (a pasted link): the page's own title replaces it once it is known. */
    val titleGuessed: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val finished: Boolean = false,
) {
    fun toCard() = MovieCard(
        title = title,
        pageUrl = pageUrl,
        poster = poster,
        subtitle = subtitle,
        year = year,
        rating = rating,
        qualityBadge = qualityBadge,
        sourceName = sourceName,
        headers = headers,
    )

    /** How much of it was watched (0-1); 0 when nothing is known or it was watched to the end. */
    val progress: Float
        get() = if (finished || durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

internal fun MovieCard.toSaved(titleGuessed: Boolean = false) = SavedMovie(
    title = title,
    pageUrl = pageUrl,
    poster = poster,
    sourceName = sourceName,
    year = year,
    rating = rating,
    subtitle = subtitle,
    qualityBadge = qualityBadge,
    headers = headers,
    titleGuessed = titleGuessed,
)

/** The first page of a source as it was last seen: shown at once on the next start, then refreshed. */
@Serializable
private data class StoredCatalog(
    val items: List<SavedMovie> = emptyList(),
    val next: String? = null,
    val genres: List<Pair<String, String>> = emptyList(),
)

/**
 * The film tab's memory: recently watched films with their progress, the person's own list, the catalog pages already
 * loaded (so switching tabs or sources does not wait on the site again) and the streams already resolved (so the detail
 * sheet's lookup is the one the player uses, instead of resolving the same page twice).
 */
internal class MovieLibrary(private val settings: AppSettings, private val scope: CoroutineScope) {

    /** What the film tab is showing; kept here so a film opened from it does not reset it. */
    val tab = MovieTabMemory()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val listSerializer = ListSerializer(SavedMovie.serializer())

    // Titles saved before JSON escapes were decoded ("Y\u00fcz\u00fck") are cleaned when read back.
    private fun clean(m: SavedMovie) = m.copy(title = MovieHtmlParser.decodeEntities(m.title))

    private fun decode(raw: String): List<SavedMovie> =
        if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList()).map(::clean)

    // ------------------------------------------------------------------ recently watched

    private val _history = MutableStateFlow(decode(settings.string(KEY_HISTORY, "").value))
    val history: StateFlow<List<SavedMovie>> = _history.asStateFlow()

    private fun saveHistory(list: List<SavedMovie>) {
        _history.value = list
        settings.setString(KEY_HISTORY, json.encodeToString(listSerializer, list))
    }

    /** Puts [card] first in the recently watched row, keeping where it was left. */
    fun recordWatched(card: MovieCard, titleGuessed: Boolean = false) {
        val old = _history.value.firstOrNull { it.pageUrl == card.pageUrl }
        val fresh = card.toSaved(titleGuessed).let { s ->
            if (old == null) s else s.copy(
                poster = s.poster ?: old.poster,
                year = s.year ?: old.year,
                rating = s.rating ?: old.rating,
                // A guessed title never replaces a real one that was already known.
                title = if (titleGuessed && !old.titleGuessed) old.title else s.title,
                titleGuessed = titleGuessed && old.titleGuessed,
                positionMs = old.positionMs,
                durationMs = old.durationMs,
                finished = false,
            )
        }
        saveHistory((listOf(fresh) + _history.value.filterNot { it.pageUrl == card.pageUrl }).take(HISTORY_KEPT))
    }

    /** The page told its real title and poster: a film opened from a bare link gets them in the row. */
    fun refine(pageUrl: String, title: String?, poster: String?) {
        val list = _history.value
        val i = list.indexOfFirst { it.pageUrl == pageUrl }
        if (i < 0) return
        val old = list[i]
        val newTitle = title?.trim()?.takeIf { it.isNotEmpty() && old.titleGuessed }
        val newPoster = poster?.takeIf { old.poster == null }
        if (newTitle == null && newPoster == null) return
        saveHistory(list.toMutableList().also {
            it[i] = old.copy(title = newTitle ?: old.title, titleGuessed = old.titleGuessed && newTitle == null, poster = newPoster ?: old.poster)
        })
    }

    /** Where the open film is (the player tells it every few seconds), for the progress bar in the row. */
    fun updateProgress(pageUrl: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val list = _history.value
        val i = list.indexOfFirst { it.pageUrl == pageUrl }
        if (i < 0) return
        val old = list[i]
        val finished = positionMs >= durationMs - FINISHED_MARGIN_MS
        // Writing every tick is not needed: a few seconds either way does not change the bar.
        if (old.finished == finished && old.durationMs == durationMs && kotlin.math.abs(old.positionMs - positionMs) < PROGRESS_STEP_MS) return
        saveHistory(list.toMutableList().also {
            it[i] = old.copy(positionMs = if (finished) 0L else positionMs, durationMs = durationMs, finished = finished)
        })
    }

    fun forget(pageUrl: String) = saveHistory(_history.value.filterNot { it.pageUrl == pageUrl })

    fun clearHistory() = saveHistory(emptyList())

    // ------------------------------------------------------------------ my list

    private val _favorites = MutableStateFlow(decode(settings.string(KEY_FAVORITES, "").value))
    val favorites: StateFlow<List<SavedMovie>> = _favorites.asStateFlow()

    fun isFavorite(pageUrl: String): Boolean = _favorites.value.any { it.pageUrl == pageUrl }

    /** Adds [card] to the person's list, or takes it out when it is there. Returns whether it is in the list now. */
    fun toggleFavorite(card: MovieCard): Boolean {
        val list = _favorites.value
        val inList = list.any { it.pageUrl == card.pageUrl }
        val next = if (inList) list.filterNot { it.pageUrl == card.pageUrl } else (listOf(card.toSaved()) + list).take(FAVORITES_KEPT)
        _favorites.value = next
        settings.setString(KEY_FAVORITES, json.encodeToString(listSerializer, next))
        return !inList
    }

    fun removeFavorite(pageUrl: String) {
        val next = _favorites.value.filterNot { it.pageUrl == pageUrl }
        _favorites.value = next
        settings.setString(KEY_FAVORITES, json.encodeToString(listSerializer, next))
    }

    // ------------------------------------------------------------------ film searches

    private val _searches = MutableStateFlow(settings.string(KEY_SEARCHES, "").value.split('\n').filter { it.isNotBlank() })
    val searches: StateFlow<List<String>> = _searches.asStateFlow()

    private fun saveSearches(list: List<String>) {
        _searches.value = list
        settings.setString(KEY_SEARCHES, list.joinToString("\n"))
    }

    /** The newest first; the same words searched again move to the top instead of showing twice. */
    fun rememberSearch(query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.isEmpty()) return
        saveSearches((listOf(q) + _searches.value.filterNot { it.equals(q, ignoreCase = true) }).take(SEARCHES_KEPT))
    }

    fun forgetSearch(query: String) = saveSearches(_searches.value.filterNot { it == query })

    fun clearSearches() = saveSearches(emptyList())

    // ------------------------------------------------------------------ catalog pages

    private class CatalogEntry(val at: TimeMark, val page: MovieCatalogPage)

    private val catalogs = LinkedHashMap<String, CatalogEntry>()
    private val catalogsLoading = HashMap<String, Deferred<Result<MovieCatalogPage>>>()

    private fun catalogKey(source: MovieSource, query: String, pageUrl: String?) =
        "${source.id}|${source.baseUrl}|${source.catalogPath}|${source.searchPath}|${query.trim().lowercase()}|${pageUrl.orEmpty()}"

    private fun isHome(query: String, pageUrl: String?) = query.isBlank() && pageUrl == null

    private fun homeKey(source: MovieSource) = KEY_CATALOG_PREFIX + source.id

    /** What was seen of this page before (this session, or the source's first page from the last one), or null. */
    fun cachedCatalog(source: MovieSource, query: String, pageUrl: String?): MovieCatalogPage? {
        catalogs[catalogKey(source, query, pageUrl)]?.let { return it.page }
        if (!isHome(query, pageUrl)) return null
        val raw = settings.string(homeKey(source), "").value
        if (raw.isBlank()) return null
        val stored = runCatching { json.decodeFromString(StoredCatalog.serializer(), raw) }.getOrNull() ?: return null
        if (stored.items.isEmpty()) return null
        return MovieCatalogPage(items = stored.items.map { clean(it).toCard() }, nextPageUrl = stored.next, genres = stored.genres)
    }

    /** The page was loaded from the site a short while ago: no need to ask again. */
    fun isCatalogFresh(source: MovieSource, query: String, pageUrl: String?): Boolean =
        catalogs[catalogKey(source, query, pageUrl)]?.let { it.at.elapsedNow() < CATALOG_LIFETIME } == true

    /** Loads the page from the site (one request per page at a time) and keeps it. */
    suspend fun catalog(source: MovieSource, query: String, pageUrl: String?): Result<MovieCatalogPage> {
        val key = catalogKey(source, query, pageUrl)
        val lookup = catalogsLoading.getOrPut(key) {
            scope.async {
                fetchMovieCatalog(source, query, pageUrlOverride = pageUrl).onSuccess { page ->
                    if (page.items.isEmpty()) return@onSuccess
                    catalogs.remove(key)
                    catalogs[key] = CatalogEntry(TimeSource.Monotonic.markNow(), page)
                    while (catalogs.size > CATALOGS_KEPT) catalogs.remove(catalogs.keys.first())
                    if (isHome(query, pageUrl)) {
                        val stored = StoredCatalog(page.items.take(HOME_ITEMS_KEPT).map { it.toSaved() }, page.nextPageUrl, page.genres)
                        settings.setString(homeKey(source), json.encodeToString(StoredCatalog.serializer(), stored))
                    }
                }
            }.also { it.invokeOnCompletion { catalogsLoading.remove(key) } }
        }
        return lookup.await()
    }

    // ------------------------------------------------------------------ resolved streams

    private class StreamEntry(val at: TimeMark, val stream: ResolvedMovieStream)

    private val streams = LinkedHashMap<String, StreamEntry>()
    private val streamsLoading = HashMap<String, Deferred<Result<ResolvedMovieStream>>>()

    /**
     * The playable streams of a film page. The detail sheet starts this as it opens; pressing Watch then waits for the
     * same lookup (or takes its answer) instead of starting again. Stream addresses expire, so an answer is kept briefly.
     */
    suspend fun resolve(url: String, headers: Map<String, String>): Result<ResolvedMovieStream> {
        streams[url]?.takeIf { it.at.elapsedNow() < STREAM_LIFETIME }?.let { return Result.success(it.stream) }
        val lookup = streamsLoading.getOrPut(url) {
            scope.async {
                resolveExternalMovie(url, headers).onSuccess { r ->
                    if (r.streams.isEmpty()) return@onSuccess
                    val entry = StreamEntry(TimeSource.Monotonic.markNow(), r)
                    streams.remove(url)
                    streams[url] = entry
                    r.activeEpisodeUrl?.takeIf { it != url }?.let { epUrl ->
                        streams.remove(epUrl)
                        streams[epUrl] = entry
                    }
                    while (streams.size > STREAMS_KEPT) streams.remove(streams.keys.first())
                }
            }.also { it.invokeOnCompletion { streamsLoading.remove(url) } }
        }
        return lookup.await()
    }

    /** The kept answer did not play (expired, or the server moved it): the next lookup goes to the site. */
    fun forgetStream(url: String) {
        streams.remove(url)
    }

    private companion object {
        const val KEY_HISTORY = "movie_history"
        const val KEY_FAVORITES = "movie_favorites"
        const val KEY_CATALOG_PREFIX = "movie_catalog_"
        const val KEY_SEARCHES = "movie_searches"
        const val SEARCHES_KEPT = 12
        const val HISTORY_KEPT = 40
        const val FAVORITES_KEPT = 300
        const val HOME_ITEMS_KEPT = 60
        const val CATALOGS_KEPT = 24
        const val STREAMS_KEPT = 12
        const val FINISHED_MARGIN_MS = 90_000L
        const val PROGRESS_STEP_MS = 15_000L
        val CATALOG_LIFETIME: Duration = Duration.parse("15m")
        val STREAM_LIFETIME: Duration = Duration.parse("8m")
    }
}

/** A short, plain explanation of why a film source or stream could not be reached, instead of the raw error. */
internal fun friendlyMovieError(e: Throwable?, english: Boolean): String {
    fun tr(en: String, tr: String) = if (english) en else tr
    val name = e?.let { it::class.simpleName }.orEmpty().lowercase()
    val msg = (e?.message.orEmpty() + " " + e?.cause?.message.orEmpty()).lowercase()
    return when {
        "unknownhost" in name || "unable to resolve host" in msg || "no address associated" in msg ->
            tr("No internet connection, or the site's address has changed.", "İnternet bağlantısı yok ya da sitenin adresi değişmiş.")
        "timeout" in name || "timed out" in msg || "timeout" in msg ->
            tr("The source took too long to answer. Try again in a moment.", "Kaynak zamanında yanıt vermedi. Biraz sonra tekrar dene.")
        Regex("""\b(403|429|503)\b""").containsMatchIn(msg) || "cloudflare" in msg ->
            tr("The site is blocking access right now. Try later or switch source.", "Site şu an erişimi engelliyor. Biraz sonra dene ya da başka kaynağa geç.")
        Regex("""\b404\b""").containsMatchIn(msg) ->
            tr("Page not found. The source's address may have changed.", "Sayfa bulunamadı. Kaynağın adresi değişmiş olabilir.")
        "ssl" in name || "ssl" in msg || "certificate" in msg || "handshake" in msg ->
            tr("A secure connection could not be made.", "Güvenli bağlantı kurulamadı.")
        "connect" in name || "failed to connect" in msg || "connection refused" in msg || "reset" in msg ->
            tr("Couldn't connect to the source.", "Kaynağa bağlanılamadı.")
        else -> tr("The source isn't answering right now. Try again or switch source.", "Kaynak şu an yanıt vermiyor. Tekrar dene ya da başka bir kaynağa geç.")
    }
}

/**
 * The film tab's state, held by the controller: the tab itself is taken down while a film plays full screen, and
 * coming back should find the same source, search, genre, loaded pages, filters and scroll position.
 */
internal class MovieTabMemory {
    val selectedSourceId = mutableStateOf<String?>(null)
    val searchQuery = mutableStateOf("")
    val submittedQuery = mutableStateOf("")
    val selectedGenreUrl = mutableStateOf<String?>(null)
    val cards = mutableStateOf<List<MovieCard>>(emptyList())
    val genres = mutableStateOf<List<Pair<String, String>>>(emptyList())
    val nextPageUrl = mutableStateOf<String?>(null)
    val searchAll = mutableStateOf(false)
    val sort = mutableStateOf(MovieSort.SITE)
    val minRating = mutableStateOf<Float?>(null)
    val minYear = mutableStateOf<Int?>(null)
    val kind = mutableStateOf<Boolean?>(null)
    val list = LazyListState()

    /** Which page the shown cards belong to (source, search, genre); the same one is not loaded again. */
    var loadedKey: String? = null

    /** Which page the scroll position belongs to; a different one starts at the top. */
    var scrollKey: String? = null

    /** The last search asked from outside the tab (controller.movieQuery) that was already applied. */
    var handledExternalQuery: String = ""

    /** The Film section's page (its bar), and the one whose state was last set up. */
    val page = mutableStateOf(FilmPage.HOME)
    var appliedPage: FilmPage = FilmPage.HOME

    /** Tapping the open page's button again scrolls it to the top (counted; [handledScrollToTop] is the last done). */
    val scrollToTop = mutableStateOf(0)
    var handledScrollToTop = 0

    /** Kept while another page is open: the search page's words and the home's genre. */
    var savedSearch: String = ""
    var savedGenre: String? = null
}

/** The pages of the Film section, one per button on its bar. */
internal enum class FilmPage { HOME, SEARCH, LIBRARY }
