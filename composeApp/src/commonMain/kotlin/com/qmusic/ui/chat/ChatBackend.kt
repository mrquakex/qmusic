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

/**
 * A photo in a conversation: its size, and a tiny blurred preview (base64 JPEG, ~1 KB) that shows at once; the photo
 * itself ([id] under media/) is fetched when it is shown.
 */
data class ChatImage(val id: String, val width: Int, val height: Int, val thumb: String)

/** A photo picked to send: already shrunk and compressed on the phone. */
class PickedImage(val jpeg: ByteArray, val width: Int, val height: Int, val thumb: String)

/** The uid of the owner's Claude (see server/qmusic-push/claude-bot.js). */
const val CLAUDE_BOT_UID = "claudebot"

/** The one person whose Claude it is: only they can call it into a conversation. */
const val CLAUDE_OWNER_UID = "2hxOw0dRcwUwsW7N6ogH4fRcqXo1"

/** A message that calls Claude into a conversation: it starts with "claude" or "@claude". */
fun callsClaude(text: String): Boolean = Regex("^\\s*@?claude\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

/** Claude at work on a message: [phase] is looking (at a photo), thinking or writing. */
data class ClaudeAssist(val target: String, val phase: String)

/** The message a message answers: shown quoted above it. */
data class ChatReply(val id: String, val from: String, val text: String)

/** Whether someone has the app open now, and when they last had. */
data class Presence(val online: Boolean, val lastSeen: Long)

/** The reactions people can put on a message. */
val REACTIONS = listOf("❤️", "😂", "👍", "😮", "😢", "🔥")

data class ChatMessage(
    val id: String,
    val from: String,
    val text: String,
    val replyTo: ChatReply? = null,
    val image: ChatImage? = null,
    /** The model that wrote it ("Claude Sonnet 5.5"), for Claude's answers. */
    val model: String? = null,
    /** Still being written (Claude's answer as it streams in). */
    val streaming: Boolean = false,
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

    suspend fun send(other: ChatUser, text: String, share: SharedItem?, replyTo: ChatReply?): Result<Unit>
    suspend fun markRead(chatId: String)
    suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit>

    /** The people this person blocked: their messages do not reach them. */
    fun blocked(): Flow<Set<String>>
    suspend fun setBlocked(uid: String, blocked: Boolean): Result<Unit>

    /** Removes the person's profile, @username and every message they sent. */
    suspend fun deleteAccount(): Result<Unit>

    /** Sends a photo (with an optional caption). */
    suspend fun sendImage(other: ChatUser, image: PickedImage, caption: String, replyTo: ChatReply?): Result<Unit> =
        Result.failure(UnsupportedOperationException())

    /** The photo [id] of [chatId] (kept on the phone after the first time). */
    suspend fun imageBytes(chatId: String, id: String): ByteArray? = null

    /** What Claude is doing in [chatId] right now (null when nothing). */
    fun assist(chatId: String): Flow<ClaudeAssist?> = kotlinx.coroutines.flow.flowOf(null)

    /** Whether [uid] is online, and since when they are not. */
    fun presence(uid: String): Flow<Presence?> = kotlinx.coroutines.flow.flowOf(null)

    /** The other person is typing in [chatId]. */
    fun typing(chatId: String, otherUid: String): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    suspend fun setTyping(chatId: String, typing: Boolean) {}

    /** The reactions of a conversation: message id → (person → emoji). */
    fun reactions(chatId: String): Flow<Map<String, Map<String, String>>> = kotlinx.coroutines.flow.flowOf(emptyMap())

    /** Puts [emoji] on a message (null takes ours away). */
    suspend fun react(chatId: String, messageId: String, emoji: String?) {}

    /** Voice and video calls, where this platform has them. */
    val calls: CallService? get() = null
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
