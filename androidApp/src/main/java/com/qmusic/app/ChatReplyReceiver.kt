package com.qmusic.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** "Reply", "Mark read", "Mute", and swipe-dismiss on a message notification, done without opening the app. */
class ChatReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(EXTRA_CHAT) ?: return
        val app = context.applicationContext
        if (intent.action == ChatPush.ACTION_DISMISS) {
            ChatPush.dismissed(app, chatId)
            return
        }
        val peer = ChatPush.peerOf(intent) ?: return
        if (peer.uid == ChatPush.LOCKED_UID) return
        // A receiver gets about ten seconds: the work is kept alive that long, and given up after.
        val pending = goAsync()
        scope.launch {
            var replyDone = intent.action != ChatPush.ACTION_REPLY
            try {
                withTimeoutOrNull(9_000) {
                    when (intent.action) {
                        ChatPush.ACTION_REPLY -> {
                            val text = ChatPush.replyText(intent)?.trim().orEmpty()
                            val ok = text.isNotEmpty() && try {
                                com.qmusic.ui.chat.ChatNotificationActions.reply(app, peer, text)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                false
                            }
                            if (ok) {
                                ChatPush.replied(app, chatId, peer, text)
                                replyDone = true
                            }
                        }
                        ChatPush.ACTION_READ -> {
                            ChatPush.clear(app, chatId)
                            try {
                                com.qmusic.ui.chat.ChatNotificationActions.markRead(app, peer.uid)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                            }
                        }
                        ChatPush.ACTION_MUTE -> {
                            ChatPush.clear(app, chatId)
                            try {
                                com.qmusic.ui.chat.ChatNotificationActions.mute(app, chatId, System.currentTimeMillis() + 8 * 3600 * 1000L)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            } finally {
                if (!replyDone) runCatching { ChatPush.repost(app, chatId) }
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_CHAT = "chat_id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
