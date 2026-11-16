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
    /** The ringing channel, with the ringtone the person chose (see QeyfSounds). */
    fun ringChannelId(context: Context): String = com.qmusic.ui.chat.QeyfSounds.ringChannelId(context)
    const val MISSED_CHANNEL_ID = MISSED_CHANNEL

    /** The ringing and ongoing channels exist (group calls ring through the same one). */
    fun ensureChannels(context: Context) = channels(context)

    fun ensureMissedChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(MISSED_CHANNEL, if (tr(context)) "Cevapsız aramalar" else "Missed calls", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
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

    private const val PREFS = "qmusic_calls"
    const val EXTRA_CALLBACK_UID = "callback_uid"
    const val EXTRA_CALLBACK_NAME = "callback_name"
    const val EXTRA_CALLBACK_USERNAME = "callback_username"
    const val EXTRA_CALLBACK_PHOTO = "callback_photo"
    const val EXTRA_CALLBACK_VIDEO = "callback_video"

    /**
     * Rings: full screen over the lock screen, Qeyf's ringtone, Answer and Decline, the caller's picture. What rings is
     * remembered, so that if the caller gives up before the app has taken the call over it can still say "missed".
     */
    fun showIncoming(context: Context, peer: ChatUser, video: Boolean, callId: String = "") {
        // The photo not here yet: it rings with the initial now, and the notification gets the photo once it is in.
        if (!NotificationAvatars.has(peer.photo)) {
            NotificationAvatars.prefetch(peer.photo) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (prefs.getString("ring_id", null) == callId) showIncoming(context, peer, video, callId)
            }
        }
        channels(context)
        // A new call: whatever was left of the last ringing notification goes first, so this one rings afresh (an
        // update of the old one would come silently: it only alerts once). The same call again (its photo came in)
        // just updates it, quietly.
        val previous = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ring_id", null)
        if (previous != callId) runCatching { context.getSystemService(NotificationManager::class.java).cancel(RING_ID) }
        val tr = tr(context)
        val icon = NotificationAvatars.icon(peer.uid, peer.name, peer.photo)
        val caller = Person.Builder().setName(peer.name).setKey(peer.uid).setIcon(icon).setImportant(true).build()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ring_id", callId)
            .putString("ring_uid", peer.uid)
            .putString("ring_name", peer.name)
            .putString("ring_username", peer.username)
            .putString("ring_photo", peer.photo.orEmpty())
            .putBoolean("ring_video", video)
            .apply()
        val manager = context.getSystemService(NotificationManager::class.java)
        val text = if (video) (if (tr) "Görüntülü arama" else "Video call") else (if (tr) "Sesli arama" else "Voice call")
        // The person's own ringtone, if they have one (each ringtone has its own channel).
        val channel = com.qmusic.ui.chat.QeyfSounds.ringChannelId(context, peer.uid)
        fun base() = NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
            .setLargeIcon(NotificationAvatars.bitmap(peer.uid, peer.name, peer.photo))
            .setContentTitle(peer.name)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // Putting the photo in later does not start the ringing over.
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, EXTRA_OPEN, 14))
            .setTimeoutAfter(50_000)
        // Android 12+ refuses a call-style notification that may not open full screen (and is not a foreground
        // service): it is then not shown at all. Without that permission, or if it is refused anyway, the ringing
        // call comes as an ordinary notification with Decline and Answer.
        val fullScreen = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()
        val callStyle = if (!fullScreen) null else runCatching {
            base()
                .setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(
                        caller,
                        action(context, CallActionReceiver.DECLINE, 11),
                        openApp(context, EXTRA_ACCEPT, 12),
                    ).setIsVideo(video),
                )
                .setFullScreenIntent(openApp(context, EXTRA_OPEN, 13), true)
                .build()
        }.getOrNull()
        val plain = {
            base()
                .addAction(0, if (tr) "Reddet" else "Decline", action(context, CallActionReceiver.DECLINE, 11))
                .addAction(0, if (tr) "Cevapla" else "Answer", openApp(context, EXTRA_ACCEPT, 12))
                .apply { if (fullScreen) setFullScreenIntent(openApp(context, EXTRA_OPEN, 13), true) }
                .build()
        }
        fun post(n: Notification): Boolean {
            // Rings until it is answered, declined or cancelled (not just once).
            n.flags = n.flags or Notification.FLAG_INSISTENT
            return runCatching { manager.notify(RING_ID, n) }
                .onFailure { android.util.Log.w("QeyfCall", "ring notification refused: ${it.message}") }
                .isSuccess
        }
        if (callStyle == null || !post(callStyle)) post(plain())
    }

    /** The caller gave up (the push said so): if this phone was still ringing for that call, it is a missed call. */
    fun missedFromPush(context: Context, callId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ringing = prefs.getString("ring_id", null)
        val uid = prefs.getString("ring_uid", null)
        cancelIncoming(context)
        if (ringing == null || uid == null || (callId.isNotEmpty() && ringing.isNotEmpty() && ringing != callId)) return
        val peer = ChatUser(uid, prefs.getString("ring_name", null).orEmpty(), prefs.getString("ring_username", null).orEmpty(), prefs.getString("ring_photo", null)?.ifBlank { null })
        val video = prefs.getBoolean("ring_video", false)
        if (!com.qmusic.ui.chat.CallHooks.appVisible) showMissed(context, peer, video)
        // In the list of calls too (the app was not running to write it).
        com.qmusic.ui.chat.ChatCallActions.logMissed(context, peer, video, ringing.ifBlank { "push" + System.currentTimeMillis() })
    }

    /** Calls back [peer] from a notification: the app opens and dials. */
    private fun callBack(context: Context, peer: ChatUser, video: Boolean, code: Int): PendingIntent = PendingIntent.getActivity(
        context,
        code,
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_CALLBACK_UID, peer.uid)
            .putExtra(EXTRA_CALLBACK_NAME, peer.name)
            .putExtra(EXTRA_CALLBACK_USERNAME, peer.username)
            .putExtra(EXTRA_CALLBACK_PHOTO, peer.photo.orEmpty())
            .putExtra(EXTRA_CALLBACK_VIDEO, video)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * "Missed call from X" (or, with [busy], "X called while you were on another call"): their picture, and Call back
     * and Message.
     */
    fun showMissed(context: Context, peer: ChatUser, video: Boolean, busy: Boolean = false) {
        ensureMissedChannel(context)
        val tr = tr(context)
        val kind = if (video) (if (tr) "Görüntülü arama" else "Video call") else (if (tr) "Sesli arama" else "Voice call")
        val id = ("missed" + peer.uid).hashCode()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(
                id,
                NotificationCompat.Builder(context, MISSED_CHANNEL)
                    .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                    .setLargeIcon(NotificationAvatars.bitmap(peer.uid, peer.name, peer.photo))
                    .setContentTitle(if (busy) (if (tr) "${peer.name} aradı" else "${peer.name} called") else (if (tr) "Cevapsız arama" else "Missed call"))
                    .setContentText(
                        if (busy) (if (tr) "Sen başka bir aramadayken · $kind" else "While you were on another call · $kind")
                        else "${peer.name} · $kind",
                    )
                    .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                    .setAutoCancel(true)
                    .setContentIntent(ChatPush.openChat(context, peer))
                    .addAction(android.R.drawable.ic_menu_call, if (tr) "Geri ara" else "Call back", callBack(context, peer, video, id + 1))
                    .addAction(android.R.drawable.ic_menu_send, if (tr) "Mesaj" else "Message", ChatPush.openChat(context, peer))
                    .build(),
            )
        }
    }

    /** Takes the "Call back" of a missed call: the app dials (the call screen asks for the microphone first). */
    fun callBackIfAsked(context: Context, intent: Intent?) {
        val uid = intent?.getStringExtra(EXTRA_CALLBACK_UID) ?: return
        val peer = ChatUser(
            uid,
            intent.getStringExtra(EXTRA_CALLBACK_NAME).orEmpty(),
            intent.getStringExtra(EXTRA_CALLBACK_USERNAME).orEmpty(),
            intent.getStringExtra(EXTRA_CALLBACK_PHOTO)?.ifBlank { null },
        )
        val video = intent.getBooleanExtra(EXTRA_CALLBACK_VIDEO, false)
        intent.removeExtra(EXTRA_CALLBACK_UID)
        context.getSystemService(NotificationManager::class.java).cancel(("missed" + uid).hashCode())
        com.qmusic.ui.chat.ChatCallActions.wake(context)
        com.qmusic.ui.chat.CallLaunch.callBack.value = peer to video
    }

    fun cancelIncoming(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
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
        // Started a moment after the call already ended (a call cancelled within a second or two): Android still
        // needs it to show itself once, and then it goes at once, leaving nothing behind.
        val live = com.qmusic.ui.chat.CallHooks.state?.value?.let { it.phase != com.qmusic.ui.chat.CallPhase.ENDED } == true
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
        if (!started || !live) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Its "call going on" notification never outlives it.
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { getSystemService(android.app.NotificationManager::class.java).cancel(CallNotifications.ONGOING_ID) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO = "video"
        const val EXTRA_NAME = "name"
    }
}
