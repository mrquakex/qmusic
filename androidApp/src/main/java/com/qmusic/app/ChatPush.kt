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
    const val ACTION_MUTE = "com.qmusic.app.CHAT_MUTE"
    const val ACTION_DISMISS = "com.qmusic.app.CHAT_DISMISS"
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
        // The app is really on screen (not gone to the background, not shrunk to a floating window): the conversation
        // open in it shows the message itself. Left in the background with a conversation open, that conversation's
        // messages notified nothing until the app was opened again.
        val inFront = com.qmusic.ui.chat.CallHooks.appVisible && !com.qmusic.ui.videos.VideoPip.active.value
        if (inFront && ChatPresence.openChatId == chatId) return
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
        // The app is open (another conversation, the videos, the music): a strip at the top of the app with a short
        // sound, not a notification over it.
        if (inFront && com.qmusic.ui.chat.NotificationPrefs.inAppBanner.value) {
            com.qmusic.ui.chat.ChatBanners.current.value = com.qmusic.ui.chat.ChatBanner(
                id = System.currentTimeMillis(),
                conversation = conversation,
                author = if (group) authorName else "",
                text = text,
            )
            com.qmusic.ui.chat.QeyfSounds.playChat(context, com.qmusic.ui.chat.SoundKind.RECEIVE)
            return
        }
        show(context, chatId, conversation, group, lines, count, alert = true)
    }

    /** Each conversation's last notification, to show again in full once the phone is unlocked. */
    private val shown = HashMap<String, Pair<ChatUser, Boolean>>()

    /** The phone was unlocked: the notifications kept short on the lock screen show in full, quietly. */
    fun unlocked(context: Context) {
        if (!com.qmusic.ui.chat.NotificationPrefs.hideOnLock.value) return
        val all = synchronized(history) {
            history.mapNotNull { (id, lines) ->
                val conv = shown[id] ?: return@mapNotNull null
                Triple(id, conv, lines.toList() to (unread[id] ?: 0))
            }
        }
        all.forEach { (chatId, conv, pair) ->
            show(context, chatId, conv.first, conv.second, pair.first, pair.second, alert = false)
        }
    }

    private const val GROUP_KEY = "nuvelo_messages"
    private const val SUMMARY_ID = 1999

    /** Two or more conversations with notifications: they go together under one ("3 conversations"), quietly. */
    private fun updateSummary(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val others = runCatching {
            manager.activeNotifications.filter { it.notification.group == GROUP_KEY && it.id != SUMMARY_ID }
        }.getOrDefault(emptyList())
        if (others.size < 2) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val tr = tr(context)
        val inbox = NotificationCompat.InboxStyle()
        others.take(6).forEach { n ->
            val title = n.notification.extras.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)
                ?: n.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
            if (title != null) inbox.addLine(title)
        }
        val summary = if (tr) "${others.size} sohbetten yeni mesaj" else "New messages in ${others.size} chats"
        inbox.setSummaryText(summary)
        runCatching {
            manager.notify(
                SUMMARY_ID,
                NotificationCompat.Builder(context, com.qmusic.ui.chat.QeyfSounds.messageChannelId(context))
                    .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                    .setContentTitle("Nuvelo")
                    .setContentText(summary)
                    .setStyle(inbox)
                    .setGroup(GROUP_KEY)
                    .setGroupSummary(true)
                    // The conversations' own notifications make the sound; the summary never does.
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    .setSilent(true)
                    .setAutoCancel(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context, SUMMARY_ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                    .build(),
            )
        }
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
            shown.remove(chatId)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(chatId.hashCode())
        updateSummary(context)
    }

    /** Swiped away from the shade: forget its lines and count without cancelling again. */
    fun dismissed(context: Context, chatId: String) {
        synchronized(history) {
            history.remove(chatId)
            unread.remove(chatId)
            shown.remove(chatId)
        }
        updateSummary(context)
    }

    /** Re-shows the current notification quietly (so Android clears a failed or timed-out inline reply spinner). */
    fun repost(context: Context, chatId: String) {
        val state = synchronized(history) {
            val pair = shown[chatId] ?: return@synchronized null
            val lines = history[chatId]?.toList().orEmpty()
            val count = unread[chatId] ?: 0
            Triple(pair.first, pair.second, lines to count)
        }
        if (state != null) {
            val (conversation, group, linesAndCount) = state
            val (lines, count) = linesAndCount
            if (lines.isNotEmpty()) {
                show(context, chatId, conversation, group, lines, count, alert = false)
                return
            }
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = runCatching { manager.activeNotifications.firstOrNull { it.id == chatId.hashCode() }?.notification }.getOrNull()
        if (existing != null) {
            val recovered = runCatching {
                android.app.Notification.Builder.recoverBuilder(context, existing).setOnlyAlertOnce(true).build()
            }.getOrNull()
            if (recovered != null && runCatching { manager.notify(chatId.hashCode(), recovered) }.isSuccess) return
            runCatching { manager.cancel(chatId.hashCode()) }
            updateSummary(context)
        }
    }

    private fun dismissIntent(context: Context, chatId: String) = PendingIntent.getBroadcast(
        context,
        chatId.hashCode() + 4,
        Intent(context, ChatReplyReceiver::class.java)
            .setAction(ACTION_DISMISS)
            .putExtra(ChatReplyReceiver.EXTRA_CHAT, chatId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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
        synchronized(history) { shown[chatId] = conversation to group }
        // Asked to keep messages off the locked screen, and it is locked: neither who nor what until it is unlocked
        // (then [unlocked] shows it in full).
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (com.qmusic.ui.chat.NotificationPrefs.hideOnLock.value && keyguard.isKeyguardLocked) {
            val said = if (count > 1) (if (tr) "$count yeni mesaj" else "$count new messages") else (if (tr) "Yeni mesaj" else "New message")
            runCatching {
                manager.notify(
                    chatId.hashCode(),
                    NotificationCompat.Builder(context, com.qmusic.ui.chat.QeyfSounds.messageChannelId(context))
                        .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                        .setContentTitle("Nuvelo")
                        .setContentText(said)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setNumber(count)
                        .setGroup(GROUP_KEY)
                        .setOnlyAlertOnce(!alert)
                        .setSilent(!alert)
                        .setAutoCancel(true)
                        .setContentIntent(openChat(context, conversation))
                        .setDeleteIntent(dismissIntent(context, chatId))
                        .build(),
                )
            }
            updateSummary(context)
            return
        }
        // The messages channel plays the chosen Qeyf sound (see QeyfSounds).
        val channel = com.qmusic.ui.chat.QeyfSounds.messageChannelId(context)
        // A locked or secret conversation: no shortcut, no Reply / Mark read / Mute actions, minimal private text only.
        if (conversation.uid == LOCKED_UID) {
            val said = if (count > 1) {
                if (tr) "$count yeni mesaj" else "$count new messages"
            } else {
                lines.lastOrNull()?.text?.takeIf { it.isNotBlank() } ?: if (tr) "Yeni mesaj" else "New message"
            }
            runCatching {
                manager.notify(
                    chatId.hashCode(),
                    NotificationCompat.Builder(context, channel)
                        .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                        .setContentTitle("Nuvelo")
                        .setContentText(said)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setNumber(count)
                        .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                        .setGroup(GROUP_KEY)
                        .setOnlyAlertOnce(!alert)
                        .setSilent(!alert)
                        .setAutoCancel(true)
                        .setContentIntent(openChat(context, conversation))
                        .setDeleteIntent(dismissIntent(context, chatId))
                        .build(),
                )
            }
            updateSummary(context)
            return
        }
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
            // The phone (and a watch) may offer ready answers that fit the message.
            .setAllowGeneratedReplies(true)
            .build()
        val read = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_view, if (tr) "Okundu" else "Mark read", actionIntent(ACTION_READ, 2))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        // A busy conversation quieted for eight hours, right from the notification.
        val mute = NotificationCompat.Action.Builder(android.R.drawable.ic_lock_silent_mode, if (tr) "8 saat sessize al" else "Mute 8 hours", actionIntent(ACTION_MUTE, 3))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MUTE)
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
                    .setDeleteIntent(dismissIntent(context, chatId))
                    .setGroup(GROUP_KEY)
                    .addAction(reply)
                    .addAction(read)
                    .addAction(mute)
                    .build(),
            )
        }
        updateSummary(context)
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
    internal const val LOCKED_UID = "__locked"

    /** Android's own category for conversation shortcuts (ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION). */
    private const val SHORTCUT_CATEGORY = "android.shortcut.conversation"
}
