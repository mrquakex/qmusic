package com.qmusic.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** "Reply" and "Mark read" on a message notification, done without opening the app. */
class ChatReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(EXTRA_CHAT) ?: return
        val peer = ChatPush.peerOf(intent) ?: return
        val app = context.applicationContext
        // A receiver gets about ten seconds: the work is kept alive that long, and given up after.
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(9_000) {
                    when (intent.action) {
                        ChatPush.ACTION_REPLY -> {
                            val text = ChatPush.replyText(intent)?.trim().orEmpty()
                            if (text.isNotEmpty() && com.qmusic.ui.chat.ChatNotificationActions.reply(app, peer, text)) {
                                ChatPush.replied(app, chatId, peer, text)
                            }
                        }
                        ChatPush.ACTION_READ -> {
                            ChatPush.clear(app, chatId)
                            com.qmusic.ui.chat.ChatNotificationActions.markRead(app, peer.uid)
                        }
                        ChatPush.ACTION_MUTE -> {
                            ChatPush.clear(app, chatId)
                            com.qmusic.ui.chat.ChatNotificationActions.mute(app, chatId, System.currentTimeMillis() + 8 * 3600 * 1000L)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_CHAT = "chat_id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
