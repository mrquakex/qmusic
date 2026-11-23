package com.qmusic.ui.chat

import android.content.Context

/**
 * What a message notification can do without opening the app: answer from the notification, mark the conversation read,
 * and tell the sender the message arrived. Each waits for the sign-in to come back first (a notification can wake the
 * app before it has), and gives up quietly when there is no account.
 */
object ChatNotificationActions {
    /** Sends [text] to [peer]; true when it went (or is queued to go as soon as there is a connection). */
    suspend fun reply(context: Context, peer: ChatUser, text: String): Boolean {
        val backend = FirebaseChatBackend.get(context.applicationContext) ?: return false
        val me = backend.awaitMe() ?: return false
        if (text.isBlank()) return false
        val sent = backend.send(peer, text.trim(), null, null).isSuccess
        if (sent) backend.markRead(conversationId(me.uid, peer))
        return sent
    }

    suspend fun markRead(context: Context, peerUid: String) {
        val backend = FirebaseChatBackend.get(context.applicationContext) ?: return
        val me = backend.awaitMe() ?: return
        backend.markRead(if (isGroupId(peerUid)) peerUid else chatIdOf(me.uid, peerUid))
    }

    /** The conversation stays quiet until [until] (no notifications; the server sends none either). */
    suspend fun mute(context: Context, chatId: String, until: Long) {
        val backend = FirebaseChatBackend.get(context.applicationContext) ?: return
        backend.awaitMe() ?: return
        backend.setMuted(chatId, until)
    }

    /** A message notification arrived: this phone has it, the sender sees two ticks. */
    suspend fun delivered(context: Context, chatId: String) {
        val backend = FirebaseChatBackend.get(context.applicationContext) ?: return
        backend.awaitMe() ?: return
        backend.markDelivered(chatId)
    }
}
