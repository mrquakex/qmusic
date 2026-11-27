package com.qmusic.ui.videos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
 * - Cinematic featured hero banner with 1-tap Watch & Watch Together
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
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(activeSource?.id, activeSource?.baseUrl, submittedQuery, selectedGenreUrl, reloadTick) {
        val src = activeSource
        if (src == null) {
            cards = emptyList()
            genres = emptyList()
            nextPageUrl = null
            loading = false
            loadError = null
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        fetchMovieCatalog(src, submittedQuery, pageUrlOverride = if (submittedQuery.isBlank()) selectedGenreUrl else null).fold(
            onSuccess = { page ->
                cards = page.items
                nextPageUrl = page.nextPageUrl
                if (page.genres.isNotEmpty() && selectedGenreUrl == null && submittedQuery.isBlank()) {
                    genres = page.genres
                }
                loading = false
            },
            onFailure = { e ->
                cards = emptyList()
                nextPageUrl = null
                loading = false
                loadError = e.message ?: s.loadError
            },
        )
    }

    fun loadNextPage() {
        val src = activeSource ?: return
        val next = nextPageUrl ?: return
        if (loading || loadingMore) return
        loadingMore = true
        scope.launch {
            fetchMovieCatalog(src, "", pageUrlOverride = next).fold(
                onSuccess = { page ->
                    val existing = cards.mapTo(HashSet()) { it.pageUrl }
                    val fresh = page.items.filter { it.pageUrl !in existing }
                    cards = cards + fresh
                    nextPageUrl = if (fresh.isEmpty()) null else page.nextPageUrl
                    loadingMore = false
                },
                onFailure = { loadingMore = false },
            )
        }
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
    val featured = remember(cards, submittedQuery, selectedGenreUrl, localQuery) {
        if (localQuery == null && submittedQuery.isBlank() && selectedGenreUrl == null) cards.firstOrNull { it.poster != null } ?: cards.firstOrNull() else null
    }
    val gridCards = remember(cards, featured, localQuery) {
        when {
            localQuery != null -> cards.filter { it.title.contains(localQuery, ignoreCase = true) }
            featured != null && cards.size > 1 -> cards.filterNot { it.pageUrl == featured.pageUrl }
            else -> cards
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
                        )

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
                        if (featured != null) {
                            item(key = "featured-hero-${featured.pageUrl}") {
                                FeaturedCinemaHero(
                                    card = featured,
                                    english = english,
                                    watchLabel = x.moviePlaySolo,
                                    togetherLabel = x.watchTogether,
                                    tablet = tablet,
                                    onOpenDetails = { selectedCard = featured },
                                    onWatch = {
                                        controller.openExternalUrl(
                                            rawUrl = featured.pageUrl,
                                            title = featured.title,
                                            sourceName = featured.sourceName,
                                            poster = featured.poster,
                                            headers = featured.headers,
                                        )
                                    },
                                    onWatchTogether = {
                                        sharedItemForUrl(featured.pageUrl, featured.title, featured.sourceName, featured.poster)?.let {
                                            ChatMusic.offerTogether(it)
                                        }
                                    },
                                )
                            }
                            if (gridCards.isNotEmpty()) {
                                item(key = "grid-caption") {
                                    SectionCaption(
                                        selectedGenreLabel?.uppercase() ?: tr("LATEST", "SON EKLENENLER"),
                                        modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                                    )
                                }
                            }
                        }

                        // Responsive 2..5-column cinema poster grid
                        val rows = gridCards.chunked(cols)
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
                            if (controller.openExternalUrl(directUrl)) linkDialogOpen = false
                            else directError = x.movieInvalidUrl
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
        MovieDetailSheet(
            card = card,
            genreHint = selectedGenreLabel,
            english = english,
            watchLabel = x.moviePlaySolo,
            togetherLabel = x.watchTogether,
            shareLabel = s.share,
            onDismiss = { selectedCard = null },
            onWatch = { resolved ->
                selectedCard = null
                controller.openExternalUrl(
                    rawUrl = card.pageUrl,
                    title = resolved?.title ?: card.title,
                    sourceName = card.sourceName,
                    poster = card.poster ?: resolved?.poster,
                    headers = card.headers,
                )
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
    genreHint: String?,
    english: Boolean,
    watchLabel: String,
    togetherLabel: String,
    shareLabel: String,
    onDismiss: () -> Unit,
    onWatch: (ResolvedMovieStream?) -> Unit,
    onWatchTogether: (ResolvedMovieStream?) -> Unit,
    onShare: () -> Unit,
) {
    fun tr(en: String, tr: String) = if (english) en else tr
    var resolved by remember(card.pageUrl) { mutableStateOf<ResolvedMovieStream?>(null) }
    var resolving by remember(card.pageUrl) { mutableStateOf(true) }
    var resolveErr by remember(card.pageUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(card.pageUrl) {
        resolving = true
        resolveErr = null
        resolveExternalMovie(card.pageUrl, card.headers).fold(
            onSuccess = {
                resolved = it
                resolving = false
            },
            onFailure = {
                resolveErr = it.message
                resolving = false
            },
        )
    }
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
                .verticalScroll(rememberScrollState())
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
                    resolved = r,
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
                        label = watchLabel,
                        icon = QMusicIcons.Play,
                        primary = true,
                        height = 52.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) { onWatch(r) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        CinemaButton(
                            label = togetherLabel,
                            icon = QMusicIcons.Group,
                            primary = false,
                            modifier = Modifier.weight(1f),
                        ) { onWatchTogether(r) }
                        CinemaButton(
                            label = shareLabel,
                            icon = QMusicIcons.Share,
                            primary = false,
                            modifier = Modifier.weight(1f),
                        ) { onShare() }
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
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
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

/** Cinematic featured hero banner above the poster grid (pure Nuvelo monochrome). */
@Composable
private fun FeaturedCinemaHero(
    card: MovieCard,
    english: Boolean,
    watchLabel: String,
    togetherLabel: String,
    tablet: Boolean,
    onOpenDetails: () -> Unit,
    onWatch: () -> Unit,
    onWatchTogether: () -> Unit,
) {
    val shape = QMusicShapes.large
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (tablet) 300.dp else 236.dp)
            .clip(shape)
            .background(VideoColors.Surface)
            .border(0.5.dp, BorderHover, shape)
            .clickable(onClick = onOpenDetails),
    ) {
        FadeInPoster(url = card.poster, contentDescription = card.title, iconSize = 44.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.20f),
                        0.40f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Black.copy(alpha = 0.94f),
                    ),
                ),
        )
        Text(
            text = if (english) "FEATURED" else "ÖNE ÇIKAN",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.50f))
                .border(0.5.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            val meta = listOfNotNull(
                card.rating?.let { "IMDb $it" },
                card.year,
                card.qualityBadge,
                card.sourceName.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Text(meta, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = card.title,
                color = Color.White,
                fontSize = if (tablet) 26.sp else 22.sp,
                lineHeight = if (tablet) 31.sp else 27.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .pressScale(0.95f)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable(onClick = onWatch)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(QMusicIcons.Play, null, tint = Color.Black, modifier = Modifier.size(15.dp))
                    Text(watchLabel, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    modifier = Modifier
                        .pressScale(0.95f)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(50))
                        .clickable(onClick = onWatchTogether)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(QMusicIcons.Group, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Text(togetherLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
