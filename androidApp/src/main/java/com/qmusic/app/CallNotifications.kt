package com.qmusic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import com.qmusic.ui.chat.ChatCallActions
import com.qmusic.ui.chat.ChatUser

/**
 * The call notifications: the ringing one (full screen over the lock screen, like a phone call) and the one that keeps
 * the app alive while a call goes on.
 */
object CallNotifications {
    private const val RING_CHANNEL = "qmusic_calls_ring"
    private const val ONGOING_CHANNEL = "qmusic_calls_ongoing"
    private const val MISSED_CHANNEL = "qmusic_calls_missed"
    const val RING_ID = 2001
    const val ONGOING_ID = 2002
    const val EXTRA_ACCEPT = "call_accept"
    const val EXTRA_OPEN = "call_open"

    private fun tr(context: Context) =
        context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"

    private fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val tr = tr(context)
        manager.createNotificationChannel(
            NotificationChannel(RING_CHANNEL, if (tr) "Gelen aramalar" else "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, if (tr) "Süren arama" else "Ongoing call", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun openApp(context: Context, extra: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context,
        code,
        Intent(context, MainActivity::class.java).putExtra(extra, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        code,
        Intent(context, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showIncoming(context: Context, peer: ChatUser, video: Boolean) {
        channels(context)
        val tr = tr(context)
        val caller = Person.Builder().setName(peer.name).setKey(peer.uid).setImportant(true).build()
        val notification = NotificationCompat.Builder(context, RING_CHANNEL)
            .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
            .setContentTitle(peer.name)
            .setContentText(if (video) (if (tr) "Görüntülü arama" else "Video call") else (if (tr) "Sesli arama" else "Voice call"))
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    action(context, CallActionReceiver.DECLINE, 11),
                    openApp(context, EXTRA_ACCEPT, 12),
                ).setIsVideo(video),
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(openApp(context, EXTRA_OPEN, 13), true)
            .setContentIntent(openApp(context, EXTRA_OPEN, 14))
            .setTimeoutAfter(50_000)
            .build()
        // Rings until it is answered, declined or cancelled (not just once).
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        runCatching { context.getSystemService(NotificationManager::class.java).notify(RING_ID, notification) }
    }

    /** "Missed call from X": tapping it opens the conversation, with the call button there. */
    fun showMissed(context: Context, peer: ChatUser, video: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(MISSED_CHANNEL, if (tr(context)) "Cevapsız aramalar" else "Missed calls", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val tr = tr(context)
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(
                ("missed" + peer.uid).hashCode(),
                NotificationCompat.Builder(context, MISSED_CHANNEL)
                    .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                    .setContentTitle(if (tr) "Cevapsız arama" else "Missed call")
                    .setContentText(
                        peer.name + " · " + if (video) (if (tr) "Görüntülü arama" else "Video call") else (if (tr) "Sesli arama" else "Voice call"),
                    )
                    .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                    .setAutoCancel(true)
                    .setContentIntent(ChatPush.openChat(context, peer))
                    .build(),
            )
        }
    }

    fun cancelIncoming(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(RING_ID)
    }

    fun ongoing(context: Context, peer: ChatUser?, video: Boolean): Notification {
        channels(context)
        val tr = tr(context)
        return NotificationCompat.Builder(context, ONGOING_CHANNEL)
            .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
            .setContentTitle(peer?.name ?: "Qeyf")
            .setContentText(if (tr) "Arama sürüyor" else "Call in progress")
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    Person.Builder().setName(peer?.name ?: "Qeyf").build(),
                    action(context, CallActionReceiver.HANG_UP, 15),
                ).setIsVideo(video),
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openApp(context, EXTRA_OPEN, 16))
            .build()
    }
}

/** Decline and hang up, from the notifications. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DECLINE -> {
                CallNotifications.cancelIncoming(context)
                ChatCallActions.decline(context)
            }
            HANG_UP -> ChatCallActions.hangUp(context)
        }
    }

    companion object {
        const val DECLINE = "com.qmusic.app.CALL_DECLINE"
        const val HANG_UP = "com.qmusic.app.CALL_HANG_UP"
    }
}

/** Keeps the microphone (and the camera) working while a call goes on with the screen off or another app open. */
class CallKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val peer = intent?.getStringExtra(EXTRA_NAME)?.let { ChatUser(uid = "", name = it, username = "") }
        val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        } else {
            0
        }
        // First as a phone call (allowed to start and keep the microphone in the background, the call being the phone's
        // own through Core-Telecom), then as microphone / camera only, then as a plain service: whatever this phone takes.
        val tries = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or media)
            add(media)
            add(0)
        }.distinct()
        val notification = CallNotifications.ongoing(this, peer, video)
        val started = tries.any { type -> runCatching { ServiceCompat.startForeground(this, CallNotifications.ONGOING_ID, notification, type) }.isSuccess }
        if (!started) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_VIDEO = "video"
        const val EXTRA_NAME = "name"
    }
}
