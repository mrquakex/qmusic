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
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.qmusic.ui.chat.ChatLaunch
import com.qmusic.ui.chat.ChatLaunchRequest
import com.qmusic.ui.chat.ChatPresence
import com.qmusic.ui.chat.ChatUser
import kotlinx.coroutines.launch

/**
 * Message notifications, the way Android shows conversations: each conversation is a "conversation" (a long-lived
 * shortcut with the person's picture, so it goes in the Conversations part of the shade and can be made a priority one),
 * its messages listed under their writers' names and pictures (a group titled with the group), the count of the unread
 * ones on it, Reply and Mark as read on it. Not shown for the conversation open on screen.
 */
object ChatPush {
    private const val EXTRA_UID = "chat_uid"
    private const val EXTRA_NAME = "chat_name"
    private const val EXTRA_USERNAME = "chat_username"
    private const val EXTRA_PHOTO = "chat_photo"
    private const val EXTRA_PROFILE = "chat_profile"
    private const val FRIEND_CHANNEL = "qmusic_friends"

    private fun tr(context: Context) = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ friends

    /** "X wants to be your friend" / "X accepted": tapping it opens their profile. */
    fun onFriend(context: Context, data: Map<String, String>) {
        val from = (data["sender"] ?: data["from"]) ?: return
        if (!allowed(context)) return
        val tr = tr(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(FRIEND_CHANNEL, if (tr) "Arkadaşlık" else "Friends", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val name = data["name"].orEmpty().ifBlank { "Nuvelo" }
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
        runCatching {
            manager.notify(
                ("friend$from").hashCode(),
                NotificationCompat.Builder(context, FRIEND_CHANNEL)
                    .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                    .setLargeIcon(NotificationAvatars.bitmap(from, name, data["photo"]))
                    .setContentTitle(if (accepted) (if (tr) "Yeni arkadaş" else "New friend") else (if (tr) "Arkadaşlık isteği" else "Friend request"))
                    .setContentText(text)
                    .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }

    // ------------------------------------------------------------------ calls

    /**
     * A call's push: it rings at once from what the push carries, and the app, woken, takes the call over from there.
     * "cancel": the caller gave up. When the app had not taken the call over yet (it was not running), the ringing
     * notification is all there was: it becomes a missed call here.
     */
    fun onCall(context: Context, data: Map<String, String>) {
        if (data["action"] == "cancel") {
            CallNotifications.missedFromPush(context, data["callId"].orEmpty())
            return
        }
        val from = (data["sender"] ?: data["from"])
        if (from != null && !com.qmusic.ui.chat.CallHooks.appVisible) {
            CallNotifications.showIncoming(
                context,
                // Someone kept in the secret space: who calls is not shown, when the person chose so.
                if (data["chatId"].orEmpty() in com.qmusic.ui.chat.SecretChats.ids && !com.qmusic.ui.chat.SecretChats.showCallerName) {
                    ChatUser(from, if (tr(context)) "Nuvelo araması" else "Nuvelo call", "", null)
                } else {
                    ChatUser(from, data["name"].orEmpty().ifBlank { "Nuvelo" }, data["username"].orEmpty(), data["photo"]?.ifBlank { null })
                },
                data["video"] == "1",
                data["callId"].orEmpty(),
            )
        }
        com.qmusic.ui.chat.ChatCallActions.wake(context)
    }

    // ------------------------------------------------------------------ messages

    private const val KEY_REPLY = "chat_reply_text"
    const val ACTION_REPLY = "com.qmusic.app.CHAT_REPLY"
    const val ACTION_READ = "com.qmusic.app.CHAT_READ"
    private const val HISTORY = 8

    /** One line of a conversation's notification: who wrote it (null: the person themself), what, and when. */
    private data class Line(val text: String, val at: Long, val mine: Boolean, val authorKey: String, val authorName: String, val authorPhoto: String?)

    /** The last few lines of each conversation's notification (the newest at the end), and how many came unread. */
    private val history = HashMap<String, ArrayDeque<Line>>()
    private val unread = HashMap<String, Int>()

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Opens the conversation with [peer] (a message or a missed call tapped). */
    fun openChat(context: Context, peer: ChatUser): PendingIntent = PendingIntent.getActivity(
        context,
        peer.uid.hashCode(),
        chatIntent(context, peer),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun chatIntent(context: Context, peer: ChatUser) = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(EXTRA_UID, peer.uid)
        .putExtra(EXTRA_NAME, peer.name)
        .putExtra(EXTRA_USERNAME, peer.username)
        .putExtra(EXTRA_PHOTO, peer.photo.orEmpty())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** A message (or a reaction) came: it goes in its conversation's notification. */
    fun notify(context: Context, data: Map<String, String>) {
        val chatId = data["chatId"] ?: return
        val from = (data["sender"] ?: data["from"]) ?: return
        // The sender sees two ticks as soon as the phone has it, whether it is shown or not.
        if (data["type"] != "reaction") {
            background.launch { runCatching { com.qmusic.ui.chat.ChatNotificationActions.delivered(context.applicationContext, chatId) } }
        }
        if (ChatPresence.openChatId == chatId) return
        // A locked conversation, or one in the secret space: neither who nor what, and a tap opens the messages (the
        // lock, or the secret space's PIN, asks first).
        val secret = chatId in com.qmusic.ui.chat.SecretChats.ids || data["secret"] == "1"
        if (chatId in com.qmusic.ui.chat.LockedChats.ids || secret) {
            val tr = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
            val hidden = ChatUser(LOCKED_UID, "Nuvelo", "", null)
            val invite = secret && data["body"].orEmpty().startsWith("Gizli bir sohbete")
            val said = when {
                invite -> if (tr) "Gizli bir sohbete davet edildin" else "You were invited to a secret chat"
                tr -> "Yeni mesaj"
                else -> "New message"
            }
            val line = Line(said, System.currentTimeMillis(), false, LOCKED_UID, "Nuvelo", null)
            val (lines, count) = synchronized(history) {
                val list = history.getOrPut(chatId) { ArrayDeque() }
                list.clear()
                list.addLast(line)
                unread[chatId] = (unread[chatId] ?: 0) + 1
                list.toList() to unread.getValue(chatId)
            }
            show(context, chatId, hidden, false, lines, count, alert = true)
            return
        }
        val conversation = ChatUser(from, data["name"].orEmpty().ifBlank { "Nuvelo" }, data["username"].orEmpty(), data["photo"]?.ifBlank { null })
        val group = data["group"] == "1"
        // In a group each line has its writer; one-to-one, the other person (or Claude speaking in the conversation).
        val authorKey = data["author"]?.ifBlank { null } ?: from
        val authorName = data["authorName"]?.ifBlank { null } ?: if (group) "" else conversation.name
        val english = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") == "en"
        val given = if (group && data["text"] != null && data["type"] != "reaction") data["text"].orEmpty() else data["body"].orEmpty()
        // End-to-end encrypted: opened here with the conversation's key ("New message" when it cannot be).
        var raw = com.qmusic.ui.chat.E2ePush.open(context.applicationContext, chatId, given) ?: if (english) "New message" else "Yeni mesaj"
        data["enc"]?.takeIf { it.startsWith("e2e:") }?.let { enc ->
            com.qmusic.ui.chat.E2ePush.open(context.applicationContext, chatId, enc)?.let { quote -> raw = "$raw: \"${quote.take(120)}\"" }
        }
        // Kept with a mark in front ("📷", "🎤 0:12"): the notification says it in words.
        val text = com.qmusic.ui.chat.ChatPreview.plain(raw, english)
        val line = Line(text, System.currentTimeMillis(), false, authorKey, authorName, data["authorPhoto"]?.ifBlank { null } ?: if (group) null else conversation.photo)
        val (lines, count) = synchronized(history) {
            val list = history.getOrPut(chatId) { ArrayDeque() }
            list.addLast(line)
            while (list.size > HISTORY) list.removeFirst()
            unread[chatId] = (unread[chatId] ?: 0) + 1
            list.toList() to unread.getValue(chatId)
        }
        show(context, chatId, conversation, group, lines, count, alert = true)
    }

    /** After an answer from the notification: it stays, with the answer under their message, quietly. */
    fun replied(context: Context, chatId: String, peer: ChatUser, text: String) {
        val (lines, group) = synchronized(history) {
            val list = history.getOrPut(chatId) { ArrayDeque() }
            list.addLast(Line(text, System.currentTimeMillis(), true, "me", "", null))
            while (list.size > HISTORY) list.removeFirst()
            unread[chatId] = 0
            list.toList() to com.qmusic.ui.chat.isGroupId(chatId)
        }
        show(context, chatId, peer, group, lines, 0, alert = false)
    }

    /** Read from the notification (or the conversation opened): it goes, and its lines are forgotten. */
    fun clear(context: Context, chatId: String) {
        synchronized(history) {
            history.remove(chatId)
            unread.remove(chatId)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(chatId.hashCode())
    }

    /**
     * The conversation as an Android "conversation": a long-lived shortcut with its picture, which the notification
     * points to (Android 11+ puts it with the conversations, and lets the person make it a priority one).
     */
    private fun publishShortcut(context: Context, chatId: String, conversation: ChatUser, icon: androidx.core.graphics.drawable.IconCompat, person: Person) {
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, chatId)
                .setShortLabel(conversation.name.take(25))
                .setLongLabel(conversation.name)
                .setIcon(icon)
                .setIntent(chatIntent(context, conversation))
                .setLongLived(true)
                .setLocusId(LocusIdCompat(chatId))
                .setPerson(person)
                .setCategories(setOf(SHORTCUT_CATEGORY))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    private fun show(context: Context, chatId: String, conversation: ChatUser, group: Boolean, lines: List<Line>, count: Int, alert: Boolean) {
        if (!allowed(context)) return
        val tr = tr(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // The messages channel plays the chosen Qeyf sound (see QeyfSounds).
        val channel = com.qmusic.ui.chat.QeyfSounds.messageChannelId(context)
        val conversationIcon = NotificationAvatars.icon(conversation.uid, conversation.name, conversation.photo)
        val conversationPerson = Person.Builder().setName(conversation.name).setKey(conversation.uid).setIcon(conversationIcon).setImportant(true).build()
        publishShortcut(context, chatId, conversation, conversationIcon, conversationPerson)

        val me = Person.Builder().setName(if (tr) "Sen" else "You").setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me)
        if (group) {
            style.setConversationTitle(conversation.name)
            style.setGroupConversation(true)
        }
        val people = HashMap<String, Person>()
        lines.forEach { line ->
            val sender = when {
                line.mine -> me
                !group -> conversationPerson
                else -> people.getOrPut(line.authorKey) {
                    Person.Builder()
                        .setName(line.authorName.ifBlank { "?" })
                        .setKey(line.authorKey)
                        .setIcon(NotificationAvatars.icon(line.authorKey, line.authorName, line.authorPhoto))
                        .build()
                }
            }
            style.addMessage(line.text, line.at, sender)
        }

        fun actionIntent(action: String, code: Int) = PendingIntent.getBroadcast(
            context,
            chatId.hashCode() + code,
            Intent(context, ChatReplyReceiver::class.java)
                .setAction(action)
                .putExtra(ChatReplyReceiver.EXTRA_CHAT, chatId)
                .putExtra(EXTRA_UID, conversation.uid)
                .putExtra(EXTRA_NAME, conversation.name)
                .putExtra(EXTRA_USERNAME, conversation.username)
                .putExtra(EXTRA_PHOTO, conversation.photo.orEmpty()),
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
                    .setShortcutId(chatId)
                    .setLocusId(LocusIdCompat(chatId))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    // The unread count, for the launcher's badge on the app icon.
                    .setNumber(count)
                    .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                    .setOnlyAlertOnce(!alert)
                    .setSilent(!alert)
                    .setAutoCancel(true)
                    .setContentIntent(openChat(context, conversation))
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

    /** A tapped message notification (or conversation shortcut): the app opens that conversation. */
    fun openIfAsked(intent: Intent?) {
        val uid = intent?.getStringExtra(EXTRA_UID) ?: return
        if (uid == LOCKED_UID) {
            intent.removeExtra(EXTRA_UID)
            ChatLaunch.request.value = ChatLaunchRequest(null)
            return
        }
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

    /** Who a locked conversation's notification is "from". */
    private const val LOCKED_UID = "__locked"

    /** Android's own category for conversation shortcuts (ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION). */
    private const val SHORTCUT_CATEGORY = "android.shortcut.conversation"
}
