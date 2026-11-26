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
    /** A line about themselves ("Hakkımda"). */
    val about: String = "",
)

/** What the person shares: their last seen (and being online), and whether they tell others they read. */
/** Who sees something: everyone, friends only, or nobody. */
enum class Visibility { EVERYONE, FRIENDS, NOBODY }

data class Privacy(
    val hideLastSeen: Boolean = false,
    val hideReadReceipts: Boolean = false,
    /** Who sees the profile photo. */
    val photo: Visibility = Visibility.EVERYONE,
    /** Who sees the about line. */
    val about: Visibility = Visibility.EVERYONE,
    /** Who may add the person to a group (FRIENDS or EVERYONE). */
    val groupAdd: Visibility = Visibility.EVERYONE,
)

/** A list the person sends one message to many through: each gets it in their own conversation. */
data class Broadcast(val id: String, val name: String, val members: List<ChatUser>, val log: List<Pair<String, Long>> = emptyList())

/** A backend where a tapped photo loads whatever the auto-download setting says. */
interface ForcesImages {
    fun forceImage(id: String)
}

/** What an invite link shows before joining. */
data class GroupInvite(val code: String, val groupId: String, val name: String, val photo: String?)

enum class StatusKind { PHOTO, VIDEO, TEXT }

/** One status: a photo, a video or a text on a colour, gone 24 hours after [at]. */
data class StatusItem(
    val id: String,
    val owner: ChatUser,
    val kind: StatusKind,
    val url: String = "",
    val text: String = "",
    /** The background of a text status (ARGB). */
    val background: Long = 0xFF1E1E1E,
    val at: Long,
    val thumb: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    /** The typeface of a text status (an index into the status fonts). */
    val font: Int = 0,
    /** Words written over a photo or a video, where they were put. */
    val overlay: StatusOverlay? = null,
    /** A song that goes with it (tapped, it plays in the app). */
    val song: SharedItem? = null,
)

/** Words over a status picture: [x] and [y] are where their middle is (0-1 of the width and height). */
data class StatusOverlay(val text: String, val x: Float, val y: Float, val color: Long)

/** What a status carries besides its picture or words. */
data class StatusExtras(
    val overlay: StatusOverlay? = null,
    val song: SharedItem? = null,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
)

/** Someone's statuses, oldest first, and whether the person has seen all of them. */
data class StatusGroup(val owner: ChatUser, val items: List<StatusItem>, val seenAll: Boolean, val seen: Set<String>)

/** Who sees the person's statuses: all friends, only the chosen ones, or all friends but the chosen ones. */
enum class StatusAudience { FRIENDS, ONLY, EXCEPT }

data class StatusPrivacy(val audience: StatusAudience = StatusAudience.FRIENDS, val people: Set<String> = emptySet())

enum class CallDirection { IN, OUT, GROUP }

/** How a call went for the person: talked, missed (not answered here), declined, or not answered by them. */
enum class CallOutcome { ANSWERED, MISSED, DECLINED, NOT_ANSWERED }

/** One line of the person's call list. */
data class CallLogEntry(
    val id: String,
    val peer: ChatUser,
    val video: Boolean,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val at: Long,
    val seconds: Long,
)

/** A poll: a question, its choices (2-12), multi-select, optional anonymity, and whether creator closed voting. */
data class ChatPoll(
    val question: String,
    val options: List<String>,
    val multiple: Boolean,
    val anonymous: Boolean = false,
    val closed: Boolean = false,
)

/** An event sent in a message: what, when (epoch ms) and where; the answers are its votes (0 going, 1 maybe, 2 not). */
data class ChatEvent(val title: String, val at: Long, val place: String = "")

/** A place sent in a message; [live]: a live location, shared until [until] (where it is now: [LiveShare]). */
data class ChatLocation(val lat: Double, val lng: Double, val label: String = "", val live: Boolean = false, val until: Long = 0)

/** A contact sent in a message. */
data class ChatContact(val name: String, val phone: String)

