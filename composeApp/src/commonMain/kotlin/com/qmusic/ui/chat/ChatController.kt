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

    /** The list: conversations not archived and not locked. */
    val threads: StateFlow<List<ChatThread>?> = allThreads
        .map { list -> list?.filter { !it.archived && !it.locked } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The conversations moved to "Archived" (locked ones stay in the locked folder). */
    val archived: StateFlow<List<ChatThread>> = allThreads
        .map { list -> list.orEmpty().filter { it.archived && !it.locked } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The locked conversations: opened with the phone's lock, hidden from the list and from notifications. */
    val lockedThreads: StateFlow<List<ChatThread>> = allThreads
        .map { list -> list.orEmpty().filter { it.locked } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // The app keeps the locked ones' ids so a notification for them shows no name and no text.
        scope.launch { lockedThreads.collect { list -> LockedChats.update(list.map { it.id }.toSet()) } }
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
        if (tab == ChatTab.CALLS) scope.launch { runCatching { backend.setCallsSeen() } }
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
        scope.launch { runCatching { backend.deleteStatus(item.id) } }
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

    suspend fun inviteCode(group: ChatUser, renew: Boolean = false): String? =
        backend.groupInviteCode(group.uid, renew).onFailure { _error.value = it.message }.getOrNull()

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
        val all = allThreads.value.orEmpty().filter { !it.locked }
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

    fun openSettings() = _routes.update { list -> list.filter { it !is ChatRoute.Settings } + ChatRoute.Settings }

    /** The list entry of the conversation with [other] (its counts and marks), when there is one. */
    fun threadOf(other: ChatUser): ChatThread? {
        val id = chatIdWith(other) ?: return null
        return allThreads.value?.firstOrNull { it.id == id }
    }

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
        .map { list -> list.orEmpty().map { it.other } }
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

    fun sendPoll(other: ChatUser, poll: ChatPoll) {
        scope.launch { backend.sendPoll(other, poll).onFailure { _error.value = it.message } }
    }

    fun pollVotes(other: ChatUser) = chatIdWith(other)?.let { id -> backend.pollVotes(id).catch { emit(emptyMap()) } } ?: flowOf(emptyMap())

    fun vote(other: ChatUser, message: ChatMessage, choices: List<Int>) {
        val id = chatIdWith(other) ?: return
        scope.launch { runCatching { backend.vote(id, message.id, choices) } }
    }

    fun sendLocation(other: ChatUser, location: ChatLocation) {
        scope.launch { backend.sendLocation(other, location).onFailure { _error.value = it.message } }
    }

    fun sendContact(other: ChatUser, contact: ChatContact) {
        scope.launch { backend.sendContact(other, contact).onFailure { _error.value = it.message } }
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
        (f + t.orEmpty().map { it.other }.filter { !it.isGroup && it.uid != CLAUDE_BOT_UID })
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
        .flatMapLatest { if (it?.me?.uid == CLAUDE_OWNER_UID) backend.claudeState().catch { emit(null) } else flowOf(null) }
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

    fun setBlocked(other: ChatUser, block: Boolean) {
        scope.launch { backend.setBlocked(other.uid, block).onFailure { _error.value = it.message } }
        if (block) _routes.update { list -> list.filterNot { it is ChatRoute.Conversation && it.other.uid == other.uid } }
    }

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
