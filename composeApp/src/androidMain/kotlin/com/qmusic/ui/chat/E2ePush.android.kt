package com.qmusic.ui.chat

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Notifications of encrypted messages: the preview comes sealed ("e2e:…"), and is opened here with the conversation's
 * key (the app may have just been woken by the push: the key is fetched, a few seconds at most).
 */
object E2ePush {
    /** [raw] opened when sealed; [raw] itself when not; null when it cannot be opened (the notification says "New message"). */
    fun open(context: Context, chatId: String, raw: String): String? {
        val at = raw.indexOf("e2e:")
        if (at < 0) return raw
        val head = raw.substring(0, at)
        val sealed = raw.substring(at)
        FirebaseChatBackend.get(context) ?: return null
        val engine = E2eEngine.instance ?: return null
        val version = sealed.substringAfter("e2e:").substringBefore(':').toIntOrNull() ?: return null
        val opened = runBlocking {
            withTimeoutOrNull(6_000) {
                // Signed in and the key pair loaded first (a cold start), then the conversation's key.
                while (!engine.ready.value) kotlinx.coroutines.delay(100)
                engine.loadKey(chatId, version)
                engine.openText(chatId, sealed)
            }
        }
        return opened?.takeIf { it != E2e.UNREADABLE }?.let { head + it }
    }
}
