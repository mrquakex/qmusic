package com.qmusic.ui.chat

import kotlinx.datetime.Clock
import kotlinx.coroutines.flow.distinctUntilChanged
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** The pages of the messages section, the last one on screen. */
/** The tabs of the messages section's bottom bar. */
enum class ChatTab { CHATS, STATUS, GROUPS, CALLS }

/** The filters over the conversation list. */
enum class ChatFilter { ALL, UNREAD, FAVORITES, GROUPS, ARCHIVED }

sealed interface ChatRoute {
    data object NewChat : ChatRoute
    data object Friends : ChatRoute
    data class Profile(val user: ChatUser) : ChatRoute
    data class Conversation(val other: ChatUser) : ChatRoute
    data object NewGroup : ChatRoute
    data class GroupInfo(val group: ChatUser) : ChatRoute
    data class AddMembers(val group: ChatUser) : ChatRoute
    data object Archived : ChatRoute
    data object Starred : ChatRoute
    data object Calls : ChatRoute
    data class CallDetails(val peer: ChatUser) : ChatRoute
    data class NewCall(val group: Boolean) : ChatRoute
    data class StatusViewer(val ownerUid: String, val mine: Boolean) : ChatRoute
    data object TextStatus : ChatRoute
    data object StatusPrivacyPage : ChatRoute
    data object MyQr : ChatRoute
    data object LockedFolder : ChatRoute
    data class JoinGroup(val code: String) : ChatRoute
    data object Broadcasts : ChatRoute
    data class BroadcastEdit(val id: String?) : ChatRoute
    data class BroadcastChat(val id: String) : ChatRoute
    data object Settings : ChatRoute
    /** The secret space (behind the PIN), and its pages. */
    data object Secret : ChatRoute
    data object SecretNewGroup : ChatRoute
    data object SecretStartChat : ChatRoute
    data object SecretMove : ChatRoute
    data object SecretSettings : ChatRoute
    /** The safety code of an encrypted conversation with [user]. */
    data class SafetyCode(val user: ChatUser) : ChatRoute
    /** End-to-end encryption: what it is, the backup PIN, encrypting new chats. */
    data object Encryption : ChatRoute
    /** Everyone sharing their live location with this person, on one map. */
    data object LivePeople : ChatRoute
    /** The admin panel (only for admins; the server checks every step again). */
    data object Admin : ChatRoute
    /** People this person has blocked, with one-tap unblock. */
    data object BlockedPeople : ChatRoute
}

