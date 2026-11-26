package com.qmusic.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow

/** Content shared into Nuvelo from another app (Android Share Sheet / Direct Share). */
data class IncomingShare(
    val text: String? = null,
    val uris: List<String> = emptyList(),
    val mimeType: String = "",
    /** Non-null when the user picked a Direct Share conversation shortcut (chatId). */
    val targetChatId: String? = null,
)

object IncomingChatShare {
    val pending = MutableStateFlow<IncomingShare?>(null)

    /** Resolves Android content:// URIs into images/files off the main thread. Set by MainActivity. */
    var resolveUris: ((uris: List<String>, mimeType: String, onReady: (List<PickedImage>, List<PickedFile>) -> Unit) -> Unit)? = null

    fun offer(text: String? = null, uris: List<String> = emptyList(), mimeType: String = "", targetChatId: String? = null) {
        if (text.isNullOrBlank() && uris.isEmpty()) return
        pending.value = IncomingShare(
            text = text?.takeIf { it.isNotBlank() },
            uris = uris,
            mimeType = mimeType,
            targetChatId = targetChatId?.takeIf { it.isNotBlank() },
        )
    }

    fun consume(): IncomingShare? = pending.value.also { pending.value = null }
}
