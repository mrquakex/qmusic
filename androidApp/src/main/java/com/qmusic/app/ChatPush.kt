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
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.qmusic.ui.chat.ChatLaunch
import com.qmusic.ui.chat.ChatLaunchRequest
import com.qmusic.ui.chat.ChatPresence
import com.qmusic.ui.chat.ChatUser

/**
 * "New message" notifications. The server function sends a data message (type "chat") with the sender and the text;
 * it is shown unless that very conversation is open on screen. Tapping it opens the conversation.
 */
object ChatPush {
    private const val CHANNEL_ID = "qmusic_messages"
    private const val EXTRA_UID = "chat_uid"
    private const val EXTRA_NAME = "chat_name"
    private const val EXTRA_USERNAME = "chat_username"
    private const val EXTRA_PHOTO = "chat_photo"
    private const val EXTRA_PROFILE = "chat_profile"
    private const val FRIEND_CHANNEL = "qmusic_friends"

    /** "X wants to be your friend" / "X accepted": tapping it opens their profile. */
    fun onFriend(context: Context, data: Map<String, String>) {
        val from = data["from"] ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val tr = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(FRIEND_CHANNEL, if (tr) "Arkadaşlık" else "Friends", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val name = data["name"].orEmpty().ifBlank { "QMusic" }
        val accepted = data["action"] == "accepted"
        val text = when {
            accepted && tr -> "$name arkadaşlık isteğini kabul etti"
            accepted -> "$name accepted your friend request"
            tr -> "$name seni arkadaş olarak eklemek istiyor"
            else -> "$name wants to be your friend"
        }
        val open = PendingIntent.getActivity(
            context,
            ("friend$from").hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_UID, from)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_USERNAME, data["username"].orEmpty())
                .putExtra(EXTRA_PHOTO, data["photo"].orEmpty())
                .putExtra(EXTRA_PROFILE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            ("friend$from").hashCode(),
            NotificationCompat.Builder(context, FRIEND_CHANNEL)
                .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                .setContentTitle(if (accepted) (if (tr) "Yeni arkadaş" else "New friend") else (if (tr) "Arkadaşlık isteği" else "Friend request"))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    /** A call's push: the app wakes and hears the call itself (and rings); a cancelled call stops ringing. */
    fun onCall(context: Context, data: Map<String, String>) {
        if (data["action"] == "cancel") {
            CallNotifications.cancelIncoming(context)
            return
        }
        // Rings at once from what the push carries; the app, woken, takes the call over from there.
        val from = data["from"]
        if (from != null && !com.qmusic.ui.chat.CallHooks.appVisible) {
            CallNotifications.showIncoming(
                context,
                ChatUser(from, data["name"].orEmpty().ifBlank { "QMusic" }, data["username"].orEmpty(), data["photo"]?.ifBlank { null }),
                data["video"] == "1",
            )
        }
        com.qmusic.ui.chat.ChatCallActions.wake(context)
    }

    fun notify(context: Context, data: Map<String, String>) {
        val chatId = data["chatId"] ?: return
        val from = data["from"] ?: return
        if (ChatPresence.openChatId == chatId) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val tr = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, if (tr) "Mesajlar" else "Messages", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val name = data["name"].orEmpty().ifBlank { "QMusic" }
        val body = data["body"].orEmpty()
        val open = PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_UID, from)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_USERNAME, data["username"].orEmpty())
                .putExtra(EXTRA_PHOTO, data["photo"].orEmpty())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sender = Person.Builder().setName(name).setKey(from).build()
        manager.notify(
            chatId.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                .setStyle(
                    NotificationCompat.MessagingStyle(Person.Builder().setName(if (tr) "Sen" else "You").build())
                        .addMessage(body, System.currentTimeMillis(), sender),
                )
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    /** A tapped message notification: the app opens that conversation. */
    fun openIfAsked(intent: Intent?) {
        val uid = intent?.getStringExtra(EXTRA_UID) ?: return
        val user = ChatUser(
            uid = uid,
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
            photo = intent.getStringExtra(EXTRA_PHOTO)?.ifBlank { null },
        )
        val profile = intent.getBooleanExtra(EXTRA_PROFILE, false)
        intent.removeExtra(EXTRA_UID)
        intent.removeExtra(EXTRA_PROFILE)
        ChatLaunch.request.value = ChatLaunchRequest(user, profile)
    }
}