/** The routes that belong to the secret space (closed when it locks). */
internal fun ChatRoute.inSecret(): Boolean =
    this is ChatRoute.Secret || this is ChatRoute.SecretNewGroup || this is ChatRoute.SecretStartChat ||
        this is ChatRoute.SecretMove || this is ChatRoute.SecretSettings

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

    /** Bans, announcements, the least version; the admin panel for admins. */
    val admin: AdminConsole? = backend.admin

    fun reportError(message: String) {
        _error.value = message
    }

    private val _info = MutableStateFlow<String?>(null)

    /** A short note for the person ("Copied"), cleared once shown. */
    val info: StateFlow<String?> = _info.asStateFlow()
    fun reportInfo(message: String) { _info.value = message }
    fun clearInfo() { _info.value = null }

    private val ready = account.map { it as? ChatAccount.Ready }

    private val blockedIds: StateFlow<Set<String>> = ready
        .flatMapLatest { if (it == null) flowOf(emptySet()) else backend.blocked().catch { emit(emptySet()) } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Every conversation, pinned ones first, then newest first (without the people blocked). */
    private val allThreads: StateFlow<List<ChatThread>?> = ready
        .flatMapLatest { if (it == null) flowOf(null) else backend.threads().catch { emit(emptyList()) } }
        .combine(blockedIds) { list, blocked ->
            list?.filter { it.other.uid !in blocked }
                ?.sortedWith(compareByDescending<ChatThread> { it.pinned }.thenByDescending { it.lastAt })
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The list: conversations not archived, not locked, not in the secret space. */
    val threads: StateFlow<List<ChatThread>?> = allThreads
        .map { list -> list?.filter { !it.archived && !it.locked && !it.secret } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The conversations moved to "Archived" (locked ones stay in the locked folder). */
    val archived: StateFlow<List<ChatThread>> = allThreads
        .map { list -> list.orEmpty().filter { it.archived && !it.locked && !it.secret } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The locked conversations: opened with the phone's lock, hidden from the list and from notifications. */
    val lockedThreads: StateFlow<List<ChatThread>> = allThreads
        .map { list -> list.orEmpty().filter { it.locked && !it.secret } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The secret space's conversations (invitations first): shown nowhere else. */
    val secretThreads: StateFlow<List<ChatThread>> = allThreads
        .map { list -> list.orEmpty().filter { it.secret }.sortedWith(compareByDescending<ChatThread> { it.invite }.thenByDescending { it.pinned }.thenByDescending { it.lastAt }) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // The app keeps the locked ones' ids so a notification for them shows no name and no text.
        scope.launch { lockedThreads.collect { list -> LockedChats.update(list.map { it.id }.toSet()) } }
        // And the secret ones' (the same, and their calls may hide who calls).
        scope.launch { secretThreads.collect { list -> SecretChats.update(list.map { it.id }.toSet()) } }
    }

    // ------------------------------------------------------------------ the secret space

    /** Moves the conversation into the secret space (it is encrypted from then on) or back to the list. */
    fun setSecret(thread: ChatThread, secret: Boolean) {
        scope.launch {
            runCatching { backend.setSecret(thread.id, secret) }
            if (secret) SecretSpace.defaultTtl.takeIf { it > 0 }?.let { ttl -> backend.setTtl(thread.other, ttl) }
        }
    }

    /** Makes a secret group with [members] (friends only) and opens it. */
    fun createSecretGroup(name: String, members: List<ChatUser>, endsAt: Long = 0L) {
        run {
            backend.createSecretGroup(name, members, endsAt).onSuccess { group ->
                SecretSpace.defaultTtl.takeIf { it > 0 }?.let { ttl -> backend.setTtl(group, ttl) }
                _routes.update { list -> list.filter { it !is ChatRoute.SecretNewGroup } + ChatRoute.Conversation(group) }
            }
        }
    }

    /** Starts a secret conversation with [user]: it stays in the secret space from its first message. */
    fun startSecretChat(user: ChatUser) {
        val id = chatIdWith(user) ?: return
        val existing = allThreads.value?.firstOrNull { it.id == id }
        scope.launch {
            if (existing != null) backend.setSecret(id, true) else {
                backend.markSecretPending(id)
                backend.setSecret(id, true)
            }
        }
        _routes.update { list -> list.filter { it !is ChatRoute.SecretStartChat } + ChatRoute.Conversation(user) }
    }

    /** Secret notes: a conversation with oneself, kept in the secret space and encrypted. */
    fun openSecretNotes(me: ChatUser) {
        val id = chatIdWith(me) ?: return
        scope.launch {
            backend.markSecretPending(id)
            backend.setSecret(id, true)
        }
        openConversation(me)
    }

    /** Everyone sharing a live location with this person now, in any conversation (the secret ones while open). */
    fun livePeople(includeSecret: Boolean): kotlinx.coroutines.flow.Flow<List<Pair<ChatThread, LiveLocation>>> {
        val list = (threads.value.orEmpty() + archived.value + if (includeSecret) secretThreads.value else emptyList()).distinctBy { it.id }
        if (list.isEmpty()) return flowOf(emptyList())
        return combine(list.map { t -> LiveShare.watch(t.id).map { m -> m.values.map { t to it } } }) { parts -> parts.flatMap { it.toList() } }
    }

    fun acceptInvite(thread: ChatThread) {
        scope.launch { backend.acceptInvite(thread.id).onFailure { _error.value = it.message } }
    }

    fun declineInvite(thread: ChatThread) {
        scope.launch { backend.declineInvite(thread.id).onFailure { _error.value = it.message } }
    }

    /** The secret space locked: its pages and its conversations close. */
    fun closeSecret() {
        val secretIds = secretThreads.value.map { it.other.uid }.toSet()
        _routes.update { list ->
            list.filterNot { r -> r.inSecret() || (r is ChatRoute.Conversation && r.other.uid in secretIds) || (r is ChatRoute.GroupInfo && r.group.uid in secretIds) }
        }
    }

    /** Whether the conversation with [other] is kept in the secret space. */
    fun isSecret(other: ChatUser): Boolean = chatIdWith(other)?.let { id -> secretThreads.value.any { it.id == id } } == true

    /** Turns end-to-end encryption on for the conversation with [other]. */
    fun enableEncryption(other: ChatUser, quiet: Boolean = false) {
        scope.launch {
            backend.enableEncryption(other).onFailure { if (!quiet) _error.value = if (it.message == "e2e-nokeys") E2eWords(ChatMusic.english).noKeys else it.message }
        }
    }

    fun setShareHistory(group: ChatUser, share: Boolean) {
        scope.launch { backend.setShareHistory(group.uid, share).onFailure { _error.value = it.message } }
    }

    fun setLocked(thread: ChatThread, locked: Boolean) {
        scope.launch { runCatching { backend.setLocked(thread.id, locked) } }
    }

    /** How many conversations have something not read: the badge on the entry button (archived ones do not count). */
    val unreadCount: StateFlow<Int> = threads
        .map { list -> list?.count { it.unread } ?: 0 }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    fun setPinned(thread: ChatThread, pinned: Boolean) {
        scope.launch { runCatching { backend.setPinned(thread.id, pinned) } }
    }

    fun setArchived(thread: ChatThread, archived: Boolean) {
        scope.launch { runCatching { backend.setArchived(thread.id, archived) } }
    }

    fun openArchived() = _routes.update { list -> list.filter { it !is ChatRoute.Archived } + ChatRoute.Archived }

    fun openStarred() = _routes.update { list -> list.filter { it !is ChatRoute.Starred } + ChatRoute.Starred }

    fun openCalls() = selectTab(ChatTab.CALLS)

    private val _tab = MutableStateFlow(ChatTab.CHATS)
    val tab: StateFlow<ChatTab> = _tab.asStateFlow()

    fun selectTab(tab: ChatTab) {
        _tab.value = tab
        if (tab == ChatTab.CALLS) markCallsSeen()
    }

    /** The calls' badge clears (the calls tab is looked at). */
    fun markCallsSeen() {
        scope.launch { runCatching { backend.setCallsSeen() } }
    }

    private val _filter = MutableStateFlow(ChatFilter.ALL)
    val filter: StateFlow<ChatFilter> = _filter.asStateFlow()
    fun setFilter(filter: ChatFilter) { _filter.value = filter }

    fun push(route: ChatRoute) = _routes.update { list -> list.filterNot { it::class == route::class } + route }

    fun setFavorite(thread: ChatThread, favorite: Boolean) {
        scope.launch { runCatching { backend.setFavorite(thread.id, favorite) } }
    }

    private val callsSeenAt: StateFlow<Long> = ready
        .flatMapLatest { if (it == null) flowOf(0L) else backend.callsSeenAt().catch { emit(0L) } }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    /** Missed calls since the person last looked at the Calls tab: its badge. */
    val missedCalls: StateFlow<Int> = combine(callLogFlow(), callsSeenAt) { log, seen ->
        log.count { it.outcome == CallOutcome.MISSED && it.at > seen }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    private fun callLogFlow() = ready.flatMapLatest { if (it == null) flowOf(emptyList()) else backend.callLog().catch { emit(emptyList()) } }

    // ------------------------------------------------------------------ status

    val myStatuses: StateFlow<List<StatusItem>> = ready
        .flatMapLatest { if (it == null) flowOf(emptyList()) else backend.myStatuses().catch { emit(emptyList()) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val friendStatuses: StateFlow<List<StatusGroup>> = ready
        .flatMapLatest { if (it == null) flowOf(emptyList()) else backend.friendStatuses().catch { emit(emptyList()) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** People with a status the person has not seen yet: their picture gets a ring. */
    /** Whose statuses the person muted. */
    val mutedStatuses: StateFlow<Set<String>> = ready
        .flatMapLatest { if (it == null) flowOf(emptySet()) else backend.mutedStatuses().catch { emit(emptySet()) } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun setStatusMuted(owner: ChatUser, muted: Boolean) {
        scope.launch { runCatching { backend.setStatusMuted(owner.uid, muted) } }
    }

    val unseenStatusOwners: StateFlow<Set<String>> = combine(friendStatuses, mutedStatuses) { list, muted ->
        list.filter { !it.seenAll && it.owner.uid !in muted }.map { it.owner.uid }.toSet()
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _posting = MutableStateFlow(false)

    /** A status going up now. */
    val posting: StateFlow<Boolean> = _posting.asStateFlow()

    private fun post(block: suspend () -> Result<Unit>) {
        _posting.value = true
        scope.launch {
            block().onFailure { _error.value = it.message }
            _posting.value = false
        }
    }

    fun postTextStatus(text: String, background: Long, font: Int = 0, extras: StatusExtras = StatusExtras()) = post { backend.postTextStatus(text, background, font, extras) }
    fun postPhotoStatus(image: PickedImage, caption: String, extras: StatusExtras = StatusExtras()) = post { backend.postPhotoStatus(image, caption, extras) }
    fun postVideoStatus(file: PickedFile, caption: String, extras: StatusExtras = StatusExtras()) = post { backend.postVideoStatus(file, caption, extras) }

    /** What can be shared now (the song playing, the video open), and how a shared song or video is opened: set by the app. */
    var shareables: () -> List<SharedItem> = { emptyList() }
    var openShare: (SharedItem) -> Unit = {}

    /** Songs for [query] from the music side (to add one to a status), and a song's audio address (to play it there). */
    var searchSongs: suspend (String) -> List<SharedItem> = { emptyList() }
    var songAudio: suspend (String) -> String? = { null }

    fun deleteStatus(item: StatusItem) {
        scope.launch {
            runCatching { backend.deleteStatus(item.id) }.onFailure { e -> if (e !is CancellationException) _error.value = e.message }
        }
    }

    fun markStatusSeen(item: StatusItem) {
        scope.launch { runCatching { backend.markStatusSeen(item) } }
    }

    fun statusViewers(item: StatusItem) = backend.statusViewers(item.id).catch { emit(emptyList()) }

    val statusPrivacy: StateFlow<StatusPrivacy> = backend.statusPrivacy

    fun setStatusPrivacy(privacy: StatusPrivacy) {
        scope.launch { runCatching { backend.setStatusPrivacy(privacy) } }
    }

    /** Answers a status: a message to its owner, quoting it. */
    fun replyToStatus(item: StatusItem, text: String, quote: String) {
        send(item.owner, text, replyTo = ChatReply("status_" + item.id, item.owner.uid, quote, item.thumb))
    }

    // ------------------------------------------------------------------ group invite links

    /** The group's invite code (made when there is none yet, which only admins may); [report]: a failure shows. */
    suspend fun inviteCode(group: ChatUser, renew: Boolean = false, report: Boolean = true): String? =
        backend.groupInviteCode(group.uid, renew).onFailure { if (report) _error.value = it.message }.getOrNull()

    suspend fun groupInvite(code: String): GroupInvite? = backend.groupInvite(code)

    fun openInvite(code: String) = push(ChatRoute.JoinGroup(code))

    fun joinGroup(code: String) {
        scope.launch {
            backend.joinByInvite(code)
                .onSuccess { group ->
                    _routes.update { list -> list.filterNot { it is ChatRoute.JoinGroup } }
                    openConversation(group)
                }
                .onFailure { _error.value = it.message }
        }
    }

    // ------------------------------------------------------------------ broadcast lists

    val broadcasts: StateFlow<List<Broadcast>> = ready
        .flatMapLatest { if (it == null) flowOf(emptyList()) else backend.broadcasts().catch { emit(emptyList()) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun saveBroadcast(id: String?, name: String, members: List<ChatUser>) {
        scope.launch {
            backend.saveBroadcast(id, name, members)
                .onSuccess { key ->
                    _routes.update { list -> list.filterNot { it is ChatRoute.BroadcastEdit } }
                    if (id == null) push(ChatRoute.BroadcastChat(key))
                }
                .onFailure { _error.value = it.message }
        }
    }

    fun deleteBroadcast(id: String) {
        _routes.update { list -> list.filterNot { it is ChatRoute.BroadcastChat || it is ChatRoute.BroadcastEdit } }
        scope.launch { runCatching { backend.deleteBroadcast(id) } }
    }

    /** One message to everyone on the list, each in their own conversation (they do not see each other). */
    fun sendBroadcast(list: Broadcast, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        list.members.forEach { send(it, body) }
        scope.launch { runCatching { backend.logBroadcast(list.id, body) } }
    }

    // ------------------------------------------------------------------ media and storage

    val autoDownload: StateFlow<String> get() = backend.autoDownload
    fun setAutoDownload(mode: String) = backend.setAutoDownload(mode)
    fun mayAutoDownload(): Boolean = backend.mayAutoDownload()
    suspend fun mediaCacheBytes(): Long = backend.mediaCacheBytes()
    suspend fun clearMediaCache() = backend.clearMediaCache()

    /** A photo tapped to load: it comes whatever the auto-download setting says. */
    fun forceImage(image: ChatImage) {
        (backend as? ForcesImages)?.forceImage(image.id)
    }

    // ------------------------------------------------------------------ searching all conversations

    /** A message found by searching: in which conversation, and what it said. */
    data class FoundMessage(val thread: ChatThread, val message: ChatMessage)

    /**
     * Messages saying [query], in every conversation (the last few hundred of each), newest first. Read once from the
     * server (cached by it after), so a search is quick the second time.
     */
    suspend fun searchMessages(query: String): List<FoundMessage> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val all = allThreads.value.orEmpty().filter { !it.locked && !it.secret }
        val found = kotlinx.coroutines.coroutineScope {
            all.map { t ->
                async {
                    backend.recentMessages(t.id, SEARCH_DEPTH)
                        .filter { m -> !m.deleted && !m.system && m.text.contains(q, ignoreCase = true) }
                        .map { FoundMessage(t, it) }
                }
            }.flatMap { it.await() }
        }
        return found.sortedByDescending { it.message.at }.take(60)
    }

    /** A message to show when its conversation opens (a search result was tapped). */
    private val jumps = HashMap<String, String>()

    fun openAt(found: FoundMessage) {
        jumps[found.thread.other.uid] = found.message.id
        openConversation(found.thread.other)
    }

    /** The message to go to in [other]'s conversation, once. */
    fun takeJump(other: ChatUser): String? = jumps.remove(other.uid)

    // ------------------------------------------------------------------ export

    /** The whole conversation as plain text: "15.11.2026 14:03 - Name: text". */
    suspend fun exportText(other: ChatUser, me: ChatUser, s: ChatStrings): String {
        val id = chatIdWith(other) ?: return ""
        val names = if (other.isGroup) {
            runCatching { kotlinx.coroutines.withTimeoutOrNull(5_000) { backend.group(id).first { it != null } }?.members?.associate { it.user.uid to it.user.name } }.getOrNull().orEmpty()
        } else {
            mapOf(other.uid to other.name)
        }
        val all = runCatching { backend.allMessages(id) }.getOrDefault(emptyList())
        return buildString {
            all.forEach { m ->
                if (m.deleted) return@forEach
                val who = when {
                    m.system -> ""
                    m.from == me.uid -> me.name
                    else -> names[m.from] ?: other.name
                }
                val body = buildList {
                    if (m.text.isNotBlank()) add(m.text)
                    if (m.images.isNotEmpty()) add("<" + s.photo + " ×" + m.images.size + ">")
                    m.attachment?.let { add("<" + it.name.ifBlank { it.kind.name.lowercase() } + ">") }
                    m.location?.let { add("<" + it.lat + ", " + it.lng + ">") }
                    m.contact?.let { add("<" + it.name + " " + it.phone + ">") }
                    m.poll?.let { add("<" + it.question + ">") }
                }.joinToString(" ")
                append(exportTime(m.at)).append(" - ")
                if (who.isNotEmpty()) append(who).append(": ")
                append(body).append('\n')
            }
        }
    }

    // ------------------------------------------------------------------ call links

    /** A call link (qeyf://c/<username>) was opened: that person, with the voice and video buttons. */
    fun openCallLink(username: String) {
        val name = username.trim().removePrefix("@").lowercase()
        if (!isValidUsername(name)) return
        scope.launch {
            val found = backend.findUsers(name).getOrNull().orEmpty().firstOrNull { it.username == name }
            if (found != null) push(ChatRoute.CallDetails(found)) else _error.value = "@$name"
        }
    }

    // ------------------------------------------------------------------ QR

    /** Someone's Qeyf code was scanned (qeyf://u/<username>): their profile opens. */
    fun openByUsername(username: String) {
        val name = username.trim().removePrefix("@").lowercase()
        if (!isValidUsername(name)) return
        scope.launch {
            val found = backend.findUsers(name).getOrNull().orEmpty().firstOrNull { it.username == name }
            if (found != null) openProfile(found) else _error.value = "@$name"
        }
    }

    /** The person's calls, newest first. */
    val callLog: StateFlow<List<CallLogEntry>> = ready
        .flatMapLatest { if (it == null) flowOf(emptyList()) else backend.callLog().catch { emit(emptyList()) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun clearCallLog() {
        scope.launch { runCatching { backend.clearCallLog() } }
    }

    fun deleteCalls(ids: List<String>) {
        scope.launch { runCatching { backend.deleteCalls(ids) } }
    }

    fun openSettings() = _routes.update { list -> list.filter { it !is ChatRoute.Settings } + ChatRoute.Settings }

    /** The list entry of the conversation with [other] (its counts and marks), when there is one. */
    fun threadOf(other: ChatUser): ChatThread? {
        val id = chatIdWith(other) ?: return null
        return allThreads.value?.firstOrNull { it.id == id }
    }

    /** Resolves a non-secret conversation's peer/group from its [chatId] (e.g. Direct Share shortcut). */
    fun userForChatId(chatId: String): ChatUser? = allThreads.value?.firstOrNull { it.id == chatId && !it.secret }?.other

    fun setMuted(other: ChatUser, until: Long) {
        val id = chatIdWith(other) ?: return
        scope.launch { runCatching { backend.setMuted(id, until) } }
    }

    // ------------------------------------------------------------------ more of messages

    /** The last [limit] messages (scrolling up raises it). */
    fun messages(other: ChatUser, limit: kotlinx.coroutines.flow.Flow<Int>) =
        chatIdWith(other)?.let { id -> limit.flatMapLatest { n -> backend.messages(id, n).catch { emit(emptyList()) } } } ?: emptyFlow()

    fun readMarks(other: ChatUser) = chatIdWith(other)?.let { id -> backend.readMarks(id).catch { emit(emptyMap()) } } ?: flowOf(emptyMap())

    fun deliveredMarks(other: ChatUser) = chatIdWith(other)?.let { id -> backend.deliveredMarks(id).catch { emit(emptyMap()) } } ?: flowOf(emptyMap())

    fun edit(other: ChatUser, message: ChatMessage, text: String) {
        val id = chatIdWith(other) ?: return
        scope.launch { backend.editMessage(id, message.id, text).onFailure { _error.value = it.message } }
    }

    fun deleteForEveryone(other: ChatUser, message: ChatMessage) {
        val id = chatIdWith(other) ?: return
        scope.launch { backend.deleteForEveryone(id, message.id).onFailure { _error.value = it.message } }
    }

    fun deleteForMe(other: ChatUser, message: ChatMessage) {
        val id = chatIdWith(other) ?: return
        scope.launch { runCatching { backend.deleteForMe(id, message.id) } }
    }

    fun forward(from: ChatUser, messages: List<ChatMessage>, targets: List<ChatUser>) {
        val id = chatIdWith(from) ?: return
        if (targets.isEmpty()) return
        scope.launch {
            for (m in messages.sortedBy { it.at }) backend.forward(id, m, targets).onFailure { _error.value = it.message }
        }
    }

    /** Conversations a message can be forwarded to (the list, archived ones too). */
    val forwardTargets: StateFlow<List<ChatUser>> = allThreads
        .map { list -> list.orEmpty().filter { !it.secret }.map { it.other } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val starred: StateFlow<List<StarredMessage>> = ready
        .flatMapLatest { if (it == null) flowOf(emptyList()) else backend.starred().catch { emit(emptyList()) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun setStarred(other: ChatUser, message: ChatMessage, fromName: String, chatName: String, starred: Boolean) {
        val id = chatIdWith(other) ?: return
        scope.launch { runCatching { backend.setStarred(id, message, fromName, chatName, starred) } }
    }

    /** Opens the conversation a starred message is in. */
    fun openStarredChat(item: StarredMessage) {
        val thread = allThreads.value?.firstOrNull { it.id == item.chatId } ?: return
        openConversation(thread.other)
    }

    fun ttl(other: ChatUser) = chatIdWith(other)?.let { id -> backend.ttl(id).catch { emit(0L) } } ?: flowOf(0L)

    fun setTtl(other: ChatUser, ttlMs: Long) {
        scope.launch { backend.setTtl(other, ttlMs).onFailure { _error.value = it.message } }
    }

    suspend fun linkPreview(url: String): LinkPreview? = runCatching { backend.linkPreview(url) }.getOrNull()

    // ------------------------------------------------------------------ polls, places, contacts

    fun sendEvent(other: ChatUser, event: ChatEvent) {
        scope.launch { backend.sendEvent(other, event).onFailure { _error.value = it.message } }
    }

    fun sendPoll(other: ChatUser, poll: ChatPoll) {
        scope.launch { backend.sendPoll(other, poll).onFailure { _error.value = it.message } }
    }

    fun pollVotes(other: ChatUser) = chatIdWith(other)?.let { id -> backend.pollVotes(id).catch { emit(emptyMap()) } } ?: flowOf(emptyMap())

    fun vote(other: ChatUser, message: ChatMessage, choices: List<Int>) {
        val id = chatIdWith(other) ?: return
        scope.launch { runCatching { backend.vote(id, message.id, choices) } }
    }

    /**
     * Starts sharing this phone's live location in the conversation with [other] for [durationMs]: a message with
     * the place now (its card follows the sharing), and the phone keeps updating it until then.
     */
    fun shareLiveLocation(other: ChatUser, place: ChatLocation, durationMs: Long) {
        val chatId = chatIdWith(other) ?: return
        val until = Clock.System.now().toEpochMilliseconds() + backend.serverOffset.value + durationMs
        LiveShare.start(chatId, until)
        sendLocation(other, place.copy(live = true, until = until))
    }

    fun stopLiveLocation(other: ChatUser) {
        chatIdWith(other)?.let { LiveShare.stop(it) }
    }

    fun sendLocation(other: ChatUser, location: ChatLocation) {
        scope.launch { backend.sendLocation(other, location).onFailure { _error.value = it.message } }
    }

    fun sendContact(other: ChatUser, contact: ChatContact) {
        scope.launch { backend.sendContact(other, contact).onFailure { _error.value = it.message } }
    }

    // ------------------------------------------------------------------ chat games

    fun games(other: ChatUser) = chatIdWith(other)?.let { id ->
        backend.games(id).catch { emit(emptyMap()) }
    } ?: flowOf(emptyMap())

    fun gameStats(other: ChatUser) = chatIdWith(other)?.let { id ->
        backend.gameStats(id).catch { emit(com.qmusic.ui.chat.games.ChatGameStats()) }
    } ?: flowOf(com.qmusic.ui.chat.games.ChatGameStats())

    fun createGame(
        other: ChatUser,
        config: com.qmusic.ui.chat.games.GameConfig,
        s: ChatStrings,
        onCreated: (String) -> Unit = {},
    ) {
        val me = (account.value as? ChatAccount.Ready)?.me ?: return
        val chatId = chatIdWith(other) ?: return
        val engine = com.qmusic.ui.chat.games.GameRegistry.of(config.kind) ?: return
        val solo = other.uid == me.uid
        val opponent = if (other.isGroup || solo) null else other
        val now = serverNow()
        val gameId = "gm" + com.qmusic.ui.chat.games.Sha256.randomSaltHex().take(18)
        val initial = engine.createInitial(
            gameId = gameId,
            chatId = chatId,
            creator = me,
            opponent = opponent,
            solo = solo,
            config = config,
            nowMs = now,
        )
        val preview = "🎮 " + engine.title(s)
        scope.launch {
            backend.createGame(other, initial, preview)
                .onSuccess { id -> onCreated(id) }
                .onFailure { _error.value = it.message }
        }
    }

    fun joinGame(other: ChatUser, gameId: String, onJoined: () -> Unit = {}) {
        val me = (account.value as? ChatAccount.Ready)?.me ?: return
        val chatId = chatIdWith(other) ?: return
        scope.launch {
            backend.updateGame(chatId, gameId) { current ->
                val engine = com.qmusic.ui.chat.games.GameRegistry.of(current.kind) ?: return@updateGame null
                engine.join(current, me, serverNow())
            }.onSuccess { onJoined() }
        }
    }

    fun submitGameAction(other: ChatUser, gameId: String, action: com.qmusic.ui.chat.games.GameAction) {
        val me = (account.value as? ChatAccount.Ready)?.me ?: return
        val chatId = chatIdWith(other) ?: return
        scope.launch {
            var becameFinished = false
            backend.updateGame(chatId, gameId) { current ->
                val engine = com.qmusic.ui.chat.games.GameRegistry.of(current.kind) ?: return@updateGame null
                val next = engine.applyAction(current, me.uid, action, serverNow()) ?: return@updateGame null
                becameFinished = current.status != com.qmusic.ui.chat.games.GameStatus.FINISHED &&
                    next.status == com.qmusic.ui.chat.games.GameStatus.FINISHED
                next
            }.onSuccess { updated ->
                if (becameFinished) {
                    launch { runCatching { backend.recordGameWin(chatId, gameId, updated.winnerUid) } }
                }
            }
        }
    }

    fun forfeitGame(other: ChatUser, gameId: String) =
        submitGameAction(other, gameId, com.qmusic.ui.chat.games.GameAction("forfeit"))

    fun cancelGame(other: ChatUser, gameId: String) =
        submitGameAction(other, gameId, com.qmusic.ui.chat.games.GameAction("cancel"))

    fun rematchGame(
        other: ChatUser,
        previous: com.qmusic.ui.chat.games.GameSession,
        s: ChatStrings,
        onCreated: (String) -> Unit = {},
    ) {
        val state = com.qmusic.ui.chat.games.GameJson.parseObject(previous.stateJson)
        val defaultCfg = com.qmusic.ui.chat.games.GameRegistry.defaultConfig(previous.kind)
        val mode = (state["mode"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: defaultCfg.mode
        val extra = (state["category"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: defaultCfg.extra
        createGame(
            other = other,
            config = com.qmusic.ui.chat.games.GameConfig(
                kind = previous.kind,
                mode = mode,
                maxRounds = previous.maxRounds.coerceAtLeast(1),
                turnSeconds = previous.turnSeconds,
                extra = extra,
            ),
            s = s,
            onCreated = onCreated,
        )
    }

    // ------------------------------------------------------------------ the person

    val privacy: StateFlow<Privacy> = backend.privacy

    fun setPrivacy(privacy: Privacy) {
        scope.launch { runCatching { backend.setPrivacy(privacy) } }
    }

    fun updateProfile(name: String, about: String) {
        scope.launch { backend.updateProfile(name, about).onFailure { _error.value = it.message } }
    }

    fun setProfilePhoto(image: PickedImage) {
        scope.launch { backend.setProfilePhoto(image).onFailure { _error.value = it.message } }
    }

    // ------------------------------------------------------------------ more of groups

    fun setGroupPhoto(group: ChatUser, image: PickedImage) {
        scope.launch { backend.setGroupPhoto(group.uid, image).onFailure { _error.value = it.message } }
    }

    fun setGroupDescription(group: ChatUser, text: String) {
        scope.launch { backend.setGroupDescription(group.uid, text).onFailure { _error.value = it.message } }
    }

    fun setAdmin(group: ChatUser, member: ChatUser, admin: Boolean) {
        scope.launch { backend.setAdmin(group.uid, member, admin).onFailure { _error.value = it.message } }
    }

    /** Group calls (null where the platform has none). */
    val groupCalls: GroupCallService? = backend.groupCalls

    fun groupCallParticipants(group: ChatUser) = groupCalls?.participants(group.uid)?.catch { emit(emptyList()) } ?: flowOf(emptyList())

    // ------------------------------------------------------------------ groups

    fun openNewGroup() = _routes.update { list -> list.filter { it !is ChatRoute.NewGroup } + ChatRoute.NewGroup }

    fun openGroupInfo(group: ChatUser) = _routes.update { list -> list.filterNot { it is ChatRoute.GroupInfo } + ChatRoute.GroupInfo(group) }

    fun openAddMembers(group: ChatUser) = _routes.update { list -> list.filterNot { it is ChatRoute.AddMembers } + ChatRoute.AddMembers(group) }

    /** People who can be put in a group: friends, and the people of the conversations. */
    val groupCandidates: StateFlow<List<ChatUser>> = combine(friends(), allThreads) { f, t ->
        (f + t.orEmpty().filter { !it.secret }.map { it.other }.filter { !it.isGroup && it.uid != CLAUDE_BOT_UID })
            .distinctBy { it.uid }
            .sortedBy { it.name.lowercase() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun group(group: ChatUser) = backend.group(group.uid).catch { emit(null) }

    fun createGroup(name: String, members: List<ChatUser>) {
        run {
            backend.createGroup(name, members).onSuccess { group ->
                _routes.update { list -> list.filter { it !is ChatRoute.NewGroup } + ChatRoute.Conversation(group) }
            }
        }
    }

    fun addMembers(group: ChatUser, members: List<ChatUser>) {
        scope.launch { backend.addMembers(group.uid, members).onFailure { _error.value = it.message } }
        _routes.update { list -> list.filterNot { it is ChatRoute.AddMembers } }
    }

    fun removeMember(group: ChatUser, member: ChatUser) {
        scope.launch { backend.removeMember(group.uid, member).onFailure { _error.value = it.message } }
    }

    fun renameGroup(group: ChatUser, name: String) {
        scope.launch { backend.renameGroup(group.uid, name).onFailure { _error.value = it.message } }
    }

    fun leaveGroup(group: ChatUser) {
        scope.launch { backend.leaveGroup(group.uid).onFailure { _error.value = it.message } }
        _routes.update { list -> list.filterNot { (it is ChatRoute.Conversation && it.other.uid == group.uid) || (it is ChatRoute.GroupInfo && it.group.uid == group.uid) } }
    }

    // ------------------------------------------------------------------ together

    /** Listening / watching together (set by the app, which has the players). */
    var together: TogetherController? = null

    fun startTogether(other: ChatUser, item: SharedItem, inviteText: String) {
        val chatId = chatIdWith(other) ?: return
        together?.start(chatId, item) { backend.sendTogetherInvite(other, item, inviteText) }
    }

    fun joinTogether(other: ChatUser) {
        val chatId = chatIdWith(other) ?: return
        together?.join(chatId)
    }

    private fun friends() = ready.flatMapLatest { if (it == null) flowOf(emptyList()) else backend.friends().catch { emit(emptyList()) } }

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

    /** Someone's profile read fresh (their about line, a new picture). */
    suspend fun userFresh(uid: String): ChatUser? = runCatching { backend.user(uid) }.getOrNull()

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
        // Searching again: no old results meanwhile (the page shows that it looks).
        _found.value = null
        searchJob = scope.launch {
            delay(SEARCH_DELAY_MS)
            val me = (account.value as? ChatAccount.Ready)?.me?.uid
            _found.value = backend.findUsers(prefix).getOrNull().orEmpty().filter { it.uid != me }
        }
    }

    // ------------------------------------------------------------------ a conversation

    fun chatIdWith(other: ChatUser): String? = (account.value as? ChatAccount.Ready)?.me?.uid?.let { conversationId(it, other) }

    fun messages(other: ChatUser) = chatIdWith(other)?.let { id -> backend.messages(id).catch { emit(emptyList()) } } ?: emptyFlow()

    fun otherReadAt(other: ChatUser) = if (other.isGroup) flowOf(0L) else chatIdWith(other)?.let { id -> backend.otherReadAt(id, other.uid).catch { emit(0L) } } ?: flowOf(0L)

    fun otherDeliveredAt(other: ChatUser) = if (other.isGroup) flowOf(0L) else chatIdWith(other)?.let { id -> backend.otherDeliveredAt(id, other.uid).catch { emit(0L) } } ?: flowOf(0L)

    fun markRead(other: ChatUser) {
        val id = chatIdWith(other) ?: return
        scope.launch { runCatching { backend.markRead(id) } }
    }

    fun markRead(thread: ChatThread) {
        scope.launch { runCatching { backend.markRead(thread.id) } }
    }

    fun markUnread(thread: ChatThread) {
        scope.launch { runCatching { backend.markUnread(thread.id) } }
    }

    fun clearChat(thread: ChatThread) {
        scope.launch { runCatching { backend.clearChat(thread.id) } }
    }

    fun clearChat(other: ChatUser) {
        val id = chatIdWith(other) ?: return
        scope.launch { runCatching { backend.clearChat(id) } }
    }

    fun send(other: ChatUser, text: String, share: SharedItem? = null, replyTo: ChatReply? = null, mentions: Set<String> = emptySet()): Boolean {
        val body = text.trim()
        if (body.isEmpty() && share == null) return false
        if (body.length > MAX_LENGTH) {
            _error.value = "max $MAX_LENGTH"
            return false
        }
        scope.launch {
            val sent = if (mentions.isNotEmpty() && share == null && other.isGroup) {
                backend.sendMentioning(other, body, replyTo, mentions)
            } else {
                backend.send(other, body, share, replyTo)
            }
            sent.onFailure { e -> if (e !is CancellationException) _error.value = e.message }
        }
        return true
    }

    // ------------------------------------------------------------------ GIFs and stickers

    suspend fun searchGifs(stickers: Boolean, query: String, page: Int = 1): Result<List<GifItem>> = backend.searchGifs(stickers, query, page)

    fun sendGif(other: ChatUser, item: GifItem, sticker: Boolean, replyTo: ChatReply? = null) {
        scope.launch {
            backend.sendGif(other, ChatGif(item.url, item.width, item.height, sticker), replyTo)
                .onFailure { e -> if (e !is CancellationException) _error.value = e.message }
        }
    }

    // ------------------------------------------------------------------ pins, view once, group settings, likes

    fun pinned(other: ChatUser) = chatIdWith(other)?.let { id -> backend.pinned(id).catch { emit(emptyList()) } } ?: flowOf(emptyList())

    fun pin(other: ChatUser, message: ChatMessage, preview: String, pinned: Boolean) {
        val id = chatIdWith(other) ?: return
        scope.launch { backend.pin(id, message, preview, pinned).onFailure { _error.value = it.message } }
    }

    /** Unpins a message by id (used from the pinned banner, even when the message is older than the loaded page). */
    fun unpin(other: ChatUser, messageId: String) {
        val id = chatIdWith(other) ?: return
        scope.launch {
            backend.pin(id, ChatMessage(id = messageId, from = "", text = "", at = 0L), "", false)
                .onFailure { _error.value = it.message }
        }
    }

    fun opened(other: ChatUser) = chatIdWith(other)?.let { id -> backend.opened(id).catch { emit(emptyMap()) } } ?: flowOf(emptyMap())

    fun markOpened(other: ChatUser, message: ChatMessage) {
        val id = chatIdWith(other) ?: return
        scope.launch { runCatching { backend.markOpened(id, message.id) } }
    }

    fun setGroupSettings(group: ChatUser, settings: GroupSettings) {
        scope.launch { backend.setGroupSettings(group.uid, settings).onFailure { _error.value = it.message } }
    }

    fun statusLikes(item: StatusItem) = backend.statusLikes(item.id).catch { emit(emptySet()) }
    fun likedStatus(item: StatusItem) = backend.likedStatus(item).catch { emit(false) }

    val wallpaper: StateFlow<Int> = ready
        .flatMapLatest { if (it == null) flowOf(0) else backend.wallpaper().catch { emit(0) } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    fun setWallpaper(index: Int) {
        scope.launch { runCatching { backend.setWallpaper(index) } }
    }

    fun likeStatus(item: StatusItem, liked: Boolean) {
        scope.launch { runCatching { backend.likeStatus(item, liked) } }
    }

    fun sendFile(other: ChatUser, file: PickedFile, caption: String = "", replyTo: ChatReply? = null) {
        if (file.size > MAX_FILE_BYTES) {
            _error.value = "max 100 MB"
            return
        }
        scope.launch {
            backend.sendFile(other, file, caption.trim(), replyTo).onFailure { e -> if (e !is CancellationException) _error.value = e.message }
        }
    }

    fun sendImages(other: ChatUser, images: List<PickedImage>, caption: String, replyTo: ChatReply? = null, viewOnce: Boolean = false) {
        if (images.isEmpty()) return
        scope.launch {
            backend.sendImages(other, if (viewOnce) images.take(1) else images.take(MAX_PHOTOS), caption.trim(), replyTo, viewOnce).onFailure { e -> if (e !is CancellationException) _error.value = e.message }
        }
    }

    /** The owner's Claude: the model answering now. */
    val claudeState: StateFlow<ClaudeState?> = ready
        .combine(ClaudeAccess.granted) { account, granted -> account?.me?.uid?.takeIf { it == CLAUDE_OWNER_UID || it in granted } }
        .distinctUntilChanged()
        .flatMapLatest { uid -> if (uid != null) backend.claudeState().catch { emit(null) } else flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun setClaudeModel(model: String) {
        scope.launch { runCatching { backend.setClaudeModel(model) } }
    }

    fun resetClaude() {
        scope.launch { runCatching { backend.resetClaude() } }
    }

    suspend fun imageBytes(other: ChatUser, image: ChatImage): ByteArray? = chatIdWith(other)?.let { backend.imageBytes(it, image) }

    fun presence(other: ChatUser) = if (other.isGroup) flowOf(null) else backend.presence(other.uid).catch { emit(null) }

    fun assist(other: ChatUser) = chatIdWith(other)?.let { id -> backend.assist(id).catch { emit(null) } } ?: flowOf(null)

    fun typing(other: ChatUser) = chatIdWith(other)?.let { id -> backend.typing(id, other.uid).catch { emit(false) } } ?: flowOf(false)

    fun recording(other: ChatUser) = chatIdWith(other)?.let { id -> backend.recording(id, other.uid).catch { emit(false) } } ?: flowOf(false)

    private var recordingBeat: Job? = null

    /** Leaving a conversation while recording: the others stop seeing it. */
    fun stopRecordingIfAny(other: ChatUser) {
        if (recordingBeat != null) setRecording(other, false)
    }

    /** Recording a voice message: the other side sees it, renewed every few seconds until it stops. */
    fun setRecording(other: ChatUser, on: Boolean) {
        val id = chatIdWith(other) ?: return
        recordingBeat?.cancel()
        recordingBeat = null
        if (on) {
            recordingBeat = scope.launch {
                while (true) {
                    runCatching { backend.setRecording(id, true) }
                    delay(4_000)
                }
            }
        } else {
            scope.launch { runCatching { backend.setRecording(id, false) } }
        }
    }

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
        // Its writer hears about it, with what the message said.
        val preview = message.text.ifBlank {
            when {
                // The mark is turned into words where it is shown (see ChatPreview).
                message.images.isNotEmpty() -> "📷"
                message.attachment != null -> message.attachment.name.ifBlank { "📎" }
                message.share != null -> message.share.title
                message.poll != null -> message.poll.question
                else -> ""
            }
        }
        scope.launch { runCatching { backend.react(id, message.id, emoji, message.from, preview) } }
    }

    fun delete(other: ChatUser, message: ChatMessage) {
        val id = chatIdWith(other) ?: return
        scope.launch { backend.deleteMessage(id, message.id).onFailure { _error.value = it.message } }
    }

    val blocked: StateFlow<Set<String>> = blockedIds

    private val _transcripts = MutableStateFlow<Map<String, String?>>(emptyMap())
    /** Voice messages turned to text, by message id (null while it is being done). */
    val transcripts: StateFlow<Map<String, String?>> = _transcripts

    fun transcribe(m: ChatMessage, s: ChatStrings) {
        val voice = m.attachment ?: return
        if (_transcripts.value.containsKey(m.id)) return
        _transcripts.update { it + (m.id to null) }
        scope.launch {
            backend.transcribe(voice)
                .onSuccess { text -> _transcripts.update { it + (m.id to text.ifBlank { s.nothingHeard }) } }
                .onFailure { _transcripts.update { it - m.id }; _error.value = s.aiFailed }
        }
    }

    /** Reads the message aloud (or stops the one being read). */
    fun speak(m: ChatMessage, s: ChatStrings) {
        if (m.text.isBlank()) return
        scope.launch { backend.speak(m.text).onFailure { _error.value = s.aiFailed } }
    }

    private val _translations = MutableStateFlow<Map<String, String?>>(emptyMap())
    /** Messages translated into the app's language, by message id (null while it is being done). */
    val translations: StateFlow<Map<String, String?>> = _translations

    fun translate(m: ChatMessage, s: ChatStrings) {
        if (m.text.isBlank()) return
        if (_translations.value[m.id] != null) {
            // Shown already: a second "Translate" takes it away.
            _translations.update { it - m.id }
            return
        }
        _translations.update { it + (m.id to null) }
        scope.launch {
            backend.translate(m.text, if (s.english) "en" else "tr")
                .onSuccess { text -> _translations.update { it + (m.id to text) } }
                .onFailure { _translations.update { it - m.id }; _error.value = s.aiFailed }
        }
    }

    /** Reports [other] (a person or a group) with the last messages of the conversation; blocks (or leaves) too when asked. */
    fun report(other: ChatUser, reason: String, recent: List<ChatMessage>, alsoBlock: Boolean, s: ChatStrings) {
        // Nuvelo's own account cannot be reported or blocked (the server refuses it too): said plainly.
        if (other.uid == CLAUDE_OWNER_UID) {
            _info.value = if (s.english) "This is Nuvelo's account: it cannot be reported or blocked" else "Bu Nuvelo'nun hesabı: şikayet edilemez, engellenemez"
            return
        }
        val chatId = chatIdWith(other) ?: other.uid
        val lines = recent.filter { !it.system && !it.deleted }.takeLast(5).map { m ->
            m.from to (m.text.ifBlank { if (m.images.isNotEmpty()) "[foto]" else if (m.attachment != null) "[dosya]" else "[mesaj]" })
        }
        scope.launch {
            backend.report(chatId, other.uid, reason, lines)
                .onSuccess { _info.value = s.reportSent }
                .onFailure { _error.value = it.message }
            if (alsoBlock) {
                if (other.isGroup) leaveGroup(other) else setBlocked(other, true)
            }
        }
    }

    /** Now on the server's clock (the messages' times are the server's). */
    fun serverNow(): Long = Clock.System.now().toEpochMilliseconds() + backend.serverOffset.value

    fun setBlocked(uid: String, block: Boolean) {
        if (block && uid == CLAUDE_OWNER_UID) {
            _info.value = if (ChatMusic.english) "This is Nuvelo's account: it cannot be blocked" else "Bu Nuvelo'nun hesabı: engellenemez"
            return
        }
        scope.launch { backend.setBlocked(uid, block).onFailure { _error.value = it.message } }
        if (block) _routes.update { list -> list.filterNot { it is ChatRoute.Conversation && it.other.uid == uid } }
    }

    fun setBlocked(other: ChatUser, block: Boolean) = setBlocked(other.uid, block)

    companion object {
        /** How many of each conversation's last messages a search reads. */
        const val SEARCH_DEPTH = 400
        const val MAX_LENGTH = 4000
        const val MAX_PHOTOS = 10
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        private const val TYPING_EVERY_MS = 3_000L
        private const val TYPING_IDLE_MS = 5_000L
        private const val SEARCH_DELAY_MS = 300L
    }
}
