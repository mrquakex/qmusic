package com.qmusic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.qmusic.ui.videos.VideoBackground
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the app alive while a QVideo video plays with the screen off or the app left, exposing a Media3
 * [MediaSession] + [MediaStyleNotificationHelper.MediaStyle] notification so lockscreen scrub bar, artwork,
 * and Bluetooth/headset media buttons control [VideoBackground].
 */
@OptIn(UnstableApi::class)
class VideoKeepAliveService : Service() {
    private var title: String = ""
    private val scope = MainScope()
    private var mediaSession: MediaSession? = null
    private var sessionExo: ExoPlayer? = null
    private var forwardingPlayer: VideoSessionPlayer? = null
    private var artworkUrlLoaded: String? = null
    private var artworkBitmap: Bitmap? = null
    private var artworkJob: Job? = null
    private var stateListener: (() -> Unit)? = null
    private var startedForeground = false

    override fun onCreate() {
        super.onCreate()
        val listener: () -> Unit = {
            scope.launch { onVideoStateChanged() }
        }
        stateListener = listener
        VideoBackground.onStateChanged = listener
        ensureSession()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REWIND -> VideoBackground.onSeekBy?.invoke(-10_000L)
            ACTION_TOGGLE -> VideoBackground.toggle?.invoke()
            ACTION_FORWARD -> VideoBackground.onSeekBy?.invoke(10_000L)
            ACTION_NEXT -> VideoBackground.onNext?.invoke()
        }
        intent?.getStringExtra(EXTRA_TITLE)?.let { title = it }
        ensureSession()
        maybeLoadArtwork()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
            startedForeground = true
        }.onFailure {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun onVideoStateChanged() {
        ensureSession()
        maybeLoadArtwork()
        if (startedForeground) refreshNotification()
    }

    private fun ensureSession() {
        val exo = VideoBackground.player
        if (exo == null) {
            releaseSession()
            return
        }
        if (sessionExo === exo && mediaSession != null) {
            forwardingPlayer?.notifyStateChanged()
            return
        }
        releaseSession()
        val fp = VideoSessionPlayer(exo) { title }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val built = runCatching {
            MediaSession.Builder(this, fp)
                .setId("NuveloVideoSession")
                .setSessionActivity(open)
                .build()
        }.getOrNull()
        if (built != null) {
            sessionExo = exo
            forwardingPlayer = fp
            mediaSession = built
        }
    }

    private fun releaseSession() {
        runCatching { mediaSession?.release() }
        mediaSession = null
        forwardingPlayer = null
        sessionExo = null
    }

    private fun maybeLoadArtwork() {
        val url = VideoBackground.artworkUrl?.takeIf { it.isNotBlank() }
        if (url == artworkUrlLoaded) return
        artworkUrlLoaded = url
        artworkBitmap = null
        artworkJob?.cancel()
        if (url == null) return
        val app = applicationContext
        artworkJob = scope.launch {
            val request = ImageRequest.Builder(app).data(url).size(512).allowHardware(false).build()
            val bmp = runCatching {
                (SingletonImageLoader.get(app).execute(request) as? SuccessResult)?.image?.toBitmap()
            }.getOrNull()
            if (artworkUrlLoaded == url && bmp != null) {
                artworkBitmap = bmp
                if (startedForeground) refreshNotification()
            }
        }
    }

    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification()) }
    }

    private fun serviceAction(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, VideoKeepAliveService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Video", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val tr = getSharedPreferences("qmusic_settings", MODE_PRIVATE).getString("language", "tr") != "en"
        val playing = VideoBackground.isPlaying.value
        val displayTitle = (VideoBackground.title ?: title).ifBlank { "Video" }
        val displayArtist = VideoBackground.artist?.takeIf { it.isNotBlank() } ?: "Video"
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
            .setContentTitle(displayTitle)
            .setContentText(displayArtist)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_rew, "-10s", serviceAction(ACTION_REWIND, 2))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) (if (tr) "Duraklat" else "Pause") else (if (tr) "Oynat" else "Play"),
                serviceAction(ACTION_TOGGLE, 1),
            )
            .addAction(android.R.drawable.ic_media_ff, "+10s", serviceAction(ACTION_FORWARD, 3))
        if (VideoBackground.onNext != null) {
            builder.addAction(
                android.R.drawable.ic_media_next,
                if (tr) "Sonraki" else "Next",
                serviceAction(ACTION_NEXT, 4),
            )
        }
        artworkBitmap?.let(builder::setLargeIcon)
        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        }
        return builder.build()
    }

    override fun onDestroy() {
        if (VideoBackground.onStateChanged === stateListener) VideoBackground.onStateChanged = null
        startedForeground = false
        artworkJob?.cancel()
        scope.cancel()
        releaseSession()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "qvideo_playback"
        private const val NOTIFICATION_ID = 4107
        const val ACTION_REWIND = "com.qmusic.app.VIDEO_REWIND"
        const val ACTION_TOGGLE = "com.qmusic.app.VIDEO_TOGGLE"
        const val ACTION_FORWARD = "com.qmusic.app.VIDEO_FORWARD"
        const val ACTION_NEXT = "com.qmusic.app.VIDEO_NEXT"
        const val EXTRA_TITLE = "title"
    }
}