/** The card of a link in a message: the page's title, a line about it and its picture. */
data class LinkPreview(val url: String, val title: String, val description: String, val image: String?, val site: String)

/** A message the person starred, to find it again. */
data class StarredMessage(val chatId: String, val messageId: String, val text: String, val from: String, val fromName: String, val chatName: String, val at: Long)

/** The first link in a text, or null. */
fun firstLink(text: String): String? = Regex("""https?://[^\s<>"]+[^\s<>".,;:!?)\]]""").find(text)?.value

/** The links in a text. */
fun links(text: String): List<String> = Regex("""https?://[^\s<>"]+[^\s<>".,;:!?)\]]""").findAll(text).map { it.value }.toList()

/** A text made only of one to three emoji: shown large, without a bubble. */
fun isBigEmoji(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 24) return false
    if (t.any { it.isLetterOrDigit() }) return false
    var count = 0
    var i = 0
    while (i < t.length) {
        val cp = t.codePointAtCompat(i)
        val size = if (cp > 0xFFFF) 2 else 1
        // Joiners, variation selectors and skin tones belong to the emoji before them.
        if (cp != 0x200D && cp !in 0xFE00..0xFE0F && cp !in 0x1F3FB..0x1F3FF && cp != 0x20E3 && !t[i].isWhitespace()) {
            if (i == 0 || t.codePointBeforeCompat(i) != 0x200D) count++
        }
        if (cp < 0x2000 && !t[i].isWhitespace()) return false
        i += size
    }
    return count in 1..3
}

private fun String.codePointAtCompat(i: Int): Int {
    val c = this[i]
    if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
        return ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00) + 0x10000
    }
    return c.code
}

private fun String.codePointBeforeCompat(i: Int): Int {
    val c = this[i - 1]
    if (c.isLowSurrogate() && i - 2 >= 0 && this[i - 2].isHighSurrogate()) {
        return ((this[i - 2].code - 0xD800) shl 10) + (c.code - 0xDC00) + 0x10000
    }
    return c.code
}

enum class ShareKind { SONG, VIDEO }

/** A song or a video sent in a conversation: enough to show its card and to play it. */
data class SharedItem(
    val kind: ShareKind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnail: String? = null,
    /** Where it starts when opened (a second of the song or the video); 0 from the start. */
    val startMs: Long = 0,
    /** A line of the song's lyrics sent with it (it starts where the line is sung). */
    val quote: String = "",
)

/**
 * A photo in a conversation: its size, and a tiny blurred preview (base64 JPEG, ~1 KB) that shows at once; the photo
 * itself ([id] under media/) is fetched when it is shown.
 */
/** [key]: in an encrypted conversation, the key the file at [url] is sealed with. */
data class ChatImage(val id: String, val width: Int, val height: Int, val thumb: String, val url: String? = null, val key: String = "")

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
    /** In an encrypted conversation: the key the file at [url] is sealed with. */
    val key: String = "",
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
data class GroupInfo(
    val id: String,
    val name: String,
    val photo: String?,
    val members: List<GroupMember>,
    val createdBy: String?,
    val description: String = "",
    val settings: GroupSettings = GroupSettings(),
    /** Made in the secret space: hidden in every member's list, its notifications say nothing. */
    val secret: Boolean = false,
    /** New members may read what was said before they came (an encrypted group's keys are shared with them). */
    val shareHistory: Boolean = false,
    /** A group that deletes itself then (with everything in it); 0: never. */
    val endsAt: Long = 0L,
) {
    /** Those in it: everyone but who is still invited (a secret group's invitation not yet taken). */
    val joined: List<GroupMember> get() = members.filter { !it.invited }
}

/** [invited]: asked into a secret group and not yet said yes: shown apart, told nothing until then. */
data class GroupMember(val user: ChatUser, val admin: Boolean, val invited: Boolean = false)

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
/** A photo ready to send; [hd]: the same photo at full quality (made when asked: "HD"), null when it is no bigger. */
class PickedImage(val jpeg: ByteArray, val width: Int, val height: Int, val thumb: String, val hd: (suspend () -> PickedImage)? = null)

