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
import kotlinx.coroutines.launch
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
        val from = (data["sender"] ?: data["from"]) ?: return
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
        val name = data["name"].orEmpty().ifBlank { "Qeyf" }
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
        val from = (data["sender"] ?: data["from"])
        if (from != null && !com.qmusic.ui.chat.CallHooks.appVisible) {
            CallNotifications.showIncoming(
                context,
                ChatUser(from, data["name"].orEmpty().ifBlank { "Qeyf" }, data["username"].orEmpty(), data["photo"]?.ifBlank { null }),
                data["video"] == "1",
            )
        }
        com.qmusic.ui.chat.ChatCallActions.wake(context)
    }

    private const val KEY_REPLY = "chat_reply_text"
    const val ACTION_REPLY = "com.qmusic.app.CHAT_REPLY"
    const val ACTION_READ = "com.qmusic.app.CHAT_READ"
    private const val HISTORY = 8

    /** The last few lines of each conversation's notification (the newest at the end), mine included after a reply. */
    private val history = HashMap<String, ArrayDeque<Triple<String, Long, Boolean>>>()

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun tr(context: Context) = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"

    /** Opens the conversation with [peer] (a message or a missed call tapped). */
    fun openChat(context: Context, peer: ChatUser): PendingIntent = PendingIntent.getActivity(
        context,
        peer.uid.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_UID, peer.uid)
            .putExtra(EXTRA_NAME, peer.name)
            .putExtra(EXTRA_USERNAME, peer.username)
            .putExtra(EXTRA_PHOTO, peer.photo.orEmpty())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun notify(context: Context, data: Map<String, String>) {
        val chatId = data["chatId"] ?: return
        val from = (data["sender"] ?: data["from"]) ?: return
        // The sender sees two ticks as soon as the phone has it, whether it is shown or not.
        background.launch { runCatching { com.qmusic.ui.chat.ChatNotificationActions.delivered(context.applicationContext, chatId) } }
        if (ChatPresence.openChatId == chatId) return
        val peer = ChatUser(from, data["name"].orEmpty().ifBlank { "Qeyf" }, data["username"].orEmpty(), data["photo"]?.ifBlank { null })
        val lines = synchronized(history) {
            history.getOrPut(chatId) { ArrayDeque() }.also {
                it.addLast(Triple(data["body"].orEmpty(), System.currentTimeMillis(), false))
                while (it.size > HISTORY) it.removeFirst()
            }.toList()
        }
        show(context, chatId, peer, lines, alert = true)
    }

    /** After an answer from the notification: it stays, with the answer under their message, quietly. */
    fun replied(context: Context, chatId: String, peer: ChatUser, text: String) {
        val lines = synchronized(history) {
            history.getOrPut(chatId) { ArrayDeque() }.also {
                it.addLast(Triple(text, System.currentTimeMillis(), true))
                while (it.size > HISTORY) it.removeFirst()
            }.toList()
        }
        show(context, chatId, peer, lines, alert = false)
    }

    /** Read from the notification (or the conversation opened): it goes, and its lines are forgotten. */
    fun clear(context: Context, chatId: String) {
        synchronized(history) { history.remove(chatId) }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(chatId.hashCode())
    }

    private fun show(context: Context, chatId: String, peer: ChatUser, lines: List<Triple<String, Long, Boolean>>, alert: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val tr = tr(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // The messages channel plays the chosen Qeyf sound (see QeyfSounds).
        val channel = com.qmusic.ui.chat.QeyfSounds.messageChannelId(context)
        val sender = Person.Builder().setName(peer.name).setKey(peer.uid).build()
        val me = Person.Builder().setName(if (tr) "Sen" else "You").build()
        val style = NotificationCompat.MessagingStyle(me)
        lines.forEach { (text, at, mine) -> style.addMessage(text, at, if (mine) me else sender) }

        fun actionIntent(action: String, code: Int) = PendingIntent.getBroadcast(
            context,
            chatId.hashCode() + code,
            Intent(context, ChatReplyReceiver::class.java)
                .setAction(action)
                .putExtra(ChatReplyReceiver.EXTRA_CHAT, chatId)
                .putExtra(EXTRA_UID, peer.uid)
                .putExtra(EXTRA_NAME, peer.name)
                .putExtra(EXTRA_USERNAME, peer.username)
                .putExtra(EXTRA_PHOTO, peer.photo.orEmpty()),
            // The reply action's intent receives the typed text, so it must stay mutable.
            if (action == ACTION_REPLY) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reply = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            if (tr) "Yanıtla" else "Reply",
            actionIntent(ACTION_REPLY, 1),
        )
            .addRemoteInput(androidx.core.app.RemoteInput.Builder(KEY_REPLY).setLabel(if (tr) "Mesaj yaz" else "Message").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val read = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_view, if (tr) "Okundu" else "Mark read", actionIntent(ACTION_READ, 2))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        runCatching {
            manager.notify(
                chatId.hashCode(),
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                    .setStyle(style)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOnlyAlertOnce(!alert)
                    .setSilent(!alert)
                    .setAutoCancel(true)
                    .setContentIntent(openChat(context, peer))
                    .addAction(reply)
                    .addAction(read)
                    .build(),
            )
        }
    }

    /** The text typed into the notification's reply box. */
    fun replyText(intent: Intent): String? =
        androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()

    /** The person a notification action is about. */
    fun peerOf(intent: Intent): ChatUser? {
        val uid = intent.getStringExtra(EXTRA_UID) ?: return null
        return ChatUser(uid, intent.getStringExtra(EXTRA_NAME).orEmpty(), intent.getStringExtra(EXTRA_USERNAME).orEmpty(), intent.getStringExtra(EXTRA_PHOTO)?.ifBlank { null })
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
