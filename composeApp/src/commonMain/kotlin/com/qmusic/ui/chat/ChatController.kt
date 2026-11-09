package com.qmusic.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The pages of the messages section, the last one on screen. */
sealed interface ChatRoute {
    data object NewChat : ChatRoute
    data object Friends : ChatRoute
    data class Profile(val user: ChatUser) : ChatRoute
    data class Conversation(val other: ChatUser) : ChatRoute
}

/**
 * The messages section: the account, the list of conversations, the one open, and what is being typed. Lives as long
 * as the app (the unread count shows on the entry button even while the section is closed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatController(
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
) {
    val account: StateFlow<ChatAccount> = backend.account

    /** Voice and video calls (null where the platform has none). */
    val calls: CallService? = backend.calls

    fun reportError(message: String) {
        _error.value = message
    }

    private val ready = account.map { it as? ChatAccount.Ready }

    private val blockedIds: StateFlow<Set<String>> = ready
        .flatMapLatest { if (it == null) flowOf(emptySet()) else backend.blocked().catch { emit(emptySet()) } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Conversations, newest first (without the people blocked). */
    val threads: StateFlow<List<ChatThread>?> = ready
        .flatMapLatest { if (it == null) flowOf(null) else backend.threads().catch { emit(emptyList()) } }
        .combine(blockedIds) { list, blocked -> list?.filter { it.other.uid !in blocked } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** How many conversations have something not read: the badge on the entry button. */
    val unreadCount: StateFlow<Int> = threads
        .map { list -> list?.count { it.unread } ?: 0 }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private val _routes = MutableStateFlow<List<ChatRoute>>(emptyList())
    val routes: StateFlow<List<ChatRoute>> = _routes.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** A short message for the person when something did not work (cleared once shown). */
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // ------------------------------------------------------------------ account

    fun signIn() = run { backend.signIn() }

    fun signOut() {
        _routes.value = emptyList()
        backend.signOut()
    }

    private val _usernameTaken = MutableStateFlow(false)
    val usernameTaken: StateFlow<Boolean> = _usernameTaken.asStateFlow()

    fun saveProfile(name: String, username: String) {
        _usernameTaken.value = false
        run {
            backend.saveProfile(name.trim(), username.trim().lowercase()).onFailure { if (it is UsernameTaken) _usernameTaken.value = true }
        }
    }

    fun deleteAccount() {
        _routes.value = emptyList()
        run { backend.deleteAccount() }
    }

    private fun run(block: suspend () -> Result<*>) {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            try {
                block().onFailure { e -> if (e !is UsernameTaken) _error.value = e.message ?: e::class.simpleName }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _busy.value = false
            }
        }
    }

    // ------------------------------------------------------------------ pages

    fun openNewChat() = _routes.update { it + ChatRoute.NewChat }

    fun openFriends() = _routes.update { list -> list.filter { it !is ChatRoute.Friends } + ChatRoute.Friends }

    fun openProfile(user: ChatUser) = _routes.update { list -> list.filterNot { it is ChatRoute.Profile && it.user.uid == user.uid } + ChatRoute.Profile(user) }

    // ------------------------------------------------------------------ friends

    val friends: StateFlow<List<ChatUser>> = ready
        .flatMapLatest { if (it == null) flowOf(emptyList()) else backend.friends().catch { emit(emptyList()) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val friendRequests: StateFlow<List<ChatUser>> = ready
        .flatMapLatest { if (it == null) flowOf(emptyList()) else backend.friendRequests().catch { emit(emptyList()) } }
        .combine(blockedIds) { list, blocked -> list.filter { it.uid !in blocked } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val sent: StateFlow<Set<String>> = ready
        .flatMapLatest { if (it == null) flowOf(emptySet()) else backend.sentRequests().catch { emit(emptySet()) } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Where the person stands with [uid], kept up to date. */
    fun friendStatus(uid: String) = combine(friends, friendRequests, sent) { f, incoming, out ->
        when {
            f.any { it.uid == uid } -> FriendStatus.FRIENDS
            incoming.any { it.uid == uid } -> FriendStatus.INCOMING
            uid in out -> FriendStatus.REQUESTED
            else -> FriendStatus.NONE
        }
    }

    fun requestFriend(user: ChatUser) {
        scope.launch { backend.requestFriend(user).onFailure { _error.value = it.message } }
    }

    fun acceptFriend(user: ChatUser) {
        scope.launch { backend.acceptFriend(user).onFailure { _error.value = it.message } }
    }

    fun unfriend(user: ChatUser) {
        scope.launch { backend.unfriend(user).onFailure { _error.value = it.message } }
    }

    /** Opens someone's profile by id (a tapped friend notification). */
    fun openProfileOf(uid: String, fallback: ChatUser) {
        scope.launch { openProfile(runCatching { backend.user(uid) }.getOrNull() ?: fallback) }
    }

    fun openConversation(other: ChatUser) {
        // Opened from the people search: the search page is done.
        _routes.update { list -> list.filter { it !is ChatRoute.NewChat && !(it is ChatRoute.Conversation && it.other.uid == other.uid) } + ChatRoute.Conversation(other) }
    }

    /** False when there was nothing open (the section itself should close). */
    fun pop(): Boolean {
        if (_routes.value.isEmpty()) return false
        _routes.update { it.dropLast(1) }
        return true
    }

    // ------------------------------------------------------------------ people search

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _found = MutableStateFlow<List<ChatUser>?>(null)
    val found: StateFlow<List<ChatUser>?> = _found.asStateFlow()
    private var searchJob: Job? = null

    fun setQuery(text: String) {
        _query.value = text
        searchJob?.cancel()
        val prefix = text.trim().removePrefix("@").lowercase()
        if (prefix.isEmpty()) {
            _found.value = null
            return
        }
        searchJob = scope.launch {
            delay(SEARCH_DELAY_MS)
            val me = (account.value as? ChatAccount.Ready)?.me?.uid
            _found.value = backend.findUsers(prefix).getOrNull().orEmpty().filter { it.uid != me }
        }
    }

    // ------------------------------------------------------------------ a conversation

    fun chatIdWith(other: ChatUser): String? = (account.value as? ChatAccount.Ready)?.me?.uid?.let { chatIdOf(it, other.uid) }

    fun messages(other: ChatUser) = chatIdWith(other)?.let { id -> backend.messages(id).catch { emit(emptyList()) } } ?: emptyFlow()

    fun otherReadAt(other: ChatUser) = chatIdWith(other)?.let { id -> backend.otherReadAt(id, other.uid).catch { emit(0L) } } ?: flowOf(0L)

    fun markRead(other: ChatUser) {
        val id = chatIdWith(other) ?: return
        scope.launch { runCatching { backend.markRead(id) } }
    }

    fun send(other: ChatUser, text: String, share: SharedItem? = null, replyTo: ChatReply? = null): Boolean {
        val body = text.trim()
        if (body.isEmpty() && share == null) return false
        if (body.length > MAX_LENGTH) {
            _error.value = "max $MAX_LENGTH"
            return false
        }
        scope.launch {
            backend.send(other, body, share, replyTo).onFailure { e -> if (e !is CancellationException) _error.value = e.message }
        }
        return true
    }

    fun sendImages(other: ChatUser, images: List<PickedImage>, caption: String, replyTo: ChatReply? = null) {
        if (images.isEmpty()) return
        scope.launch {
            backend.sendImages(other, images.take(MAX_PHOTOS), caption.trim(), replyTo).onFailure { e -> if (e !is CancellationException) _error.value = e.message }
        }
    }

    /** The owner's Claude: the model answering now. */
    val claudeState: StateFlow<ClaudeState?> = ready
        .flatMapLatest { if (it?.me?.uid == CLAUDE_OWNER_UID) backend.claudeState().catch { emit(null) } else flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun setClaudeModel(model: String) {
        scope.launch { runCatching { backend.setClaudeModel(model) } }
    }

    fun resetClaude() {
        scope.launch { runCatching { backend.resetClaude() } }
    }

    suspend fun imageBytes(other: ChatUser, id: String): ByteArray? = chatIdWith(other)?.let { backend.imageBytes(it, id) }

    fun presence(other: ChatUser) = backend.presence(other.uid).catch { emit(null) }

    fun assist(other: ChatUser) = chatIdWith(other)?.let { id -> backend.assist(id).catch { emit(null) } } ?: flowOf(null)

    fun typing(other: ChatUser) = chatIdWith(other)?.let { id -> backend.typing(id, other.uid).catch { emit(false) } } ?: flowOf(false)

    private var typingSince = 0L
    private var typingStop: Job? = null

    /** Called as the person types: tells the other side, at most every few seconds, and stops a while after. */
    fun onTyping(other: ChatUser, now: Long) {
        val id = chatIdWith(other) ?: return
        if (now - typingSince > TYPING_EVERY_MS) {
            typingSince = now
            scope.launch { runCatching { backend.setTyping(id, true) } }
        }
        typingStop?.cancel()
        typingStop = scope.launch {
            delay(TYPING_IDLE_MS)
            stopTyping(other)
        }
    }

    fun stopTyping(other: ChatUser) {
        val id = chatIdWith(other) ?: return
        typingSince = 0L
        typingStop?.cancel()
        scope.launch { runCatching { backend.setTyping(id, false) } }
    }

    fun reactions(other: ChatUser) = chatIdWith(other)?.let { id -> backend.reactions(id).catch { emit(emptyMap()) } } ?: flowOf(emptyMap())

    fun react(other: ChatUser, message: ChatMessage, emoji: String?) {
        val id = chatIdWith(other) ?: return
        scope.launch { runCatching { backend.react(id, message.id, emoji) } }
    }

    fun delete(other: ChatUser, message: ChatMessage) {
        val id = chatIdWith(other) ?: return
        scope.launch { backend.deleteMessage(id, message.id).onFailure { _error.value = it.message } }
    }

    val blocked: StateFlow<Set<String>> = blockedIds

    fun setBlocked(other: ChatUser, block: Boolean) {
        scope.launch { backend.setBlocked(other.uid, block).onFailure { _error.value = it.message } }
        if (block) _routes.update { list -> list.filterNot { it is ChatRoute.Conversation && it.other.uid == other.uid } }
    }

    companion object {
        const val MAX_LENGTH = 4000
        const val MAX_PHOTOS = 10
        private const val TYPING_EVERY_MS = 3_000L
        private const val TYPING_IDLE_MS = 5_000L
        private const val SEARCH_DELAY_MS = 300L
    }
}