/** The uid of the owner's Claude (see server/qmusic-push/claude-bot.js). */
const val CLAUDE_BOT_UID = "claudebot"

/** The uid of the owner's Antigravity (Gemini, see server/qmusic-push/antigravity-bot.js). */
const val ANTIGRAVITY_BOT_UID = "antigravitybot"

/** One of the owner's AI assistants in the messages (Claude or Antigravity). */
fun isAiBot(uid: String): Boolean = uid == CLAUDE_BOT_UID || uid == ANTIGRAVITY_BOT_UID

/** An assistant's name as people see it. */
fun aiBotName(uid: String): String = if (uid == ANTIGRAVITY_BOT_UID) "Antigravity" else "Claude"

/** Where a bot's state and settings live: claudeState / antigravityState, claudeSettings / antigravitySettings. */
fun aiBotNode(bot: String, kind: String): String = (if (bot == ANTIGRAVITY_BOT_UID) "antigravity" else "claude") + kind

/** Nuvelo's own account (its team's messages to people). */
const val NUVELO_UID = "nuvelo"

/** The one person whose Claude it is: only they can call it into a conversation. */
const val CLAUDE_OWNER_UID = "2hxOw0dRcwUwsW7N6ogH4fRcqXo1"

/** A message that calls Claude into a conversation: "claude" anywhere in it, as a word or its start ("Claude'a sor"). */
fun callsClaude(text: String): Boolean = Regex("(^|[^A-Za-z0-9ÇĞİÖŞÜçğıöşü])@?claude", RegexOption.IGNORE_CASE).containsMatchIn(text)

/** A message that calls Antigravity: "antigravity" (or "gemini") anywhere in it, as a word or its start. */
fun callsAntigravity(text: String): Boolean = Regex("(^|[^A-Za-z0-9ÇĞİÖŞÜçğıöşü])@?(antigravity|gemini)", RegexOption.IGNORE_CASE).containsMatchIn(text)

/** Where two people stand: nothing yet, a request one way or the other, or friends. */
enum class FriendStatus { NONE, REQUESTED, INCOMING, FRIENDS }

/** The owner's Claude: the model picked ("opus") and how it calls itself ("Claude Opus 5.5"). */
data class ClaudeState(val model: String, val label: String)

/** The models the owner can pick, with a word about each. */
val CLAUDE_MODELS = listOf("opus", "sonnet", "haiku")

/** Antigravity's models (Gemini 4 Neon, Carbon, Argon; Flash the quick one). */
val ANTIGRAVITY_MODELS = listOf("neon", "carbon", "argon", "flash")

/** The models of [bot]. */
fun aiBotModels(bot: String): List<String> = if (bot == ANTIGRAVITY_BOT_UID) ANTIGRAVITY_MODELS else CLAUDE_MODELS

/** Claude at work on a message: [phase] is looking (at a photo), thinking or writing. */
data class ClaudeAssist(
    val target: String,
    val phase: String,
    /** What it does right now, in words ("Web'de arıyor: ..."); empty when nothing particular. */
    val detail: String = "",
    /** Its thoughts so far (the last part). */
    val thought: String = "",
    /** Which assistant is at work (claudebot or antigravitybot). */
    val by: String = CLAUDE_BOT_UID,
)

