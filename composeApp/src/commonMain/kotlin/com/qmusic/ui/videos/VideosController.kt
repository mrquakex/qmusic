package com.qmusic.ui.videos

import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.video.VideoChannelPage
import com.qmusic.engine.video.VideoChip
import com.qmusic.engine.video.VideoComment
import com.qmusic.engine.video.VideoDetails
import com.qmusic.engine.video.VideoEntry
import com.qmusic.engine.video.VideoFeed
import com.qmusic.engine.video.VideoItem
import com.qmusic.engine.video.VideoQuality
import com.qmusic.engine.video.VideoSearchFilter
import com.qmusic.engine.video.VideoService
import com.qmusic.engine.video.VideoSignInRequired
import com.qmusic.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a list of the Videos section shows right now. */
data class FeedState(
    val entries: List<VideoEntry> = emptyList(),
    val chips: List<VideoChip> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** True once a load has finished (with videos, nothing, or an error). */
    val loaded: Boolean = false,
    val error: Boolean = false,
    val signInNeeded: Boolean = false,
)

/** One list: loads its first page, then more of it, and for the home page switches between its filter chips. */
class FeedLoader(
    private val scope: CoroutineScope,
    private val service: VideoService,
    private val first: suspend () -> Result<VideoFeed>,
) {
    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state.asStateFlow()
    private var job: Job? = null

    /** Loads the first page unless there is one already (or [force]). */
    fun load(force: Boolean = false) {
        val s = _state.value
        if (!force && (s.loading || (s.loaded && !s.error))) return
        run(chip = null)
    }

    fun reset() {
        job?.cancel()
        _state.value = FeedState()
    }

    fun selectChip(chip: VideoChip) {
        if (chip.selected) return
        _state.update { s -> s.copy(chips = s.chips.map { it.copy(selected = it.token == chip.token) }) }
        run(chip)
    }

    private fun run(chip: VideoChip?) {
        job?.cancel()
        _state.update { it.copy(loading = true, error = false, signInNeeded = false, entries = if (chip != null) emptyList() else it.entries) }
        val keptChips = _state.value.chips
        job = scope.launch {
            val result = if (chip != null) service.more(chip.token) else first()
            result.fold(
                onSuccess = { feed ->
                    _state.value = FeedState(
                        entries = feed.entries,
                        // A filter's own answer carries no chips: keep the row that was there.
                        chips = feed.chips.ifEmpty { keptChips },
                        continuation = feed.continuation,
                        loaded = true,
                        signInNeeded = feed.isEmpty && !service.isSignedIn,
                    )
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(loading = false, loaded = true, error = true, signInNeeded = e is VideoSignInRequired || e.isSignIn()) }
                },
            )
        }
    }

    fun loadMore() {
        val s = _state.value
        val token = s.continuation ?: return
        if (s.loading || s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            service.more(token).fold(
                onSuccess = { feed ->
                    _state.update { cur ->
                        val seen = cur.entries.flatMap { it.ids() }.toHashSet()
                        val fresh = feed.entries.filter { e -> e.ids().let { ids -> ids.isEmpty() || ids.any { it !in seen } } }
                        cur.copy(
                            entries = cur.entries + fresh,
                            // No new videos but a token: stop, or the list would ask forever.
                            continuation = if (fresh.isEmpty()) null else feed.continuation,
                            loadingMore = false,
                        )
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(loadingMore = false) }
                },
            )
        }
    }
}

internal fun VideoEntry.ids(): List<String> = when (this) {
    is VideoEntry.Video -> listOf(item.id)
    is VideoEntry.Channel -> listOf(item.id)
    is VideoEntry.Playlist -> listOf(item.id)
    is VideoEntry.Shorts -> items.map { it.id }
}

private fun Throwable.isSignIn() = message?.contains("Sign in", ignoreCase = true) == true

sealed interface VideoRoute {
    data class Search(val query: String) : VideoRoute
    data class Watch(val video: VideoItem) : VideoRoute
    data class Channel(val id: String, val name: String?) : VideoRoute
    data class Playlist(val id: String, val title: String) : VideoRoute
}

enum class VideoTab { HOME, SUBSCRIPTIONS, LIBRARY }