@UnstableApi
private class VideoSessionPlayer(
    exo: ExoPlayer,
    private val fallbackTitle: () -> String,
) : ForwardingPlayer(exo) {
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        runCatching { super.addListener(listener) }
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        runCatching { super.removeListener(listener) }
    }

    fun notifyStateChanged() {
        val meta = runCatching { mediaMetadata }.getOrNull() ?: return
        val cmds = runCatching { availableCommands }.getOrNull() ?: return
        val playingNow = runCatching { isPlaying }.getOrDefault(false)
        val events = Player.Events(
            FlagSet.Builder()
                .addAll(
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                )
                .build(),
        )
        for (listener in listeners) {
            runCatching {
                listener.onMediaMetadataChanged(meta)
                listener.onAvailableCommandsChanged(cmds)
                listener.onIsPlayingChanged(playingNow)
                listener.onEvents(this, events)
            }
        }
    }

    override fun getAvailableCommands(): Player.Commands {
        val builder = super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
            )
            .removeAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        if (VideoBackground.onNext != null) {
            builder.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            builder.removeAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun play() {
        if (!playWhenReady || playbackState == Player.STATE_ENDED) {
            VideoBackground.toggle?.invoke() ?: super.play()
        } else {
            super.play()
        }
    }

    override fun pause() {
        super.pause()
    }

    override fun getSeekBackIncrement(): Long = 10_000L

    override fun getSeekForwardIncrement(): Long = 10_000L

    override fun seekBack() {
        VideoBackground.onSeekBy?.invoke(-10_000L) ?: seekTo((currentPosition - 10_000L).coerceAtLeast(0L))
    }

    override fun seekForward() {
        VideoBackground.onSeekBy?.invoke(10_000L) ?: seekTo((currentPosition + 10_000L).coerceAtLeast(0L))
    }

    override fun hasNextMediaItem(): Boolean = VideoBackground.onNext != null

    override fun seekToNext() {
        VideoBackground.onNext?.invoke()
    }

    override fun seekToNextMediaItem() {
        VideoBackground.onNext?.invoke()
    }

    override fun getMediaMetadata(): MediaMetadata {
        val builder = super.getMediaMetadata().buildUpon()
        val displayTitle = VideoBackground.title?.takeIf { it.isNotBlank() } ?: fallbackTitle().ifBlank { "Video" }
        builder.setTitle(displayTitle)
        builder.setDisplayTitle(displayTitle)
        VideoBackground.artist?.takeIf { it.isNotBlank() }?.let { artist ->
            builder.setArtist(artist)
            builder.setSubtitle(artist)
        }
        VideoBackground.artworkUrl?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()?.let(builder::setArtworkUri)
        }
        return builder.build()
    }
}
