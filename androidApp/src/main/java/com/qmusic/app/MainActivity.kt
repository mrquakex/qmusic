package com.qmusic.app

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.Icon
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.util.Rational
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.qmusic.player.service.MediaActions
import com.qmusic.ui.QMusicApp
import com.qmusic.ui.chat.AttachmentKind
import com.qmusic.ui.chat.PickedFile
import com.qmusic.ui.chat.PickedImage
import com.qmusic.ui.strings.EnglishStrings
import com.qmusic.ui.strings.TurkishStrings
import com.qmusic.ui.viewmodel.AppLinks
import com.qmusic.ui.viewmodel.MainViewModel
import com.qmusic.ui.viewmodel.ScreenTab
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val pip: () -> Unit = { runOnUiThread { updatePip() } }
        pipListener = pip
        com.qmusic.ui.videos.VideoPip.onChanged = pip
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        val app = applicationContext
        val resolveUris: (List<String>, String, (List<PickedImage>, List<PickedFile>) -> Unit) -> Unit = { uris, mimeType, onReady ->
            resolveIncomingShareUris(app, uris, mimeType, onReady)
        }
        resolveShareUrisListener = resolveUris
        com.qmusic.ui.chat.IncomingChatShare.resolveUris = resolveUris
        val gameSalts = app.getSharedPreferences("nuvelo_game_salts", Context.MODE_PRIVATE)
        com.qmusic.ui.chat.games.GameSaltStore.persistLoad = { key -> gameSalts.getString(key, null) }
        com.qmusic.ui.chat.games.GameSaltStore.persistSave = { key, value ->
            gameSalts.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }

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

        // While a 1-1 or group call rings or goes on, the app shows over the lock screen and turns the screen on.
        val syncCallWindow: () -> Unit = {
            val call = com.qmusic.ui.chat.CallHooks.state?.value
            val over = (call != null && call.phase != com.qmusic.ui.chat.CallPhase.ENDED) ||
                com.qmusic.ui.chat.CallHooks.groupActiveFlow.value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(over)
                setTurnScreenOn(over)
            }
            updatePip()
        }
        likeWatcher.launch {
            com.qmusic.ui.chat.CallHooks.groupActiveFlow.collect {
                syncCallWindow()
            }
        }
        likeWatcher.launch {
            while (com.qmusic.ui.chat.CallHooks.state == null) kotlinx.coroutines.delay(500)
            com.qmusic.ui.chat.CallHooks.state?.collect {
                syncCallWindow()
            }
        }
        likeWatcher.launch {
            com.qmusic.ui.videos.VideoBackground.isPlaying.collect {
                updatePip()
            }
        }
        likeWatcher.launch {
            com.qmusic.ui.chat.GroupCallUi.minimized.collect {
                updatePip()
            }
        }
        likeWatcher.launch {
            snapshotFlow { com.qmusic.ui.chat.GroupCallUi.service }.collectLatest { service ->
                updatePip()
                service?.state?.collect { updatePip() }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                pipReceiver,
                IntentFilter().apply {
                    addAction(PIP_REWIND)
                    addAction(PIP_TOGGLE)
                    addAction(PIP_FORWARD)
                    addAction(PIP_CALL_MUTE)
                    addAction(PIP_CALL_END)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        // A rotation recreates the activity with the same intent: the link or share was handled the first time already.
        if (savedInstanceState == null) {
            openLinkIfAny(intent)
            handleIncomingShare(intent)
            openShortcutIfAny(intent)
        }

        CastBridge.init(this)
        // A TV linked by code: on a TV, Qeyf waits for a phone; on a phone, it plays on linked TVs.
        com.qmusic.ui.tv.TvAndroid.init(this)
        // Songs as Qeyf's call sounds (ringing, per person, and what callers hear).
        com.qmusic.ui.chat.CallTonesAndroid.init(this)
        // Music in the messages: profile songs, what friends play, shared playlists, reactions.
        com.qmusic.ui.chat.ChatMusicAndroid.init(this)
        // Live location: sharings that were going on carry on.
        com.qmusic.ui.chat.LiveLocations.init(this)
        com.qmusic.ui.chat.ArrivalWatch.init(this)
        signInForTestIfAsked(intent)
        // The last crash (if any) goes to the admins' panel, once.
        container.crashReports.lastReport.value?.let { report ->
            com.qmusic.ui.chat.AdminCrashes.report(this, report)
        }
        // The secret space: its settings kept, screenshots blocked while it shows, and it locks when the screen goes off.
        val secretPrefs = app.getSharedPreferences("nuvelo_secret_chats", MODE_PRIVATE)
        com.qmusic.ui.chat.SecretSpace.load = { key -> secretPrefs.getString("s_$key", null) ?: if (key == "showCaller") (if (secretPrefs.getBoolean("showCaller", true)) "1" else "0") else null }
        com.qmusic.ui.chat.SecretSpace.save = { key, value ->
            secretPrefs.edit().apply {
                if (key == "showCaller") putBoolean("showCaller", value != "0") else if (value == null) remove("s_$key") else putString("s_$key", value)
            }.apply()
        }
        val secureWindow: (Boolean) -> Unit = { on ->
            runOnUiThread {
                if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        secureWindowListener = secureWindow
        com.qmusic.ui.chat.SecretSpace.secureWindow = secureWindow
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenOff,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        // The heart in the playback notification: shows and toggles the like of the playing song.
        val toggleLike: () -> Unit = { viewModel.currentTrack.value?.let(viewModel::toggleFavorite) }
        toggleLikeListener = toggleLike
        MediaActions.onToggleLike = toggleLike
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
            val service = Intent(app, VideoKeepAliveService::class.java)
            if (active) {
                service.putExtra(VideoKeepAliveService.EXTRA_TITLE, title)
                runCatching { ContextCompat.startForegroundService(app, service) }
            } else {
                app.stopService(service)
            }
        }

        // QVideo downloads keep going with the app left (see DownloadKeepAliveService).
        com.qmusic.ui.videos.VideoDownloadHooks.onActive = { count, percent, title ->
            val service = Intent(app, DownloadKeepAliveService::class.java)
            if (count > 0) {
                service.putExtra(DownloadKeepAliveService.EXTRA_COUNT, count)
                service.putExtra(DownloadKeepAliveService.EXTRA_PERCENT, percent)
                service.putExtra(DownloadKeepAliveService.EXTRA_TITLE, title)
                runCatching { ContextCompat.startForegroundService(app, service) }
            } else {
                app.stopService(service)
            }
        }

        setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                com.qmusic.ui.components.LaunchSplash { QMusicApp(viewModel = viewModel) }
                val language by container.settings.language.collectAsState()
                com.qmusic.ui.tv.TvOverlay(english = language == "en")
                // A call shrunk into its bubble floats over everything.
                com.qmusic.ui.chat.CallBubbleOverlay()
                // A message from another conversation while the app is open: a strip at the top.
                com.qmusic.ui.chat.ChatBannerOverlay()
            }
        }
    }

    private var viewModel: MainViewModel? = null
    private var pipListener: (() -> Unit)? = null
    private var secureWindowListener: ((Boolean) -> Unit)? = null
    private var toggleLikeListener: (() -> Unit)? = null
    private var resolveShareUrisListener: ((List<String>, String, (List<PickedImage>, List<PickedFile>) -> Unit) -> Unit)? = null

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

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipRemoteAction(iconRes: Int, title: String, action: String, code: Int): RemoteAction {
        val pending = PendingIntent.getBroadcast(
            this,
            code,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, iconRes), title, title, pending)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val en = getSharedPreferences("qmusic_settings", MODE_PRIVATE).getString("language", "tr") == "en"
        val oneToOne = com.qmusic.ui.chat.CallHooks.state?.value?.takeIf { it.phase != com.qmusic.ui.chat.CallPhase.ENDED }
        val groupActive = com.qmusic.ui.chat.CallHooks.groupActive
        val callActive = oneToOne != null || groupActive
        val actions = if (callActive) {
            val muted = oneToOne?.muted ?: (com.qmusic.ui.chat.GroupCallUi.service?.state?.value?.muted == true)
            listOf(
                pipRemoteAction(
                    iconRes = if (muted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_btn_speak_now,
                    title = if (muted) (if (en) "Unmute" else "Sesi aç") else (if (en) "Mute" else "Sessize al"),
                    action = PIP_CALL_MUTE,
                    code = 201,
                ),
                pipRemoteAction(
                    iconRes = android.R.drawable.ic_menu_close_clear_cancel,
                    title = if (en) "End call" else "Kapat",
                    action = PIP_CALL_END,
                    code = 202,
                ),
            )
        } else {
            val playing = com.qmusic.ui.videos.VideoBackground.isPlaying.value
            listOf(
                pipRemoteAction(android.R.drawable.ic_media_rew, "-10s", PIP_REWIND, 101),
                pipRemoteAction(
                    iconRes = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    title = if (playing) (if (en) "Pause" else "Duraklat") else (if (en) "Play" else "Oynat"),
                    action = PIP_TOGGLE,
                    code = 102,
                ),
                pipRemoteAction(android.R.drawable.ic_media_ff, "+10s", PIP_FORWARD, 103),
            )
        }
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(if (com.qmusic.ui.videos.VideoPip.portrait) Rational(9, 16) else Rational(16, 9))
            .setActions(actions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(com.qmusic.ui.videos.VideoPip.armed)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /**
     * Android 12 and later: the floating window opens by itself as the person leaves the app (swiping home too), for
     * as long as something is armed (a video playing, a call going on or ringing out); also refreshes PiP buttons live.
     */
    private fun updatePip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isInPictureInPictureMode) return
        runCatching {
            setPictureInPictureParams(buildPipParams())
        }
    }

    /** A video or call that is armed shrinks into a floating window when the person leaves the app. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (com.qmusic.ui.videos.VideoPip.armed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                enterPictureInPictureMode(buildPipParams())
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.qmusic.ui.videos.VideoPip.active.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) updatePip()
    }

    private val likeWatcher = MainScope()

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PIP_REWIND -> com.qmusic.ui.videos.VideoBackground.onSeekBy?.invoke(-10_000L)
                PIP_TOGGLE -> com.qmusic.ui.videos.VideoBackground.toggle?.invoke()
                PIP_FORWARD -> com.qmusic.ui.videos.VideoBackground.onSeekBy?.invoke(10_000L)
                PIP_CALL_MUTE -> {
                    val call = com.qmusic.ui.chat.CallHooks.state?.value
                    if (call != null && call.phase != com.qmusic.ui.chat.CallPhase.ENDED) {
                        com.qmusic.ui.chat.CallUi.service?.toggleMute()
                    } else if (com.qmusic.ui.chat.CallHooks.groupActive) {
                        com.qmusic.ui.chat.GroupCallUi.service?.toggleMute()
                    }
                    updatePip()
                }
                PIP_CALL_END -> {
                    val call = com.qmusic.ui.chat.CallHooks.state?.value
                    if (call != null && call.phase != com.qmusic.ui.chat.CallPhase.ENDED) {
                        com.qmusic.ui.chat.CallUi.service?.hangUp() ?: com.qmusic.ui.chat.ChatCallActions.hangUp(this@MainActivity)
                    } else if (com.qmusic.ui.chat.CallHooks.groupActive) {
                        com.qmusic.ui.chat.GroupCallUi.service?.leave() ?: com.qmusic.ui.chat.ChatCallActions.hangUp(this@MainActivity)
                    }
                }
            }
        }
    }

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

    /** The screen went off: the secret space locks. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            com.qmusic.ui.chat.SecretSpace.onScreenOff()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        runCatching { unregisterReceiver(pipReceiver) }
        if (com.qmusic.ui.videos.VideoPip.onChanged === pipListener) com.qmusic.ui.videos.VideoPip.onChanged = null
        if (com.qmusic.ui.chat.SecretSpace.secureWindow === secureWindowListener) com.qmusic.ui.chat.SecretSpace.secureWindow = {}
        if (MediaActions.onToggleLike === toggleLikeListener) MediaActions.onToggleLike = null
        if (com.qmusic.ui.chat.IncomingChatShare.resolveUris === resolveShareUrisListener) {
            com.qmusic.ui.chat.IncomingChatShare.resolveUris = null
        }
        RingtoneBridge.pickContact = null
        RingtoneBridge.requestContactsPermission = null
        RingtoneBridge.openWriteSettings = null
        likeWatcher.cancel()
        LyricsNotification.update(this, null, null, "")
        super.onDestroy()
    }

    /**
     * Signs in with a Firebase custom token handed in by adb (the test account on an emulator, which has no Google
     * account). Only a token made with the server's private Firebase key works, so this opens no account to anyone else.
     */
    private fun signInForTestIfAsked(intent: Intent?) {
        val token = intent?.getStringExtra("nuvelo_test_token")?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra("nuvelo_test_token")
        com.qmusic.ui.chat.TestSignIn.withToken(token)
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
        signInForTestIfAsked(intent)
        openLinkIfAny(intent)
        handleIncomingShare(intent)
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
        const val PIP_REWIND = "com.qmusic.app.PIP_REWIND"
        const val PIP_TOGGLE = "com.qmusic.app.PIP_TOGGLE"
        const val PIP_FORWARD = "com.qmusic.app.PIP_FORWARD"
        const val PIP_CALL_MUTE = "com.qmusic.app.PIP_CALL_MUTE"
        const val PIP_CALL_END = "com.qmusic.app.PIP_CALL_END"
    }

    /** A YouTube / YouTube Music / Spotify / Nuvelo link tapped elsewhere. */
    private fun openLinkIfAny(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val scheme = intent.data?.scheme?.lowercase()
        // A Qeyf / Nuvelo code (qeyf://u/<username> or nuvelo://u/<username>, g/<code>, c/<username>).
        if (scheme == "qeyf" || scheme == "nuvelo") {
            val raw = intent.dataString.orEmpty()
            val normalized = if (raw.startsWith("nuvelo://", ignoreCase = true)) {
                "qeyf://" + raw.substring("nuvelo://".length)
            } else {
                raw
            }
            (com.qmusic.ui.chat.qeyfLaunchOf(raw) ?: com.qmusic.ui.chat.qeyfLaunchOf(normalized))?.let { request ->
                com.qmusic.ui.chat.ChatLaunch.request.value = request
            }
            intent.action = Intent.ACTION_MAIN
            return
        }
        val text = intent.dataString ?: return
        viewModel?.openLink(text)
        intent.action = Intent.ACTION_MAIN
    }

    /** Content shared from Android's Share Sheet or Direct Share conversation shortcuts. */
    private fun handleIncomingShare(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        val mimeType = intent.type.orEmpty()
        val targetChatId = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID) else null)
            ?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("android.intent.extra.shortcut.ID")?.takeIf { it.isNotBlank() }

        val streamUris: List<Uri> = when (action) {
            Intent.ACTION_SEND -> {
                val single = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (single != null) {
                    listOf(single)
                } else if (!mimeType.startsWith("text/plain", ignoreCase = true)) {
                    clipDataUris(intent)
                } else {
                    emptyList()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val multiple = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                multiple.ifEmpty { clipDataUris(intent) }
            }
            else -> emptyList()
        }
        val uris = streamUris.map { it.toString() }

        if (action == Intent.ACTION_SEND &&
            mimeType.startsWith("text/plain", ignoreCase = true) &&
            uris.isEmpty() &&
            targetChatId == null &&
            text != null &&
            AppLinks.parse(text) != null
        ) {
            viewModel?.openLink(text)
            intent.action = Intent.ACTION_MAIN
            return
        }

        if (text != null || uris.isNotEmpty()) {
            com.qmusic.ui.chat.IncomingChatShare.offer(text, uris, mimeType, targetChatId)
            com.qmusic.ui.chat.ChatLaunch.request.value = com.qmusic.ui.chat.ChatLaunchRequest(null)
        }
        intent.action = Intent.ACTION_MAIN
    }

    private fun clipDataUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return buildList {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let(::add)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.qmusic.ui.chat.CallHooks.setVisible(true)
        com.qmusic.ui.chat.ChatLock.onShown(System.currentTimeMillis())
        com.qmusic.ui.chat.SecretSpace.onForeground()
    }

    override fun onStop() {
        com.qmusic.ui.chat.CallHooks.setVisible(false)
        com.qmusic.ui.chat.ChatLock.onHidden(System.currentTimeMillis())
        com.qmusic.ui.chat.SecretSpace.onBackground()
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
            checkForUpdates(silent = true, now = true)
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

private val shareResolveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private const val SHARE_IMAGE_MAX_SIDE = 1920
private const val SHARE_IMAGE_MAX_BYTES = 1_200_000
private const val SHARE_HD_MAX_SIDE = 4096
private const val SHARE_HD_MAX_BYTES = 6_000_000
private const val SHARE_THUMB_SIDE = 24
private const val SHARE_CACHE_MAX_BYTES = 100L * 1024L * 1024L

private fun resolveIncomingShareUris(
    app: Context,
    uris: List<String>,
    fallbackMime: String,
    onReady: (List<PickedImage>, List<PickedFile>) -> Unit,
) {
    shareResolveScope.launch {
        val (images, files) = withContext(Dispatchers.IO) {
            val outImages = ArrayList<PickedImage>()
            val outFiles = ArrayList<PickedFile>()
            cleanStaleSharedCache(app)
            for (raw in uris) {
                val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: continue
                val mime = app.contentResolver.getType(uri)?.takeIf { it.isNotBlank() }
                    ?: fallbackMime.takeIf { it.isNotBlank() && it != "*/*" }
                    ?: "application/octet-stream"
                if (mime.startsWith("image/", ignoreCase = true) && !mime.equals("image/gif", ignoreCase = true)) {
                    val img = runCatching { decodeSharedImage(app, uri, SHARE_IMAGE_MAX_SIDE, SHARE_IMAGE_MAX_BYTES) }.getOrNull()
                    if (img != null) {
                        outImages += img
                        continue
                    }
                }
                val file = runCatching { describeSharedFile(app, uri, mime) }.getOrNull()
                if (file != null) outFiles += file
            }
            outImages to outFiles
        }
        onReady(images, files)
    }
}

private fun cleanStaleSharedCache(app: Context) {
    runCatching {
        val dir = File(app.cacheDir, "shared")
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - 24L * 3600_000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}

private fun decodeSharedImage(
    app: Context,
    uri: Uri,
    maxSide: Int,
    maxBytes: Int,
): PickedImage {
    val origSide = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        max(bounds.outWidth, bounds.outHeight)
    }.getOrDefault(0)

    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
            val side = max(info.size.width, info.size.height)
            if (side > maxSide) {
                val s = maxSide.toFloat() / side
                decoder.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        var sample = 1
        while (origSide / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("cannot decode image")
        val exifDeg = runCatching {
            app.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (exifDeg != 0f) {
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(exifDeg) }, true)
            if (rotated !== raw) runCatching { raw.recycle() }
            rotated
        } else {
            raw
        }
    }

    val scale = maxSide.toFloat() / max(bitmap.width, bitmap.height)
    val sized = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        ).also { if (it !== bitmap) runCatching { bitmap.recycle() } }
    } else {
        bitmap
    }

    var quality = if (maxSide > SHARE_IMAGE_MAX_SIDE) 92 else 85
    var jpeg: ByteArray
    do {
        jpeg = ByteArrayOutputStream().use { out ->
            sized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        quality -= 10
    } while (jpeg.size > maxBytes && quality > 30)

    val thumbScale = SHARE_THUMB_SIDE.toFloat() / max(sized.width, sized.height)
    val thumbBitmap = Bitmap.createScaledBitmap(
        sized,
        (sized.width * thumbScale).roundToInt().coerceAtLeast(1),
        (sized.height * thumbScale).roundToInt().coerceAtLeast(1),
        true,
    )
    val thumb = ByteArrayOutputStream().use { out ->
        thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 50, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
    if (thumbBitmap !== sized) runCatching { thumbBitmap.recycle() }

    val w = sized.width
    val h = sized.height
    val bigger = maxSide == SHARE_IMAGE_MAX_SIDE && origSide > SHARE_IMAGE_MAX_SIDE * 11 / 10
    val hdSourceUri: Uri? = if (bigger) cacheSharedStream(app, uri, "hd.jpg")?.let(Uri::fromFile) ?: uri else null
    runCatching { sized.recycle() }

    return PickedImage(
        jpeg = jpeg,
        width = w,
        height = h,
        thumb = thumb,
        hd = if (bigger && hdSourceUri != null) {
            { withContext(Dispatchers.IO) { decodeSharedImage(app, hdSourceUri, SHARE_HD_MAX_SIDE, SHARE_HD_MAX_BYTES) } }
        } else {
            null
        },
    )
}

private fun cacheSharedStream(app: Context, uri: Uri, nameHint: String): File? {
    if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.exists() }
    return runCatching {
        val dir = File(app.cacheDir, "shared").apply { mkdirs() }
        val safe = nameHint.ifBlank { "shared" }.replace(Regex("[\\\\/:*?\"<>|]"), "_").takeLast(64)
        val out = File(dir, "${System.currentTimeMillis()}_${System.nanoTime() and 0xFFFF}_$safe")
        var copied = 0L
        app.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    copied += n
                    if (copied > SHARE_CACHE_MAX_BYTES) {
                        out.delete()
                        return null
                    }
                    output.write(buf, 0, n)
                }
            }
        } ?: run {
            out.delete()
            return null
        }
        if (copied > 0L) out else { out.delete(); null }
    }.getOrNull()
}