/** The message a message answers: shown quoted above it. */
/** A quoted message; [thumb] is a tiny base64 picture (a status answered). */
data class ChatReply(val id: String, val from: String, val text: String, val thumb: String = "")

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
    /** The sender changed the text after sending. */
    val edited: Boolean = false,
    /** The sender deleted it for everyone ("This message was deleted"). */
    val deleted: Boolean = false,
    /** Passed on from another conversation. */
    val forwarded: Boolean = false,
    /** A disappearing message: when it goes (server time), 0 when it stays. */
    val expireAt: Long = 0,
    val poll: ChatPoll? = null,
    val event: ChatEvent? = null,
    val location: ChatLocation? = null,
    val contact: ChatContact? = null,
    /** A photo that opens once, then is gone for whoever got it. */
    val viewOnce: Boolean = false,
    /** Who the message names with @ (uids). */
    val mentions: Set<String> = emptySet(),
    /** A GIF or a sticker (KLIPY). */
    val gif: ChatGif? = null,
    /** Sent end-to-end encrypted; [locked]: its key is not on this phone (yet), so it cannot be read. */
    val e2e: Boolean = false,
    val locked: Boolean = false,
    /** An interactive /oyna chat game card (live state lives under games/{chatId}/{id}). */
    val game: com.qmusic.ui.chat.games.ChatGameRef? = null,
)

/** A GIF or a sticker in a message: where it is and its size; a sticker shows without a bubble. */
data class ChatGif(val url: String, val width: Int, val height: Int, val sticker: Boolean)

/** One result of a GIF or sticker search: a small moving preview and the one that is sent. */
data class GifItem(val id: String, val preview: String, val url: String, val width: Int, val height: Int)

/** A message pinned to the top of its conversation ([expireAt] = 0 means permanent). */
data class PinnedMessage(val messageId: String, val text: String, val from: String, val at: Long, val expireAt: Long = 0L)

