package com.qmusic.player.offline

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
import com.qmusic.player.PlayerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that exists only while songs are downloading: it keeps the process (and so the downloads)
 * alive when the app is left, and shows how many songs are still to come.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForegroundCompat(notification(remaining = 0))

        if (watcher?.isActive != true) {
            val downloads = PlayerHolder.downloads(applicationContext)
            watcher = scope.launch {
                downloads.progress.collect { progress ->
                    if (progress.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(NOTIFICATION_ID, notification(progress.size, progress.values.average().toFloat()))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Determines notification texts based on the in-app language preference saved in SharedPreferences
     * ("tr" by default or "en"). This keeps notification text synchronized with the in-app language setting
     * regardless of the system-wide device language.
     */
    private fun notification(remaining: Int, averageProgress: Float = 0f): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val prefs = getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE)
        val isEnglish = prefs.getString("language", "tr") == "en"

        val title = if (isEnglish) "Downloading music" else "Müzik indiriliyor"
        val content = if (isEnglish) {
            if (remaining > 0) "$remaining ${if (remaining == 1) "song" else "songs"} left" else "Starting…"
        } else {
            if (remaining > 0) "$remaining şarkı kaldı" else "Başlatılıyor…"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(100, (averageProgress * 100).toInt(), remaining == 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of songs being saved for offline listening"
                    setShowBadge(false)
                },
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "qmusic_downloads"
        const val NOTIFICATION_ID = 1002
    }
}
