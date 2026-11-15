package com.qmusic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Holds the app alive while QVideo downloads videos, with a notification that shows how far they are. The downloads
 * themselves run in the app; this only keeps the process from being stopped when the person leaves.
 */
class DownloadKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val percent = intent?.getIntExtra(EXTRA_PERCENT, 0) ?: 0
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(count, percent, title), type)
        return START_NOT_STICKY
    }

    private fun notification(count: Int, percent: Int, title: String): Notification {
        val english = getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") == "en"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, if (english) "QVideo downloads" else "QVideo indirmeleri", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val heading = when {
            english && count > 1 -> "Downloading $count videos"
            english -> "Downloading a video"
            count > 1 -> "$count video indiriliyor"
            else -> "Video indiriliyor"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
            .setContentTitle(heading)
            .setContentText(title.ifBlank { "QVideo" })
            .setProgress(100, percent, percent == 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "qvideo_downloads"
        private const val NOTIFICATION_ID = 4108
        const val EXTRA_COUNT = "count"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_TITLE = "title"
    }
}
