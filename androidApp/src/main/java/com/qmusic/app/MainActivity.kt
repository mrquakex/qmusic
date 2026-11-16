package com.qmusic.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxSize
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.qmusic.ui.viewmodel.MainViewModel
import com.qmusic.ui.viewmodel.ScreenTab

class MainActivity : AppCompatActivity() {

    // The answers the ringtone feature waits for: the picked contact, the Contacts permission, a return from Settings.
    private var contactPick: CompletableDeferred<Uri?>? = null
    private val contactPicker = registerForActivityResult(ActivityResultContracts.PickContact()) { contactPick?.complete(it) }
    private var contactsAnswer: CompletableDeferred<Boolean>? = null
    private val contactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            contactsAnswer?.complete(result.values.all { it })
        }
    private var settingsBack: CompletableDeferred<Unit>? = null
    private val settingsPage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { settingsBack?.complete(Unit) }

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
                        ringtoneMaker = container.ringtoneMaker,
                    ) as T
            },
        )[MainViewModel::class.java]

        this.viewModel = viewModel
        hookRingtones()
        openUpdatesIfAsked(intent)
        showWhatsNewIfAsked(intent)
        installNowIfAsked(intent)
        ChatPush.openIfAsked(intent)
        GroupCallNotifications.openIfAsked(this, intent)
        acceptCallIfAsked(intent)
        CallNotifications.callBackIfAsked(this, intent)
        // While a call rings or goes on, the app shows over the lock screen and turns the screen on.
        likeWatcher.launch {
            while (com.qmusic.ui.chat.CallHooks.state == null) kotlinx.coroutines.delay(500)
            com.qmusic.ui.chat.CallHooks.state?.collect { call ->
                val over = call != null && call.phase != com.qmusic.ui.chat.CallPhase.ENDED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(over)
                    setTurnScreenOn(over)
                }
            }
        }
        // A rotation recreates the activity with the same intent: the link was opened the first time already.
        if (savedInstanceState == null) {
            openLinkIfAny(intent)
            openShortcutIfAny(intent)
        }

        CastBridge.init(this)
        // A TV linked by code: on a TV, Qeyf waits for a phone; on a phone, it plays on linked TVs.
        com.qmusic.ui.tv.TvAndroid.init(this)
        // Songs as Qeyf's call sounds (ringing, per person, and what callers hear).
        com.qmusic.ui.chat.CallTonesAndroid.init(this)
        // Music in the messages: profile songs, what friends play, shared playlists, reactions.
        com.qmusic.ui.chat.ChatMusicAndroid.init(this)

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

        // QVideo: a video that is playing keeps going with the screen off (see VideoKeepAliveService).
        com.qmusic.ui.videos.VideoBackground.onState = { active, title ->
            val service = Intent(this, VideoKeepAliveService::class.java)
            if (active) {
                service.putExtra(VideoKeepAliveService.EXTRA_TITLE, title)
                ContextCompat.startForegroundService(this, service)
            } else {
                stopService(service)
            }
        }

        // QVideo downloads keep going with the app left (see DownloadKeepAliveService).
        com.qmusic.ui.videos.VideoDownloadHooks.onActive = { count, percent, title ->
            val service = Intent(this, DownloadKeepAliveService::class.java)
            if (count > 0) {
                service.putExtra(DownloadKeepAliveService.EXTRA_COUNT, count)
                service.putExtra(DownloadKeepAliveService.EXTRA_PERCENT, percent)
                service.putExtra(DownloadKeepAliveService.EXTRA_TITLE, title)
                runCatching { ContextCompat.startForegroundService(this, service) }
            } else {
                stopService(service)
            }
        }

        setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                QMusicApp(viewModel = viewModel)
                val language by container.settings.language.collectAsState()
                com.qmusic.ui.tv.TvOverlay(english = language == "en")
            }
        }
    }

    private var viewModel: MainViewModel? = null

    /** A remote's key used for a TV's playing (its release is not passed on either). */
    private var tvKeyHeld = -1

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (com.qmusic.ui.tv.TvScreen.isTv) {
            if (event.action == android.view.KeyEvent.ACTION_UP && event.keyCode == tvKeyHeld) {
                tvKeyHeld = -1
                return true
            }
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val key = when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> com.qmusic.ui.tv.TvKey.CENTER
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> com.qmusic.ui.tv.TvKey.PLAY_PAUSE
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> com.qmusic.ui.tv.TvKey.LEFT
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> com.qmusic.ui.tv.TvKey.RIGHT
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> com.qmusic.ui.tv.TvKey.NEXT
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> com.qmusic.ui.tv.TvKey.PREVIOUS
                    android.view.KeyEvent.KEYCODE_BACK -> com.qmusic.ui.tv.TvKey.BACK
                    else -> null
                }
                if (key != null && com.qmusic.ui.tv.TvScreen.onKey(key)) {
                    tvKeyHeld = event.keyCode
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** A video that is playing shrinks into a floating window when the person leaves the app. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (com.qmusic.ui.videos.VideoPip.armed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(if (com.qmusic.ui.videos.VideoPip.portrait) android.util.Rational(9, 16) else android.util.Rational(16, 9))
                        .build(),
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.qmusic.ui.videos.VideoPip.active.value = isInPictureInPictureMode
    }
    private val likeWatcher = MainScope()

    /** What the ringtone feature needs from the screen in front (see [RingtoneBridge]). */
    private fun hookRingtones() {
        RingtoneBridge.pickContact = {
            val answer = CompletableDeferred<Uri?>()
            contactPick = answer
            contactPicker.launch(null)
            answer.await()
        }
        RingtoneBridge.requestContactsPermission = {
            val needed = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                true
            } else {
                val answer = CompletableDeferred<Boolean>()
                contactsAnswer = answer
                contactsPermission.launch(needed)
                answer.await()
            }
        }
        RingtoneBridge.openWriteSettings = {
            val answer = CompletableDeferred<Unit>()
            settingsBack = answer
            settingsPage.launch(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")),
            )
            answer.await()
        }
    }

    override fun onDestroy() {
        RingtoneBridge.pickContact = null
        RingtoneBridge.requestContactsPermission = null
        RingtoneBridge.openWriteSettings = null
        likeWatcher.cancel()
        LyricsNotification.update(this, null, null, "")
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openUpdatesIfAsked(intent)
        showWhatsNewIfAsked(intent)
        installNowIfAsked(intent)
        ChatPush.openIfAsked(intent)
        GroupCallNotifications.openIfAsked(this, intent)
        acceptCallIfAsked(intent)
        CallNotifications.callBackIfAsked(this, intent)
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
        // A Qeyf code (qeyf://u/<username>): that person's profile in the messages section.
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "qeyf") {
            com.qmusic.ui.chat.qeyfLaunchOf(intent.dataString.orEmpty())?.let { request ->
                com.qmusic.ui.chat.ChatLaunch.request.value = request
            }
            intent.action = Intent.ACTION_MAIN
            return
        }
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        viewModel?.openLink(text)
    }

    override fun onStart() {
        super.onStart()
        com.qmusic.ui.chat.CallHooks.setVisible(true)
        com.qmusic.ui.chat.ChatLock.onShown(System.currentTimeMillis())
    }

    override fun onStop() {
        com.qmusic.ui.chat.CallHooks.setVisible(false)
        com.qmusic.ui.chat.ChatLock.onHidden(System.currentTimeMillis())
        AutoUpdate.onAppHidden(this)
        super.onStop()
    }

    /** The answer button of a ringing call was tapped: the call screen answers as soon as it is up. */
    private fun acceptCallIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(CallNotifications.EXTRA_ACCEPT, false) != true) return
        intent.removeExtra(CallNotifications.EXTRA_ACCEPT)
        CallNotifications.cancelIncoming(this)
        com.qmusic.ui.chat.CallLaunch.acceptRequestedAt.value = System.currentTimeMillis()
    }

    /** Tapped the "new version" notification: show Settings with the check already done. */
    /** Tapped "Qeyf was updated": what changed in this version. */
    private fun showWhatsNewIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WHATS_NEW, false) != true) return
        intent.removeExtra(EXTRA_WHATS_NEW)
        viewModel?.showWhatsNew()
    }

    private fun openUpdatesIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdatePush.EXTRA_OPEN_UPDATES, false) != true) return
        intent.removeExtra(UpdatePush.EXTRA_OPEN_UPDATES)
        viewModel?.apply {
            selectTab(ScreenTab.SETTINGS)
            checkForUpdates()
        }
    }

    /**
     * Tapped "a new version is out": the downloaded update goes in now (Android's sheet asks first when it must);
     * when it is not downloaded yet, it is fetched and installed as from the update button.
     */
    private fun installNowIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdatePush.EXTRA_INSTALL_NOW, false) != true) return
        intent.removeExtra(UpdatePush.EXTRA_INSTALL_NOW)
        getSystemService(android.app.NotificationManager::class.java)?.cancel(UpdatePush.NOTIFICATION_ID_PUBLIC)
        if (AutoUpdate.installNow(this)) return
        viewModel?.apply {
            selectTab(ScreenTab.SETTINGS)
            checkForUpdates(silent = true)
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
