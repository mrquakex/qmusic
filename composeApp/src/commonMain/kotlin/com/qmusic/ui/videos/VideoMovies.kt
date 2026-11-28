package com.qmusic.ui.videos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.chat.ChatMusic
import com.qmusic.ui.chat.MovieSource
import com.qmusic.ui.chat.ShareKind
import com.qmusic.ui.chat.SharedItem
import com.qmusic.ui.motion.Motion
import com.qmusic.ui.motion.pressScale
import com.qmusic.ui.theme.BorderHover
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.QMusicShapes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/** Extracts an 11-char YouTube video ID from common youtube.com / youtu.be URLs, or null if not YouTube. */
internal fun extractYouTubeIdFromUrl(url: String): String? {
    val clean = url.trim()
    val idRegex = Regex("""^[A-Za-z0-9_-]{11}$""")
    val patterns = listOf(
        Regex("""(?i)youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""(?i)youtube\.com/watch\?[^#]*\bv=([A-Za-z0-9_-]{11})"""),
        Regex("""(?i)youtube\.com/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})"""),
    )
    for (p in patterns) {
        val m = p.find(clean)?.groupValues?.getOrNull(1)
        if (m != null && idRegex.matches(m)) return m
    }
    return null
}

/** Adaptive column count for the Film poster grid across phone and tablets. */
internal fun movieGridColumnCount(widthDp: Float): Int = when {
    widthDp >= 1100f -> 5
    widthDp >= 840f -> 4
    widthDp >= 600f -> 3
    else -> 2
}