private fun describeSharedFile(app: Context, uri: Uri, mimeHint: String): PickedFile? {
    val resolver = app.contentResolver
    var name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "file"
    var size = 0L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    val cached = cacheSharedStream(app, uri, name)
    if (size <= 0L && cached != null) size = cached.length()
    val resolvedUri = cached?.let(Uri::fromFile) ?: uri
    val location = resolvedUri.toString()
    val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: mimeHint.ifBlank { "application/octet-stream" }
    val isVideo = mime.startsWith("video/", ignoreCase = true)
    if (!isVideo) {
        return PickedFile(
            kind = AttachmentKind.FILE,
            location = location,
            name = name,
            mime = mime,
            size = size,
        )
    }

    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(app, resolvedUri)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) w = h.also { h = w }
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        val thumb = frame?.let { f ->
            try {
                val side = 320
                val scale = side.toFloat() / max(f.width, f.height)
                val small = if (scale < 1f) {
                    Bitmap.createScaledBitmap(f, (f.width * scale).toInt().coerceAtLeast(1), (f.height * scale).toInt().coerceAtLeast(1), true)
                } else {
                    f
                }
                val out = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 60, out)
                if (small !== f) runCatching { small.recycle() }
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } finally {
                runCatching { f.recycle() }
            }
        }.orEmpty()
        PickedFile(
            kind = AttachmentKind.VIDEO,
            location = location,
            name = name,
            mime = if (mime.startsWith("video/", ignoreCase = true)) mime else "video/mp4",
            size = size,
            durationMs = duration,
            width = w,
            height = h,
            thumb = thumb,
        )
    } catch (_: Exception) {
        PickedFile(
            kind = AttachmentKind.VIDEO,
            location = location,
            name = name,
            mime = if (mime.startsWith("video/", ignoreCase = true)) mime else "video/mp4",
            size = size,
        )
    } finally {
        runCatching { retriever.release() }
    }
}
