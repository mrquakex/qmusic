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

    fun send(other: ChatUser, text: String, share: SharedItem? = null): Boolean {
        val body = text.trim()
        if (body.isEmpty() && share == null) return false
        if (body.length > MAX_LENGTH) {
            _error.value = "max $MAX_LENGTH"
            return false
        }
        scope.launch {
            backend.send(other, body, share).onFailure { e -> if (e !is CancellationException) _error.value = e.message }
        }
        return true
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
        private const val SEARCH_DELAY_MS = 300L
    }
}