/** What a group lets its members do: only admins write, only admins change its name, photo and description. */
data class GroupSettings(val sendAdmins: Boolean = false, val editAdmins: Boolean = false)

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
    /** How many messages came since the person last looked. */
    val unreadCount: Int = 0,
    /** Until when the person read this conversation. */
    val readAt: Long = 0,
    /** No notifications until then (-1: for good; 0: not muted). */
    val mutedUntil: Long = 0,
    /** Someone is typing in it (when they last typed, and who, for groups). */
    val typingAt: Long = 0,
    val typingName: String = "",
    /** What they are doing is recording a voice message (with [typingAt]). */
    val typingRec: Boolean = false,
    /** Marked as a favourite (the Favourites filter of the list). */
    val favorite: Boolean = false,
    /** Kept in the locked chats (opened with the phone's lock; notifications do not show it). */
    val locked: Boolean = false,
    /** Someone named the person with @ in what came since they last read (a group). */
    val mentioned: Boolean = false,
    /** Kept in the secret space (shown nowhere else; its notifications say only "New message"). */
    val secret: Boolean = false,
    /** An invitation to a secret group, not accepted yet: nothing of it shows until it is. */
    val invite: Boolean = false,
) {
    fun muted(now: Long): Boolean = mutedUntil == -1L || mutedUntil > now

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
    suspend fun markUnread(chatId: String) {}
    suspend fun clearChat(chatId: String) {}
    suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit>

    /** The people this person blocked: their messages do not reach them. */
    fun blocked(): Flow<Set<String>>
    suspend fun setBlocked(uid: String, blocked: Boolean): Result<Unit>

    /**
     * Reports [target] (a person or a group) to Nuvelo, with the [reason] and the last messages of the conversation
     * as this phone reads them ([recent]: who and what, so an encrypted chat can be looked at too).
     */
    suspend fun report(chatId: String, target: String, reason: String, recent: List<Pair<String, String>>): Result<Unit> =
        Result.failure(UnsupportedOperationException())

    /** A voice message as text (done on Nuvelo's server by EVREN; an encrypted one is opened on the phone first). */
    suspend fun transcribe(voice: ChatAttachment): Result<String> = Result.failure(UnsupportedOperationException())

    /** Reads [text] aloud (EVREN's voice "kaan"); a second call while it reads stops it. Done: when it finished. */
    suspend fun speak(text: String): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** [text] in the language [to] ("tr", "en", ...). */
    suspend fun translate(text: String, to: String): Result<String> = Result.failure(UnsupportedOperationException())

    /** Removes the person's profile, @username and every message they sent. */
    suspend fun deleteAccount(): Result<Unit>

    /** Sends photos (one, or an album) with an optional caption. */
    suspend fun sendImages(other: ChatUser, images: List<PickedImage>, caption: String, replyTo: ChatReply?, viewOnce: Boolean = false): Result<Unit> =
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
    fun claudeState(bot: String = CLAUDE_BOT_UID): Flow<ClaudeState?> = kotlinx.coroutines.flow.flowOf(null)

    /** Picks the model of the owner's Claude ("opus", "sonnet", "haiku"). */
    suspend fun setClaudeModel(model: String, bot: String = CLAUDE_BOT_UID) {}

    /** Starts the owner's conversation with Claude afresh. */
    suspend fun resetClaude(bot: String = CLAUDE_BOT_UID) {}

    /** The photo [id] of [chatId] (kept on the phone after the first time). */
    suspend fun imageBytes(chatId: String, id: String): ByteArray? = null

    /** What Claude is doing in [chatId] right now (null when nothing). */
    fun assist(chatId: String): Flow<ClaudeAssist?> = kotlinx.coroutines.flow.flowOf(null)

    /** Whether [uid] is online, and since when they are not. */
    fun presence(uid: String): Flow<Presence?> = kotlinx.coroutines.flow.flowOf(null)

    /** The other person is typing in [chatId]. */
    fun typing(chatId: String, otherUid: String): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

    /** The other side (someone else, in a group) is recording a voice message now. */
    fun recording(chatId: String, otherUid: String): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    suspend fun setRecording(chatId: String, recording: Boolean) {}
    suspend fun setTyping(chatId: String, typing: Boolean) {}

    /** The reactions of a conversation: message id → (person → emoji). */
    fun reactions(chatId: String): Flow<Map<String, Map<String, String>>> = kotlinx.coroutines.flow.flowOf(emptyMap())

    /** Puts [emoji] on a message (null takes ours away); [author] and [preview] tell its writer about it. */
    suspend fun react(chatId: String, messageId: String, emoji: String?, author: String? = null, preview: String = "") {}

    /** Voice and video calls, where this platform has them. */
    val calls: CallService? get() = null

    /** Bans, announcements, the least version, and for admins the panel (null where there is no server). */
    val admin: AdminConsole? get() = null

    // ------------------------------------------------------------------ files, voice, video

    /** Sends a voice message, a video or a file (it goes to the media server first). */
    suspend fun sendFile(other: ChatUser, file: PickedFile, caption: String, replyTo: ChatReply?): Result<Unit> =
        Result.failure(UnsupportedOperationException())

    /** Sends a file with optional HD quality preservation for video (delegates to standard sendFile by default). */
    suspend fun sendFile(other: ChatUser, file: PickedFile, caption: String, replyTo: ChatReply?, hd: Boolean): Result<Unit> =
        sendFile(other, file, caption, replyTo)

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

    // ------------------------------------------------------------------ more of a conversation

    /** The last [limit] messages of a conversation (scrolling up asks for more). */
    fun messages(chatId: String, limit: Int): Flow<List<ChatMessage>> = messages(chatId)

    /** Until when each member read [chatId], and until when their phone has its messages. */
    fun readMarks(chatId: String): Flow<Map<String, Long>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    fun deliveredMarks(chatId: String): Flow<Map<String, Long>> = kotlinx.coroutines.flow.flowOf(emptyMap())

    suspend fun editMessage(chatId: String, messageId: String, text: String): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Blanks a message of ours for everyone ("This message was deleted"). */
    suspend fun deleteForEveryone(chatId: String, messageId: String): Result<Unit> = deleteMessage(chatId, messageId)

    /** Hides a message on the person's own phones only. */
    suspend fun deleteForMe(chatId: String, messageId: String) {}
    fun hidden(chatId: String): Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet())

    /** Sends a copy of [message] (its text, photos, file, song) to each of [targets]. */
    suspend fun forward(fromChatId: String, message: ChatMessage, targets: List<ChatUser>): Result<Unit> = Result.failure(UnsupportedOperationException())

    fun starred(): Flow<List<StarredMessage>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun setStarred(chatId: String, message: ChatMessage, fromName: String, chatName: String, starred: Boolean) {}

    /** Disappearing messages of [chatId]: how long they stay (ms), 0 for off. */
    fun ttl(chatId: String): Flow<Long> = kotlinx.coroutines.flow.flowOf(0L)
    suspend fun setTtl(other: ChatUser, ttlMs: Long): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** No notifications from [chatId] until [until] (-1: for good, 0: on again). */
    suspend fun setMuted(chatId: String, until: Long) {}

    // ------------------------------------------------------------------ the person

    suspend fun updateProfile(name: String, about: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun setProfilePhoto(image: PickedImage): Result<Unit> = Result.failure(UnsupportedOperationException())
    val privacy: StateFlow<Privacy> get() = kotlinx.coroutines.flow.MutableStateFlow(Privacy())
    suspend fun setPrivacy(privacy: Privacy) {}

    // ------------------------------------------------------------------ more of a group

    suspend fun setGroupPhoto(groupId: String, image: PickedImage): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun setGroupDescription(groupId: String, text: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun setAdmin(groupId: String, member: ChatUser, admin: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Group voice and video calls, where this platform has them. */
    val groupCalls: GroupCallService? get() = null

    /** The card of a link (fetched once, then kept). */
    suspend fun linkPreview(url: String): LinkPreview? = null

    // ------------------------------------------------------------------ polls, places, contacts

    suspend fun sendPoll(other: ChatUser, poll: ChatPoll): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun sendEvent(other: ChatUser, event: ChatEvent): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** The votes of [chatId]'s polls: message id → (person → the choices they picked). */
    fun pollVotes(chatId: String): Flow<Map<String, Map<String, List<Int>>>> = kotlinx.coroutines.flow.flowOf(emptyMap())

    /** The person's choices in a poll (empty takes their vote back). */
    suspend fun vote(chatId: String, messageId: String, choices: List<Int>) {}

    /** Closes voting on a poll created by the signed-in user. */
    suspend fun closePoll(chatId: String, messageId: String): Result<Unit> = Result.failure(UnsupportedOperationException())

    suspend fun sendLocation(other: ChatUser, location: ChatLocation): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun sendContact(other: ChatUser, contact: ChatContact): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** The person's calls, newest first. */
    fun callLog(): Flow<List<CallLogEntry>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun clearCallLog() {}

    /** Takes these calls out of the person's list. */
    suspend fun deleteCalls(ids: List<String>) {}

    /** Until when the person looked at their calls (missed ones after it make the Calls tab's badge). */
    fun callsSeenAt(): Flow<Long> = kotlinx.coroutines.flow.flowOf(0L)
    suspend fun setCallsSeen() {}

    suspend fun setFavorite(chatId: String, favorite: Boolean) {}

    // ------------------------------------------------------------------ chat games

    /** Active and recent games in [chatId], keyed by game id. */
    fun games(chatId: String): Flow<Map<String, com.qmusic.ui.chat.games.GameSession>> =
        kotlinx.coroutines.flow.flowOf(emptyMap())

    /** Head-to-head game win counts in [chatId]. */
    fun gameStats(chatId: String): Flow<com.qmusic.ui.chat.games.ChatGameStats> =
        kotlinx.coroutines.flow.flowOf(com.qmusic.ui.chat.games.ChatGameStats())

    /** Creates a game under games/{chatId}/{gameId} and posts its invitation card in the chat. */
    suspend fun createGame(
        other: ChatUser,
        initial: com.qmusic.ui.chat.games.GameSession,
        preview: String,
    ): Result<String> = Result.failure(UnsupportedOperationException())

    /** Atomically updates games/{chatId}/{gameId} via server transaction; returning null aborts. */
    suspend fun updateGame(
        chatId: String,
        gameId: String,
        transform: (com.qmusic.ui.chat.games.GameSession) -> com.qmusic.ui.chat.games.GameSession?,
    ): Result<com.qmusic.ui.chat.games.GameSession> = Result.failure(UnsupportedOperationException())

    /** Records a finished game once under gameStats/{chatId} (guarded by counted/{gameId}). */
    suspend fun recordGameWin(chatId: String, gameId: String, winnerUid: String?): Result<Unit> = Result.success(Unit)

    /** Sends a lightweight push notification when a move hands the turn to [toUid]. */
    suspend fun sendGameTurnNotify(other: ChatUser, toUid: String, gameTitle: String) {}

    // ------------------------------------------------------------------ status

    /** The person's own statuses (the last 24 hours), oldest first. */
    fun myStatuses(): Flow<List<StatusItem>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** Friends' statuses the person may see, the ones with something unseen first. */
    fun friendStatuses(): Flow<List<StatusGroup>> = kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun postTextStatus(text: String, background: Long, font: Int = 0, extras: StatusExtras = StatusExtras()): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun postPhotoStatus(image: PickedImage, caption: String, extras: StatusExtras = StatusExtras()): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun postVideoStatus(file: PickedFile, caption: String, extras: StatusExtras = StatusExtras()): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun deleteStatus(id: String) {}

    /** The person saw [item]: its owner's list of viewers gets them. */
    suspend fun markStatusSeen(item: StatusItem) {}

    /** Who saw the person's status [id], and when. */
    fun statusViewers(id: String): Flow<List<Pair<ChatUser, Long>>> = kotlinx.coroutines.flow.flowOf(emptyList())

    val statusPrivacy: StateFlow<StatusPrivacy> get() = kotlinx.coroutines.flow.MutableStateFlow(StatusPrivacy())
    suspend fun setStatusPrivacy(privacy: StatusPrivacy) {}

    /** Whose statuses the person muted (they go to the bottom, without a ring). */
    fun mutedStatuses(): Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet())
    suspend fun setStatusMuted(owner: String, muted: Boolean) {}

    // ------------------------------------------------------------------ more

    suspend fun setLocked(chatId: String, locked: Boolean) {}

    /** Moves the conversation into the secret space (or back out); only for this person. */
    suspend fun setSecret(chatId: String, secret: Boolean) {}

    /** A group made in the secret space: hidden for everyone, encrypted, the others invited (they accept first). */
    suspend fun createSecretGroup(name: String, members: List<ChatUser>, endsAt: Long = 0L): Result<ChatUser> = Result.failure(UnsupportedOperationException())

    /** Takes a secret group's invitation: it shows, and its messages can be read. */
    suspend fun acceptInvite(chatId: String): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Turns it down: out of the group quietly (no one is told). */
    suspend fun declineInvite(chatId: String): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** A conversation started in the secret space: its first message keeps it there. */
    fun markSecretPending(chatId: String) {}

    /** Turns end-to-end encryption on for the conversation (every member must have a key). */
    suspend fun enableEncryption(other: ChatUser): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Whether the group lets new members read its past. */
    suspend fun setShareHistory(groupId: String, share: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** The group's invite code (made when there is none; admins only). */
    suspend fun groupInviteCode(groupId: String, renew: Boolean = false): Result<String> = Result.failure(UnsupportedOperationException())
    suspend fun groupInvite(code: String): GroupInvite? = null
    suspend fun joinByInvite(code: String): Result<ChatUser> = Result.failure(UnsupportedOperationException())

    fun broadcasts(): Flow<List<Broadcast>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun saveBroadcast(id: String?, name: String, members: List<ChatUser>): Result<String> = Result.failure(UnsupportedOperationException())
    suspend fun deleteBroadcast(id: String) {}
    suspend fun logBroadcast(id: String, text: String) {}

    /** GIFs or stickers for [query] (the popular ones when it is empty), through Qeyf's server. */
    suspend fun searchGifs(stickers: Boolean, query: String, page: Int): Result<List<GifItem>> = Result.success(emptyList())
    suspend fun sendGif(other: ChatUser, gif: ChatGif, replyTo: ChatReply?): Result<Unit> = Result.failure(UnsupportedOperationException())

    // ------------------------------------------------------------------ pins, view once, mentions, group settings, likes

    fun pinned(chatId: String): Flow<List<PinnedMessage>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun pin(chatId: String, message: ChatMessage, preview: String, pinned: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun pin(chatId: String, message: ChatMessage, preview: String, pinned: Boolean, expireAt: Long): Result<Unit> =
        pin(chatId, message, preview, pinned)

    /** Who opened each view-once photo: message id to the uids. */
    fun opened(chatId: String): Flow<Map<String, Set<String>>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    suspend fun markOpened(chatId: String, messageId: String) {}

    /** Sends a text naming [mentions] (they hear it even in a muted group). */
    suspend fun sendMentioning(other: ChatUser, text: String, replyTo: ChatReply?, mentions: Set<String>): Result<Unit> = send(other, text, null, replyTo)

    suspend fun setGroupSettings(groupId: String, settings: GroupSettings): Result<Unit> = Result.failure(UnsupportedOperationException())

    fun statusLikes(id: String): Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet())
    fun likedStatus(item: StatusItem): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

    /** The wallpaper behind every chat's messages (an index into the wallpapers; 0 is none). */
    fun wallpaper(): Flow<Int> = kotlinx.coroutines.flow.flowOf(0)
    suspend fun setWallpaper(index: Int) {}
    suspend fun likeStatus(item: StatusItem, liked: Boolean) {}

    /** Puts the person's photo, as their privacy says, on what others keep of them (groups, friends, chats). */
    suspend fun refreshMyCopies() {}

    // ------------------------------------------------------------------ media and storage

    /** When photos load by themselves: "always", "wifi" (not on mobile data) or "never" (a tap loads each). */
    val autoDownload: StateFlow<String> get() = kotlinx.coroutines.flow.MutableStateFlow("always")
    fun setAutoDownload(mode: String) {}

    /** A photo may come from the network now without a tap (the setting and the connection allow it). */
    fun mayAutoDownload(): Boolean = true

    /** How much the messages' media take on the phone, and clearing it (it comes back from the server when shown). */
    suspend fun mediaCacheBytes(): Long = 0L
    suspend fun clearMediaCache() {}

    /** The last [limit] messages of a conversation, read once (for searching across conversations). */
    suspend fun recentMessages(chatId: String, limit: Int): List<ChatMessage> = emptyList()

    /** Every message of a conversation, oldest first (for exporting it). */
    suspend fun allMessages(chatId: String): List<ChatMessage> = emptyList()
}

/** A conversation's id: both people's ids in order, so each pair has exactly one. */
fun chatIdOf(a: String, b: String): String = if (a < b) "${a}_$b" else "${b}_$a"

/** 3-20 characters: lowercase letters, digits, dot and underscore. */
fun isValidUsername(name: String): Boolean = name.length in 3..20 && name.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }

/** Asks the app to open the messages section (a tapped notification); [user] opens their conversation. */
data class ChatLaunchRequest(
    val user: ChatUser?,
    val profile: Boolean = false,
    val username: String? = null,
    /** A group invite link was opened (qeyf://g/<code>). */
    val invite: String? = null,
    /** A call link was opened (qeyf://c/<username>). */
    val callUsername: String? = null,
)

object ChatLaunch {
    val request = kotlinx.coroutines.flow.MutableStateFlow<ChatLaunchRequest?>(null)
}

/** The backend of this platform, or null where there is none. */
@Composable
expect fun rememberChatBackend(): ChatBackend?
