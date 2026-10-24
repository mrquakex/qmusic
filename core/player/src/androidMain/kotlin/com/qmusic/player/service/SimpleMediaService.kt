package com.qmusic.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.qmusic.player.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.qmusic.player.PlayerController
import com.qmusic.player.PlayerHolder
import com.qmusic.player.adapter.CrossfadeExoPlayerAdapter

/**
 * Foreground MediaLibraryService managing the MediaSession, notification controls,
 * and audio playback lifecycle for QMusic.
 */
class SimpleMediaService : MediaLibraryService() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "qmusic_playback_channel"
        const val NOTIFICATION_CHANNEL_NAME = "QMusic Audio Playback"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var currentController: PlayerController? = null
            private set
    }

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val scope = MainScope()
    private lateinit var playerAdapter: CrossfadeExoPlayerAdapter
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): SimpleMediaService = this@SimpleMediaService
        fun getPlayerController(): PlayerController = playerAdapter
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        playerAdapter = PlayerHolder.get(applicationContext)
        currentController = playerAdapter

        val sessionActivityIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val likeCommand = SessionCommand(MediaActions.LIKE, Bundle.EMPTY)
        val callback = object : MediaLibrarySession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(likeCommand).build(),
                    )
                    .build()

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == MediaActions.LIKE) MediaActions.onToggleLike?.invoke()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

        val builder = MediaLibrarySession.Builder(this, playerAdapter.forwardingPlayer, callback)
            .setId("QMusicMediaLibrarySession")

        if (sessionActivityIntent != null) {
            builder.setSessionActivity(sessionActivityIntent)
        }

        val session = builder.build()
        // Android 13+ draws the controls itself and puts the first custom button (the heart) in the "previous"
        // place, on the left, whenever it thinks there is no previous. Keeping both places for previous / next
        // leaves the heart where music apps have it: on the right.
        session.setSessionExtras(
            Bundle().apply {
                putBoolean(androidx.media3.session.MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, true)
                putBoolean(androidx.media3.session.MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, true)
            },
        )
        mediaLibrarySession = session
        // Nothing ever binds to this service as a media controller, so Media3 would never learn about the
        // session and never post the playback notification (or the lock-screen / headset controls).
        addSession(session)

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()
            .apply { setSmallIcon(R.drawable.ic_qmusic_notification) }

        setMediaNotificationProvider(notificationProvider)

        // The heart next to the playback buttons follows whether the playing song is liked.
        scope.launch {
            MediaActions.liked.collect { liked ->
                session.setCustomLayout(
                    ImmutableList.of(
                        CommandButton.Builder()
                            .setDisplayName(if (liked) "Unlike" else "Like")
                            .setIconResId(if (liked) R.drawable.ic_notification_heart_filled else R.drawable.ic_notification_heart)
                            .setSessionCommand(likeCommand)
                            .build(),
                    ),
                )
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val player = mediaLibrarySession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        currentController = null
        // Only the session is released: the shared player (PlayerHolder) outlives the service
        // so in-app playback state survives a service stop.
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "QMusic foreground playback controls and track metadata"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
