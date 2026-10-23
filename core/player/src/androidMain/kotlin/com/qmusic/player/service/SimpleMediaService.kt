package com.qmusic.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
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

        val callback = object : MediaLibrarySession.Callback {}

        val builder = MediaLibrarySession.Builder(this, playerAdapter.forwardingPlayer, callback)
            .setId("QMusicMediaLibrarySession")

        if (sessionActivityIntent != null) {
            builder.setSessionActivity(sessionActivityIntent)
        }

        val session = builder.build()
        mediaLibrarySession = session
        // Nothing ever binds to this service as a media controller, so Media3 would never learn about the
        // session and never post the playback notification (or the lock-screen / headset controls).
        addSession(session)

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()

        setMediaNotificationProvider(notificationProvider)
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
