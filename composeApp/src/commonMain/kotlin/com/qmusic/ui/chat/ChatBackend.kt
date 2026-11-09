package com.qmusic.ui.chat

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Someone who can be written to: their name, their unique @username, and their picture. */
data class ChatUser(
    val uid: String,
    val name: String,
    val username: String,
    val photo: String? = null,
)

enum class ShareKind { SONG, VIDEO }

/** A song or a video sent in a conversation: enough to show its card and to play it. */
data class SharedItem(
    val kind: ShareKind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnail: String? = null,
)

data class ChatMessage(
    val id: String,
    val from: String,
    val text: String,
    /** When the server took it, in milliseconds; the phone's own time while it is still on its way. */
    val at: Long,
    val share: SharedItem? = null,
    /** Written on this phone and not yet on the server (no connection, or a moment after sending). */
    val pending: Boolean = false,
)

/** A conversation as the list shows it. */
data class ChatThread(
    val id: String,
    val other: ChatUser,
    val lastText: String,
    val lastAt: Long,
    val lastFrom: String?,
    /** The last message came from the other person and was not read here yet. */
    val unread: Boolean,
    /** Until when the other person has read: messages sent before it show as read. */
    val otherReadAt: Long,
)

/** Where the person stands with messaging. */
sealed interface ChatAccount {
    /** This build or this device cannot do it (no Firebase set up, or a desktop). */
    data class Unavailable(val reason: String? = null) : ChatAccount

    data object Loading : ChatAccount

    data object SignedOut : ChatAccount

    /** Signed in, but no @username picked yet: others cannot find them until there is one. */
    data class NeedsProfile(val uid: String, val suggestedName: String, val photo: String?) : ChatAccount

    data class Ready(val me: ChatUser) : ChatAccount
}

class UsernameTaken : Exception("username taken")

/**
 * Where messages are kept and how they arrive. On Android it is Firebase (Google sign-in, Firestore, push messages);
 * elsewhere there is none.
 */
interface ChatBackend {
    val account: StateFlow<ChatAccount>

    /** Google's account picker; the result arrives through [account]. */
    suspend fun signIn(): Result<Unit>
    fun signOut()

    /** Takes [username] for the person (fails with [UsernameTaken] when someone has it). */
    suspend fun saveProfile(name: String, username: String): Result<Unit>

    /** People whose @username starts with [prefix]. */
    suspend fun findUsers(prefix: String): Result<List<ChatUser>>

    /** The person's conversations, newest first, updated as messages come and go. */
    fun threads(): Flow<List<ChatThread>>

    /** The last messages of a conversation, oldest first, updated live. */
    fun messages(chatId: String): Flow<List<ChatMessage>>

    /** Until when [chatId]'s other member has read (updated live). */
    fun otherReadAt(chatId: String, otherUid: String): Flow<Long>

    suspend fun send(other: ChatUser, text: String, share: SharedItem?): Result<Unit>
    suspend fun markRead(chatId: String)
    suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit>

    /** The people this person blocked: their messages do not reach them. */
    fun blocked(): Flow<Set<String>>
    suspend fun setBlocked(uid: String, blocked: Boolean): Result<Unit>

    /** Removes the person's profile, @username and every message they sent. */
    suspend fun deleteAccount(): Result<Unit>
}

/** A conversation's id: both people's ids in order, so each pair has exactly one. */
fun chatIdOf(a: String, b: String): String = if (a < b) "${a}_$b" else "${b}_$a"

/** 3-20 characters: lowercase letters, digits, dot and underscore. */
fun isValidUsername(name: String): Boolean = name.length in 3..20 && name.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }

/** Asks the app to open the messages section (a tapped notification); [user] opens their conversation. */
data class ChatLaunchRequest(val user: ChatUser?)

object ChatLaunch {
    val request = kotlinx.coroutines.flow.MutableStateFlow<ChatLaunchRequest?>(null)
}

/** The backend of this platform, or null where there is none. */
@Composable
expect fun rememberChatBackend(): ChatBackend?
