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
data class ChatImage(val id: String, val width: Int, val height: Int, val thumb: String, val url: String? = null)

enum class AttachmentKind { VOICE, VIDEO, FILE }

/**
 * A voice message, a video or a file in a message, kept on Qeyf's media server ([url]). A voice message has its
 * length and the shape of its sound ([wave], 0-100 per bar); a video its size, length and a tiny preview ([thumb]).
 */
data class ChatAttachment(
    val kind: AttachmentKind,
    val url: String,
    val name: String = "",
    val size: Long = 0,
    val mime: String = "",
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val thumb: String = "",
    val wave: List<Int> = emptyList(),
)

/**
 * A file picked (or a voice message recorded) to send. [location] is where the phone has it (a content address or a
 * file path), read only while it goes up.
 */
class PickedFile(
    val kind: AttachmentKind,
    val location: String,
    val name: String,
    val mime: String,
    val size: Long,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val thumb: String = "",
    val wave: List<Int> = emptyList(),
)

/** A group: its name and picture, and who is in it. */
data class GroupInfo(val id: String, val name: String, val photo: String?, val members: List<GroupMember>, val createdBy: String?)

data class GroupMember(val user: ChatUser, val admin: Boolean)

/** Groups have ids of their own ("grp" and 20 letters/digits), never two ids joined by "_". */
const val GROUP_PREFIX = "grp"

fun isGroupId(id: String): Boolean = id.length == 23 && id.startsWith(GROUP_PREFIX) && id.all { it.isLetterOrDigit() }

/** A group shows in the lists like a person: its id, its name and its picture. */
val ChatUser.isGroup: Boolean get() = isGroupId(uid)

/** The conversation with [other]: the group itself, or the one of the two people. */
fun conversationId(me: String, other: ChatUser): String = if (other.isGroup) other.uid else chatIdOf(me, other.uid)

/**
 * Listening (a song) or watching (a video) together in a conversation: what plays, whether it plays, and where it
 * was at [at] (server time). [by] is who changed it last; the others follow.
 */
data class TogetherState(
    val chatId: String,
    val item: SharedItem,
    val playing: Boolean,
    val positionMs: Long,
    val at: Long,
    val by: String,
    val byName: String = "",
)

/** A photo picked to send: already shrunk and compressed on the phone. */
class PickedImage(val jpeg: ByteArray, val width: Int, val height: Int, val thumb: String)

/** The uid of the owner's Claude (see server/qmusic-push/claude-bot.js). */
const val CLAUDE_BOT_UID = "claudebot"

/** The one person whose Claude it is: only they can call it into a conversation. */
const val CLAUDE_OWNER_UID = "2hxOw0dRcwUwsW7N6ogH4fRcqXo1"

