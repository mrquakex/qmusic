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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.qmusic.player.DownloadController
import com.qmusic.settings.AppSettings
import com.qmusic.player.PlayerController
import com.qmusic.player.PlayerHolder
import com.qmusic.player.service.SimpleMediaService
import com.qmusic.spotify.SpotifyImporter
import com.qmusic.updater.GithubReleaseUpdateChecker
import com.qmusic.updater.UpdateChecker
import com.qmusic.updater.UpdateInstaller

/** Hand-wired dependency container: one instance per process, created in [QMusicApplication]. */
class AppContainer(private val application: Application) {
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
    val playerController: PlayerController by lazy {
        ServicePlayerController(
            application,
            PlayerHolder.get(application).also {
                WidgetUpdater.start(application, it)
                lastFm.follow(it)
            },
        )
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

    private fun startService() {
        runCatching {
            context.startService(Intent(context, SimpleMediaService::class.java))
        }
    }
}
