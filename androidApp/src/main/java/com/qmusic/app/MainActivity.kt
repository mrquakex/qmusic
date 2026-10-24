package com.qmusic.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.qmusic.player.service.MediaActions
import com.qmusic.ui.QMusicApp
import com.qmusic.ui.strings.EnglishStrings
import com.qmusic.ui.strings.TurkishStrings
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.qmusic.ui.viewmodel.MainViewModel
import com.qmusic.ui.viewmodel.ScreenTab

class MainActivity : AppCompatActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        val container = (application as QMusicApplication).container
        val viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(
                        youTube = container.youTube,
                        playerController = container.playerController,
                        repository = container.repository,
                        spotifyImporter = container.spotifyImporter,
                        youTubeAuth = container.youTubeAuth,
                        updateChecker = container.updateChecker,
                        updateInstaller = container.updateInstaller,
                        onSessionChanged = container.sessionStore::save,
                        downloads = container.downloads,
                        settings = container.settings,
                        connectivity = container.connectivity,
                        crashReports = container.crashReports,
                        isUnmetered = container::isUnmetered,
                        scrobbling = container.lastFm,
                        spotifySession = container.spotifySession,
                        spotifyLinker = container.spotifyLinker,
                    ) as T
            },
        )[MainViewModel::class.java]

        this.viewModel = viewModel
        openUpdatesIfAsked(intent)
        // A rotation recreates the activity with the same intent: the link was opened the first time already.
        if (savedInstanceState == null) {
            openLinkIfAny(intent)
            openShortcutIfAny(intent)
        }

        CastBridge.init(this)

        // The heart in the playback notification: shows and toggles the like of the playing song.
        MediaActions.onToggleLike = { viewModel.currentTrack.value?.let(viewModel::toggleFavorite) }
        likeWatcher.launch {
            combine(viewModel.currentTrack, viewModel.favorites) { track, liked ->
                track != null && liked.any { it.id == track.id }
            }.collect { MediaActions.liked.value = it }
        }
        // Settings › Lyrics in the notification: the line being sung, in a quiet notification of its own.
        likeWatcher.launch {
            combine(viewModel.notificationLyricLine, viewModel.currentTrack) { line, track -> line to track }
                .collect { (line, track) ->
                    val channel = if (container.settings.language.value == "en") EnglishStrings.lyrics else TurkishStrings.lyrics
                    LyricsNotification.update(this@MainActivity, track, line, channel)
                }
        }

        setContent {
            QMusicApp(viewModel = viewModel)
        }
    }

    private var viewModel: MainViewModel? = null
    private val likeWatcher = MainScope()

    override fun onDestroy() {
        likeWatcher.cancel()
        LyricsNotification.update(this, null, null, "")
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openUpdatesIfAsked(intent)
        openLinkIfAny(intent)
        openShortcutIfAny(intent)
    }

    /** One of the shortcuts shown when the app icon is held. */
    private fun openShortcutIfAny(intent: Intent?) {
        if (intent?.action != ACTION_SHORTCUT) return
        val vm = viewModel ?: return
        when (intent.getStringExtra("shortcut")) {
            "search" -> vm.selectTab(ScreenTab.SEARCH)
            "downloads" -> vm.openDownloads()
            "shuffle_liked" -> likeWatcher.launch {
                // Right after launch the liked songs are still being read from the database: wait for them briefly.
                val liked = kotlinx.coroutines.withTimeoutOrNull(SHORTCUT_WAIT_MS) {
                    vm.favorites.first { it.isNotEmpty() }
                }.orEmpty()
                if (liked.isNotEmpty()) {
                    vm.shuffleAll(liked)
                    vm.setPlayerExpanded(true)
                } else {
                    vm.openDownloads()
                }
            }
        }
        intent.action = Intent.ACTION_MAIN
    }

    private companion object {
        const val ACTION_SHORTCUT = "com.qmusic.app.SHORTCUT"
        const val SHORTCUT_WAIT_MS = 3_000L
    }

    /** A YouTube / YouTube Music link tapped elsewhere, or shared to the app. */
    private fun openLinkIfAny(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        viewModel?.openLink(text)
    }

    /** Tapped the "new version" notification: show Settings with the check already done. */
    private fun openUpdatesIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdatePush.EXTRA_OPEN_UPDATES, false) != true) return
        intent.removeExtra(UpdatePush.EXTRA_OPEN_UPDATES)
        viewModel?.apply {
            selectTab(ScreenTab.SETTINGS)
            checkForUpdates()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
