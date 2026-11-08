package com.qmusic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.qmusic.ui.videos.VideoBackground

/**
 * Holds the app alive while a QVideo video plays with the screen off or the app left: a media notification with a
 * play / pause button. The player itself lives in the app; this only keeps the process from being stopped.
 */
class VideoKeepAliveService : Service() {
    private var title: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) VideoBackground.toggle?.invoke()
        intent?.getStringExtra(EXTRA_TITLE)?.let { title = it }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "QVideo", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, VideoKeepAliveService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
            .setContentTitle(title.ifBlank { "QVideo" })
            .setContentText("QVideo")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, "\u23EF", toggle)
            .build()
    }

    companion object {
        private const val CHANNEL = "qvideo_playback"
        private const val NOTIFICATION_ID = 4107
        const val ACTION_TOGGLE = "com.qmusic.app.VIDEO_TOGGLE"
        const val EXTRA_TITLE = "title"
    }
}
