package com.qmusic.app

import android.app.Application
import android.content.Context
import android.content.Intent
import com.qmusic.database.createAndroidDatabase
import com.qmusic.database.repository.MusicRepository
import com.qmusic.database.repository.MusicRepositoryImpl
import com.qmusic.engine.YouTube
import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.engine.models.SongItem
import com.qmusic.engine.models.SpotifyIds
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.qmusic.player.DownloadController
import com.qmusic.settings.AppSettings
import com.qmusic.player.PlayerController
import com.qmusic.player.PlayerHolder
import com.qmusic.player.RepeatMode
import com.qmusic.player.service.SimpleMediaService
import com.qmusic.spotify.SpotifyImporter
import com.qmusic.updater.GithubReleaseUpdateChecker
import com.qmusic.updater.UpdateChecker
import com.qmusic.updater.UpdateInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Hand-wired dependency container: one instance per process, created in [QMusicApplication]. */
class AppContainer(private val application: Application) {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var radioFilledFor: String? = null

    val repository: MusicRepository by lazy {
        MusicRepositoryImpl(createAndroidDatabase(application))
    }
    val sessionStore: YouTubeSessionStore by lazy { YouTubeSessionStore(application) }
    val settings: AppSettings by lazy { SharedPreferencesAppSettings(application) }
    val connectivity: AndroidConnectivityMonitor by lazy { AndroidConnectivityMonitor(application) }
    val crashReports: FileCrashReports by lazy { FileCrashReports(application) }

    /** True on Wi-Fi / ethernet (an unmetered connection), where big automatic downloads are fine. */
    fun isUnmetered(): Boolean {
        val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    val youTube: YouTube get() = PlayerHolder.youTube
    val youTubeAuth: YouTubeAuth by lazy { YouTubeAuth(cookieString = sessionStore.load()) }
    val spotifyImporter: SpotifyImporter by lazy { SpotifyImporter.create() }
    val updateChecker: UpdateChecker by lazy { GithubReleaseUpdateChecker() }
    val updateInstaller: UpdateInstaller by lazy { ApkUpdateInstaller(application) }
    val downloads: DownloadController get() = PlayerHolder.downloads(application)
    val lastFm: LastFm by lazy { LastFm(application) }
    val ringtoneMaker: AndroidRingtoneMaker by lazy { AndroidRingtoneMaker(application) }
    val spotifySession: SpotifyWebSession by lazy { SpotifyWebSession(application) }
    val spotifyLinker: com.qmusic.ui.viewmodel.SpotifyLinker by lazy {
        com.qmusic.ui.viewmodel.SpotifyLinker(youTube, spotifyImporter, settings)
    }

    init {
        // The player asks for the YouTube recording of a Spotify song right before it plays it, even if MainActivity is gone.
        PlayerHolder.spotifyMatcher = { song -> spotifyLinker.youtubeId(song) }
    }

    val playerController: PlayerController by lazy {
        ServicePlayerController(
            application,
            PlayerHolder.get(application).also {
                WidgetUpdater.start(application, it)
                lastFm.follow(it)
                followRadioRefill(it)
            },
        )
    }

    /** Keeps the queue topped up with YouTube Music's radio in the background even when [MainActivity] is destroyed. */
    private fun followRadioRefill(player: PlayerController) {
        processScope.launch {
            combine(player.queue, player.queueIndex, player.currentTrack) { queue, index, track ->
                Triple(queue.size - index - 1, index, track)
            }.collect { (upcoming, index, track) ->
                if (track == null || index < 0 || upcoming > RADIO_REFILL_THRESHOLD) return@collect
                if (radioFilledFor == track.id || player.repeatMode.value == RepeatMode.ONE) return@collect
                radioFilledFor = track.id
                val seed = if (SpotifyIds.isSpotify(track.id)) spotifyLinker.youtubeId(track) else track.id
                if (seed.isNullOrBlank()) return@collect
                youTube.radio(seed).onSuccess { more ->
                    if (player.currentTrack.value?.id == track.id) player.appendToQueue(more)
                }
            }
        }
    }

    private companion object {
        const val RADIO_REFILL_THRESHOLD = 3
    }
}

/**
 * Delegates to the shared player and makes sure [SimpleMediaService] is running so the
 * MediaSession notification and lock-screen controls appear as soon as playback starts.
 */
private class ServicePlayerController(
    private val context: Context,
    private val delegate: PlayerController,
) : PlayerController by delegate {
    override fun play(song: SongItem) {
        startService()
        delegate.play(song)
    }

    override fun playFrom(song: SongItem, positionMs: Long) {
        startService()
        delegate.playFrom(song, positionMs)
    }

    override fun playQueue(songs: List<SongItem>, startIndex: Int) {
        startService()
        delegate.playQueue(songs, startIndex)
    }

    override fun resume() {
        startService()
        delegate.resume()
    }

    override fun next() {
        startService()
        delegate.next()
    }

    override fun previous() {
        startService()
        delegate.previous()
    }

    override fun skipTo(index: Int) {
        startService()
        delegate.skipTo(index)
    }

    private fun startService() {
        runCatching {
            context.startService(Intent(context, SimpleMediaService::class.java))
        }
    }
}