/** A message that calls Claude into a conversation: it starts with "claude" or "@claude". */
fun callsClaude(text: String): Boolean = Regex("^\\s*@?claude\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

/** Where two people stand: nothing yet, a request one way or the other, or friends. */
enum class FriendStatus { NONE, REQUESTED, INCOMING, FRIENDS }

/** The owner's Claude: the model picked ("opus") and how it calls itself ("Claude Opus 5.5"). */
data class ClaudeState(val model: String, val label: String)

/** The models the owner can pick, with a word about each. */
val CLAUDE_MODELS = listOf("opus", "sonnet", "haiku")

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
    /** The photos of the message (one, or an album of up to ten). */
    val images: List<ChatImage> = emptyList(),
    /** The model that wrote it ("Claude Sonnet 5.5"), for Claude's answers. */
    val model: String? = null,
    /** Still being written (Claude's answer as it streams in). */
    val streaming: Boolean = false,
    /** When the server took it, in milliseconds; the phone's own time while it is still on its way. */
    val at: Long,
    val share: SharedItem? = null,
    /** Written on this phone and not yet on the server (no connection, or a moment after sending). */
    val pending: Boolean = false,
    /** A voice message, a video or a file. */
    val attachment: ChatAttachment? = null,
    /** A line about the conversation itself ("Ali added Ayşe"), shown in the middle. */
    val system: Boolean = false,
    /** An invitation to listen or watch together ([share] is what). */
    val together: Boolean = false,
    /** Going up: how much of the attachment has been sent (0-1), on the sender's phone only. */
    val progress: Float? = null,
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
    /** Pinned to the top of the list (when, so the last pinned comes first); 0 when not. */
    val pinned: Long = 0,
    /** Moved out of the list into "Archived". */
    val archived: Boolean = false,
    /** Who wrote the last message, for groups ("Ali: …"). */
    val lastFromName: String = "",
) {
    val isGroup: Boolean get() = other.isGroup
}

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

    /** Until when the other person's phone has the messages ("delivered", two grey ticks). */
    fun otherDeliveredAt(chatId: String, otherUid: String): Flow<Long> = kotlinx.coroutines.flow.flowOf(0L)

    suspend fun send(other: ChatUser, text: String, share: SharedItem?, replyTo: ChatReply?): Result<Unit>
    suspend fun markRead(chatId: String)
    suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit>

    /** The people this person blocked: their messages do not reach them. */
    fun blocked(): Flow<Set<String>>
    suspend fun setBlocked(uid: String, blocked: Boolean): Result<Unit>

    /** Removes the person's profile, @username and every message they sent. */
    suspend fun deleteAccount(): Result<Unit>

    /** Sends photos (one, or an album) with an optional caption. */
    suspend fun sendImages(other: ChatUser, images: List<PickedImage>, caption: String, replyTo: ChatReply?): Result<Unit> =
        Result.failure(UnsupportedOperationException())

    /** The person's friends. */
    fun friends(): Flow<List<ChatUser>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** People asking to be friends with the person. */
    fun friendRequests(): Flow<List<ChatUser>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** The people the person asked to be friends with (and who have not answered). */
    fun sentRequests(): Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet())

    /** Asks [user] to be friends (or accepts at once when they asked first). */
    suspend fun requestFriend(user: ChatUser): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun acceptFriend(user: ChatUser): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Declines their request, takes back ours, or ends a friendship. */
    suspend fun unfriend(user: ChatUser): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Someone's profile, read fresh. */
    suspend fun user(uid: String): ChatUser? = null

    /** The owner's Claude: which model answers now (null where there is none). */
    fun claudeState(): Flow<ClaudeState?> = kotlinx.coroutines.flow.flowOf(null)

    /** Picks the model of the owner's Claude ("opus", "sonnet", "haiku"). */
    suspend fun setClaudeModel(model: String) {}

    /** Starts the owner's conversation with Claude afresh. */
    suspend fun resetClaude() {}

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

    // ------------------------------------------------------------------ files, voice, video

    /** Sends a voice message, a video or a file (it goes to the media server first). */
    suspend fun sendFile(other: ChatUser, file: PickedFile, caption: String, replyTo: ChatReply?): Result<Unit> =
        Result.failure(UnsupportedOperationException())

    /** Attachments going up from this phone: message id → how much is sent (0-1). */
    val uploads: StateFlow<Map<String, Float>> get() = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())

    /** The bytes of a photo (from the media server, or Firebase for older ones), kept on the phone after once. */
    suspend fun imageBytes(chatId: String, image: ChatImage): ByteArray? = imageBytes(chatId, image.id)

    // ------------------------------------------------------------------ the list

    suspend fun setPinned(chatId: String, pinned: Boolean) {}
    suspend fun setArchived(chatId: String, archived: Boolean) {}

    // ------------------------------------------------------------------ groups

    /** Makes a group named [name] with [members] (and the person, its admin); the group comes back as a ChatUser. */
    suspend fun createGroup(name: String, members: List<ChatUser>): Result<ChatUser> = Result.failure(UnsupportedOperationException())
    fun group(groupId: String): Flow<GroupInfo?> = kotlinx.coroutines.flow.flowOf(null)
    suspend fun addMembers(groupId: String, members: List<ChatUser>): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun removeMember(groupId: String, member: ChatUser): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun leaveGroup(groupId: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun renameGroup(groupId: String, name: String): Result<Unit> = Result.failure(UnsupportedOperationException())

    // ------------------------------------------------------------------ together

    fun together(chatId: String): Flow<TogetherState?> = kotlinx.coroutines.flow.flowOf(null)

    /** The message that invites the others to listen / watch [item] together (with a "Join" button). */
    suspend fun sendTogetherInvite(other: ChatUser, item: SharedItem, text: String): Result<Unit> = send(other, text, item, null)

    /** Writes the session of [chatId] (null ends it). */
    suspend fun setTogether(chatId: String, state: TogetherState?) {}

    /** The server's clock minus the phone's (ms), to know where a song is now from where it was at a time. */
    val serverOffset: StateFlow<Long> get() = kotlinx.coroutines.flow.MutableStateFlow(0L)
}

/** A conversation's id: both people's ids in order, so each pair has exactly one. */
fun chatIdOf(a: String, b: String): String = if (a < b) "${a}_$b" else "${b}_$a"

/** 3-20 characters: lowercase letters, digits, dot and underscore. */
fun isValidUsername(name: String): Boolean = name.length in 3..20 && name.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }

/** Asks the app to open the messages section (a tapped notification); [user] opens their conversation. */
data class ChatLaunchRequest(val user: ChatUser?, val profile: Boolean = false)

object ChatLaunch {
    val request = kotlinx.coroutines.flow.MutableStateFlow<ChatLaunchRequest?>(null)
}

/** The backend of this platform, or null where there is none. */
@Composable
expect fun rememberChatBackend(): ChatBackend?
