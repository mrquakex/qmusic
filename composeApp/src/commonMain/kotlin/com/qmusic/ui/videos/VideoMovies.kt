package com.qmusic.ui.videos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.chat.ChatMusic
import com.qmusic.ui.chat.MovieSource
import com.qmusic.ui.chat.ShareKind
import com.qmusic.ui.chat.SharedItem
import com.qmusic.ui.theme.BorderHover
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
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
 * - Admin-managed live sources + genre pills + source search + pagination
 * - Instant pre-resolving Movie Detail sheet (synopsis, year, rating, available streams, solo & Watch Together)
 * - Direct URL modal for any .m3u8 / .mpd / .mp4 / YouTube / film page link
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoviesTab(
    controller: VideosController,
    sources: List<MovieSource>,
    s: VideoStrings,
    tablet: Boolean,
) {
    val x = s.extras()
    val english = s == VideoStrings.English
    val scope = rememberCoroutineScope()
    val activeSources = remember(sources) { sources.filter { it.enabled } }
    var selectedSourceId by remember(activeSources) { mutableStateOf(activeSources.firstOrNull()?.id) }
    val activeSource = activeSources.firstOrNull { it.id == selectedSourceId } ?: activeSources.firstOrNull()

    var linkDialogOpen by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<MovieCard?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var selectedGenreUrl by remember { mutableStateOf<String?>(null) }
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

    val sidePad = if (tablet) 18.dp else 12.dp
    val featured = remember(cards, submittedQuery, selectedGenreUrl) {
        if (submittedQuery.isBlank() && selectedGenreUrl == null) cards.firstOrNull { it.poster != null } ?: cards.firstOrNull() else null
    }
    val gridCards = remember(cards, featured) {
        if (featured != null && cards.size > 1) cards.filterNot { it.pageUrl == featured.pageUrl } else cards
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(VideoColors.Background)) {
        val cols = movieGridColumnCount(maxWidth.value)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = sidePad, end = sidePad, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Top bar: Source switcher pills + Direct Link trigger pill
            item(key = "cinema-top-bar") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (activeSources.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(activeSources, key = { it.id }) { src ->
                                LabelChip(src.name, selected = src.id == activeSource?.id) {
                                    selectedSourceId = src.id
                                    selectedGenreUrl = null
                                    submittedQuery = ""
                                    searchQuery = ""
                                }
                            }
                        }
                    } else {
                        Text(
                            x.tabMovies,
                            color = VideoColors.Text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(VideoColors.Surface)
                            .border(0.5.dp, BorderHover, RoundedCornerShape(50))
                            .clickable { linkDialogOpen = true }
                            .padding(horizontal = 13.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(VideoIcons.Film, null, tint = VideoColors.Text, modifier = Modifier.size(15.dp))
                        Text(x.movieLinkTitle, color = VideoColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }

            if (activeSources.isEmpty()) {
                item(key = "no-sources") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(VideoColors.Surface)
                            .border(0.5.dp, BorderSubtle, RoundedCornerShape(18.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(VideoIcons.Film, null, tint = VideoColors.Text, modifier = Modifier.size(28.dp))
                        Text(x.tabMovies, color = VideoColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(x.movieNoSources, color = VideoColors.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Ink)
                                .clickable { linkDialogOpen = true }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(QMusicIcons.Play, null, tint = InkInverse, modifier = Modifier.size(15.dp))
                            Text(x.movieLinkTitle, color = InkInverse, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                // Cinema search bar + dynamic genre pills
                item(key = "cinema-search") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(50))
                                .background(VideoColors.Surface)
                                .border(0.5.dp, BorderSubtle, RoundedCornerShape(50))
                                .padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(QMusicIcons.Search, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                                if (searchQuery.isEmpty()) {
                                    Text(x.movieSearchHint, color = VideoColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = VideoColors.Text, fontSize = 14.sp),
                                    cursorBrush = SolidColor(Ink),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {
                                        selectedGenreUrl = null
                                        submittedQuery = searchQuery.trim()
                                    }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        searchQuery = ""
                                        submittedQuery = ""
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(QMusicIcons.Close, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                            IconButton(
                                onClick = {
                                    if (searchQuery.trim() != submittedQuery) {
                                        selectedGenreUrl = null
                                        submittedQuery = searchQuery.trim()
                                    } else {
                                        reloadTick++
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(QMusicIcons.Refresh, null, tint = VideoColors.Text, modifier = Modifier.size(17.dp))
                            }
                        }

                        if (genres.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item(key = "genre-all") {
                                    LabelChip(s.filterAll, selected = selectedGenreUrl == null && submittedQuery.isBlank()) {
                                        selectedGenreUrl = null
                                        submittedQuery = ""
                                        searchQuery = ""
                                    }
                                }
                                items(genres, key = { it.second }) { (label, url) ->
                                    LabelChip(label, selected = selectedGenreUrl == url) {
                                        searchQuery = ""
                                        submittedQuery = ""
                                        selectedGenreUrl = url
                                    }
                                }
                            }
                        }
                    }
                }

                when {
                    loading -> item(key = "movies-loading") {
                        CinemaSkeleton(cols = cols, tablet = tablet)
                    }

                    loadError != null -> item(key = "movies-error") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(loadError ?: s.loadError, color = VideoColors.TextSecondary, fontSize = 13.sp)
                            Text(
                                s.retry,
                                color = InkInverse,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Ink)
                                    .clickable { reloadTick++ }
                                    .padding(horizontal = 18.dp, vertical = 9.dp),
                            )
                        }
                    }

                    cards.isEmpty() -> item(key = "movies-empty") {
                        Text(
                            x.movieNoResults,
                            color = VideoColors.TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }

                    else -> {
                        // Cinematic Featured Hero Banner above the poster grid
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

                        if (nextPageUrl != null) {
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
                                                .clip(RoundedCornerShape(50))
                                                .background(VideoColors.Surface)
                                                .border(0.5.dp, BorderHover, RoundedCornerShape(50))
                                                .clickable { loadNextPage() }
                                                .padding(horizontal = 22.dp, vertical = 10.dp),
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
            title = { Text(x.movieLinkTitle, color = VideoColors.Text, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(VideoColors.Background)
                            .border(0.5.dp, BorderSubtle, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 11.dp),
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
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(Ink)
                                .clickable {
                                    if (controller.openExternalUrl(directUrl)) linkDialogOpen = false
                                    else directError = x.movieInvalidUrl
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(QMusicIcons.Play, null, tint = InkInverse, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(x.moviePlaySolo, color = InkInverse, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(VideoColors.Chip)
                                .border(0.5.dp, BorderHover, RoundedCornerShape(50))
                                .clickable {
                                    val item = sharedItemForUrl(directUrl)
                                    if (item == null) {
                                        directError = x.movieInvalidUrl
                                    } else {
                                        linkDialogOpen = false
                                        ChatMusic.offerTogether(item)
                                    }
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(QMusicIcons.Group, null, tint = VideoColors.Text, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(x.watchTogether, color = VideoColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { linkDialogOpen = false }) { Text(s.close, color = VideoColors.TextSecondary) } },
        )
    }

    // Movie Detail Bottom Sheet with background stream pre-resolution (zero-wait playback on tap!)
    selectedCard?.let { card ->
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
        ModalBottomSheet(
            onDismissRequest = { selectedCard = null },
            containerColor = VideoColors.Surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .width(92.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(VideoColors.Chip)
                            .border(0.5.dp, BorderSubtle, RoundedCornerShape(12.dp)),
                    ) {
                        val img = card.poster ?: resolved?.poster
                        if (img != null) {
                            AsyncImage(model = img, contentDescription = card.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            resolved?.title?.takeIf { it.isNotBlank() } ?: card.title,
                            color = VideoColors.Text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(
                            (card.rating ?: resolved?.rating)?.let { "★ $it" },
                            card.year ?: resolved?.year,
                            card.qualityBadge,
                            card.sourceName.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(meta, color = VideoColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        when {
                            resolving -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(color = Ink, strokeWidth = 1.5.dp, modifier = Modifier.size(13.dp))
                                Text(if (english) "Finding stream…" else "Akış hazırlanıyor…", color = VideoColors.TextSecondary, fontSize = 12.sp)
                            }
                            resolved != null -> {
                                val count = resolved!!.streams.size
                                val qualities = resolved!!.streams.map { it.label }.distinct().take(3).joinToString(" / ")
                                Text(
                                    if (english) "Ready · $qualities ($count)" else "Oynatmaya hazır · $qualities",
                                    color = VideoColors.Text,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            resolveErr != null -> Text(resolveErr!!, color = VideoColors.Red, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                }
                resolved?.synopsis?.let { plot ->
                    Text(plot, color = VideoColors.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(Ink)
                            .clickable {
                                selectedCard = null
                                controller.openExternalUrl(
                                    rawUrl = card.pageUrl,
                                    title = resolved?.title ?: card.title,
                                    sourceName = card.sourceName,
                                    poster = card.poster ?: resolved?.poster,
                                    headers = card.headers,
                                )
                            }
                            .padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(QMusicIcons.Play, null, tint = InkInverse, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(x.moviePlaySolo, color = InkInverse, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(VideoColors.Chip)
                            .border(0.5.dp, BorderHover, RoundedCornerShape(50))
                            .clickable {
                                selectedCard = null
                                sharedItemForUrl(card.pageUrl, resolved?.title ?: card.title, card.sourceName, card.poster ?: resolved?.poster)?.let {
                                    ChatMusic.offerTogether(it)
                                }
                            }
                            .padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(QMusicIcons.Group, null, tint = VideoColors.Text, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(x.watchTogether, color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

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
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (tablet) 260.dp else 210.dp)
            .clip(shape)
            .background(VideoColors.Surface)
            .border(0.5.dp, BorderHover, shape)
            .clickable(onClick = onOpenDetails),
    ) {
        if (card.poster != null) {
            AsyncImage(
                model = card.poster,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.25f),
                        0.45f to Color.Black.copy(alpha = 0.50f),
                        1f to Color.Black.copy(alpha = 0.92f),
                    ),
                ),
        )
        // Top-left metadata pill
        val topMeta = listOfNotNull(
            if (english) "FEATURED" else "ÖNE ÇIKAN",
            card.rating?.let { "★ $it" },
            card.year,
            card.qualityBadge,
        ).joinToString(" · ")
        Text(
            text = topMeta,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.58f))
                .border(0.5.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = card.title,
                color = Color.White,
                fontSize = if (tablet) 22.sp else 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.sourceName.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(card.sourceName, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable(onClick = onWatch)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(QMusicIcons.Play, null, tint = Color.Black, modifier = Modifier.size(15.dp))
                    Text(watchLabel, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.16f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(50))
                        .clickable(onClick = onWatchTogether)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
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
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(VideoColors.Surface)
            .border(0.5.dp, BorderSubtle, shape)
            .clickable(onClick = onOpenDetails),
    ) {
        if (card.poster != null) {
            AsyncImage(
                model = card.poster,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                VideoIcons.Film,
                contentDescription = null,
                tint = VideoColors.TextSecondary.copy(alpha = 0.40f),
                modifier = Modifier.align(Alignment.Center).size(36.dp),
            )
        }
        // Subtle cinema gradient over bottom for crisp poster title readability
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(92.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.90f))),
        )
        // Top-left rating / year badge
        val badge = listOfNotNull(card.rating?.let { "★ $it" }, card.year ?: card.qualityBadge).joinToString(" · ")
        if (badge.isNotEmpty()) {
            Text(
                text = badge,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.66f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        // Top-right quick 1-tap Watch Together icon
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(7.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.66f))
                .border(0.5.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .clickable(onClick = onWatchTogether),
            contentAlignment = Alignment.Center,
        ) {
            Icon(QMusicIcons.Group, contentDescription = watchTogetherLabel, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        // Bottom poster title
        Text(
            text = card.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun CinemaSkeleton(cols: Int, tablet: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (tablet) 260.dp else 210.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(VideoColors.Surface),
        )
        repeat(2) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(cols) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(VideoColors.Surface),
                    )
                }
            }
        }
    }
}