enum class LibrarySection { HISTORY, LIKED, WATCH_LATER, PLAYLISTS }

/** The page of one video: what it is, how it plays, the account's actions on it, its comments. */
data class WatchState(
    val video: VideoItem,
    val details: VideoDetails? = null,
    val detailsError: Boolean = false,
    val qualities: List<VideoQuality> = emptyList(),
    val quality: VideoQuality? = null,
    val streamLoading: Boolean = true,
    val streamError: Boolean = false,
    val related: List<VideoEntry> = emptyList(),
    val relatedContinuation: String? = null,
    val liked: Boolean = false,
    val saved: Boolean = false,
    val subscribed: Boolean = false,
    val comments: List<VideoComment> = emptyList(),
    val commentsTotal: String? = null,
    val commentsContinuation: String? = null,
    val commentsLoading: Boolean = false,
    val commentsLoaded: Boolean = false,
    val signInPrompt: Boolean = false,
)

data class ChannelState(
    val id: String,
    val page: VideoChannelPage? = null,
    val tabParams: String? = null,
    val feed: FeedState = FeedState(loading = true),
    val error: Boolean = false,
    val subscribed: Boolean = false,
)

/**
 * The state of the Videos section (YouTube itself, not YouTube Music): its pages, its account and its lists. The
 * section has its own stack of pages on top of its three tabs, and its own sign-in: the music account's session is
 * used when that account is YouTube, and signing in here keeps a session of the section alone.
 */
