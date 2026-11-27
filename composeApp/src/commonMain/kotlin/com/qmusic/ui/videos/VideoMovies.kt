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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
) {
    val x = s.extras()
    val english = s == VideoStrings.English
    val scope = rememberCoroutineScope()
    val activeSources = remember(sources) {
        val enabled = sources.filter { it.enabled }
        if (enabled.isEmpty()) com.qmusic.ui.chat.DEFAULT_MOVIE_SOURCES else enabled
    }
    var selectedSourceId by remember(activeSources) { mutableStateOf(activeSources.firstOrNull()?.id) }
    val activeSource = activeSources.firstOrNull { it.id == selectedSourceId } ?: activeSources.firstOrNull()

    var linkDialogOpen by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<MovieCard?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var selectedGenreUrl by remember { mutableStateOf<String?>(null) }
    val externalMovieQuery by controller.movieQuery.collectAsState()
    LaunchedEffect(externalMovieQuery) {
        if (externalMovieQuery.isNotBlank() && externalMovieQuery != submittedQuery) {
            searchQuery = externalMovieQuery
            submittedQuery = externalMovieQuery
            selectedGenreUrl = null
        }
    }
    var cards by remember { mutableStateOf<List<MovieCard>>(emptyList()) }
    var genres by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var nextPageUrl by remember { mutableStateOf<String?>(null) }
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
    var searchAll by remember { mutableStateOf(false) }   // search every source at once
    var searchFocused by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(MovieSort.SITE) }
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

    /** Plays [card]: it goes first in the recently watched row, and starts where it was left. */
    fun watch(card: MovieCard, resolved: ResolvedMovieStream? = null) {
        val title = resolved?.title?.takeIf { it.isNotBlank() } ?: card.title
        val poster = card.poster ?: resolved?.poster
        library.recordWatched(card.copy(title = title, poster = poster))
        controller.openExternalUrl(
            rawUrl = card.pageUrl,
            title = title,
            sourceName = card.sourceName,
            poster = poster,
            headers = card.headers,
        )
    }

    fun sharedItemForUrl(url: String, title: String? = null, sourceName: String = "", poster: String? = null): SharedItem? {
        val clean = url.trim()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return null
        val ytId = extractYouTubeIdFromUrl(clean)
        val id = ytId ?: externalVideoId(clean)
        val label = title?.takeIf { it.isNotBlank() }
            ?: clean.substringBefore('?').substringAfterLast('/').ifBlank { MovieHtmlParser.originOf(clean).removePrefix("https://").removePrefix("http://") }
        val sub = sourceName.ifBlank { if (ytId != null) "YouTube" else MovieHtmlParser.originOf(clean).removePrefix("https://").removePrefix("http://") }
        return SharedItem(ShareKind.VIDEO, id, label, sub, poster)
    }


    fun tr(en: String, tr: String) = if (english) en else tr

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

    val sidePad = if (tablet) 18.dp else 12.dp
    // Instant, local filter over what is already loaded while the user is typing (no network until they submit).
    val localQuery = searchQuery.trim().takeIf { it.isNotEmpty() && it != submittedQuery }
    val selectedGenreLabel = remember(genres, selectedGenreUrl) { genres.firstOrNull { it.second == selectedGenreUrl }?.first }
    val gridCards = remember(cards, localQuery) {
        if (localQuery != null) cards.filter { it.title.contains(localQuery, ignoreCase = true) } else cards
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

    BoxWithConstraints(Modifier.fillMaxSize().background(VideoColors.Background)) {
        val cols = movieGridColumnCount(maxWidth.value)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = sidePad, end = sidePad, top = 6.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Heading: section title, current source summary and the direct-link button.
            item(key = "cinema-heading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            x.tabMovies,
                            color = VideoColors.Text,
                            fontSize = if (tablet) 26.sp else 23.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                        )
                        val summary = when {
                            activeSource == null -> tr("Paste any link to start", "Bir bağlantıyla hemen başla")
                            loading -> activeSource.name
                            refreshing -> "${activeSource.name} · ${tr("updating…", "güncelleniyor…")}"
                            else -> "${activeSource.name} · ${countLabel(cards.size, nextPageUrl != null)} ${tr("titles", "yapım")}"
                        }
                        Text(summary, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(
                        modifier = Modifier
                            .pressScale(0.95f)
                            .clip(RoundedCornerShape(50))
                            .background(VideoColors.Surface)
                            .border(0.5.dp, BorderHover, RoundedCornerShape(50))
                            .clickable { linkDialogOpen = true }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(VideoIcons.Film, null, tint = VideoColors.Text, modifier = Modifier.size(15.dp))
                        Text(x.movieLinkTitle, color = VideoColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }

            if (activeSources.isEmpty()) {
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
                // Source picker as one segmented track.
                if (activeSources.size > 1) {
                    item(key = "cinema-sources") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionCaption(tr("SOURCE", "KAYNAK"))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(50))
                                    .background(VideoColors.Surface)
                                    .border(0.5.dp, BorderSubtle, RoundedCornerShape(50))
                                    .padding(4.dp),
                            ) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(activeSources, key = { it.id }) { src ->
                                        CinemaPill(
                                            text = src.name,
                                            selected = src.id == activeSource?.id,
                                            quiet = true,
                                        ) {
                                            if (src.id != activeSource?.id) {
                                                selectedSourceId = src.id
                                                selectedGenreUrl = null
                                                submittedQuery = ""
                                                searchQuery = ""
                                                controller.clearMovieSearch()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Search, genre pills and a one-line result status.
                item(key = "cinema-search") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CinemaSearchBar(
                            query = searchQuery,
                            hint = activeSource?.let { tr("Search ${it.name}…", "${it.name} içinde ara…") } ?: x.movieSearchHint,
                            pending = searchQuery.trim() != submittedQuery && searchQuery.isNotBlank(),
                            clearLabel = tr("Clear", "Temizle"),
                            searchLabel = tr("Search", "Ara"),
                            onQueryChange = { searchQuery = it },
                            onSubmit = { submitSearch() },
                            onClear = { clearSearch() },
                            onReload = { reloadTick++ },
                            onFocusChange = { searchFocused = it },
                        )

                        // Recent film searches while the empty field is focused: one tap searches again.
                        AnimatedVisibility(
                            visible = searchFocused && searchQuery.isEmpty() && recentSearches.isNotEmpty(),
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
                                        CinemaPill(q, selected = false) {
                                            searchQuery = q
                                            focusManager.clearFocus()
                                            submitSearch()
                                        }
                                    }
                                }
                            }
                        }

                        // Where to search: this source, or all of them at once.
                        if (activeSources.size > 1 && (searchQuery.isNotBlank() || submittedQuery.isNotBlank())) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CinemaPill(activeSource?.name ?: tr("This source", "Bu kaynak"), selected = !searchAll) { searchAll = false }
                                CinemaPill(tr("All sources", "Tüm kaynaklar"), selected = searchAll) { searchAll = true }
                            }
                        }

                        if (genres.isNotEmpty()) {
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
                val browsing = localQuery == null && submittedQuery.isBlank() && selectedGenreUrl == null
                if (browsing && !loading && loadError == null && heroPicks.isNotEmpty()) {
                    item(key = "cinema-hero") {
                        CinemaHeroCarousel(
                            cards = heroPicks,
                            english = english,
                            tablet = tablet,
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

                when {
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
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(MovieSort.entries.toList(), key = { it.name }) { mode ->
                                        CinemaPill(tr(mode.en, mode.tr), selected = sort == mode, quiet = sort != mode) { sort = mode }
                                    }
                                }
                            }
                        }

                        // Responsive 2..5-column cinema poster grid
                        val rows = sortedMovies(gridCards, sort).chunked(cols)
                        items(rows.size, key = { "mrow-$it-${rows[it].first().pageUrl}" }) { rIdx ->
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
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(cols - rows[rIdx].size) { Spacer(Modifier.weight(1f)) }
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
            shareLabel = s.share,
            resolve = { library.resolve(card.pageUrl, card.headers) },
            inList = myList.any { it.pageUrl == card.pageUrl },
            onToggleList = { library.toggleFavorite(card) },
            onRestart = { controller.forgetResume(externalVideoId(card.pageUrl)) },
            onDismiss = { selectedCard = null },
            onWatch = { resolved ->
                selectedCard = null
                watch(card, resolved)
            },
            onWatchTogether = { resolved ->
                selectedCard = null
                sharedItemForUrl(card.pageUrl, resolved?.title ?: card.title, card.sourceName, card.poster ?: resolved?.poster)?.let {
                    ChatMusic.offerTogether(it)
                }
            },
            onShare = { onShare(card.pageUrl) },
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
    onWatch: (ResolvedMovieStream?) -> Unit,
    onWatchTogether: (ResolvedMovieStream?) -> Unit,
    onShare: () -> Unit,
    resolve: suspend () -> Result<ResolvedMovieStream>,
    inList: Boolean,
    onToggleList: () -> Unit,
    onRestart: () -> Unit,
) {
    fun tr(en: String, tr: String) = if (english) en else tr
    var resolved by remember(card.pageUrl) { mutableStateOf<ResolvedMovieStream?>(null) }
    var resolving by remember(card.pageUrl) { mutableStateOf(true) }
    var resolveErr by remember(card.pageUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(card.pageUrl) {
        resolving = true
        resolveErr = null
        // The same lookup the player waits for when Watch is pressed: it is not done twice.
        resolve().fold(
            onSuccess = {
                resolved = it
                if (it.streams.isEmpty()) {
                    resolveErr = tr("No playable video was found on this page.", "Bu sayfada oynatılabilir video bulunamadı.")
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
    val resumeMs = positions[externalVideoId(card.pageUrl)] ?: 0L
    val r = resolved
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
                        if (card.sourceName.isNotBlank()) SectionCaption(card.sourceName.uppercase())
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

                StreamStatus(
                    resolving = resolving,
                    resolved = r?.takeIf { it.streams.isNotEmpty() },
                    error = resolveErr,
                    pageUrl = card.pageUrl,
                    english = english,
                )

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
                    ) { onWatch(r) }
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
                                    onRestart()
                                    onWatch(r)
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        CinemaButton(
                            label = togetherLabel,
                            icon = QMusicIcons.Group,
                            primary = false,
                            modifier = Modifier.weight(1f),
                        ) { onWatchTogether(r) }
                        CinemaButton(
                            label = if (inList) tr("In my list", "Listemde") else tr("My list", "Listeme ekle"),
                            icon = if (inList) QMusicIcons.Check else QMusicIcons.Add,
                            primary = false,
                            modifier = Modifier.weight(1f),
                        ) { onToggleList() }
                    }
                    CinemaButton(
                        label = shareLabel,
                        icon = QMusicIcons.Share,
                        primary = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) { onShare() }
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
                modifier = Modifier.fillMaxWidth().onFocusChanged {
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
 * Home showcase, Netflix style: a tall poster that changes by itself every few seconds (crossfade), the title in the
 * middle, and My list / Watch / Details under it. Dots show which one is up; tapping a dot jumps to it.
 */
@Composable
private fun CinemaHeroCarousel(
    cards: List<MovieCard>,
    english: Boolean,
    tablet: Boolean,
    watchLabel: String,
    isInList: (MovieCard) -> Boolean,
    onToggleList: (MovieCard) -> Unit,
    onOpenDetails: (MovieCard) -> Unit,
    onWatch: (MovieCard) -> Unit,
) {
    fun tr(en: String, tr: String) = if (english) en else tr
    var index by remember(cards) { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(cards, tick) {
        if (cards.size < 2) return@LaunchedEffect
        while (true) {
            delay(HERO_ROTATE_MS)
            index = (index + 1) % cards.size
        }
    }
    val card = cards[index.coerceIn(0, cards.lastIndex)]
    val shape = QMusicShapes.extraLarge

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (tablet) 440.dp else 470.dp)
                .clip(shape)
                .background(VideoColors.Surface)
                .border(0.5.dp, BorderSubtle, shape)
                .clickable { onOpenDetails(card) },
        ) {
            Crossfade(targetState = card, animationSpec = tween(Motion.LONG * 2), label = "heroPoster") { c ->
                FadeInPoster(url = c.poster, contentDescription = c.title, iconSize = 48.dp)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.15f),
                            0.45f to Color.Black.copy(alpha = 0.10f),
                            0.72f to Color.Black.copy(alpha = 0.65f),
                            1f to Color.Black.copy(alpha = 0.95f),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tr("FEATURED", "ÖNE ÇIKAN"),
                    color = Color.White.copy(alpha = 0.80f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(8.dp))
                Crossfade(targetState = card, animationSpec = tween(Motion.LONG), label = "heroTitle") { c ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            c.title,
                            color = Color.White,
                            fontSize = if (tablet) 30.sp else 26.sp,
                            lineHeight = if (tablet) 35.sp else 31.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.4).sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(
                            c.rating?.let { "★ $it" },
                            c.year,
                            c.qualityBadge,
                            c.sourceName.takeIf { it.isNotBlank() },
                        ).joinToString("  •  ")
                        if (meta.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(meta, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    val inList = isInList(card)
                    HeroSideButton(label = tr("My list", "Listem"), onClick = { onToggleList(card) }) {
                        Icon(if (inList) QMusicIcons.Check else QMusicIcons.Add, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Row(
                        modifier = Modifier
                            .pressScale(0.95f)
                            .clip(QMusicShapes.small)
                            .background(Color.White)
                            .clickable { onWatch(card) }
                            .padding(horizontal = 26.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(QMusicIcons.Play, null, tint = Color.Black, modifier = Modifier.size(17.dp))
                        Text(watchLabel, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    HeroSideButton(label = tr("Details", "Detay"), onClick = { onOpenDetails(card) }) {
                        Box(
                            Modifier.size(22.dp).border(1.6.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("i", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if (cards.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                cards.indices.forEach { i ->
                    val selected = i == index
                    val w by animateDpAsState(if (selected) 18.dp else 6.dp, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "heroDot")
                    Box(
                        Modifier
                            .size(width = w, height = 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Ink else BorderHover)
                            .clickable {
                                index = i
                                tick++ // the timer starts again, so the chosen one stays its full time
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroSideButton(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
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
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
                    modifier = Modifier.width(if (tablet) 140.dp else 116.dp),
                )
            }
        }
    }
}

/** How the "all titles" grid is ordered. */
private enum class MovieSort(val tr: String, val en: String) {
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

private const val SIMILAR_COUNT = 12
private const val HERO_COUNT = 5
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
        val meta = listOfNotNull(card.year, card.sourceName.takeIf { it.isNotBlank() }).joinToString(" · ")
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
