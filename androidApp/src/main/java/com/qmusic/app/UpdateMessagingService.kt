package com.qmusic.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives the "new version" push that GitHub Actions sends when a release is published, and turns it into a
 * notification. The message carries only the version; the person taps it to land on the update button in Settings.
 */
class UpdateMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "update") return
        val version = message.data["version"] ?: return
        val summary = message.data["body"] ?: message.notification?.body
        UpdatePush.notify(applicationContext, version, summary)
    }
}

object UpdatePush {
    const val EXTRA_OPEN_UPDATES = "open_updates"
    private const val TOPIC = "releases"
    private const val CHANNEL_ID = "qmusic_updates"
    private const val NOTIFICATION_ID = 1003

    /** Joins the "releases" topic; does nothing when the app was built without the Firebase config file. */
    fun subscribe(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
    }

    fun notify(context: Context, version: String, summary: String? = null) {
        val prefs = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("update_notifications", true)) return
        if (!isNewer(version, installedVersion(context))) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_UPDATES, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bodyText = summary?.takeIf { it.isNotBlank() } ?: context.getString(R.string.update_ready_text)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                .setContentTitle(context.getString(R.string.update_ready_title, version))
                .setContentText(bodyText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    private fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** True when [candidate] (like "1.0.8") is higher than [installed]; compares number by number. */
    internal fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = installed.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
