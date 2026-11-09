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
        intent.removeExtra(EXTRA_UID)
        ChatLaunch.request.value = ChatLaunchRequest(user)
    }
}