/**
 * Dedicated Cinema / Film tab inside Video's navigation bar (pure Nuvelo monochrome, zero WebView):
 * - Netflix-style home: rotating showcase, continue watching, my list, latest / top rated / genre rows
 * - Segmented source picker, instant local filtering while typing, genre pills, source search + pagination
 * - Instant pre-resolving Movie Detail sheet (backdrop, IMDb, year, genres, synopsis, streams, Watch / Together / Share)
 * - Direct URL modal for any .m3u8 / .mpd / .mp4 / YouTube / film page link
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoviesTab(
    controller: VideosController,
    sources: List<MovieSource>,
    s: VideoStrings,
    tablet: Boolean,
    onShare: (String) -> Unit = {},
    /** The Film section: the tab is split into its pages (home, search, my list), chosen from the section's bar. */
    cinema: Boolean = false,
) {
    val x = s.extras()
    val english = s == VideoStrings.English
    val scope = rememberCoroutineScope()
    val activeSources = remember(sources) {
        val enabled = sources.filter { it.enabled }
        if (enabled.isEmpty()) com.qmusic.ui.chat.DEFAULT_MOVIE_SOURCES else enabled
    }
    // What the tab shows lives in the controller: the tab is taken down while a film plays, and coming back finds the
    // same source, search, genre, loaded pages, filters and scroll position.
    val mem = controller.movies.tab
    var selectedSourceId by mem.selectedSourceId
    val activeSource = activeSources.firstOrNull { it.id == selectedSourceId } ?: activeSources.firstOrNull()

    var linkDialogOpen by remember { mutableStateOf(false) }
    var topMenu by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<MovieCard?>(null) }

    var searchQuery by mem.searchQuery
    var submittedQuery by mem.submittedQuery
    var selectedGenreUrl by mem.selectedGenreUrl
    val externalMovieQuery by controller.movieQuery.collectAsState()
    LaunchedEffect(externalMovieQuery) {
        // Each search asked from outside is taken once; coming back from a film does not bring an old one back.
        if (externalMovieQuery == mem.handledExternalQuery) return@LaunchedEffect
        mem.handledExternalQuery = externalMovieQuery
        if (externalMovieQuery.isNotBlank() && externalMovieQuery != submittedQuery) {
            searchQuery = externalMovieQuery
            submittedQuery = externalMovieQuery
            selectedGenreUrl = null
        }
    }
    var cards by mem.cards
    var genres by mem.genres
    var nextPageUrl by mem.nextPageUrl
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadMoreError by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    val handledTick = remember { intArrayOf(0) }
    val library = controller.movies
    val movieHistory by library.history.collectAsState()
    val myList by library.favorites.collectAsState()
    val recentSearches by library.searches.collectAsState()
    var searchAll by mem.searchAll   // search every source at once
    var searchFocused by remember { mutableStateOf(false) }
    var sort by mem.sort
    var minRating by mem.minRating   // "Puan 7+"
    var minYear by mem.minYear       // "2020+"
    var kind by mem.kind             // true: dizi, false: film, null: hepsi
    // What the person already has with a film: how far it was watched, and whether it is in their list.
    val progressOf = remember(movieHistory) { movieHistory.associate { it.pageUrl to it.progress } }
    val listUrls = remember(myList) { myList.mapTo(HashSet()) { it.pageUrl } }
    val focusManager = LocalFocusManager.current

    // What was seen of a page before shows at once (this session, or the source's first page from the last one); the
    // site is asked again only when that is old, or when the person asks for it (reload, retry).
    LaunchedEffect(activeSource?.id, activeSource?.baseUrl, submittedQuery, selectedGenreUrl, reloadTick, searchAll, activeSources) {
        val src = activeSource
        if (src == null) {
            cards = emptyList()
            genres = emptyList()
            nextPageUrl = null
            loading = false
            refreshing = false
            loadError = null
            return@LaunchedEffect
        }
        val forced = reloadTick != handledTick[0]
        handledTick[0] = reloadTick
        val query = submittedQuery
        val loadKey = "${src.id}|${src.baseUrl}|$query|${if (query.isBlank()) selectedGenreUrl else null}|" +
            "${searchAll && query.isNotBlank() && activeSources.size > 1}"
        // Back from a film: the same page with what was loaded under it (more pages too) is still here.
        if (!forced && mem.loadedKey == loadKey && cards.isNotEmpty()) {
            loading = false
            loadError = null
            return@LaunchedEffect
        }
        if (searchAll && query.isNotBlank() && activeSources.size > 1) {
            // Every source is asked at the same time; their results are dealt in turn, so each source shows near the top.
            loading = true
            refreshing = false
            loadError = null
            loadMoreError = false
            val results = coroutineScope { activeSources.map { source -> async { library.catalog(source, query, null) } }.awaitAll() }
            val lists = results.map { it.getOrNull()?.items.orEmpty() }
            val merged = buildList {
                for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { l -> l.getOrNull(i)?.let(::add) }
            }.distinctBy { it.pageUrl }
            cards = merged
            mem.loadedKey = loadKey
            nextPageUrl = null
            loading = false
            if (merged.isEmpty() && results.all { it.isFailure }) {
                loadError = friendlyMovieError(results.firstNotNullOfOrNull { it.exceptionOrNull() }, english)
            }
            return@LaunchedEffect
        }
        val pageUrl = if (query.isBlank()) selectedGenreUrl else null
        val showGenres = selectedGenreUrl == null && query.isBlank()
        loadError = null
        loadMoreError = false
        val cached = library.cachedCatalog(src, query, pageUrl)
        if (cached != null) {
            cards = cached.items
            mem.loadedKey = loadKey
            nextPageUrl = cached.nextPageUrl
            if (cached.genres.isNotEmpty() && showGenres) genres = cached.genres
            loading = false
            if (!forced && library.isCatalogFresh(src, query, pageUrl)) {
                refreshing = false
                return@LaunchedEffect
            }
            refreshing = true
        } else {
            loading = true
            refreshing = false
        }
        library.catalog(src, query, pageUrl).fold(
            onSuccess = { page ->
                // A refresh does not empty a page that showed, nor drop the pages loaded under it meanwhile.
                val keepShown = cached != null && (page.items.isEmpty() || cards.size > cached.items.size)
                if (!keepShown) {
                    cards = page.items
                    mem.loadedKey = loadKey
                    nextPageUrl = page.nextPageUrl
                }
                if (page.genres.isNotEmpty() && showGenres) genres = page.genres
                loading = false
                refreshing = false
            },
            onFailure = { e ->
                loading = false
                refreshing = false
                // What was shown stays (it may be a little old); without it the reason is told plainly.
                if (cached == null) {
                    cards = emptyList()
                    nextPageUrl = null
                    loadError = friendlyMovieError(e, english)
                }
            },
        )
    }

    fun loadNextPage() {
        val src = activeSource ?: return
        val next = nextPageUrl ?: return
        if (loading || loadingMore) return
        loadingMore = true
        loadMoreError = false
        scope.launch {
            library.catalog(src, "", next).fold(
                onSuccess = { page ->
                    val existing = cards.mapTo(HashSet()) { it.pageUrl }
                    val fresh = page.items.filter { it.pageUrl !in existing }
                    cards = cards + fresh
                    nextPageUrl = if (fresh.isEmpty()) null else page.nextPageUrl
                    loadingMore = false
                },
                onFailure = {
                    loadingMore = false
                    loadMoreError = true
                },
            )
        }
    }

    /** Plays [card] (or a picked [episode] on a series): goes first in history, starts where it was left. */
    fun watch(
        card: MovieCard,
        resolved: ResolvedMovieStream? = null,
        episode: MovieEpisode? = null,
        episodesBySeason: Map<Int, List<MovieEpisode>> = emptyMap(),
        relatedMovies: List<MovieCard> = emptyList(),
    ) {
        val baseTitle = MovieHtmlParser.cleanMovieTitle(resolved?.title)?.takeIf { it.isNotBlank() } ?: card.title
        val picked = episode ?: resolved?.activeEpisodeUrl?.let { activeUrl ->
            resolved.episodes.firstOrNull { it.pageUrl == activeUrl }
                ?: episodesBySeason.values.flatten().firstOrNull { it.pageUrl == activeUrl }
        }
        val playUrl = picked?.pageUrl ?: resolved?.activeEpisodeUrl ?: card.pageUrl
        val playTitle = if (picked != null) "$baseTitle · ${picked.season}. Sezon ${picked.episode}. Bölüm" else baseTitle
        val poster = card.poster ?: resolved?.poster
        val mergedBySeason = LinkedHashMap<Int, List<MovieEpisode>>(episodesBySeason)
        resolved?.episodes?.groupBy { it.season }?.forEach { (sn, list) ->
            if (list.isNotEmpty() && mergedBySeason[sn].isNullOrEmpty()) mergedBySeason[sn] = list
        }
        val seasons = (resolved?.seasons.orEmpty() + mergedBySeason.keys).distinct().sorted()
        val ctx = MovieWatchContext(
            seriesUrl = resolved?.seriesUrl ?: card.pageUrl,
            seriesTitle = baseTitle,
            tvshowId = resolved?.tvshowId,
            year = card.year ?: resolved?.year,
            rating = card.rating ?: resolved?.rating,
            seasons = seasons,
            selectedSeason = picked?.season ?: seasons.firstOrNull(),
            episodesBySeason = mergedBySeason,
            activeEpisodeUrl = playUrl.takeIf { seasons.isNotEmpty() || mergedBySeason.isNotEmpty() },
            relatedMovies = relatedMovies.take(12),
        )
        library.recordWatched(card.copy(title = baseTitle, poster = poster))
        // A series opens one of its episodes: the series' row follows that episode's progress.
        if (playUrl != card.pageUrl) library.noteEpisode(card.pageUrl, playUrl)
        controller.openExternalUrl(
            rawUrl = playUrl,
            title = playTitle,
            sourceName = FILM_LABEL, // under the title on the video page: never the site's name
            poster = poster,
            headers = card.headers,
            context = ctx,
        )
    }

    fun sharedItemForUrl(url: String, title: String? = null, sourceName: String = "", poster: String? = null): SharedItem? {
        val clean = url.trim()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return null
        val ytId = extractYouTubeIdFromUrl(clean)
        val id = ytId ?: externalVideoId(clean)
        val label = title?.takeIf { it.isNotBlank() }
            ?: clean.substringBefore('?').substringAfterLast('/').ifBlank { MovieHtmlParser.originOf(clean).removePrefix("https://").removePrefix("http://") }
        // Shared films say "Film", not the site they come from.
        val sub = if (ytId != null) "YouTube" else FILM_LABEL
        return SharedItem(ShareKind.VIDEO, id, label, sub, poster)
    }


    fun tr(en: String, tr: String) = if (english) en else tr

    /** "Kaynak 2": the sources are numbered, their sites are not named (empty when there is only one). */
    fun sourceLabel(src: com.qmusic.ui.chat.MovieSource?): String {
        val i = activeSources.indexOfFirst { it.id == src?.id }
        return if (activeSources.size < 2 || i < 0) "" else tr("Source ${i + 1}", "Kaynak ${i + 1}")
    }

    fun submitSearch() {
        val q = searchQuery.trim()
        library.rememberSearch(q)
        if (q != submittedQuery) {
            selectedGenreUrl = null
            submittedQuery = q
            if (q.isEmpty()) controller.clearMovieSearch()
        } else {
            reloadTick++
        }
    }

    fun clearSearch() {
        searchQuery = ""
        if (submittedQuery.isNotEmpty()) {
            submittedQuery = ""
            controller.clearMovieSearch()
        }
    }

    // Back closes the search or the genre first, instead of leaving the tab.
    com.qmusic.ui.PlatformBackHandler(enabled = searchQuery.isNotEmpty() || submittedQuery.isNotBlank() || selectedGenreUrl != null) {
        focusManager.clearFocus()
        if (searchQuery.isNotEmpty() || submittedQuery.isNotBlank()) clearSearch() else selectedGenreUrl = null
    }

    // The Film section's pages share one catalog state: leaving the search page keeps its words for later and shows
    // the home again; leaving the home keeps its genre. Each move happens once (coming back from a film is not one).
    val page = if (cinema) mem.page.value else FilmPage.HOME
    val searchFocus = remember { FocusRequester() }
    var focusTick by remember { mutableStateOf(0) }
    LaunchedEffect(cinema, page) {
        if (!cinema || page == mem.appliedPage) return@LaunchedEffect
        val from = mem.appliedPage
        mem.appliedPage = page
        if (from == FilmPage.HOME) mem.savedGenre = selectedGenreUrl
        if (from == FilmPage.SEARCH) {
            mem.savedSearch = submittedQuery
            if (searchQuery.isNotEmpty() || submittedQuery.isNotEmpty()) clearSearch()
        }
        when (page) {
            FilmPage.HOME -> selectedGenreUrl = mem.savedGenre
            FilmPage.SEARCH -> {
                selectedGenreUrl = null
                if (mem.savedSearch.isNotBlank()) {
                    searchQuery = mem.savedSearch
                    submittedQuery = mem.savedSearch
                } else {
                    focusTick++
                }
            }
            FilmPage.LIBRARY -> selectedGenreUrl = null
        }
    }
    LaunchedEffect(mem.scrollToTop.value) {
        if (mem.scrollToTop.value == mem.handledScrollToTop) return@LaunchedEffect
        mem.handledScrollToTop = mem.scrollToTop.value
        mem.list.animateScrollToItem(0)
    }
    LaunchedEffect(focusTick) {
        if (focusTick == 0) return@LaunchedEffect
        delay(150)
        runCatching { searchFocus.requestFocus() }
    }

    // The first films of "continue watching" are looked up in the background once the home has settled, so their page
    // (and Watch) is ready at once; the answer is kept for a few minutes.
    val resumeCandidates = remember(movieHistory) { movieHistory.filter { it.progress > 0f }.take(PRE_RESOLVE_COUNT) }
    LaunchedEffect(resumeCandidates.map { it.pageUrl }, page) {
        if (page != FilmPage.HOME || resumeCandidates.isEmpty()) return@LaunchedEffect
        delay(PRE_RESOLVE_DELAY_MS)
        resumeCandidates.forEach { library.resolve(it.pageUrl, it.headers) }
    }

    // A new source, search or genre starts at the top, so its results are what shows; coming back from a film keeps
    // the place.
    val listState = mem.list
    LaunchedEffect(activeSource?.id, submittedQuery, selectedGenreUrl, searchAll, page) {
        val key = "${activeSource?.id}|$submittedQuery|$selectedGenreUrl|$searchAll|$page"
        if (mem.scrollKey != null && mem.scrollKey != key) listState.scrollToItem(0)
        mem.scrollKey = key
    }

    val sidePad = if (tablet) 18.dp else 12.dp
    // Instant, local filter over what is already loaded while the user is typing (no network until they submit).
    val localQuery = searchQuery.trim().takeIf { it.isNotEmpty() && it != submittedQuery }
    val selectedGenreLabel = remember(genres, selectedGenreUrl) { genres.firstOrNull { it.second == selectedGenreUrl }?.first }
    val gridCards = remember(cards, localQuery) {
        if (localQuery != null) cards.filter { matchesQuery(it.title, localQuery) } else cards
    }
    // Home page, Netflix style: a rotating showcase on top, then rows that slide sideways.
    val heroPicks = remember(cards) { cards.filter { it.poster != null }.take(HERO_COUNT) }
    val topRated = remember(cards) {
        cards.mapNotNull { c -> c.rating?.replace(',', '.')?.toFloatOrNull()?.let { c to it } }
            .filter { it.second >= 7f }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(ROW_SIZE)
    }
    // One row per genre (the first few), loaded one after another in the background and kept in the cache.
    var genreRows by remember(activeSource?.id) { mutableStateOf<Map<String, List<MovieCard>>>(emptyMap()) }
    val rowGenres = remember(genres) { genres.take(GENRE_ROWS) }
    LaunchedEffect(activeSource?.id, rowGenres) {
        val src = activeSource ?: return@LaunchedEffect
        for ((_, url) in rowGenres) {
            if (genreRows[url] != null) continue
            val page = library.cachedCatalog(src, "", url)?.takeIf { library.isCatalogFresh(src, "", url) }
                ?: library.catalog(src, "", url).getOrNull()
                ?: library.cachedCatalog(src, "", url)
            val items = page?.items?.take(ROW_SIZE).orEmpty()
            if (items.isNotEmpty()) genreRows = genreRows + (url to items)
        }
    }
    val countLabel = { n: Int, more: Boolean -> if (more) "$n+" else "$n" }

    // Speak a title instead of typing it (the phone's own speech recognition).
    val voiceSearch = com.qmusic.ui.rememberVoiceSearch { spoken ->
        searchQuery = spoken
        focusManager.clearFocus()
        submitSearch()
    }

    // Suggestions while typing: films already seen anywhere in the tab (this page, the rows, history, my list) and
    // past searches, best matches first.
    val suggestions = remember(searchQuery, submittedQuery, cards, genreRows, movieHistory, myList, recentSearches) {
        val q = searchQuery.trim()
        if (q.length < 2 || q == submittedQuery) {
            emptyList()
        } else {
            val films = (movieHistory.map { it.toCard() } + myList.map { it.toCard() } + cards + genreRows.values.flatten())
                .distinctBy { it.pageUrl }
                .mapNotNull { c -> matchRank(c.title, q)?.let { r -> MovieSuggestion(c.title, c, r) } }
            val words = recentSearches
                .mapNotNull { w -> matchRank(w, q)?.let { r -> MovieSuggestion(w, null, r + 1) } }
                .filterNot { w -> w.text.equals(q, ignoreCase = true) }
            (films + words).sortedBy { it.rank }.distinctBy { foldTurkish(it.text) }.take(SUGGESTION_COUNT)
        }
    }

    // Filters over the shown titles; the dizi/film chips only appear when both kinds can be told apart.
    val kinds = remember(cards) { cards.map { movieKind(it) }.toSet() }
    val filteredCards = remember(gridCards, minRating, minYear, kind) {
        gridCards.filter { c ->
            (minRating == null || (c.rating?.replace(',', '.')?.toFloatOrNull() ?: 0f) >= minRating!!) &&
                (minYear == null || (c.year?.toIntOrNull() ?: 0) >= minYear!!) &&
                (kind == null || movieKind(c) == kind)
        }
    }

    val showSearchUi = !cinema || page == FilmPage.SEARCH
    val showHomeUi = !cinema || page == FilmPage.HOME
    val searchIdle = cinema && page == FilmPage.SEARCH && submittedQuery.isBlank() && localQuery == null && selectedGenreUrl == null

    BoxWithConstraints(Modifier.fillMaxSize().background(VideoColors.Background)) {
        val cols = movieGridColumnCount(maxWidth.value)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = sidePad, end = sidePad, top = 6.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Heading: section title, current source summary and the direct-link button.
            // A slim top line: the page's name, then search and a menu (which source, open a link). The home has no
            // counts or source names on it.
            item(key = "cinema-heading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (page) {
                                FilmPage.HOME -> x.tabMovies
                                FilmPage.SEARCH -> tr("Search", "Ara")
                                FilmPage.LIBRARY -> tr("My list", "Listem")
                            },
                            color = VideoColors.Text,
                            fontSize = if (tablet) 26.sp else 24.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                        )
                        if (page == FilmPage.LIBRARY) {
                            Text(
                                tr(
                                    "${myList.size} in my list · ${movieHistory.size} watched",
                                    "Listende ${myList.size} · İzlediğin ${movieHistory.size}",
                                ),
                                color = VideoColors.TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        } else if (refreshing || loading) {
                            Text(
                                if (loading) tr("Loading…", "Yükleniyor…") else tr("Updating…", "Güncelleniyor…"),
                                color = VideoColors.TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                    if (cinema && page == FilmPage.HOME) {
                        TopIconButton(QMusicIcons.Search, tr("Search", "Ara")) { mem.page.value = FilmPage.SEARCH }
                    }
                    if (page != FilmPage.LIBRARY) {
                        Box {
                            TopIconButton(QMusicIcons.More, tr("More", "Diğer")) { topMenu = true }
                            DropdownMenu(
                                expanded = topMenu,
                                onDismissRequest = { topMenu = false },
                                modifier = Modifier.background(VideoColors.Surface),
                            ) {
                                if (activeSources.size > 1) {
                                    activeSources.forEach { src ->
                                        DropdownMenuItem(
                                            text = { Text(sourceLabel(src), color = VideoColors.Text, fontSize = 14.sp) },
                                            trailingIcon = {
                                                if (src.id == activeSource?.id) {
                                                    Icon(QMusicIcons.Check, null, tint = VideoColors.Text, modifier = Modifier.size(16.dp))
                                                }
                                            },
                                            onClick = {
                                                topMenu = false
                                                if (src.id != activeSource?.id) {
                                                    selectedSourceId = src.id
                                                    selectedGenreUrl = null
                                                    // On the search page the same words are searched in the other source.
                                                    if (page != FilmPage.SEARCH) {
                                                        submittedQuery = ""
                                                        searchQuery = ""
                                                        controller.clearMovieSearch()
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text(x.movieLinkTitle, color = VideoColors.Text, fontSize = 14.sp) },
                                    leadingIcon = { Icon(VideoIcons.Film, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(16.dp)) },
                                    onClick = {
                                        topMenu = false
                                        linkDialogOpen = true
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (cinema && page == FilmPage.LIBRARY) {
                if (movieHistory.isEmpty() && myList.isEmpty()) {
                    item(key = "lib-empty") {
                        CinemaEmptyState(
                            icon = QMusicIcons.Library,
                            title = tr("Nothing here yet", "Burası henüz boş"),
                            body = tr(
                                "Films you add to My list and films you watch show up here.",
                                "Listene eklediğin ve izlediğin filmler burada görünür.",
                            ),
                            primaryLabel = tr("Browse films", "Filmlere göz at"),
                            primaryIcon = VideoIcons.Film,
                            onPrimary = { mem.page.value = FilmPage.HOME },
                        )
                    }
                }
                if (movieHistory.isNotEmpty()) {
                    item(key = "lib-history") {
                        SavedMovieRow(
                            caption = tr("CONTINUE WATCHING", "İZLEMEYE DEVAM ET"),
                            action = tr("Clear", "Temizle"),
                            onAction = { library.clearHistory() },
                            movies = movieHistory,
                            onOpen = { selectedCard = it.toCard() },
                            removeLabel = tr("Remove from history", "Geçmişten kaldır"),
                            onRemove = { library.forget(it.pageUrl) },
                        )
                    }
                }
                if (myList.isNotEmpty()) {
                    item(key = "lib-list-caption") {
                        SectionCaption(
                            "${tr("MY LIST", "LİSTEM")} · ${myList.size}",
                            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                        )
                    }
                    val listRows = myList.map { it.toCard() }.chunked(cols)
                    items(listRows.size, key = { "lrow-$it-${listRows[it].first().pageUrl}" }) { rIdx ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listRows[rIdx].forEach { card ->
                                MoviePosterTile(
                                    card = card,
                                    watchTogetherLabel = x.watchTogether,
                                    onOpenDetails = { selectedCard = card },
                                    onWatchTogether = {
                                        sharedItemForUrl(card.pageUrl, card.title, card.sourceName, card.poster)?.let {
                                            ChatMusic.offerTogether(it)
                                        }
                                    },
                                    progress = progressOf[card.pageUrl] ?: 0f,
                                    inList = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(cols - listRows[rIdx].size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } else if (activeSources.isEmpty()) {
                item(key = "no-sources") {
                    CinemaEmptyState(
                        icon = VideoIcons.Film,
                        title = tr("No film sources yet", "Henüz film kaynağı yok"),
                        body = x.movieNoSources,
                        primaryLabel = x.movieLinkTitle,
                        primaryIcon = QMusicIcons.Play,
                        onPrimary = { linkDialogOpen = true },
                    )
                }
            } else {
                // Search, genre pills and a one-line result status.
                item(key = "cinema-search") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (showSearchUi) CinemaSearchBar(
                            query = searchQuery,
                            hint = tr("Search films…", "Film ara…"),
                            pending = searchQuery.trim() != submittedQuery && searchQuery.isNotBlank(),
                            clearLabel = tr("Clear", "Temizle"),
                            searchLabel = tr("Search", "Ara"),
                            onQueryChange = { searchQuery = it },
                            onSubmit = { submitSearch() },
                            onClear = { clearSearch() },
                            onReload = { reloadTick++ },
                            onFocusChange = { searchFocused = it },
                            onVoice = voiceSearch,
                            voiceLabel = tr("Search by voice", "Sesle ara"),
                            focusRequester = searchFocus,
                        )

                        AnimatedVisibility(
                            visible = showSearchUi && searchFocused && suggestions.isNotEmpty(),
                            enter = fadeIn(tween(Motion.SHORT)),
                            exit = fadeOut(tween(Motion.SHORT)),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(QMusicShapes.large)
                                    .background(VideoColors.Surface)
                                    .border(0.5.dp, BorderSubtle, QMusicShapes.large)
                                    .padding(vertical = 6.dp),
                            ) {
                                suggestions.forEach { sug ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                focusManager.clearFocus()
                                                if (sug.card != null) {
                                                    selectedCard = sug.card
                                                } else {
                                                    searchQuery = sug.text
                                                    submitSearch()
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        if (sug.card != null) {
                                            Box(
                                                Modifier
                                                    .size(width = 28.dp, height = 42.dp)
                                                    .clip(QMusicShapes.extraSmall)
                                                    .background(VideoColors.Chip),
                                            ) {
                                                FadeInPoster(url = sug.card.poster, contentDescription = null, iconSize = 14.dp)
                                            }
                                        } else {
                                            Icon(QMusicIcons.History, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(18.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(sug.text, color = VideoColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            val meta = sug.card?.year
                                            if (!meta.isNullOrEmpty()) {
                                                Text(meta, color = VideoColors.TextSecondary, fontSize = 11.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Recent film searches while the empty field is focused: one tap searches again.
                        AnimatedVisibility(
                            visible = showSearchUi && (searchFocused || searchIdle) && searchQuery.isEmpty() && recentSearches.isNotEmpty(),
                            enter = fadeIn(tween(Motion.SHORT)),
                            exit = fadeOut(tween(Motion.SHORT)),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SectionCaption(tr("RECENT SEARCHES", "SON ARAMALAR"), modifier = Modifier.weight(1f))
                                    Text(
                                        tr("Clear", "Temizle"),
                                        color = VideoColors.TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable { library.clearSearches() }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(recentSearches, key = { "recent-$it" }) { q ->
                                        RecentSearchChip(
                                            text = q,
                                            removeLabel = tr("Remove", "Kaldır"),
                                            onClick = {
                                                searchQuery = q
                                                focusManager.clearFocus()
                                                submitSearch()
                                            },
                                            onRemove = { library.forgetSearch(q) },
                                        )
                                    }
                                }
                            }
                        }

                        // Where to search: this source, or all of them at once.
                        if (showSearchUi && activeSources.size > 1 && (searchQuery.isNotBlank() || submittedQuery.isNotBlank())) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CinemaPill(tr("This source", "Bu kaynak"), selected = !searchAll) { searchAll = false }
                                CinemaPill(tr("All sources", "Tüm kaynaklar"), selected = searchAll) { searchAll = true }
                            }
                        }

                        if (showHomeUi && genres.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item(key = "genre-all") {
                                    CinemaPill(s.filterAll, selected = selectedGenreUrl == null && submittedQuery.isBlank()) {
                                        selectedGenreUrl = null
                                        clearSearch()
                                    }
                                }
                                items(genres, key = { it.second }) { (label, url) ->
                                    CinemaPill(label, selected = selectedGenreUrl == url) {
                                        searchQuery = ""
                                        if (submittedQuery.isNotEmpty()) controller.clearMovieSearch()
                                        submittedQuery = ""
                                        selectedGenreUrl = if (selectedGenreUrl == url) null else url
                                    }
                                }
                            }
                        }

                        val status: Pair<String, Pair<String, () -> Unit>?>? = when {
                            loading || loadError != null -> null
                            localQuery != null -> tr("${gridCards.size} match on this page", "Bu sayfada ${gridCards.size} eşleşme") to
                                (tr("Search source", "Kaynakta ara") to { submitSearch() })
                            submittedQuery.isNotBlank() -> tr(
                                "${countLabel(cards.size, nextPageUrl != null)} results for “$submittedQuery”",
                                "“$submittedQuery” için ${countLabel(cards.size, nextPageUrl != null)} sonuç",
                            ) to (tr("Clear", "Temizle") to { clearSearch() })
                            selectedGenreLabel != null -> "$selectedGenreLabel · ${countLabel(cards.size, nextPageUrl != null)} ${tr("titles", "yapım")}" to
                                (tr("All", "Tümü") to { selectedGenreUrl = null })
                            else -> null
                        }
                        AnimatedVisibility(visible = status != null, enter = fadeIn(tween(Motion.SHORT)), exit = fadeOut(tween(Motion.SHORT))) {
                            if (status != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        status.first,
                                        color = VideoColors.TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    status.second?.let { (label, action) ->
                                        Text(
                                            label,
                                            color = VideoColors.Text,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .clickable(onClick = action)
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Recently watched (with how far) and the person's own list: on the device, so they show even when the
                // source is slow or down.
                val browsing = showHomeUi && localQuery == null && submittedQuery.isBlank() && selectedGenreUrl == null
                if (browsing && !loading && loadError == null && heroPicks.isNotEmpty()) {
                    item(key = "cinema-hero") {
                        CinemaHeroCarousel(
                            cards = heroPicks,
                            english = english,
                            tablet = tablet,
                            viewportHeight = maxHeight,
                            watchLabel = x.moviePlaySolo,
                            isInList = { c -> myList.any { it.pageUrl == c.pageUrl } },
                            onToggleList = { library.toggleFavorite(it) },
                            onOpenDetails = { selectedCard = it },
                            onWatch = { watch(it) },
                        )
                    }
                }
                if (browsing && movieHistory.isNotEmpty()) {
                    item(key = "movie-history") {
                        SavedMovieRow(
                            caption = tr("CONTINUE WATCHING", "İZLEMEYE DEVAM ET"),
                            action = tr("Clear", "Temizle"),
                            onAction = { library.clearHistory() },
                            movies = movieHistory,
                            onOpen = { selectedCard = it.toCard() },
                            removeLabel = tr("Remove from history", "Geçmişten kaldır"),
                            onRemove = { library.forget(it.pageUrl) },
                        )
                    }
                }
                if (browsing && myList.isNotEmpty()) {
                    item(key = "movie-my-list") {
                        SavedMovieRow(
                            caption = tr("MY LIST", "LİSTEM"),
                            action = null,
                            onAction = {},
                            movies = myList,
                            onOpen = { selectedCard = it.toCard() },
                            removeLabel = tr("Remove from my list", "Listemden çıkar"),
                            onRemove = { library.removeFavorite(it.pageUrl) },
                        )
                    }
                }

                if (searchIdle) {
                    item(key = "search-idle") {
                        CinemaEmptyState(
                            icon = QMusicIcons.Search,
                            title = tr("Find a film", "Film ara"),
                            body = tr(
                                "Type a title above, or tap the mic and say it.",
                                "Yukarıya bir film adı yaz ya da mikrofona dokunup söyle.",
                            ),
                            primaryLabel = tr("Browse genres", "Türlere göz at"),
                            primaryIcon = VideoIcons.Film,
                            onPrimary = { mem.page.value = FilmPage.HOME },
                        )
                    }
                } else when {
                    loading -> item(key = "movies-loading") {
                        CinemaSkeleton(cols = cols, tablet = tablet, withHero = submittedQuery.isBlank() && selectedGenreUrl == null)
                    }

                    loadError != null -> item(key = "movies-error") {
                        CinemaEmptyState(
                            icon = QMusicIcons.CloudOff,
                            title = tr("Couldn't reach the source", "Kaynağa ulaşılamadı"),
                            body = loadError ?: s.loadError,
                            primaryLabel = s.retry,
                            primaryIcon = QMusicIcons.Refresh,
                            onPrimary = { reloadTick++ },
                            secondaryLabel = x.movieLinkTitle,
                            onSecondary = { linkDialogOpen = true },
                        )
                    }

                    gridCards.isEmpty() -> item(key = "movies-empty") {
                        val searching = localQuery != null || submittedQuery.isNotBlank()
                        val q = localQuery ?: submittedQuery
                        CinemaEmptyState(
                            icon = if (searching) QMusicIcons.Search else VideoIcons.Film,
                            title = when {
                                searching -> tr("Nothing for “$q”", "“$q” bulunamadı")
                                selectedGenreLabel != null -> tr("No titles in $selectedGenreLabel", "$selectedGenreLabel türünde yapım yok")
                                else -> x.movieNoResults
                            },
                            body = when {
                                localQuery != null -> tr(
                                    "Not on the loaded page. Search the whole source instead.",
                                    "Yüklenen sayfada yok. Tüm kaynakta aramayı dene.",
                                )
                                searching -> tr(
                                    "Check the spelling, try a shorter title or switch to another source.",
                                    "Yazımı kontrol et, daha kısa bir ad dene ya da başka bir kaynağa geç.",
                                )
                                else -> tr("Try another genre or source.", "Başka bir tür ya da kaynak dene.")
                            },
                            primaryLabel = when {
                                localQuery != null -> tr("Search source", "Kaynakta ara")
                                searching -> tr("Clear search", "Aramayı temizle")
                                selectedGenreLabel != null -> s.filterAll
                                else -> s.retry
                            },
                            primaryIcon = if (localQuery != null) QMusicIcons.Search else null,
                            onPrimary = {
                                when {
                                    localQuery != null -> { submitSearch() }
                                    searching -> { clearSearch() }
                                    selectedGenreLabel != null -> { selectedGenreUrl = null }
                                    else -> { reloadTick++ }
                                }
                            },
                            secondaryLabel = x.movieLinkTitle,
                            onSecondary = { linkDialogOpen = true },
                        )
                    }

                    else -> {
                        if (browsing) {
                            val together = { card: MovieCard ->
                                sharedItemForUrl(card.pageUrl, card.title, card.sourceName, card.poster)?.let {
                                    ChatMusic.offerTogether(it)
                                }
                                Unit
                            }
                            item(key = "row-latest") {
                                CinemaRow(
                                    caption = tr("LATEST", "SON EKLENENLER"),
                                    cards = cards.take(ROW_SIZE),
                                    tablet = tablet,
                                    watchTogetherLabel = x.watchTogether,
                                    seeAllLabel = null,
                                    onSeeAll = null,
                                    onOpen = { selectedCard = it },
                                    onWatchTogether = together,
                                    progressOf = { progressOf[it.pageUrl] ?: 0f },
                                    inListOf = { it.pageUrl in listUrls },
                                )
                            }
                            if (topRated.size >= 4) {
                                item(key = "row-top-rated") {
                                    CinemaRow(
                                        caption = tr("TOP RATED", "EN YÜKSEK PUAN"),
                                        cards = topRated,
                                        tablet = tablet,
                                        watchTogetherLabel = x.watchTogether,
                                        seeAllLabel = null,
                                        onSeeAll = null,
                                        onOpen = { selectedCard = it },
                                        onWatchTogether = together,
                                        progressOf = { progressOf[it.pageUrl] ?: 0f },
                                        inListOf = { it.pageUrl in listUrls },
                                    )
                                }
                            }
                            rowGenres.forEach { (label, url) ->
                                val list = genreRows[url]
                                if (!list.isNullOrEmpty()) {
                                    item(key = "row-genre-$url") {
                                        CinemaRow(
                                            caption = label.uppercase(),
                                            cards = list,
                                            tablet = tablet,
                                            watchTogetherLabel = x.watchTogether,
                                            seeAllLabel = tr("See all", "Tümü"),
                                            onSeeAll = { selectedGenreUrl = url },
                                            onOpen = { selectedCard = it },
                                            onWatchTogether = together,
                                            progressOf = { progressOf[it.pageUrl] ?: 0f },
                                            inListOf = { it.pageUrl in listUrls },
                                        )
                                    }
                                }
                            }
                            item(key = "grid-caption") {
                                SectionCaption(
                                    tr("ALL TITLES", "TÜM YAPIMLAR"),
                                    modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                                )
                            }
                        }

                        if (gridCards.size > 2) {
                            item(key = "grid-sort") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(MovieSort.entries.toList(), key = { it.name }) { mode ->
                                            CinemaPill(tr(mode.en, mode.tr), selected = sort == mode, quiet = sort != mode) { sort = mode }
                                        }
                                    }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        item(key = "f-r7") { CinemaPill(tr("Rating 7+", "Puan 7+"), selected = minRating == 7f) { minRating = if (minRating == 7f) null else 7f } }
                                        item(key = "f-r8") { CinemaPill(tr("Rating 8+", "Puan 8+"), selected = minRating == 8f) { minRating = if (minRating == 8f) null else 8f } }
                                        item(key = "f-y20") { CinemaPill(tr("2020 and later", "2020 ve sonrası"), selected = minYear == 2020) { minYear = if (minYear == 2020) null else 2020 } }
                                        item(key = "f-y10") { CinemaPill(tr("2010 and later", "2010 ve sonrası"), selected = minYear == 2010) { minYear = if (minYear == 2010) null else 2010 } }
                                        if (true in kinds && false in kinds) {
                                            item(key = "f-film") { CinemaPill(tr("Movies", "Film"), selected = kind == false) { kind = if (kind == false) null else false } }
                                            item(key = "f-dizi") { CinemaPill(tr("Series", "Dizi"), selected = kind == true) { kind = if (kind == true) null else true } }
                                        }
                                    }
                                    if (filteredCards.size != gridCards.size) {
                                        Text(
                                            tr("${filteredCards.size} of ${gridCards.size} shown", "${gridCards.size} yapımdan ${filteredCards.size} tanesi gösteriliyor"),
                                            color = VideoColors.TextSecondary,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(start = 4.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Responsive 2..5-column cinema poster grid (one list: which site a film comes from is not shown).
                        val shown = sortedMovies(filteredCards, sort)
                        val groups: List<Pair<String?, List<MovieCard>>> = listOf(null to shown)
                        groups.forEach { (groupName, groupCards) ->
                            if (groupName != null) {
                                item(key = "group-$groupName") {
                                    SectionCaption(
                                        "${groupName.ifBlank { tr("OTHER", "DİĞER") }.uppercase()} · ${groupCards.size}",
                                        modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                                    )
                                }
                            }
                            val rows = groupCards.chunked(cols)
                            items(rows.size, key = { "mrow-${groupName.orEmpty()}-$it-${rows[it].first().pageUrl}" }) { rIdx ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    rows[rIdx].forEach { card ->
                                        MoviePosterTile(
                                            card = card,
                                            watchTogetherLabel = x.watchTogether,
                                            onOpenDetails = { selectedCard = card },
                                            onWatchTogether = {
                                                sharedItemForUrl(card.pageUrl, card.title, card.sourceName, card.poster)?.let {
                                                    ChatMusic.offerTogether(it)
                                                }
                                            },
                                            progress = progressOf[card.pageUrl] ?: 0f,
                                            inList = card.pageUrl in listUrls,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(cols - rows[rIdx].size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }

                        if (nextPageUrl != null && localQuery == null) {
                            item(key = "movies-load-more") {
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    if (loadingMore) {
                                        CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                                    } else if (loadMoreError) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                tr("Couldn't load more", "Daha fazlası yüklenemedi"),
                                                color = VideoColors.TextSecondary,
                                                fontSize = 12.sp,
                                            )
                                            Text(
                                                s.retry,
                                                color = VideoColors.Text,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier
                                                    .pressScale(0.95f)
                                                    .clip(RoundedCornerShape(50))
                                                    .background(VideoColors.Surface)
                                                    .border(0.5.dp, BorderHover, RoundedCornerShape(50))
                                                    .clickable { loadNextPage() }
                                                    .padding(horizontal = 24.dp, vertical = 11.dp),
                                            )
                                        }
                                    } else {
                                        Text(
                                            s.loadMore,
                                            color = VideoColors.Text,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .pressScale(0.95f)
                                                .clip(RoundedCornerShape(50))
                                                .background(VideoColors.Surface)
                                                .border(0.5.dp, BorderHover, RoundedCornerShape(50))
                                                .clickable { loadNextPage() }
                                                .padding(horizontal = 24.dp, vertical = 11.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Direct URL Play / Watch Together modal dialog
    if (linkDialogOpen) {
        var directUrl by remember { mutableStateOf("") }
        var directError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { linkDialogOpen = false },
            containerColor = VideoColors.Surface,
            shape = QMusicShapes.extraLarge,
            title = { Text(x.movieLinkTitle, color = VideoColors.Text, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(QMusicShapes.medium)
                            .background(VideoColors.Background)
                            .border(0.5.dp, if (directError != null) BorderHover else BorderSubtle, QMusicShapes.medium)
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                    ) {
                        if (directUrl.isEmpty()) {
                            Text(x.movieLinkHint, color = VideoColors.TextSecondary, fontSize = 13.sp)
                        }
                        BasicTextField(
                            value = directUrl,
                            onValueChange = {
                                directUrl = it
                                directError = null
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = VideoColors.Text, fontSize = 14.sp),
                            cursorBrush = SolidColor(Ink),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    directError?.let { Text(it, color = VideoColors.Red, fontSize = 12.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CinemaButton(
                            label = x.moviePlaySolo,
                            icon = QMusicIcons.Play,
                            primary = true,
                            height = 44.dp,
                            modifier = Modifier.weight(1f),
                        ) {
                            val url = directUrl.trim()
                            if (controller.openExternalUrl(url)) {
                                linkDialogOpen = false
                                if (extractYouTubeIdFromUrl(url) == null) {
                                    val host = MovieHtmlParser.originOf(url).removePrefix("https://").removePrefix("http://")
                                    val guess = url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { host }
                                    library.recordWatched(MovieCard(title = guess, pageUrl = url, sourceName = host), titleGuessed = true)
                                }
                            } else {
                                directError = x.movieInvalidUrl
                            }
                        }
                        CinemaButton(
                            label = x.watchTogether,
                            icon = QMusicIcons.Group,
                            primary = false,
                            height = 44.dp,
                            modifier = Modifier.weight(1f),
                        ) {
                            val item = sharedItemForUrl(directUrl)
                            if (item == null) {
                                directError = x.movieInvalidUrl
                            } else {
                                linkDialogOpen = false
                                ChatMusic.offerTogether(item)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { linkDialogOpen = false }) { Text(s.close, color = VideoColors.TextSecondary) } },
        )
    }

    selectedCard?.let { card ->
        // "More like this": the genre rows this film is in first, then the open genre, then the best rated, then the rest.
        val similar = remember(card.pageUrl, genreRows, cards, topRated) {
            val sameGenre = genreRows.values.filter { l -> l.any { it.pageUrl == card.pageUrl } }.flatten()
            val pool = sameGenre + (if (selectedGenreUrl != null) cards else emptyList()) + topRated + cards
            val list = pool.filter { it.pageUrl != card.pageUrl }.distinctBy { it.pageUrl }.take(SIMILAR_COUNT)
            list to (sameGenre.size > 1 || selectedGenreUrl != null)
        }
        MovieDetailSheet(
            card = card,
            similar = similar.first,
            similarCaption = if (similar.second) tr("MORE LIKE THIS", "BENZER YAPIMLAR") else tr("YOU MIGHT ALSO LIKE", "BUNLARA DA BAK"),
            onOpenSimilar = { selectedCard = it },
            genreHint = selectedGenreLabel,
            english = english,
            watchLabel = x.moviePlaySolo,
            togetherLabel = x.watchTogether,
            shareLabel = ChatMusic.words().sendToChat,
            resolve = { library.resolve(card.pageUrl, card.headers) },
            onResolved = { controller.warmUpFilm(card.pageUrl, it) },
            inList = myList.any { it.pageUrl == card.pageUrl },
            watchedFraction = progressOf[card.pageUrl] ?: 0f,
            onToggleList = { library.toggleFavorite(card) },
            onRestart = { epUrl ->
                controller.forgetResume(externalVideoId(card.pageUrl))
                epUrl?.let { controller.forgetResume(externalVideoId(it)) }
            },
            onDismiss = { selectedCard = null },
            onWatch = { resolved, bySeason ->
                selectedCard = null
                watch(card, resolved, episodesBySeason = bySeason, relatedMovies = similar.first)
            },
            onWatchEpisode = { episode, resolved, bySeason ->
                selectedCard = null
                watch(card, resolved, episode, episodesBySeason = bySeason, relatedMovies = similar.first)
            },
            onWatchTogether = { resolved ->
                selectedCard = null
                val targetUrl = resolved?.activeEpisodeUrl ?: card.pageUrl
                sharedItemForUrl(targetUrl, resolved?.title ?: card.title, card.sourceName, card.poster ?: resolved?.poster)?.let {
                    ChatMusic.offerTogether(it)
                }
            },
            // Sent inside Nuvelo (a friend opens it in the app), not the site's address.
            onShare = {
                sharedItemForUrl(card.pageUrl, card.title, card.sourceName, card.poster)?.let { ChatMusic.offer(it) }
            },
        )
    }
}

// ------------------------------------------------------------------ detail sheet

/**
 * Movie detail bottom sheet: blurred poster backdrop, poster + title, IMDb / year / quality chips, genre tags,
 * stream readiness, expandable synopsis and the Watch / Watch together / Share actions. The stream is resolved in the
 * background as soon as the sheet opens, so Watch starts without waiting.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MovieDetailSheet(
    card: MovieCard,
    similar: List<MovieCard>,
    similarCaption: String,
    onOpenSimilar: (MovieCard) -> Unit,
    genreHint: String?,
    english: Boolean,
    watchLabel: String,
    togetherLabel: String,
    shareLabel: String,
    onDismiss: () -> Unit,
    onWatch: (ResolvedMovieStream?, Map<Int, List<MovieEpisode>>) -> Unit,
    onWatchEpisode: (MovieEpisode, ResolvedMovieStream?, Map<Int, List<MovieEpisode>>) -> Unit,
    onWatchTogether: (ResolvedMovieStream?) -> Unit,
    onShare: () -> Unit,
    resolve: suspend () -> Result<ResolvedMovieStream>,
    /** The stream is known: the player can load it already (see VideosController.warmUpFilm). */
    onResolved: (ResolvedMovieStream) -> Unit = {},
    inList: Boolean,
    /** How much of it was watched (0-1), shown as a line under Watch. */
    watchedFraction: Float = 0f,
    onToggleList: () -> Unit,
    onRestart: (String?) -> Unit,
) {
    fun tr(en: String, tr: String) = if (english) en else tr
    val scope = rememberCoroutineScope()
    var resolved by remember(card.pageUrl) { mutableStateOf<ResolvedMovieStream?>(null) }
    var resolving by remember(card.pageUrl) { mutableStateOf(true) }
    var resolveErr by remember(card.pageUrl) { mutableStateOf<String?>(null) }
    var selectedSeason by remember(card.pageUrl) { mutableStateOf<Int?>(null) }
    var seasonEpisodes by remember(card.pageUrl) { mutableStateOf<Map<Int, List<MovieEpisode>>>(emptyMap()) }
    var loadingSeason by remember(card.pageUrl) { mutableStateOf<Int?>(null) }
    LaunchedEffect(card.pageUrl) {
        resolving = true
        resolveErr = null
        // The same lookup the player waits for when Watch is pressed: it is not done twice.
        resolve().fold(
            onSuccess = {
                resolved = it
                if (it.episodes.isNotEmpty()) {
                    val grouped = it.episodes.groupBy { ep -> ep.season }
                    seasonEpisodes = grouped
                    selectedSeason = it.seasons.firstOrNull() ?: grouped.keys.minOrNull()
                } else if (it.seasons.isNotEmpty()) {
                    selectedSeason = it.seasons.first()
                }
                if (it.streams.isEmpty()) {
                    resolveErr = tr("No playable video was found on this page.", "Bu sayfada oynatılabilir video bulunamadı.")
                } else {
                    onResolved(it)
                }
                resolving = false
            },
            onFailure = {
                resolveErr = friendlyMovieError(it, english)
                resolving = false
            },
        )
    }
    val positions by VideoResume.positions.collectAsState()
    val r = resolved
    val resumeMs = (r?.activeEpisodeUrl?.let { positions[externalVideoId(it)] } ?: positions[externalVideoId(card.pageUrl)]) ?: 0L
    val title = r?.title?.takeIf { it.isNotBlank() } ?: card.title
    val poster = card.poster ?: r?.poster
    val rating = card.rating ?: r?.rating
    val year = card.year ?: r?.year
    val synopsis = r?.synopsis?.takeIf { it.isNotBlank() }
    val tags = remember(card.subtitle, genreHint, year) {
        val fromCard = card.subtitle.orEmpty()
            .split(',', '/', '·', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 24 && it != year }
        (fromCard + listOfNotNull(genreHint)).distinctBy { it.lowercase() }.take(6)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = VideoColors.Background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // A film opened from "more like this" starts at the top of the sheet.
                .verticalScroll(remember(card.pageUrl) { ScrollState(0) })
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                // Cinematic backdrop: the poster, blurred and faded into the sheet.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(VideoColors.Surface),
                ) {
                    if (poster != null) {
                        AsyncImage(
                            model = poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alpha = 0.55f,
                            modifier = Modifier.fillMaxSize().blur(36.dp),
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to VideoColors.Background.copy(alpha = 0.10f),
                                    0.55f to VideoColors.Background.copy(alpha = 0.55f),
                                    1f to VideoColors.Background,
                                ),
                            ),
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(BorderHover),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(
                        modifier = Modifier
                            .width(116.dp)
                            .aspectRatio(2f / 3f)
                            .clip(QMusicShapes.medium)
                            .background(VideoColors.Chip)
                            .border(0.5.dp, BorderHover, QMusicShapes.medium),
                    ) {
                        FadeInPoster(url = poster, contentDescription = title, iconSize = 32.dp)
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            title,
                            color = VideoColors.Text,
                            fontSize = 21.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // IMDb / year / quality
                if (rating != null || year != null || card.qualityBadge != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rating?.let { ImdbChip(it) }
                        year?.let { MetaChip(it) }
                        card.qualityBadge?.let { MetaChip(it, strong = true) }
                    }
                }

                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { tag ->
                            Text(
                                tag,
                                color = VideoColors.TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .border(0.5.dp, BorderSubtle, RoundedCornerShape(50))
                                    .padding(horizontal = 11.dp, vertical = 5.dp),
                            )
                        }
                    }
                }

                // What it plays in, as small tags (quality, sound, subtitles); the technical box only when it failed.
                val ready = r?.takeIf { it.streams.isNotEmpty() }
                if (ready != null) {
                    val streamTags = buildList {
                        ready.streams.map { it.label }.filter { it.isNotBlank() }.distinct().take(2).forEach(::add)
                        if (ready.audioTracks.any { it.language?.lowercase()?.startsWith("tr") == true || it.label.contains("Türkçe", ignoreCase = true) }) {
                            add(tr("Turkish audio", "Türkçe ses"))
                        }
                        if (ready.audioTracks.size > 1) add(tr("${ready.audioTracks.size} audio", "${ready.audioTracks.size} ses"))
                        if (ready.captions.isNotEmpty()) add(tr("Subtitles", "Altyazı"))
                    }
                    if (streamTags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            streamTags.forEach { MetaChip(it) }
                        }
                    }
                } else if (!resolving) {
                    StreamStatus(
                        resolving = false,
                        resolved = null,
                        error = resolveErr,
                        pageUrl = card.pageUrl,
                        english = english,
                    )
                }

                // Synopsis (tap to expand); shimmer lines while it is still being fetched.
                when {
                    synopsis != null -> {
                        var expanded by remember(card.pageUrl) { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(QMusicShapes.small)
                                .clickable { expanded = !expanded }
                                .animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized)),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SectionCaption(tr("SYNOPSIS", "ÖZET"))
                            Text(
                                synopsis,
                                color = VideoColors.Text.copy(alpha = 0.82f),
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!expanded && synopsis.length > 180) {
                                Text(tr("More", "Devamı"), color = VideoColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    resolving -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionCaption(tr("SYNOPSIS", "ÖZET"))
                        listOf(1f, 0.92f, 0.6f).forEach { w ->
                            Box(Modifier.fillMaxWidth(w).height(11.dp).clip(RoundedCornerShape(4.dp)).cinemaShimmer())
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                    CinemaButton(
                        label = if (resumeMs > 0L) tr("Resume · ${clock(resumeMs)}", "Devam et · ${clock(resumeMs)}") else watchLabel,
                        icon = QMusicIcons.Play,
                        primary = true,
                        height = 52.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) { onWatch(r, seasonEpisodes) }
                    // Under Watch: a moving line while the film is being prepared, then how much of it was watched.
                    when {
                        resolving -> LinearProgressIndicator(
                            color = Ink,
                            trackColor = BorderSubtle,
                            modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(50)),
                        )
                        resumeMs > 0L && watchedFraction > 0f -> Box(
                            Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(BorderSubtle),
                        ) {
                            Box(Modifier.fillMaxWidth(watchedFraction).height(3.dp).background(Ink))
                        }
                    }
                    if (resumeMs > 0L) {
                        Text(
                            tr("Start from the beginning", "Baştan izle"),
                            color = VideoColors.TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(QMusicShapes.small)
                                .clickable {
                                    onRestart(r?.activeEpisodeUrl)
                                    onWatch(r, seasonEpisodes)
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                    // The other things to do with it, as three small buttons side by side.
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        HeroAction(label = if (inList) tr("In my list", "Listemde") else tr("My list", "Listem"), onClick = onToggleList) {
                            Icon(if (inList) QMusicIcons.Check else QMusicIcons.Add, null, tint = VideoColors.Text, modifier = Modifier.size(22.dp))
                        }
                        HeroAction(label = togetherLabel, onClick = { onWatchTogether(r) }) {
                            Icon(QMusicIcons.Group, null, tint = VideoColors.Text, modifier = Modifier.size(22.dp))
                        }
                        HeroAction(label = shareLabel, onClick = onShare) {
                            Icon(QMusicIcons.Chat, null, tint = VideoColors.Text, modifier = Modifier.size(22.dp))
                        }
                    }
                }

                val seasons = r?.seasons.orEmpty()
                val activeSeason = selectedSeason ?: seasons.firstOrNull() ?: r?.episodes?.firstOrNull()?.season ?: 1
                val activeEpisodes = seasonEpisodes[activeSeason]
                    ?: r?.episodes?.filter { it.season == activeSeason }.orEmpty()
                if (seasons.isNotEmpty() || activeEpisodes.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                        SectionCaption(tr("EPISODES", "BÖLÜMLER"))
                        if (seasons.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(seasons, key = { "season-$it" }) { sn ->
                                    CinemaPill(
                                        text = tr("Season $sn", "$sn. Sezon"),
                                        selected = sn == activeSeason,
                                    ) {
                                        selectedSeason = sn
                                        val tvId = r?.tvshowId
                                        if (tvId != null && seasonEpisodes[sn] == null && loadingSeason != sn) {
                                            loadingSeason = sn
                                            scope.launch {
                                                fetchSeriesSeasonEpisodes(card.pageUrl, tvId, sn, card.headers).onSuccess { eps ->
                                                    if (eps.isNotEmpty()) seasonEpisodes = seasonEpisodes + (sn to eps)
                                                }
                                                if (loadingSeason == sn) loadingSeason = null
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        when {
                            loadingSeason == activeSeason -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(vertical = 8.dp),
                            ) {
                                CircularProgressIndicator(color = Ink, strokeWidth = 1.6.dp, modifier = Modifier.size(16.dp))
                                Text(tr("Loading episodes…", "Bölümler yükleniyor…"), color = VideoColors.TextSecondary, fontSize = 12.sp)
                            }
                            activeEpisodes.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(activeEpisodes, key = { "ep-${it.pageUrl}" }) { ep ->
                                    val epShape = QMusicShapes.medium
                                    val isCurrent = r?.activeEpisodeUrl == ep.pageUrl
                                    Column(
                                        modifier = Modifier
                                            .width(156.dp)
                                            .pressScale(0.97f)
                                            .clip(epShape)
                                            .background(VideoColors.Surface)
                                            .border(0.5.dp, if (isCurrent) BorderHover else BorderSubtle, epShape)
                                            .clickable { onWatchEpisode(ep, r, seasonEpisodes) }
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                tr("Ep ${ep.episode}", "${ep.episode}. Bölüm"),
                                                color = VideoColors.Text,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                            )
                                            ep.runtime?.let { rt ->
                                                Text(rt, color = VideoColors.TextSecondary, fontSize = 11.sp, maxLines = 1)
                                            }
                                            Spacer(Modifier.weight(1f))
                                            Icon(QMusicIcons.Play, null, tint = VideoColors.Text, modifier = Modifier.size(14.dp))
                                        }
                                        Text(
                                            ep.title,
                                            color = VideoColors.TextSecondary,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (similar.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                        SectionCaption(similarCaption)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(similar, key = { "similar-${it.pageUrl}" }) { other ->
                                val shape = QMusicShapes.medium
                                Column(
                                    modifier = Modifier
                                        .width(96.dp)
                                        .pressScale(0.97f)
                                        .clip(shape)
                                        .clickable { onOpenSimilar(other) },
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(2f / 3f)
                                            .clip(shape)
                                            .background(VideoColors.Surface)
                                            .border(0.5.dp, BorderSubtle, shape),
                                    ) {
                                        FadeInPoster(url = other.poster, contentDescription = other.title, iconSize = 24.dp)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        other.title,
                                        color = VideoColors.Text,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        lineHeight = 15.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamStatus(
    resolving: Boolean,
    resolved: ResolvedMovieStream?,
    error: String?,
    pageUrl: String,
    english: Boolean,
) {
    val shape = QMusicShapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(VideoColors.Surface)
            .border(0.5.dp, BorderSubtle, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (resolved != null && !resolving) Ink else VideoColors.Chip),
            contentAlignment = Alignment.Center,
        ) {
            when {
                resolving -> CircularProgressIndicator(color = Ink, strokeWidth = 1.6.dp, modifier = Modifier.size(14.dp))
                resolved != null -> Icon(QMusicIcons.Check, null, tint = InkInverse, modifier = Modifier.size(15.dp))
                else -> Icon(QMusicIcons.CloudOff, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(15.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                resolving -> {
                    Text(if (english) "Preparing stream…" else "Akış hazırlanıyor…", color = VideoColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (english) "Playback starts instantly when ready" else "Hazır olunca anında başlar", color = VideoColors.TextSecondary, fontSize = 12.sp)
                }
                resolved != null -> {
                    val qualities = resolved.streams.map { it.label }.distinct().take(3).joinToString(" / ")
                    val extras = listOfNotNull(
                        qualities.takeIf { it.isNotBlank() },
                        resolved.audioTracks.size.takeIf { it > 1 }?.let { if (english) "$it audio" else "$it ses" },
                        resolved.captions.size.takeIf { it > 0 }?.let { if (english) "$it subtitles" else "$it altyazı" },
                    ).joinToString(" · ")
                    Text(if (english) "Ready to play" else "Oynatmaya hazır", color = VideoColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (extras.isNotEmpty()) Text(extras, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                else -> {
                    Text(if (english) "Stream not found" else "Akış bulunamadı", color = VideoColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    error?.let { Text(it, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (!resolving && resolved == null) {
            val uriHandler = LocalUriHandler.current
            Text(
                if (english) "Browser" else "Tarayıcı",
                color = VideoColors.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(0.5.dp, BorderHover, RoundedCornerShape(50))
                    .clickable { runCatching { uriHandler.openUri(pageUrl) } }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ImdbChip(rating: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(VideoColors.Surface)
            .border(0.5.dp, BorderHover, RoundedCornerShape(50))
            .padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            "IMDb",
            color = InkInverse,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Ink)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(rating, color = VideoColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (!rating.contains('/')) Text("/10", color = VideoColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 0.dp))
    }
}

@Composable
private fun MetaChip(text: String, strong: Boolean = false) {
    Text(
        text,
        color = if (strong) VideoColors.Text else VideoColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(VideoColors.Surface)
            .border(0.5.dp, if (strong) BorderHover else BorderSubtle, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = VideoColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Monochrome pill: ink-filled when selected, quiet chip otherwise; colours glide between the two. */
@Composable
private fun CinemaPill(text: String, selected: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
    val spec = tween<Color>(Motion.MEDIUM, easing = Motion.Emphasized)
    val idleBg = if (quiet) Color.Transparent else VideoColors.Chip
    val bg by animateColorAsState(if (selected) Ink else idleBg, spec, label = "pillBg")
    val fg by animateColorAsState(if (selected) InkInverse else VideoColors.Text, spec, label = "pillFg")
    val bd by animateColorAsState(if (selected || quiet) Color.Transparent else BorderSubtle, spec, label = "pillBorder")
    Text(
        text = text,
        color = fg,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .pressScale(0.94f)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(0.5.dp, bd, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
    )
}

@Composable
private fun CinemaButton(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    onClick: () -> Unit,
) {
    val shape = QMusicShapes.medium
    Row(
        modifier = modifier
            .height(height)
            .pressScale(0.97f)
            .clip(shape)
            .background(if (primary) Ink else VideoColors.Surface)
            .then(if (primary) Modifier else Modifier.border(0.5.dp, BorderHover, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (primary) InkInverse else VideoColors.Text
        Icon(icon, null, tint = fg, modifier = Modifier.size(if (primary) 18.dp else 16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = if (primary) 15.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CinemaSearchBar(
    query: String,
    hint: String,
    pending: Boolean,
    clearLabel: String,
    searchLabel: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onReload: () -> Unit,
    onFocusChange: (Boolean) -> Unit = {},
    onVoice: (() -> Unit)? = null,
    voiceLabel: String = "",
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val border by animateColorAsState(if (focused) BorderHover else BorderSubtle, tween(Motion.SHORT), label = "searchBorder")
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(VideoColors.Surface)
            .border(if (focused) 1.dp else 0.5.dp, border, shape)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QMusicIcons.Search, null, tint = if (focused) VideoColors.Text else VideoColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(hint, color = VideoColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = VideoColors.Text, fontSize = 15.sp),
                cursorBrush = SolidColor(Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSubmit()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChange(it.isFocused)
                    },
            )
        }
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn(tween(Motion.SHORT)) + scaleIn(tween(Motion.SHORT), initialScale = 0.6f),
            exit = fadeOut(tween(Motion.SHORT)) + scaleOut(tween(Motion.SHORT), targetScale = 0.6f),
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(20.dp).clip(CircleShape).background(VideoColors.TextSecondary.copy(alpha = 0.24f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(QMusicIcons.Close, clearLabel, tint = VideoColors.Text, modifier = Modifier.size(11.dp))
                }
            }
        }
        if (onVoice != null && query.isEmpty()) {
            Box(
                modifier = Modifier.size(36.dp).pressScale(0.9f).clip(CircleShape).clickable(onClick = onVoice),
                contentAlignment = Alignment.Center,
            ) {
                Icon(QMusicIcons.Mic, voiceLabel, tint = VideoColors.TextSecondary, modifier = Modifier.size(17.dp))
            }
        }
        Spacer(Modifier.width(2.dp))
        val actionBg by animateColorAsState(if (pending) Ink else Color.Transparent, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "searchAction")
        Box(
            modifier = Modifier
                .size(36.dp)
                .pressScale(0.9f)
                .clip(CircleShape)
                .background(actionBg)
                .clickable {
                    if (pending) {
                        keyboard?.hide()
                        onSubmit()
                    } else {
                        onReload()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (pending) QMusicIcons.Search else QMusicIcons.Refresh,
                searchLabel,
                tint = if (pending) InkInverse else VideoColors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun CinemaEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String? = null,
    primaryIcon: ImageVector? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(96.dp).clip(CircleShape).border(0.5.dp, BorderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(VideoColors.Surface)
                    .border(0.5.dp, BorderHover, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = VideoColors.Text, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, color = VideoColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            color = VideoColors.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if ((primaryLabel != null && onPrimary != null) || (secondaryLabel != null && onSecondary != null)) {
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (primaryLabel != null && onPrimary != null) {
                    Row(
                        modifier = Modifier
                            .pressScale(0.95f)
                            .clip(RoundedCornerShape(50))
                            .background(Ink)
                            .clickable(onClick = onPrimary)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        primaryIcon?.let { Icon(it, null, tint = InkInverse, modifier = Modifier.size(14.dp)) }
                        Text(primaryLabel, color = InkInverse, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (secondaryLabel != null && onSecondary != null) {
                    Text(
                        secondaryLabel,
                        color = VideoColors.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .pressScale(0.95f)
                            .clip(RoundedCornerShape(50))
                            .border(0.5.dp, BorderHover, RoundedCornerShape(50))
                            .clickable(onClick = onSecondary)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** A soft monochrome sweep for loading placeholders (no colour: a faint ink sheen over the surface). */
@Composable
private fun Modifier.cinemaShimmer(): Modifier {
    val transition = rememberInfiniteTransition(label = "cinemaShimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "cinemaShimmerProgress",
    )
    val base = VideoColors.Surface
    val sheen = Ink.copy(alpha = 0.07f)
    return this.drawBehind {
        drawRect(base)
        val w = size.width.coerceAtLeast(1f)
        val start = -w + progress.value * (w * 2.5f)
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, sheen, Color.Transparent),
                start = Offset(start, 0f),
                end = Offset(start + w * 0.8f, size.height),
            ),
        )
    }
}

/** Poster image that fades in over a shimmer once it arrives, and falls back to a film glyph. */
@Composable
private fun FadeInPoster(url: String?, contentDescription: String?, iconSize: Dp = 34.dp) {
    var loaded by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(url == null) }
    val alpha by animateFloatAsState(if (loaded) 1f else 0f, tween(Motion.LONG, easing = Motion.EmphasizedDecelerate), label = "posterFade")
    Box(Modifier.fillMaxSize()) {
        if (!loaded && !failed) Box(Modifier.fillMaxSize().cinemaShimmer())
        if (failed) {
            Icon(
                VideoIcons.Film,
                contentDescription = null,
                tint = VideoColors.TextSecondary.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.Center).size(iconSize),
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
            )
        }
    }
}

// ------------------------------------------------------------------ hero, tiles, skeleton

/**
 * Home showcase: big posters side by side, whole (not cropped), the one in the middle larger and bright; under it its
 * title, rating and My list / Watch / Details. It moves on by itself every few seconds; a swipe, a dot or a tap on a
 * side poster chooses another.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CinemaHeroCarousel(
    cards: List<MovieCard>,
    english: Boolean,
    tablet: Boolean,
    /** The page's height: a phone turned sideways keeps the poster within the screen. */
    viewportHeight: Dp,
    watchLabel: String,
    isInList: (MovieCard) -> Boolean,
    onToggleList: (MovieCard) -> Unit,
    onOpenDetails: (MovieCard) -> Unit,
    onWatch: (MovieCard) -> Unit,
) {
    fun tr(en: String, tr: String) = if (english) en else tr
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The poster nearest the middle is the chosen one.
    val current by remember(cards) {
        derivedStateOf {
            val info = state.layoutInfo
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - middle) }?.index ?: 0
        }
    }
    LaunchedEffect(cards, current) {
        if (cards.size < 2) return@LaunchedEffect
        delay(HERO_ROTATE_MS)
        if (!state.isScrollInProgress) state.animateScrollToItem((current + 1) % cards.size)
    }
    val card = cards[current.coerceIn(0, cards.lastIndex)]

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // As wide as fits: about half the width, at most 250 (300 on tablets), and never taller than ~55% of the page.
            val itemWidth = minOf(maxWidth * 0.58f, if (tablet) 300.dp else 250.dp, viewportHeight * 0.55f * (2f / 3f))
            val side = (maxWidth - itemWidth) / 2
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = side),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                flingBehavior = rememberSnapFlingBehavior(state),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(cards, key = { _, c -> "hero-${c.pageUrl}" }) { i, c ->
                    val selected = i == current
                    val scale by animateFloatAsState(
                        if (selected) 1f else 0.88f,
                        tween(Motion.MEDIUM, easing = Motion.Emphasized),
                        label = "heroScale",
                    )
                    val shape = QMusicShapes.large
                    Box(
                        modifier = Modifier
                            .width(itemWidth)
                            .aspectRatio(2f / 3f)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = if (selected) 1f else 0.55f
                            }
                            .clip(shape)
                            .background(VideoColors.Surface)
                            .border(0.5.dp, BorderSubtle, shape)
                            .clickable {
                                if (selected) onOpenDetails(c) else scope.launch { state.animateScrollToItem(i) }
                            },
                    ) {
                        FadeInPoster(url = c.poster, contentDescription = c.title, iconSize = 44.dp)
                    }
                }
            }
        }
        Crossfade(targetState = card, animationSpec = tween(Motion.MEDIUM), label = "heroInfo") { c ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    c.title,
                    color = VideoColors.Text,
                    fontSize = if (tablet) 22.sp else 20.sp,
                    lineHeight = if (tablet) 27.sp else 25.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(c.rating?.let { "★ $it" }, c.year, c.qualityBadge).joinToString("  ·  ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(meta, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val inList = isInList(card)
            HeroAction(label = tr("My list", "Listem"), onClick = { onToggleList(card) }) {
                Icon(if (inList) QMusicIcons.Check else QMusicIcons.Add, null, tint = VideoColors.Text, modifier = Modifier.size(22.dp))
            }
            Row(
                modifier = Modifier
                    .pressScale(0.95f)
                    .clip(QMusicShapes.small)
                    .background(Ink)
                    .clickable { onWatch(card) }
                    .padding(horizontal = 28.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(QMusicIcons.Play, null, tint = InkInverse, modifier = Modifier.size(17.dp))
                Text(watchLabel, color = InkInverse, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            HeroAction(label = tr("Details", "Detay"), onClick = { onOpenDetails(card) }) {
                Box(Modifier.size(22.dp).border(1.6.dp, VideoColors.Text, CircleShape), contentAlignment = Alignment.Center) {
                    Text("i", color = VideoColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (cards.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                cards.indices.forEach { i ->
                    val selected = i == current
                    val w by animateDpAsState(if (selected) 18.dp else 6.dp, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "heroDot")
                    Box(
                        Modifier
                            .size(width = w, height = 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Ink else BorderHover)
                            .clickable { scope.launch { state.animateScrollToItem(i) } },
                    )
                }
            }
        }
    }
}

/** A small action under the showcase: an icon over its name. */
@Composable
private fun HeroAction(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .pressScale(0.92f)
            .clip(QMusicShapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon()
        Text(label, color = VideoColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** A round icon button on the film pages' top line. */
@Composable
private fun TopIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).pressScale(0.9f).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = VideoColors.Text, modifier = Modifier.size(22.dp))
    }
}

/** One home row: a caption (with "See all" for a genre) and posters that slide sideways. */
@Composable
private fun CinemaRow(
    caption: String,
    cards: List<MovieCard>,
    tablet: Boolean,
    watchTogetherLabel: String,
    seeAllLabel: String?,
    onSeeAll: (() -> Unit)?,
    onOpen: (MovieCard) -> Unit,
    onWatchTogether: (MovieCard) -> Unit,
    progressOf: (MovieCard) -> Float = { 0f },
    inListOf: (MovieCard) -> Boolean = { false },
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionCaption(caption, modifier = Modifier.weight(1f))
            if (seeAllLabel != null && onSeeAll != null) {
                Text(
                    seeAllLabel,
                    color = VideoColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onSeeAll)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cards, key = { it.pageUrl }) { card ->
                MoviePosterTile(
                    card = card,
                    watchTogetherLabel = watchTogetherLabel,
                    onOpenDetails = { onOpen(card) },
                    onWatchTogether = { onWatchTogether(card) },
                    progress = progressOf(card),
                    inList = inListOf(card),
                    modifier = Modifier.width(if (tablet) 140.dp else 116.dp),
                )
            }
        }
    }
}

/** One line under the search box: a film (opens its page) or a past search (searches it again). */
private data class MovieSuggestion(val text: String, val card: MovieCard?, val rank: Int)

/** Lowercase without Turkish marks, so "yuzuk" finds "Yüzük" and "ISIK" finds "ışık". */
internal fun foldTurkish(s: String): String = buildString(s.length) {
    s.forEach { c ->
        append(
            when (c) {
                'ı', 'İ', 'I', 'î', 'Î' -> 'i'
                'ş', 'Ş' -> 's'
                'ğ', 'Ğ' -> 'g'
                'ü', 'Ü', 'û', 'Û' -> 'u'
                'ö', 'Ö' -> 'o'
                'ç', 'Ç' -> 'c'
                'â', 'Â' -> 'a'
                else -> c.lowercaseChar()
            },
        )
    }
}

internal fun matchesQuery(title: String, query: String): Boolean = foldTurkish(title).contains(foldTurkish(query.trim()))

/** 0: the title starts with it, 1: a word starts with it, 2: it is somewhere inside; null: no match. */
private fun matchRank(text: String, query: String): Int? {
    val t = foldTurkish(text)
    val q = foldTurkish(query.trim())
    return when {
        q.isEmpty() -> null
        t.startsWith(q) -> 0
        t.split(' ', '-', ':', '.', ',').any { it.startsWith(q) } -> 1
        q in t -> 2
        else -> null
    }
}

/** Dizi (true) or film (false) from the page's address, when the site says so in it; null when it cannot be told. */
private fun movieKind(card: MovieCard): Boolean? {
    val u = card.pageUrl.lowercase()
    return when {
        "/dizi" in u || "-dizi" in u || "/series" in u || "/tv/" in u || "sezon" in u || "bolum" in u -> true
        "/film" in u || "/movie" in u -> false
        else -> null
    }
}

/** A past search: tap searches it again, the × (or a long press) forgets it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentSearchChip(text: String, removeLabel: String, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(VideoColors.Chip)
            .border(0.5.dp, BorderSubtle, RoundedCornerShape(50))
            .combinedClickable(onClick = onClick, onLongClick = onRemove)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = VideoColors.Text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(vertical = 8.dp))
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(QMusicIcons.Close, removeLabel, tint = VideoColors.TextSecondary, modifier = Modifier.size(11.dp))
        }
    }
}

/** How the "all titles" grid is ordered. */
internal enum class MovieSort(val tr: String, val en: String) {
    SITE("Önerilen", "Suggested"),
    RATING("Puan", "Rating"),
    YEAR("En yeni", "Newest"),
    TITLE("A-Z", "A-Z"),
}

private fun sortedMovies(cards: List<MovieCard>, sort: MovieSort): List<MovieCard> = when (sort) {
    MovieSort.SITE -> cards
    MovieSort.RATING -> cards.sortedByDescending { it.rating?.replace(',', '.')?.toFloatOrNull() ?: -1f }
    MovieSort.YEAR -> cards.sortedByDescending { it.year?.toIntOrNull() ?: 0 }
    MovieSort.TITLE -> cards.sortedBy { it.title.lowercase() }
}

/** What a film's page says under its title, and a shared film's line: never the site it comes from. */
internal const val FILM_LABEL = "Film"

private const val SIMILAR_COUNT = 12
private const val PRE_RESOLVE_COUNT = 2
private const val PRE_RESOLVE_DELAY_MS = 1_500L
private const val SUGGESTION_COUNT = 6
private const val HERO_COUNT = 8
private const val HERO_ROTATE_MS = 7_000L
private const val ROW_SIZE = 20
private const val GENRE_ROWS = 4

/** A horizontal row of films kept on the device (recently watched, my list), each with how far it was watched. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedMovieRow(
    caption: String,
    action: String?,
    onAction: () -> Unit,
    movies: List<SavedMovie>,
    onOpen: (SavedMovie) -> Unit,
    removeLabel: String,
    onRemove: (SavedMovie) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionCaption(caption, modifier = Modifier.weight(1f))
            if (action != null) {
                Text(
                    action,
                    color = VideoColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onAction)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(movies, key = { it.pageUrl }) { movie ->
                val shape = QMusicShapes.medium
                var menu by remember { mutableStateOf(false) }
                // Tap opens it; a long press offers to take it out of the row.
                Box {
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    modifier = Modifier.background(VideoColors.Surface),
                ) {
                    DropdownMenuItem(
                        text = { Text(removeLabel, color = VideoColors.Text, fontSize = 14.sp) },
                        leadingIcon = { Icon(QMusicIcons.Close, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            menu = false
                            onRemove(movie)
                        },
                    )
                }
                Column(
                    modifier = Modifier
                        .width(108.dp)
                        .pressScale(0.97f)
                        .clip(shape)
                        .combinedClickable(onClick = { onOpen(movie) }, onLongClick = { menu = true }),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(shape)
                            .background(VideoColors.Surface)
                            .border(0.5.dp, BorderSubtle, shape),
                    ) {
                        FadeInPoster(url = movie.poster, contentDescription = movie.title, iconSize = 26.dp)
                        if (movie.progress > 0f) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .background(Color.White.copy(alpha = 0.22f)),
                            ) {
                                Box(Modifier.fillMaxWidth(movie.progress).height(3.dp).background(Color.White))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        movie.title,
                        color = VideoColors.Text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun MoviePosterTile(
    card: MovieCard,
    watchTogetherLabel: String,
    onOpenDetails: () -> Unit,
    onWatchTogether: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    inList: Boolean = false,
) {
    val shape = QMusicShapes.medium
    Column(
        modifier = modifier
            .pressScale(0.97f)
            .clip(shape)
            .clickable(onClick = onOpenDetails),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(VideoColors.Surface)
                .border(0.5.dp, BorderSubtle, shape),
        ) {
            FadeInPoster(url = card.poster, contentDescription = card.title)
            // Top-left IMDb rating
            card.rating?.let { rating ->
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(QMusicIcons.Star, null, tint = Color.White, modifier = Modifier.size(10.dp))
                    Text(rating, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            // Top-right quick 1-tap Watch Together
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                    .clickable(onClick = onWatchTogether),
                contentAlignment = Alignment.Center,
            ) {
                Icon(QMusicIcons.Group, contentDescription = watchTogetherLabel, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            // Bottom-left quality badge
            card.qualityBadge?.let { q ->
                Text(
                    q,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            // Bottom-right: in my list
            if (inList) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(7.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(QMusicIcons.Check, null, tint = Color.Black, modifier = Modifier.size(12.dp))
                }
            }
            // Along the bottom: how far it was watched
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.22f)),
                ) {
                    Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Color.White))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = card.title,
            color = VideoColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        val meta = card.year.orEmpty()
        Text(
            text = meta.ifEmpty { " " },
            color = VideoColors.TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CinemaSkeleton(cols: Int, tablet: Boolean, withHero: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (withHero) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (tablet) 300.dp else 236.dp)
                    .clip(QMusicShapes.large)
                    .border(0.5.dp, BorderSubtle, QMusicShapes.large)
                    .cinemaShimmer(),
            )
        }
        repeat(2) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(cols) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(QMusicShapes.medium)
                                .border(0.5.dp, BorderSubtle, QMusicShapes.medium)
                                .cinemaShimmer(),
                        )
                        Box(Modifier.fillMaxWidth(0.85f).height(11.dp).clip(RoundedCornerShape(4.dp)).cinemaShimmer())
                        Box(Modifier.fillMaxWidth(0.5f).height(9.dp).clip(RoundedCornerShape(4.dp)).cinemaShimmer())
                    }
                }
            }
        }
    }
}