class VideosController(
    val service: VideoService,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    /** The YouTube Music session, when YouTube is the music account. */
    private val musicSession: () -> YouTubeAuth?,
) {
    private val cookies = settings.string(KEY_COOKIES, "")

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _tab = MutableStateFlow(VideoTab.HOME)
    val tab: StateFlow<VideoTab> = _tab.asStateFlow()

    private val _routes = MutableStateFlow<List<VideoRoute>>(emptyList())
    val routes: StateFlow<List<VideoRoute>> = _routes.asStateFlow()

    /** Without an account YouTube's own home is empty: a search of what people watch fills it instead. */
    var fallbackQuery: String = "popüler videolar"

    val home = FeedLoader(scope, service) {
        service.home().let { r ->
            if (!service.isSignedIn && r.getOrNull()?.isEmpty == true) service.search(fallbackQuery, VideoSearchFilter.VIDEOS) else r
        }
    }
    val subscriptions = FeedLoader(scope, service) { service.subscriptions() }
    val subscribedChannels = FeedLoader(scope, service) { service.subscribedChannels() }
    private val library = LibrarySection.entries.associateWith { section ->
        FeedLoader(scope, service) {
            when (section) {
                LibrarySection.HISTORY -> service.history()
                LibrarySection.LIKED -> service.likedVideos()
                LibrarySection.WATCH_LATER -> service.watchLater()
                LibrarySection.PLAYLISTS -> service.playlists()
            }
        }
    }

    fun library(section: LibrarySection): FeedLoader = library.getValue(section)

    init {
        refreshAccount()
    }

    // ------------------------------------------------------------------ the account

    /** Picks the session to use: the section's own when it has one, else the music account's. */
    fun refreshAccount() {
        val own = cookies.value.takeIf { it.isNotBlank() }?.let { YouTubeAuth(cookieString = it) }?.takeIf { it.isLoggedIn }
        val music = musicSession()?.takeIf { it.isLoggedIn }
        service.auth = own ?: music
        val now = service.isSignedIn
        if (now != _signedIn.value) {
            _signedIn.value = now
            resetLists()
        }
    }

    fun signedInWith(rawCookies: String) {
        settings.setString(KEY_COOKIES, rawCookies)
        service.auth = YouTubeAuth(cookieString = rawCookies)
        _signedIn.value = service.isSignedIn
        resetLists()
    }

    fun signOut() {
        settings.setString(KEY_COOKIES, "")
        // The music account's session stays: only what the section had of its own goes.
        service.auth = null
        refreshAccount()
        _signedIn.value = service.isSignedIn
        resetLists()
    }

    private fun resetLists() {
        home.reset()
        subscriptions.reset()
        subscribedChannels.reset()
        library.values.forEach { it.reset() }
    }

    // ------------------------------------------------------------------ pages

    fun selectTab(tab: VideoTab) {
        _routes.value = emptyList()
        _fullscreen.value = false
        _tab.value = tab
    }

    fun push(route: VideoRoute) {
        // One video at a time: a video opened from a video's page takes its place instead of piling up under it.
        _routes.update { stack -> (if (route is VideoRoute.Watch && stack.lastOrNull() is VideoRoute.Watch) stack.dropLast(1) else stack) + route }
        when (route) {
            is VideoRoute.Search -> search(route.query)
            // The same video that is playing small just opens big again, where it was.
            is VideoRoute.Watch -> if (_watch.value?.video?.id != route.video.id) startWatching(route.video)
            is VideoRoute.Channel -> loadChannel(route.id, null)
            is VideoRoute.Playlist -> openPlaylist(route.id)
        }
    }

    /** Back: the topmost page goes; true when there was one. */
    fun pop(): Boolean {
        val stack = _routes.value
        if (stack.isEmpty()) return false
        val gone = stack.last()
        _routes.value = stack.dropLast(1)
        // Leaving the page of a video does not stop it: it goes on in the small player above the bottom bar.
        if (gone is VideoRoute.Watch) _fullscreen.value = false
        return true
    }

    fun open(item: VideoItem) = push(VideoRoute.Watch(item))

    // ------------------------------------------------------------------ search

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _filter = MutableStateFlow<String?>(null)
    val filter: StateFlow<String?> = _filter.asStateFlow()

    private var results = newResults("")
    private val _results = MutableStateFlow<FeedLoader?>(null)
    val searchResults: StateFlow<FeedLoader?> = _results.asStateFlow()
    private var suggestJob: Job? = null

    /** Whether the search box is open on its own (before a query is sent). */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    fun openSearchBox() {
        _searching.value = true
    }

    fun closeSearchBox() {
        _searching.value = false
        _suggestions.value = emptyList()
    }

    fun typeQuery(text: String) {
        _query.value = text
        suggestJob?.cancel()
        if (text.isBlank()) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = scope.launch {
            delay(SUGGEST_DELAY_MS)
            service.suggestions(text.trim()).onSuccess { _suggestions.value = it.take(MAX_SUGGESTIONS) }
        }
    }

    /** Sends [text] as the search: a results page opens on top of the current one. */
    fun submitSearch(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        _query.value = q
        _searching.value = false
        _suggestions.value = emptyList()
        // A search made from the results page replaces it instead of piling up.
        if (_routes.value.lastOrNull() is VideoRoute.Search) _routes.update { it.dropLast(1) }
        push(VideoRoute.Search(q))
    }

    fun setFilter(params: String?) {
        if (_filter.value == params) return
        _filter.value = params
        search(_query.value)
    }

    private fun newResults(q: String) = FeedLoader(scope, service) { service.search(q, _filter.value) }

    private fun search(q: String) {
        _query.value = q
        results = newResults(q)
        _results.value = results
        results.load(force = true)
    }

    // ------------------------------------------------------------------ watching

    private val _watch = MutableStateFlow<WatchState?>(null)
    val watch: StateFlow<WatchState?> = _watch.asStateFlow()
    private var watchJob: Job? = null

    private fun startWatching(video: VideoItem) {
        watchJob?.cancel()
        _watch.value = WatchState(video = video)
        val id = video.id
        watchJob = scope.launch {
            // The stream and the page load side by side: the picture should not wait for the comments' address.
            launch { loadStream(id) }
            service.watch(id).fold(
                onSuccess = { d ->
                    _watch.update { w ->
                        if (w?.video?.id != id) w else w.copy(
                            details = d,
                            related = d.related.entries,
                            relatedContinuation = d.related.continuation,
                            subscribed = d.subscribed == true,
                        )
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _watch.update { w -> if (w?.video?.id != id) w else w.copy(detailsError = true) }
                },
            )
        }
    }

    private suspend fun loadStream(id: String) {
        service.qualities(id).fold(
            onSuccess = { list ->
                _watch.update { w ->
                    if (w?.video?.id != id) w else w.copy(
                        qualities = list,
                        quality = list.pickDefault(),
                        streamLoading = false,
                        streamError = list.isEmpty(),
                    )
                }
            },
            onFailure = { e ->
                if (e is CancellationException) throw e
                _watch.update { w -> if (w?.video?.id != id) w else w.copy(streamLoading = false, streamError = true) }
            },
        )
    }

    /** When a video ends: the first video of "up next" plays, as on YouTube's autoplay. */
    fun playNext() {
        val w = _watch.value ?: return
        val next = w.related.firstNotNullOfOrNull { (it as? VideoEntry.Video)?.item } ?: return
        if (_routes.value.lastOrNull() is VideoRoute.Watch) push(VideoRoute.Watch(next)) else startWatching(next)
    }

    fun retryStream() {
        val id = _watch.value?.video?.id ?: return
        _watch.update { it?.copy(streamLoading = true, streamError = false) }
        scope.launch { loadStream(id) }
    }

    fun selectQuality(quality: VideoQuality) {
        _watch.update { it?.copy(quality = quality) }
    }

    private fun stopWatching() {
        watchJob?.cancel()
        _watch.value = null
        _fullscreen.value = false
    }

    /** Ends the video for good (the small player's close button, or leaving the section). */
    fun closeWatch() {
        _routes.update { stack -> stack.filter { it !is VideoRoute.Watch } }
        stopWatching()
    }

    private val _fullscreen = MutableStateFlow(false)
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    fun setFullscreen(on: Boolean) {
        _fullscreen.value = on && _watch.value != null
    }

    fun loadMoreRelated() {
        val w = _watch.value ?: return
        val token = w.relatedContinuation ?: return
        _watch.update { it?.copy(relatedContinuation = null) }
        scope.launch {
            service.more(token).onSuccess { feed ->
                _watch.update { cur ->
                    if (cur?.video?.id != w.video.id) cur else {
                        val seen = cur.related.flatMap { it.ids() }.toHashSet()
                        val fresh = feed.entries.filter { e -> e.ids().any { it !in seen } }
                        cur.copy(related = cur.related + fresh, relatedContinuation = if (fresh.isEmpty()) null else feed.continuation)
                    }
                }
            }
        }
    }

    fun loadComments() {
        val w = _watch.value ?: return
        val token = if (w.commentsLoaded) w.commentsContinuation else w.details?.commentsToken
        if (token == null || w.commentsLoading) return
        _watch.update { it?.copy(commentsLoading = true) }
        scope.launch {
            service.comments(token).fold(
                onSuccess = { page ->
                    _watch.update { cur ->
                        if (cur?.video?.id != w.video.id) cur else cur.copy(
                            comments = cur.comments + page.comments.filter { c -> cur.comments.none { it.id == c.id } },
                            commentsTotal = page.total ?: cur.commentsTotal,
                            commentsContinuation = page.continuation,
                            commentsLoading = false,
                            commentsLoaded = true,
                        )
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _watch.update { it?.copy(commentsLoading = false, commentsLoaded = true) }
                },
            )
        }
    }

    fun toggleLike() = accountAction(
        change = { w -> w.copy(liked = !w.liked) },
        send = { w -> service.setLike(w.video.id, !w.liked) },
    )

    fun toggleSaved() = accountAction(
        change = { w -> w.copy(saved = !w.saved) },
        send = { w -> service.setWatchLater(w.video.id, !w.saved) },
    )

    fun toggleSubscribe() {
        val w = _watch.value ?: return
        val channelId = w.details?.channelId ?: w.video.channelId ?: return
        accountAction(
            change = { cur -> cur.copy(subscribed = !cur.subscribed) },
            send = { cur -> service.setSubscribed(channelId, !cur.subscribed) },
        )
    }

    fun dismissSignInPrompt() {
        _watch.update { it?.copy(signInPrompt = false) }
    }

    /** Does what an account does on the page at once, and puts it back if YouTube refuses. */
    private fun accountAction(change: (WatchState) -> WatchState, send: suspend (WatchState) -> Result<Unit>) {
        val before = _watch.value ?: return
        if (!service.isSignedIn) {
            _watch.update { it?.copy(signInPrompt = true) }
            return
        }
        _watch.value = change(before)
        scope.launch {
            send(before).onFailure { e ->
                if (e is CancellationException) throw e
                _watch.update { cur -> if (cur?.video?.id == before.video.id) before.copy(details = cur.details) else cur }
            }
        }
    }

    // ------------------------------------------------------------------ channels

    private val _channel = MutableStateFlow<ChannelState?>(null)
    val channel: StateFlow<ChannelState?> = _channel.asStateFlow()
    private var channelJob: Job? = null
    private var channelMore: Job? = null

    private fun loadChannel(id: String, params: String?) {
        channelJob?.cancel()
        val keep = _channel.value?.takeIf { it.id == id }
        _channel.value = ChannelState(id = id, page = keep?.page, tabParams = params, subscribed = keep?.subscribed ?: false)
        channelJob = scope.launch {
            service.channel(id, params).fold(
                onSuccess = { page ->
                    _channel.value = ChannelState(
                        id = id,
                        page = page,
                        tabParams = params,
                        feed = FeedState(
                            entries = page.videos.entries,
                            continuation = page.videos.continuation,
                            loaded = true,
                        ),
                        subscribed = page.subscribed == true,
                    )
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _channel.update { it?.copy(error = true, feed = FeedState(loaded = true, error = true)) }
                },
            )
        }
    }

    fun openChannelTab(params: String) {
        val id = _channel.value?.id ?: return
        loadChannel(id, params)
    }

    fun loadMoreChannel() {
        val c = _channel.value ?: return
        val token = c.feed.continuation ?: return
        if (c.feed.loadingMore || channelMore?.isActive == true) return
        _channel.update { it?.copy(feed = it.feed.copy(loadingMore = true)) }
        channelMore = scope.launch {
            service.more(token).fold(
                onSuccess = { feed ->
                    _channel.update { cur ->
                        if (cur?.id != c.id) cur else {
                            val seen = cur.feed.entries.flatMap { it.ids() }.toHashSet()
                            val fresh = feed.entries.filter { e -> e.ids().any { it !in seen } }
                            cur.copy(feed = cur.feed.copy(entries = cur.feed.entries + fresh, continuation = if (fresh.isEmpty()) null else feed.continuation, loadingMore = false))
                        }
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _channel.update { it?.copy(feed = it.feed.copy(loadingMore = false)) }
                },
            )
        }
    }

    fun toggleChannelSubscribe() {
        val c = _channel.value ?: return
        if (!service.isSignedIn) return
        val target = !c.subscribed
        _channel.value = c.copy(subscribed = target)
        scope.launch {
            service.setSubscribed(c.id, target).onFailure { e ->
                if (e is CancellationException) throw e
                _channel.update { cur -> if (cur?.id == c.id) cur.copy(subscribed = !target) else cur }
            }
        }
    }

    // ------------------------------------------------------------------ playlists

    private var playlist = FeedLoader(scope, service) { Result.success(VideoFeed()) }
    private val _playlist = MutableStateFlow<FeedLoader?>(null)
    val openedPlaylist: StateFlow<FeedLoader?> = _playlist.asStateFlow()

    private fun openPlaylist(id: String) {
        playlist = FeedLoader(scope, service) { service.playlist(id) }
        _playlist.value = playlist
        playlist.load(force = true)
    }

    companion object {
        const val KEY_COOKIES = "video_cookies"
        private const val SUGGEST_DELAY_MS = 180L
        private const val MAX_SUGGESTIONS = 8

        val FILTERS: List<Pair<String?, String>> = listOf(
            null to "all",
            VideoSearchFilter.VIDEOS to "videos",
            VideoSearchFilter.CHANNELS to "channels",
            VideoSearchFilter.PLAYLISTS to "playlists",
            VideoSearchFilter.LIVE to "live",
        )
    }
}

/** Plays at 720p when it can (a phone screen gains little from more), else the best below it, else whatever there is. */
internal fun List<VideoQuality>.pickDefault(): VideoQuality? =
    firstOrNull { it.isLive }
        ?: filter { it.height in 1..720 }.maxByOrNull { it.height * 100 + it.fps }
        ?: minByOrNull { it.height }
