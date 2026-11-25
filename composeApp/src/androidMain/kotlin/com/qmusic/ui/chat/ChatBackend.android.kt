package com.qmusic.ui.chat

import com.google.firebase.database.ChildEventListener
import kotlinx.coroutines.flow.first
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
actual fun rememberChatBackend(): ChatBackend? {
    val context = LocalContext.current
    val backend = remember { FirebaseChatBackend.get(context.applicationContext) }
    context.findActivity()?.let { backend?.activity = WeakReference(it) }
    return backend
}

/** For the push service: a new token goes to the signed-in account (when the app made the backend this run). */
object FirebaseChatPushTokens {
    fun refresh() {
        FirebaseChatBackend.peek()?.onNewPushToken()
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Messages through Firebase: Google sign-in (Credential Manager → Firebase Auth) and the Realtime Database, which
 * keeps what was read on the phone (conversations open without a connection) and sends what was written offline once
 * there is one. The phone's push token is stored for the server function that sends "new message" notifications.
 *
 * Layout: users/{uid} (profile), usernames/{name} = uid, userChats/{uid}/{chatId} (each person's list, with the last
 * message), chats/{chatId}/messages/{id} and chats/{chatId}/read/{uid}, blocked/{uid}/{other} = true,
 * pushTokens/{uid}/{key} = token. See firebase/database.rules.json for who may read and write what.
 */
internal class FirebaseChatBackend private constructor(private val app: Context) : ChatBackend, ForcesImages {
    private val auth = FirebaseAuth.getInstance()
    private val db: FirebaseDatabase = openDatabase()
    private val root: DatabaseReference = db.reference

    /** End-to-end encryption (see E2eEngine): made here, so it shares the database. */
    private val e2e: E2eEngine = E2eEngine(app, root).also {
        E2eEngine.instance = it
        E2e.api = it
        it.groupMembers = { id ->
            groupInfo(id, fresh = true)?.let { g -> g.members.map { m -> m.user.uid } to g.members.filter { m -> m.admin }.map { m -> m.user.uid }.minOrNull() }
        }
        it.shareHistoryOf = { id -> groupInfo(id)?.shareHistory == true }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The screen Google's account picker opens over. */
    var activity: WeakReference<Activity>? = null

    /** Bans, announcements, the least version, and the admin panel. */
    override val admin: AdminConsole by lazy { FirebaseAdminConsole(app, root) }

    private val _account = MutableStateFlow<ChatAccount>(ChatAccount.Loading)
    override val account: StateFlow<ChatAccount> = _account.asStateFlow()
    private var profileRef: DatabaseReference? = null
    private var profileListener: ValueEventListener? = null

    /** Messages sent from this phone that the server has not confirmed yet (shown with a clock). */
    private val pendingIds = MutableStateFlow<Set<String>>(emptySet())

    /** Google's id for this app, written into the app by the Firebase config once Google sign-in is turned on. */
    private val webClientId: String? = app.resources.getIdentifier("default_web_client_id", "string", app.packageName)
        .takeIf { it != 0 }?.let { app.getString(it) }

    /** Voice and video calls, ringing for this account while it is signed in. */
    override val calls: FirebaseCallService by lazy {
        FirebaseCallService(
            app = app,
            root = root,
            me = { (_account.value as? ChatAccount.Ready)?.me },
            logEntry = { id, peer, video, direction, outcome, seconds -> logCall(id, peer, video, direction, outcome, seconds) },
            logCall = { peer, video, seconds ->
                val tr = app.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
                val text = when {
                    seconds == null -> if (tr) "📞 Cevapsız arama" else "📞 Missed call"
                    video -> (if (tr) "🎥 Görüntülü arama · " else "🎥 Video call · ") + duration(seconds)
                    else -> (if (tr) "📞 Sesli arama · " else "📞 Voice call · ") + duration(seconds)
                }
                scope.launch { send(peer, text, null, null) }
            },
        )
    }

    private fun onUser(user: FirebaseUser?) {
        profileListener?.let { profileRef?.removeEventListener(it) }
        profileListener = null
        forgetProfiles()
        runCatching { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setUserId(user?.uid.orEmpty()) }
        runCatching { watchStatusPrivacy(user?.uid) }
        runCatching { calls.listenFor(user?.uid) }
        trackPresence(user?.uid)
        runCatching { watchDeliveries(user?.uid) }
        runCatching { e2e.signedIn(user?.uid) }
        if (user == null) {
            _account.value = if (webClientId == null) ChatAccount.Unavailable(SETUP_NEEDED) else ChatAccount.SignedOut
            return
        }
        if (_account.value !is ChatAccount.Ready) _account.value = ChatAccount.Loading
        // Which version this account opened last, and when (for the admin panel; only admins can read it).
        root.child("appInfo").child(user.uid).setValue(mapOf("v" to com.qmusic.ui.AppVersion.NAME, "at" to ServerValue.TIMESTAMP))
        val ref = root.child("users").child(user.uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val name = snap.child("name").getValue(String::class.java)
                val username = snap.child("username").getValue(String::class.java)
                _account.value = if (name != null && username != null) {
                    val photo = snap.child("photo").getValue(String::class.java)
                    val about = snap.child("about").getValue(String::class.java)
                    if (photo == null || about == null) {
                        // A photo or about shown to friends or nobody is kept in privateProfile.
                        scope.launch {
                            val priv = runCatching { root.child("privateProfile").child(user.uid).get().await() }.getOrNull()
                            val current = (_account.value as? ChatAccount.Ready)?.me ?: return@launch
                            if (current.uid != user.uid || priv == null) return@launch
                            _account.value = ChatAccount.Ready(
                                current.copy(
                                    photo = current.photo ?: priv.child("photo").getValue(String::class.java),
                                    about = current.about.ifEmpty { priv.child("about").getValue(String::class.java).orEmpty() },
                                ),
                            )
                        }
                    }
                    ChatAccount.Ready(ChatUser(user.uid, name, username, photo, about.orEmpty()))
                } else {
                    ChatAccount.NeedsProfile(user.uid, user.displayName.orEmpty(), user.photoUrl?.toString())
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (auth.currentUser?.uid == user.uid) _account.value = ChatAccount.Unavailable(error.message)
            }
        }
        ref.addValueEventListener(listener)
        profileRef = ref
        profileListener = listener
        privacyListener?.let { privacyRef?.removeEventListener(it) }
        val pRef = root.child("users").child(user.uid).child("privacy")
        val pListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                fun vis(key: String) = runCatching { Visibility.valueOf(snap.child(key).getValue(String::class.java).orEmpty()) }.getOrDefault(Visibility.EVERYONE)
                val next = Privacy(
                    hideLastSeen = snap.child("hideLastSeen").getValue(Boolean::class.java) == true,
                    hideReadReceipts = snap.child("hideReadReceipts").getValue(Boolean::class.java) == true,
                    photo = vis("photo"),
                    about = vis("about"),
                    groupAdd = vis("groupAdd"),
                )
                val changed = next.hideLastSeen != _privacy.value.hideLastSeen
                _privacy.value = next
                if (changed) trackPresence(user.uid)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        pRef.addValueEventListener(pListener)
        privacyRef = pRef
        privacyListener = pListener
        scope.launch { runCatching { savePushToken(user.uid) } }
    }

    private fun me(): String = auth.currentUser?.uid ?: error("not signed in")

    private val _privacy = MutableStateFlow(Privacy())
    override val privacy: StateFlow<Privacy> = _privacy.asStateFlow()
    private var privacyRef: DatabaseReference? = null
    private var privacyListener: ValueEventListener? = null

    override suspend fun setPrivacy(privacy: Privacy) {
        val uid = me()
        val mine = (_account.value as? ChatAccount.Ready)?.me
        root.child("users").child(uid).child("privacy").setValue(
            mapOf(
                "hideLastSeen" to privacy.hideLastSeen,
                "hideReadReceipts" to privacy.hideReadReceipts,
                "photo" to privacy.photo.name,
                "about" to privacy.about.name,
                "groupAdd" to privacy.groupAdd.name,
            ),
        ).await()
        // The photo and the about line move to where only the chosen people can read them.
        if (mine != null) placeProfile(uid, mine.photo, mine.about, privacy)
        _privacy.value = privacy
        refreshMyCopies()
    }

    /** users/{uid} (everyone reads it) holds the photo and about only when everyone may see them; else privateProfile. */
    private suspend fun placeProfile(uid: String, photo: String?, about: String, privacy: Privacy) {
        val photoPublic = privacy.photo == Visibility.EVERYONE
        val aboutPublic = privacy.about == Visibility.EVERYONE
        root.updateChildren(
            mapOf(
                "users/$uid/photo" to if (photoPublic) photo else null,
                "users/$uid/about" to if (aboutPublic) about.ifEmpty { null } else null,
                "privateProfile/$uid/photo" to if (photoPublic) null else photo,
                "privateProfile/$uid/about" to if (aboutPublic) null else about.ifEmpty { null },
            ),
        ).await()
    }

    /** The person's photo as others who are not their friends may see it. */
    private fun ChatUser.publicPhoto(): String? = if (_privacy.value.photo == Visibility.EVERYONE) photo else null

    /** The person's photo as their friends may see it. */
    private fun ChatUser.friendPhoto(): String? = if (_privacy.value.photo == Visibility.NOBODY) null else photo

    override suspend fun updateProfile(name: String, about: String): Result<Unit> = runCatching {
        val clean = name.trim().take(40)
        require(clean.isNotEmpty()) { "name" }
        val uid = me()
        root.child("users").child(uid).child("name").setValue(clean).await()
        val mine = (_account.value as? ChatAccount.Ready)?.me
        placeProfile(uid, mine?.photo, about.trim().take(140), _privacy.value)
        if (mine != null) _account.value = ChatAccount.Ready(mine.copy(name = clean))
        // The new name goes into the others' lists (their conversations, their friends, the groups).
        refreshMyCopies()
    }

    override suspend fun setProfilePhoto(image: PickedImage): Result<Unit> = runCatching {
        val uid = me()
        // "u_<uid>": a place on the media server only this person can put files in.
        val url = kotlinx.coroutines.withContext(Dispatchers.IO) {
            MediaUpload.upload(token(), "u_$uid", "jpg", "image/jpeg", image.jpeg.size.toLong(), { image.jpeg.inputStream() }) {}
        }
        imageCache.put(url, image.jpeg)
        val mine = (_account.value as? ChatAccount.Ready)?.me
        placeProfile(uid, url, mine?.about.orEmpty(), _privacy.value)
        if (mine != null) _account.value = ChatAccount.Ready(mine.copy(photo = url))
        refreshMyCopies()
    }

    // ------------------------------------------------------------------ account

    override suspend fun signIn(): Result<Unit> = runCatching {
        val clientId = webClientId ?: error(SETUP_NEEDED)
        val screen = activity?.get() ?: error("no screen")
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val response = try {
            CredentialManager.create(screen).getCredential(screen, request)
        } catch (_: GetCredentialCancellationException) {
            return@runCatching
        }
        val credential = response.credential
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Google sign-in failed" }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    override fun signOut() {
        val uid = auth.currentUser?.uid
        scope.launch {
            // This phone stops getting the account's notifications.
            if (uid != null) runCatching { removePushToken(uid) }
            if (uid != null) runCatching { root.child("presence").child(uid).setValue(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP)) }
            auth.signOut()
            runCatching { CredentialManager.create(app).clearCredentialState(ClearCredentialStateRequest()) }
        }
    }

    override suspend fun saveProfile(name: String, username: String): Result<Unit> = runCatching {
        require(isValidUsername(username)) { "username" }
        require(name.isNotBlank() && name.length <= 40) { "name" }
        val uid = me()
        // A name an admin barred cannot be taken (the rules refuse it too).
        val barred = runCatching {
            root.child("bannedNames").child(username).get().await().exists() &&
                root.child("usernames").child(username).get().await().getValue(String::class.java) != uid
        }.getOrDefault(false)
        if (barred) throw UsernameTaken()
        val photo = auth.currentUser?.photoUrl?.toString()
        // The name is claimed with a transaction: whoever writes it first has it.
        val claimed = suspendCancellableCoroutine { cont ->
            root.child("usernames").child(username).runTransaction(object : Transaction.Handler {
                override fun doTransaction(current: MutableData): Transaction.Result {
                    val owner = current.getValue(String::class.java)
                    if (owner != null && owner != uid) return Transaction.abort()
                    current.value = uid
                    return Transaction.success(current)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    if (error != null) cont.resumeWithException(error.toException()) else cont.resume(committed)
                }
            })
        }
        if (!claimed) throw UsernameTaken()
        val old = root.child("users").child(uid).child("username").get().await().getValue(String::class.java)
        root.child("users").child(uid).setValue(mapOf("name" to name, "username" to username, "photo" to photo)).await()
        if (old != null && old != username) runCatching { root.child("usernames").child(old).removeValue().await() }
    }

    override suspend fun findUsers(prefix: String): Result<List<ChatUser>> = runCatching {
        val names = root.child("usernames").orderByKey().startAt(prefix).endAt(prefix + "").limitToFirst(20).get().await()
        names.children.mapNotNull { it.getValue(String::class.java) }.mapNotNull { uid ->
            val snap = root.child("users").child(uid).get().await()
            val name = snap.child("name").getValue(String::class.java) ?: return@mapNotNull null
            val username = snap.child("username").getValue(String::class.java) ?: return@mapNotNull null
            ChatUser(uid, name, username, snap.child("photo").getValue(String::class.java))
        }
    }

    // ------------------------------------------------------------------ conversations

    private fun listen(ref: com.google.firebase.database.Query) = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /** The conversations with unread messages at the last look (see [threads]). */
    private var wasUnread: Set<String> = emptySet()

    override fun threads(): Flow<List<ChatThread>> {
        val uid = me()
        val ref = root.child("userChats").child(uid)
        ref.keepSynced(true)
        return callbackFlow {
            val job = launch {
                combine(listen(ref), e2e.changes, liveProfiles) { snap, _, live -> snap to live }.collect { (snap, live) ->
                    val list = snap.children.mapNotNull { it.toThread() }
                    // Read on another device (the web, another phone): the notification here goes too.
                    val nowUnread = list.filter { it.unread || it.unreadCount > 0 }.map { it.id }.toSet()
                    (wasUnread - nowUnread).forEach { id -> ChatPresence.onReadElsewhere?.invoke(id) }
                    wasUnread = nowUnread
                    // The other person's name and photo as they are now, not as they were at the last message.
                    watchProfiles(list.filter { !it.isGroup }.map { it.other.uid })
                    healThreadCopies(uid, list, live)
                    trySend(list.map { t -> if (t.isGroup) t else t.copy(other = t.other.fresh(live)) }.sortedByDescending { it.lastAt })
                }
            }
            awaitClose { job.cancel() }
        }
    }

    private fun DataSnapshot.toThread(): ChatThread? {
        val chatId = key ?: return null
        val otherUid = child("other").getValue(String::class.java) ?: return null
        val lastAt = child("lastAt").getValue(Long::class.java) ?: return null
        val lastFrom = child("lastFrom").getValue(String::class.java)
        val read = child("read").getValue(Long::class.java) ?: 0L
        return ChatThread(
            id = chatId,
            other = ChatUser(
                uid = otherUid,
                name = child("name").getValue(String::class.java) ?: "?",
                username = child("username").getValue(String::class.java).orEmpty(),
                photo = child("photo").getValue(String::class.java),
            ),
            lastText = child("lastText").getValue(String::class.java).orEmpty().let { last ->
                if (!last.startsWith("e2e:")) {
                    last
                } else {
                    e2e.watch(chatId)
                    e2e.openText(chatId, last)?.takeIf { it != E2e.UNREADABLE } ?: "🔒"
                }
            },
            lastAt = lastAt,
            lastFrom = lastFrom,
            // From them, or from Claude speaking in this conversation: anything not ours that came after our last look.
            unread = lastFrom != null && lastFrom != auth.currentUser?.uid && lastAt > read,
            otherReadAt = 0L,
            pinned = child("pinned").getValue(Long::class.java) ?: 0L,
            archived = child("archived").getValue(Boolean::class.java) == true,
            lastFromName = child("lastFromName").getValue(String::class.java).orEmpty(),
            unreadCount = (child("unread").getValue(Long::class.java) ?: 0L).toInt(),
            readAt = read,
            mutedUntil = child("mutedUntil").getValue(Long::class.java) ?: 0L,
            typingAt = child("typingAt").getValue(Long::class.java) ?: 0L,
            typingName = child("typingName").getValue(String::class.java).orEmpty(),
            typingRec = child("typingRec").getValue(Boolean::class.java) == true,
            favorite = child("favorite").getValue(Boolean::class.java) == true,
            locked = child("locked").getValue(Boolean::class.java) == true,
            mentioned = child("mention").getValue(Boolean::class.java) == true,
            secret = child("secret").getValue(Boolean::class.java) == true,
            invite = child("invite").getValue(Boolean::class.java) == true,
        )
    }

    override fun messages(chatId: String): Flow<List<ChatMessage>> = messages(chatId, MESSAGES_SHOWN)

    override fun messages(chatId: String, limit: Int): Flow<List<ChatMessage>> {
        val ref = root.child("chats").child(chatId).child("messages")
        ref.keepSynced(true)
        val query = ref.orderByChild("at").limitToLast(limit)
        e2e.watch(chatId)
        return combine(listen(query), pendingIds, outbox, _uploads, combine(hidden(chatId), e2e.changes) { h, _ -> h }) { snap, pending, out, progress, hidden ->
            val now = System.currentTimeMillis() + _serverOffset.value
            val sent = snap.children.mapNotNull { MsgNode.of(chatId, it).toMessage(pending) }.filter { it.id !in hidden && (it.expireAt == 0L || it.expireAt > now) }
            val ids = sent.mapTo(HashSet()) { it.id }
            sent + out[chatId].orEmpty().filter { it.id !in ids }.map { it.copy(progress = progress[it.id] ?: 0f) }
        }
    }

    override fun hidden(chatId: String): Flow<Set<String>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptySet())
            awaitClose {}
            return@callbackFlow
        }
        val job = launch {
            listen(root.child("hidden").child(uid).child(chatId)).collect { snap -> trySend(snap.children.mapNotNull { it.key }.toSet()) }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun deleteForMe(chatId: String, messageId: String) {
        root.child("hidden").child(me()).child(chatId).child(messageId).setValue(true)
    }

    override suspend fun editMessage(chatId: String, messageId: String, text: String): Result<Unit> = runCatching {
        val body = text.trim()
        require(body.isNotEmpty() && body.length <= 4000) { "text" }
        val ref = root.child("chats").child(chatId).child("messages").child(messageId)
        val sealed = ref.child("e2e").get().await().getValue(String::class.java)
        if (sealed != null) {
            val version = sealed.substringAfter("e2e:").substringBefore(':').toIntOrNull() ?: error("e2e")
            e2e.loadKey(chatId, version) ?: error("e2e-key")
            val content = e2e.openContent(chatId, sealed)?.toMutableMap() ?: error("e2e-key")
            content["text"] = body
            ref.updateChildren(mapOf("e2e" to (e2e.sealContent(chatId, version, content) ?: error("e2e-key")), "edited" to true)).await()
        } else {
            ref.updateChildren(mapOf("text" to body, "edited" to true)).await()
        }
        val pinRef = root.child("chats").child(chatId).child("pinned").child(messageId)
        if (runCatching { pinRef.get().await().exists() }.getOrDefault(false)) {
            runCatching {
                val version = e2e.versionNow(chatId) ?: sealed?.substringAfter("e2e:")?.substringBefore(':')?.toIntOrNull()
                val pinText = if (version != null) {
                    e2e.loadKey(chatId, version) ?: error("e2e-key")
                    e2e.sealText(chatId, version, takeUtf8(body, 180)) ?: error("e2e-key")
                } else {
                    if (sealed != null) error("e2e-key")
                    body.take(300)
                }
                pinRef.child("text").setValue(pinText).await()
            }
        }
        Unit
    }

    override suspend fun deleteForEveryone(chatId: String, messageId: String): Result<Unit> = runCatching {
        root.child("chats").child(chatId).child("messages").child(messageId).updateChildren(
            mapOf(
                "text" to "",
                "deleted" to true,
                "images" to null,
                "image" to null,
                "attachment" to null,
                "share" to null,
                "replyTo" to null,
                "together" to null,
                "edited" to null,
                "poll" to null,
                "location" to null,
                "contact" to null,
                "e2e" to null,
                "event" to null,
                "gif" to null,
            ),
        ).await()
        runCatching { root.child("chats").child(chatId).child("pinned").child(messageId).removeValue().await() }
        Unit
    }

    override suspend fun forward(fromChatId: String, message: ChatMessage, targets: List<ChatUser>): Result<Unit> = runCatching {
        val images = message.images.map { img ->
            mapOf(
                "id" to img.id,
                // Older photos (kept by id) live on the media server under their id.
                "url" to (img.url ?: "${MediaUpload.BASE}/m/$fromChatId/${img.id}.jpg"),
                "w" to img.width,
                "h" to img.height,
                "thumb" to img.thumb,
                "key" to img.key.ifEmpty { null },
            )
        }.ifEmpty { null }
        val extra = HashMap<String, Any?>()
        extra["forwarded"] = true
        message.location?.let { l -> extra["location"] = mapOf("lat" to l.lat, "lng" to l.lng, "label" to l.label) }
        message.contact?.let { c -> extra["contact"] = mapOf("name" to c.name, "phone" to c.phone) }
        message.attachment?.let { a ->
            extra["attachment"] = buildMap<String, Any?> {
                put("kind", a.kind.name.lowercase())
                put("url", a.url)
                put("name", a.name)
                put("size", a.size)
                put("mime", a.mime)
                if (a.durationMs > 0) put("duration", a.durationMs)
                if (a.width > 0) put("w", a.width)
                if (a.height > 0) put("h", a.height)
                if (a.thumb.isNotEmpty()) put("thumb", a.thumb)
                if (a.wave.isNotEmpty()) put("wave", a.wave.joinToString("") { ('A' + (it / 4).coerceIn(0, 25)).toString() })
                if (a.key.isNotEmpty()) put("key", a.key)
            }
        }
        val label = when {
            message.attachment?.kind == AttachmentKind.VOICE -> "🎤"
            message.attachment?.kind == AttachmentKind.VIDEO -> "🎬"
            message.attachment?.kind == AttachmentKind.FILE -> "📄 " + message.attachment.name
            images != null -> "📷"
            message.location != null -> "📍"
            message.contact != null -> "👤 " + message.contact.name
            else -> null
        }
        val preview = listOfNotNull(label, message.text.ifBlank { null }).joinToString(" ").ifBlank { null }
        for (target in targets) sendMessage(target, message.text, message.share, null, images, preview, extra = extra)
    }

    override fun starred(): Flow<List<StarredMessage>> = callbackFlow {
        val job = launch {
            combine(listen(root.child("starred").child(me())), e2e.changes) { snap, _ -> snap }.collect { snap ->
                trySend(
                    snap.children.mapNotNull { m ->
                        val starChat = m.child("chatId").getValue(String::class.java) ?: return@mapNotNull null
                        StarredMessage(
                            chatId = starChat,
                            messageId = m.child("messageId").getValue(String::class.java) ?: return@mapNotNull null,
                            text = m.child("text").getValue(String::class.java).orEmpty().let { t ->
                                if (t.startsWith("e2e:")) e2e.openText(starChat, t)?.takeIf { it != E2e.UNREADABLE } ?: "🔒" else t
                            },
                            from = m.child("from").getValue(String::class.java).orEmpty(),
                            fromName = m.child("fromName").getValue(String::class.java).orEmpty(),
                            chatName = m.child("chatName").getValue(String::class.java).orEmpty(),
                            at = m.child("at").getValue(Long::class.java) ?: 0L,
                        )
                    }.sortedByDescending { it.at },
                )
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun setStarred(chatId: String, message: ChatMessage, fromName: String, chatName: String, starred: Boolean) {
        val ref = root.child("starred").child(me()).child("$chatId|${message.id}")
        if (!starred) {
            ref.removeValue()
            return
        }
        val tr = app.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
        val str = if (tr) ChatStrings.Turkish else ChatStrings.English
        val text = if (message.locked) {
            "🔒"
        } else {
            message.text.ifBlank {
                when {
                    message.attachment?.kind == AttachmentKind.VOICE -> "🎤"
                    message.attachment?.kind == AttachmentKind.VIDEO -> "🎬"
                    message.attachment != null -> if (message.attachment.name.isNotBlank()) "📄 " + message.attachment.name else "📄"
                    message.gif != null -> if (message.gif.sticker) str.sticker else "GIF"
                    message.event != null -> "📅 " + message.event.title.ifBlank { if (tr) "Etkinlik" else "Event" }
                    message.images.isNotEmpty() -> if (message.images.size == 1) "📷" else "📷 ×" + message.images.size
                    message.share != null -> (if (message.share.kind == ShareKind.SONG) "🎵 " else "🎬 ") + message.share.title
                    message.poll != null -> "📊 " + message.poll.question
                    message.location != null -> "📍"
                    message.contact != null -> "👤 " + message.contact.name.ifBlank { message.contact.phone }
                    else -> ""
                }
            }
        }
        val version = e2e.versionNow(chatId)
        val starText = if (version != null) {
            e2e.loadKey(chatId, version) ?: return
            e2e.sealText(chatId, version, takeUtf8(text, 300)) ?: return
        } else {
            if (message.e2e) return
            text.take(300)
        }
        ref.setValue(mapOf("chatId" to chatId, "messageId" to message.id, "text" to starText, "from" to message.from, "fromName" to fromName, "chatName" to chatName, "at" to message.at))
    }

    // ------------------------------------------------------------------ disappearing messages, muting

    private val ttls = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun ttl(chatId: String): Flow<Long> = callbackFlow {
        val job = launch {
            listen(root.child("chats").child(chatId).child("settings").child("ttl")).collect { snap ->
                val ttl = snap.getValue(Long::class.java) ?: 0L
                ttls[chatId] = ttl
                trySend(ttl)
            }
        }
        awaitClose { job.cancel() }
    }

    private suspend fun ttlOf(chatId: String): Long =
        ttls[chatId] ?: (runCatching { root.child("chats").child(chatId).child("settings").child("ttl").get().await().getValue(Long::class.java) }.getOrNull() ?: 0L)
            .also { ttls[chatId] = it }

    override suspend fun setTtl(other: ChatUser, ttlMs: Long): Result<Unit> = runCatching {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        val chatId = conversationId(me.uid, other)
        root.updateChildren(
            mapOf(
                "chats/$chatId/settings/ttl" to ttlMs,
                "ttlChats/$chatId" to if (ttlMs > 0) true else null,
            ),
        ).await()
        ttls[chatId] = ttlMs
        val tr = app.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
        val who = me.name.substringBefore(' ')
        val span = when (ttlMs) {
            24 * 3600_000L -> if (tr) "24 saat" else "24 hours"
            7 * 24 * 3600_000L -> if (tr) "7 gün" else "7 days"
            90 * 24 * 3600_000L -> if (tr) "90 gün" else "90 days"
            else -> "${ttlMs / 3600_000L}h"
        }
        val line = when {
            ttlMs <= 0 -> if (tr) "$who süreli mesajları kapattı" else "$who turned off disappearing messages"
            tr -> "$who süreli mesajları açtı: yeni mesajlar $span sonra silinecek"
            else -> "$who turned on disappearing messages: new messages go after $span"
        }
        sendMessage(other, line, null, null, null, "⏱ $line", extra = mapOf("system" to "ttl", "who" to who, "whom" to span), noExpiry = true)
    }

    override suspend fun setMuted(chatId: String, until: Long) {
        root.child("userChats").child(me()).child(chatId).child("mutedUntil").setValue(if (until == 0L) null else until)
    }

    // ------------------------------------------------------------------ read marks

    override fun readMarks(chatId: String): Flow<Map<String, Long>> = marks(chatId, "read", hideWhenPrivate = true)
    override fun deliveredMarks(chatId: String): Flow<Map<String, Long>> = marks(chatId, "delivered", hideWhenPrivate = false)

    /** Until when each member read / has the messages; who hides their own read marks does not see the others' either. */
    private fun marks(chatId: String, what: String, hideWhenPrivate: Boolean): Flow<Map<String, Long>> =
        combine(
            callbackFlow {
                val job = launch {
                    listen(root.child("chats").child(chatId).child(what)).collect { snap ->
                        trySend(snap.children.mapNotNull { m -> m.key?.let { k -> k to (m.getValue(Long::class.java) ?: 0L) } }.toMap())
                    }
                }
                awaitClose { job.cancel() }
            },
            _privacy,
        ) { map, p -> if (hideWhenPrivate && p.hideReadReceipts) emptyMap() else map }

    // ------------------------------------------------------------------ polls, places, contacts

    override suspend fun sendEvent(other: ChatUser, event: ChatEvent): Result<Unit> = runCatching {
        val title = event.title.trim().take(100)
        require(title.isNotEmpty()) { "event" }
        sendMessage(
            other, "", null, null, null, "📅 $title",
            extra = mapOf("event" to mapOf("t" to title, "at" to event.at, "p" to event.place.trim().take(100).takeIf { it.isNotEmpty() })),
        )
    }

    override suspend fun sendPoll(other: ChatUser, poll: ChatPoll): Result<Unit> = runCatching {
        val question = poll.question.trim().take(300)
        val options = poll.options.map { it.trim().take(100) }.filter { it.isNotEmpty() }.distinct().take(12)
        require(question.isNotEmpty() && options.size >= 2) { "poll" }
        sendMessage(other, "", null, null, null, "📊 $question", extra = mapOf("poll" to mapOf("q" to question, "o" to options, "m" to poll.multiple)))
    }

    override fun pollVotes(chatId: String): Flow<Map<String, Map<String, List<Int>>>> = callbackFlow {
        val job = launch {
            listen(root.child("chats").child(chatId).child("polls")).collect { snap ->
                trySend(
                    snap.children.associate { poll ->
                        poll.key.orEmpty() to poll.children.mapNotNull { v ->
                            val uid = v.key ?: return@mapNotNull null
                            val picks = v.getValue(String::class.java).orEmpty().split(',').mapNotNull { it.toIntOrNull() }
                            if (picks.isEmpty()) null else uid to picks
                        }.toMap()
                    },
                )
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun vote(chatId: String, messageId: String, choices: List<Int>) {
        val ref = root.child("chats").child(chatId).child("polls").child(messageId).child(me())
        if (choices.isEmpty()) ref.removeValue() else ref.setValue(choices.distinct().sorted().joinToString(","))
    }

    override suspend fun sendLocation(other: ChatUser, location: ChatLocation): Result<Unit> = runCatching {
        val tr = app.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
        sendMessage(
            other, "", null, null, null, if (tr) "📍 Konum" else "📍 Location",
            extra = mapOf(
                "location" to mapOf(
                    "lat" to location.lat,
                    "lng" to location.lng,
                    "label" to location.label.take(120),
                    "live" to location.live.takeIf { it },
                    "until" to location.until.takeIf { location.live },
                ),
            ),
        )
    }

    override suspend fun sendContact(other: ChatUser, contact: ChatContact): Result<Unit> = runCatching {
        require(contact.phone.isNotBlank()) { "phone" }
        sendMessage(
            other, "", null, null, null, "👤 " + contact.name.ifBlank { contact.phone },
            extra = mapOf("contact" to mapOf("name" to contact.name.take(80), "phone" to contact.phone.take(40))),
        )
    }

    // ------------------------------------------------------------------ link cards

    private val previews = android.util.LruCache<String, LinkPreview>(64)

    override suspend fun linkPreview(url: String): LinkPreview? {
        previews.get(url)?.let { return it }
        val card = kotlinx.coroutines.withContext(Dispatchers.IO) { LinkPreviews.fetch(url) } ?: return null
        previews.put(url, card)
        return card
    }

    // ------------------------------------------------------------------ group calls

    override val groupCalls: FirebaseGroupCallService by lazy {
        FirebaseGroupCallService(app, root, { (_account.value as? ChatAccount.Ready)?.me }, calls) { id, group, video, seconds ->
            logCall(id, group, video, CallDirection.GROUP, if (seconds > 0) CallOutcome.ANSWERED else CallOutcome.NOT_ANSWERED, seconds)
        }.also { groups ->
            // Someone added to a call: the two phones go on together in a group call.
            calls.onMoveToRoom = { room, video -> groups.join(room, video) }
        }
    }

    private fun MsgNode.toMessage(pending: Set<String>): ChatMessage? {
        val id = key ?: return null
        val from = child("from").getValue(String::class.java) ?: return null
        // "Messages are now end-to-end encrypted": no longer shown (encryption is simply how chats are).
        val sys = child("system").getValue(String::class.java)
        if (sys == "e2e") return null
        val shareSnap = child("share")
        val share = if (!shareSnap.exists()) {
            null
        } else {
            val kind = runCatching { ShareKind.valueOf(shareSnap.child("kind").getValue(String::class.java).orEmpty()) }.getOrNull()
            val shareId = shareSnap.child("id").getValue(String::class.java)
            if (kind == null || shareId == null) {
                null
            } else {
                SharedItem(
                    kind = kind,
                    id = shareId,
                    title = shareSnap.child("title").getValue(String::class.java).orEmpty(),
                    subtitle = shareSnap.child("subtitle").getValue(String::class.java).orEmpty(),
                    thumbnail = shareSnap.child("thumbnail").getValue(String::class.java),
                    startMs = (shareSnap.child("start").getValue(Long::class.java) ?: 0L).coerceAtLeast(0L),
                    quote = shareSnap.child("quote").getValue(String::class.java).orEmpty().take(300),
                )
            }
        }
        val replySnap = child("replyTo")
        val reply = replySnap.child("id").getValue(String::class.java)?.let { replyId ->
            ChatReply(
                replyId,
                replySnap.child("from").getValue(String::class.java).orEmpty(),
                replySnap.child("text").getValue(String::class.java).orEmpty(),
                replySnap.child("thumb").getValue(String::class.java).orEmpty(),
            )
        }
        fun MsgNode.toImage(): ChatImage? = child("id").getValue(String::class.java)?.let { imageId ->
            ChatImage(
                id = imageId,
                width = child("w").getValue(Int::class.java) ?: 0,
                height = child("h").getValue(Int::class.java) ?: 0,
                thumb = child("thumb").getValue(String::class.java).orEmpty(),
                url = child("url").getValue(String::class.java),
                key = child("key").getValue(String::class.java).orEmpty(),
            ).also { if (it.url != null) E2eMedia.remember(it.url, it.key) }
        }
        val att = child("attachment")
        val attachment = att.child("url").getValue(String::class.java)?.let { url ->
            runCatching { AttachmentKind.valueOf(att.child("kind").getValue(String::class.java).orEmpty().uppercase()) }.getOrNull()?.let { kind ->
                ChatAttachment(
                    kind = kind,
                    url = url,
                    name = att.child("name").getValue(String::class.java).orEmpty(),
                    size = att.child("size").getValue(Long::class.java) ?: 0L,
                    mime = att.child("mime").getValue(String::class.java).orEmpty(),
                    durationMs = att.child("duration").getValue(Long::class.java) ?: 0L,
                    width = att.child("w").getValue(Int::class.java) ?: 0,
                    height = att.child("h").getValue(Int::class.java) ?: 0,
                    thumb = att.child("thumb").getValue(String::class.java).orEmpty(),
                    wave = att.child("wave").getValue(String::class.java).orEmpty().map { (it - 'A').coerceIn(0, 25) * 4 },
                    key = att.child("key").getValue(String::class.java).orEmpty(),
                ).also { E2eMedia.remember(url, it.key) }
            }
        }
        // An album ("images"), or one photo as the first version wrote it ("image").
        val images = child("images").children.mapNotNull { it.toImage() }.ifEmpty { listOfNotNull(child("image").toImage()) }
        val locked = child("_locked").getValue(Boolean::class.java) == true
        val rawText = if (locked) E2e.UNREADABLE else child("text").getValue(String::class.java).orEmpty()
        val msgText = if (sys == "pin") {
            val tr = app.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
            val str = if (tr) ChatStrings.Turkish else ChatStrings.English
            if (from == auth.currentUser?.uid) {
                str.pinnedYou
            } else {
                val who = child("who").getValue(String::class.java).orEmpty()
                if (who.isNotBlank()) str.pinnedBy(who) else rawText
            }
        } else {
            rawText
        }
        return ChatMessage(
            id = id,
            from = from,
            text = msgText,
            replyTo = reply,
            images = images,
            model = child("model").getValue(String::class.java),
            streaming = child("streaming").getValue(Boolean::class.java) == true,
            at = child("at").getValue(Long::class.java) ?: System.currentTimeMillis(),
            share = share,
            pending = id in pending,
            attachment = attachment,
            system = child("system").exists(),
            together = child("together").getValue(Boolean::class.java) == true,
            edited = child("edited").getValue(Boolean::class.java) == true,
            deleted = child("deleted").getValue(Boolean::class.java) == true,
            forwarded = child("forwarded").getValue(Boolean::class.java) == true,
            expireAt = child("expireAt").getValue(Long::class.java) ?: 0L,
            poll = child("poll").takeIf { it.exists() }?.let { p ->
                val question = p.child("q").getValue(String::class.java) ?: return@let null
                val options = p.child("o").children.mapNotNull { it.getValue(String::class.java) }
                if (options.size < 2) null else ChatPoll(question, options, p.child("m").getValue(Boolean::class.java) == true)
            },
            event = child("event").takeIf { it.exists() }?.let { e ->
                val title = e.child("t").getValue(String::class.java) ?: return@let null
                ChatEvent(title, e.child("at").getValue(Long::class.java) ?: 0L, e.child("p").getValue(String::class.java).orEmpty())
            },
            location = child("location").takeIf { it.exists() }?.let { l ->
                val lat = l.child("lat").getValue(Double::class.java) ?: return@let null
                val lng = l.child("lng").getValue(Double::class.java) ?: return@let null
                ChatLocation(
                    lat,
                    lng,
                    l.child("label").getValue(String::class.java).orEmpty(),
                    live = l.child("live").getValue(Boolean::class.java) == true,
                    until = l.child("until").getValue(Long::class.java) ?: 0L,
                )
            },
            contact = child("contact").takeIf { it.exists() }?.let { c ->
                ChatContact(c.child("name").getValue(String::class.java).orEmpty(), c.child("phone").getValue(String::class.java) ?: return@let null)
            },
            viewOnce = child("viewOnce").getValue(Boolean::class.java) == true,
            mentions = child("mentions").children.mapNotNull { it.key }.toSet(),
            gif = child("gif").takeIf { it.exists() }?.let { g ->
                ChatGif(
                    url = g.child("url").getValue(String::class.java) ?: return@let null,
                    width = g.child("w").getValue(Int::class.java) ?: 0,
                    height = g.child("h").getValue(Int::class.java) ?: 0,
                    sticker = g.child("sticker").getValue(Boolean::class.java) == true,
                )
            },
            e2e = child("_e2e").getValue(Boolean::class.java) == true,
            locked = locked,
        )
    }

    override fun otherDeliveredAt(chatId: String, otherUid: String): Flow<Long> = callbackFlow {
        val job = launch {
            listen(root.child("chats").child(chatId).child("delivered").child(otherUid)).collect { trySend(it.getValue(Long::class.java) ?: 0L) }
        }
        awaitClose { job.cancel() }
    }

    /** This phone has the conversation's messages up to now: the sender sees two ticks. */
    fun markDelivered(chatId: String) {
        val uid = auth.currentUser?.uid ?: return
        root.child("chats").child(chatId).child("delivered").child(uid).setValue(ServerValue.TIMESTAMP)
    }

    /**
     * While signed in, every new message to this person (the list's entries changing) is marked delivered at once, the
     * app open or not, as long as it runs: a push wakes it, and that is enough.
     */
    private var deliveryRef: DatabaseReference? = null
    private var deliveryListener: ChildEventListener? = null
    private val deliveredUpTo = HashMap<String, Long>()

    private fun watchDeliveries(uid: String?) {
        deliveryListener?.let { deliveryRef?.removeEventListener(it) }
        deliveryListener = null
        deliveredUpTo.clear()
        if (uid == null) return
        val ref = root.child("userChats").child(uid)
        val listener = object : ChildEventListener {
            fun seen(snap: DataSnapshot) {
                val chatId = snap.key ?: return
                val from = snap.child("lastFrom").getValue(String::class.java) ?: return
                val at = snap.child("lastAt").getValue(Long::class.java) ?: return
                if (from == uid || at <= (deliveredUpTo[chatId] ?: 0L)) return
                deliveredUpTo[chatId] = at
                markDelivered(chatId)
            }

            override fun onChildAdded(snap: DataSnapshot, previous: String?) = seen(snap)
            override fun onChildChanged(snap: DataSnapshot, previous: String?) = seen(snap)
            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, previous: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addChildEventListener(listener)
        deliveryRef = ref
        deliveryListener = listener
    }

    /** The signed-in person, waited for (a notification's action can wake the app before sign-in is restored). */
    suspend fun awaitMe(timeoutMs: Long = 15_000): ChatUser? = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        (account.first { it is ChatAccount.Ready || it is ChatAccount.SignedOut || it is ChatAccount.Unavailable } as? ChatAccount.Ready)?.me
    }

    override fun otherReadAt(chatId: String, otherUid: String): Flow<Long> = combine(
        callbackFlow {
            val job = launch {
                listen(root.child("chats").child(chatId).child("read").child(otherUid)).collect { trySend(it.getValue(Long::class.java) ?: 0L) }
            }
            awaitClose { job.cancel() }
        },
        _privacy,
    ) { at, p -> if (p.hideReadReceipts) 0L else at }

    override suspend fun send(other: ChatUser, text: String, share: SharedItem?, replyTo: ChatReply?): Result<Unit> = runCatching {
        sendMessage(other, text, share, replyTo, images = null, preview = null)
    }

    private suspend fun sendMessage(
        other: ChatUser,
        text: String,
        share: SharedItem?,
        replyTo: ChatReply?,
        images: List<Map<String, Any?>>?,
        preview: String?,
        extra: Map<String, Any?> = emptyMap(),
        fixedId: String? = null,
        noExpiry: Boolean = false,
    ) {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        val chatId = conversationId(me.uid, other)
        val messageId = fixedId ?: root.child("chats").child(chatId).child("messages").push().key ?: error("no id")
        val ttl = if (noExpiry || other.uid == CLAUDE_BOT_UID) 0L else ttlOf(chatId)
        var message: Map<String, Any?> = buildMap<String, Any?> {
            putAll(extra)
            if (ttl > 0) put("expireAt", System.currentTimeMillis() + _serverOffset.value + ttl)
            put("from", me.uid)
            put("text", text)
            put("at", ServerValue.TIMESTAMP)
            if (replyTo != null) {
                put("replyTo", mapOf("id" to replyTo.id, "from" to replyTo.from, "text" to replyTo.text.take(PREVIEW_LENGTH), "thumb" to replyTo.thumb.takeIf { it.isNotEmpty() && it.length < 4_000 }))
            }
            if (!images.isNullOrEmpty()) put("images", images)
            if (share != null) {
                put(
                    "share",
                    mapOf(
                        "kind" to share.kind.name,
                        "id" to share.id,
                        "title" to share.title,
                        "subtitle" to share.subtitle,
                        "thumbnail" to share.thumbnail,
                        "start" to share.startMs.takeIf { it > 0 },
                        "quote" to share.quote.takeIf { it.isNotEmpty() }?.take(300),
                    ),
                )
            }
        }
        val shown = preview ?: text.ifBlank { share?.let { (if (it.kind == ShareKind.SONG) "🎵 " else "🎬 ") + it.title }.orEmpty() }
        val plainPreview = shown.take(PREVIEW_LENGTH)
        var preview = plainPreview
        // An encrypted conversation: the content and the preview go sealed (system lines stay readable).
        if (other.uid != CLAUDE_BOT_UID && !message.containsKey("system")) {
            var version = e2e.versionNow(chatId)
            // New conversations are encrypted from their first message (when everyone's app can take it).
            val now = System.currentTimeMillis()
            if (version == null && SecretSpace.encryptNewChats && (noKeysUntil[chatId] ?: 0L) < now) {
                if (encryptIfPossible(chatId)) version = e2e.versionNow(chatId) else noKeysUntil[chatId] = now + 10 * 60_000L
            }
            if (version != null) {
                e2e.loadKey(chatId, version) ?: error("e2e-key")
                val sealed = e2e.sealContent(chatId, version, message.filterKeys { it !in MsgNode.ROUTING && it != "mentions" }) ?: error("e2e-key")
                message = message.filterKeys { it in MsgNode.ROUTING || it == "mentions" } + mapOf("e2e" to sealed, "text" to "")
                preview = e2e.sealText(chatId, version, plainPreview) ?: error("e2e-key")
            }
        }
        if (other.isGroup) {
            sendToGroup(me, chatId, messageId, message, preview)
            return
        }
        val mine = "userChats/${me.uid}/$chatId"
        val theirs = "userChats/${other.uid}/$chatId"
        val updates = mapOf<String, Any?>(
            "chats/$chatId/messages/$messageId" to message,
            "chats/$chatId/read/${me.uid}" to ServerValue.TIMESTAMP,
            // Sent: no longer typing.
            "typing/$chatId/${me.uid}" to null,
            "$mine/other" to other.uid,
            "$mine/name" to other.name,
            "$mine/username" to other.username,
            "$mine/photo" to other.photo,
            "$mine/lastText" to preview,
            "$mine/lastAt" to ServerValue.TIMESTAMP,
            "$mine/lastFrom" to me.uid,
            "$mine/read" to ServerValue.TIMESTAMP,
            "$theirs/other" to me.uid,
            "$theirs/name" to me.name,
            "$theirs/username" to me.username,
            "$theirs/photo" to me.publicPhoto(),
            "$theirs/lastText" to preview,
            "$theirs/lastAt" to ServerValue.TIMESTAMP,
            "$theirs/lastFrom" to me.uid,
            "$theirs/unread" to ServerValue.increment(1),
            "$theirs/typingAt" to null,
            "$mine/unread" to 0,
            // The push to the other person's phones (sent by QMusic's server, see server/qmusic-push).
            "notify/${root.child("notify").push().key}" to mapOf(
                "from" to me.uid,
                "to" to other.uid,
                "chatId" to chatId,
                "type" to "chat",
                "body" to preview,
                "at" to ServerValue.TIMESTAMP,
            ),
        ).let { all ->
            // The first message of a conversation started in the secret space keeps it there (only ever set, never
            // cleared here: a later message must not take a secret conversation out).
            if (secretPending.remove(chatId)) all + ("$mine/secret" to true) else all
        }
        // Not awaited: without a connection it waits on the phone (shown with a clock) and goes when there is one.
        pendingIds.update { it + messageId }
        root.updateChildren(updates).addOnCompleteListener { pendingIds.update { it - messageId } }
    }

    /** A message to a group: every member's list gets it ("Ali: …"), and one push entry the server sends to all. */
    private suspend fun sendToGroup(me: ChatUser, groupId: String, messageId: String, message: Map<String, Any?>, preview: String) {
        val group = groupInfo(groupId) ?: error("no group")
        val updates = HashMap<String, Any?>()
        updates["chats/$groupId/messages/$messageId"] = message
        updates["chats/$groupId/read/${me.uid}"] = ServerValue.TIMESTAMP
        updates["typing/$groupId/${me.uid}"] = null
        for (member in group.members) {
            val base = "userChats/${member.user.uid}/$groupId"
            updates["$base/other"] = groupId
            updates["$base/name"] = group.name
            updates["$base/username"] = ""
            updates["$base/photo"] = group.photo
            updates["$base/lastText"] = preview
            updates["$base/lastAt"] = ServerValue.TIMESTAMP
            updates["$base/lastFrom"] = me.uid
            updates["$base/lastFromName"] = me.name.substringBefore(' ')
            updates["$base/typingAt"] = null
            if (member.user.uid != me.uid) updates["$base/unread"] = ServerValue.increment(1)
        }
        updates["userChats/${me.uid}/$groupId/unread"] = 0
        updates["userChats/${me.uid}/$groupId/read"] = ServerValue.TIMESTAMP
        @Suppress("UNCHECKED_CAST")
        val mentions = (message["mentions"] as? Map<String, Boolean>)?.takeIf { it.isNotEmpty() }
        // Who is named in it sees an @ beside the group in their list, until they read it (as on WhatsApp).
        mentions?.keys?.filter { uid -> uid != me.uid && group.members.any { it.user.uid == uid } }?.forEach { uid ->
            updates["userChats/$uid/$groupId/mention"] = true
        }
        updates["notify/${root.child("notify").push().key}"] = buildMap<String, Any?> {
            put("from", me.uid)
            put("to", groupId)
            put("chatId", groupId)
            put("type", "chat")
            put("body", if (group.secret) "" else preview)
            put("at", ServerValue.TIMESTAMP)
            if (mentions != null) put("mentions", mentions)
            if (group.secret) {
                put("secret", true)
                // Those just asked in hear of the invitation (the server tells only them so).
                if (message["system"] == "created" || message["system"] == "added") put("invite", true)
            }
        }
        pendingIds.update { it + messageId }
        root.updateChildren(updates).addOnCompleteListener { pendingIds.update { it - messageId } }
    }

    override suspend fun sendImages(other: ChatUser, images: List<PickedImage>, caption: String, replyTo: ChatReply?, viewOnce: Boolean): Result<Unit> = runCatching {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        val chatId = conversationId(me.uid, other)
        val messageId = root.child("chats").child(chatId).child("messages").push().key ?: error("no id")
        // Shown at once (from the phone's own copies) while the photos go up to the media server side by side; the
        // message itself follows once all are there, so it never points at a missing one.
        val local = images.mapIndexed { i, image ->
            val id = "$messageId-$i"
            imageCache.put("$chatId/$id", image.jpeg)
            ChatImage(id, image.width, image.height, image.thumb)
        }
        addOutbox(chatId, ChatMessage(id = messageId, from = me.uid, text = caption, images = local, at = System.currentTimeMillis(), pending = true))
        try {
            val sent = LongArray(images.size)
            val total = images.sumOf { it.jpeg.size.toLong() }.coerceAtLeast(1)
            val sealed = other.uid != CLAUDE_BOT_UID && e2e.versionNow(chatId) != null
            val entries = kotlinx.coroutines.coroutineScope {
                images.mapIndexed { i, image ->
                    async(Dispatchers.IO) {
                        // Encrypted: the server gets only sealed bytes; the key goes inside the sealed message.
                        val key = if (sealed) E2eCrypto.bytes(32) else null
                        val body = if (key == null) image.jpeg else java.io.ByteArrayOutputStream().also { o -> E2eStream.seal(key, image.jpeg.inputStream(), o) }.toByteArray()
                        val url = MediaUpload.upload(token(), chatId, if (key == null) "jpg" else "bin", if (key == null) "image/jpeg" else "application/octet-stream", body.size.toLong(), { body.inputStream() }) { done ->
                            sent[i] = done
                            _uploads.update { it + (messageId to sent.sum().toFloat() / total) }
                        }
                        imageCache.put(url, image.jpeg)
                        key?.let { E2eMedia.remember(url, E2eCrypto.b64(it)) }
                        // A view-once photo has no blurred preview: nothing of it shows before it is opened.
                        mapOf("id" to local[i].id, "url" to url, "w" to image.width, "h" to image.height, "thumb" to if (viewOnce) "" else image.thumb, "key" to key?.let { E2eCrypto.b64(it) })
                    }
                }.map { it.await() }
            }
            val tr = app.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
            val label = when {
                viewOnce -> if (tr) "📷 Tek seferlik fotoğraf" else "📷 View once photo"
                entries.size == 1 -> "📷"
                else -> "📷 ×${entries.size}"
            }
            sendMessage(
                other = other,
                text = caption,
                share = null,
                replyTo = replyTo,
                images = entries,
                preview = if (caption.isBlank() || viewOnce) label else "$label $caption",
                extra = if (viewOnce) mapOf("viewOnce" to true) else emptyMap(),
                fixedId = messageId,
            )
        } finally {
            removeOutbox(chatId, messageId)
        }
    }

    // ------------------------------------------------------------------ voice messages, videos, files

    /** What is going up from this phone, per conversation: shown in it with how far it got. */
    private val outbox = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val _uploads = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val uploads: StateFlow<Map<String, Float>> = _uploads.asStateFlow()

    private fun addOutbox(chatId: String, message: ChatMessage) = outbox.update { it + (chatId to (it[chatId].orEmpty() + message)) }

    private fun removeOutbox(chatId: String, messageId: String) {
        outbox.update { map -> map + (chatId to map[chatId].orEmpty().filter { it.id != messageId }) }
        _uploads.update { it - messageId }
    }

    private suspend fun token(): String = auth.currentUser?.getIdToken(false)?.await()?.token ?: error("not signed in")

    override suspend fun sendFile(other: ChatUser, file: PickedFile, caption: String, replyTo: ChatReply?): Result<Unit> = runCatching {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        val chatId = conversationId(me.uid, other)
        val messageId = root.child("chats").child(chatId).child("messages").push().key ?: error("no id")
        val ext = when (file.kind) {
            AttachmentKind.VOICE -> "m4a"
            AttachmentKind.VIDEO -> "mp4"
            AttachmentKind.FILE -> file.name.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        }
        val localAttachment = ChatAttachment(file.kind, file.location, file.name, file.size, file.mime, file.durationMs, file.width, file.height, file.thumb, file.wave)
        addOutbox(chatId, ChatMessage(id = messageId, from = me.uid, text = caption, attachment = localAttachment, at = System.currentTimeMillis(), pending = true))
        try {
            // A video goes as 720p H.264 (a phone's 4K clip of 200 MB becomes ~20 MB); the first 40 % of the ring is that.
            var source = file.location
            var size = file.size
            var share = 0f
            if (file.kind == AttachmentKind.VIDEO) {
                VideoCompressor.compress(app, file.location) { p -> _uploads.update { it + (messageId to p * 0.4f) } }?.let { small ->
                    if (small.length() in 1 until size) {
                        source = small.absolutePath
                        size = small.length()
                    }
                }
                share = 0.4f
            }
            require(size <= MediaUpload.MAX_BYTES) { "max 100 MB" }
            // Encrypted: sealed on the phone first, and the sealed copy goes up.
            val seal = if (other.uid != CLAUDE_BOT_UID && e2e.versionNow(chatId) != null) {
                val from = source
                kotlinx.coroutines.withContext(Dispatchers.IO) { E2eMedia.sealToFile(app, { openLocation(from) }) }
            } else {
                null
            }
            val upSize = seal?.first?.length() ?: size
            val url = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val from = source
                    MediaUpload.upload(
                        token(),
                        chatId,
                        if (seal == null) ext else "bin",
                        if (seal == null) file.mime.ifBlank { "application/octet-stream" } else "application/octet-stream",
                        upSize,
                        { seal?.first?.inputStream() ?: openLocation(from) },
                    ) { done ->
                        _uploads.update { it + (messageId to share + (1 - share) * done.toFloat() / upSize.coerceAtLeast(1)) }
                    }
                }
            } finally {
                seal?.first?.delete()
            }
            seal?.let { E2eMedia.remember(url, it.second) }
            if (source != file.location) runCatching { java.io.File(source).delete() }
            // Our own voice message plays from the phone's copy, without fetching it back.
            if (file.kind == AttachmentKind.VOICE) localCopies[url] = file.location
            val attachment = buildMap<String, Any?> {
                put("kind", file.kind.name.lowercase())
                put("url", url)
                put("name", file.name.take(120))
                put("size", size)
                put("mime", file.mime)
                if (file.durationMs > 0) put("duration", file.durationMs)
                if (file.width > 0) put("w", file.width)
                if (file.height > 0) put("h", file.height)
                if (file.thumb.isNotEmpty()) put("thumb", file.thumb)
                // The shape of the sound as letters A-Z (one per bar), small enough to sit in the message.
                if (file.wave.isNotEmpty()) put("wave", file.wave.joinToString("") { ('A' + (it / 4).coerceIn(0, 25)).toString() })
                seal?.let { put("key", it.second) }
            }
            val label = when (file.kind) {
                AttachmentKind.VOICE -> "🎤 " + duration(file.durationMs / 1000)
                AttachmentKind.VIDEO -> "🎬 " + (if (file.durationMs > 0) duration(file.durationMs / 1000) else "")
                AttachmentKind.FILE -> "📄 " + file.name
            }.trim()
            sendMessage(
                other = other,
                text = caption,
                share = null,
                replyTo = replyTo,
                images = null,
                preview = if (caption.isBlank()) label else "$label $caption",
                extra = mapOf("attachment" to attachment),
                fixedId = messageId,
            )
        } finally {
            removeOutbox(chatId, messageId)
        }
    }

    /** Our own recordings: the media link → the file on the phone. */
    val localCopies = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun openLocation(location: String): java.io.InputStream =
        if (location.startsWith("content:") || location.startsWith("file:")) {
            app.contentResolver.openInputStream(android.net.Uri.parse(location)) ?: error("cannot read")
        } else {
            java.io.FileInputStream(location)
        }

    // ------------------------------------------------------------------ pinned, archived

    override suspend fun setPinned(chatId: String, pinned: Boolean) {
        root.child("userChats").child(me()).child(chatId).child("pinned").setValue(if (pinned) ServerValue.TIMESTAMP else null)
    }

    override suspend fun setArchived(chatId: String, archived: Boolean) {
        root.child("userChats").child(me()).child(chatId).child("archived").setValue(if (archived) true else null)
    }

    // ------------------------------------------------------------------ groups
    //
    //   groups/{id} = {name, photo, createdBy, createdAt, members: {uid: {name, username, photo, role: admin|member}}}
    //   messages, read marks, reactions and typing as for two people, under the group's id

    private val groups = java.util.concurrent.ConcurrentHashMap<String, GroupInfo>()

    private fun DataSnapshot.toGroup(): GroupInfo? {
        val id = key ?: return null
        val name = child("name").getValue(String::class.java) ?: return null
        val members = child("members").children.mapNotNull { m ->
            val uid = m.key ?: return@mapNotNull null
            GroupMember(
                user = ChatUser(uid, m.child("name").getValue(String::class.java) ?: "?", m.child("username").getValue(String::class.java).orEmpty(), m.child("photo").getValue(String::class.java)),
                admin = m.child("role").getValue(String::class.java) == "admin",
                invited = m.child("invited").getValue(Boolean::class.java) == true,
            )
        }.sortedWith(compareByDescending<GroupMember> { it.admin }.thenBy { it.user.name.lowercase() })
        return GroupInfo(
            id, name, child("photo").getValue(String::class.java), members, child("createdBy").getValue(String::class.java),
            description = child("description").getValue(String::class.java).orEmpty(),
            settings = GroupSettings(
                sendAdmins = child("settings").child("sendAdmins").getValue(Boolean::class.java) == true,
                editAdmins = child("settings").child("editAdmins").getValue(Boolean::class.java) == true,
            ),
            secret = child("secret").getValue(Boolean::class.java) == true,
            shareHistory = child("shareHistory").getValue(Boolean::class.java) == true,
            endsAt = child("endsAt").getValue(Long::class.java) ?: 0L,
        )
    }

    override suspend fun setGroupPhoto(groupId: String, image: PickedImage): Result<Unit> = runCatching {
        val url = kotlinx.coroutines.withContext(Dispatchers.IO) {
            MediaUpload.upload(token(), groupId, "jpg", "image/jpeg", image.jpeg.size.toLong(), { image.jpeg.inputStream() }) {}
        }
        imageCache.put(url, image.jpeg)
        root.child("groups").child(groupId).child("photo").setValue(url).await()
        // Every member's list shows the new picture at once.
        val info = groupInfo(groupId, fresh = true) ?: return@runCatching
        root.updateChildren(info.members.associate { "userChats/${it.user.uid}/$groupId/photo" to url })
    }

    override suspend fun setGroupDescription(groupId: String, text: String): Result<Unit> = runCatching {
        root.child("groups").child(groupId).child("description").setValue(text.trim().take(500)).await()
    }

    override suspend fun searchGifs(stickers: Boolean, query: String, page: Int): Result<List<GifItem>> = runCatching {
        val idToken = token()
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val q = java.net.URLEncoder.encode(query.trim().take(60), "UTF-8")
            val url = java.net.URL("${MediaUpload.BASE}/media/gifs?kind=${if (stickers) "stickers" else "gifs"}&q=$q&page=$page")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $idToken")
            }
            try {
                val code = conn.responseCode
                if (code == 503) error("gif-key")
                require(code == 200) { "gifs $code" }
                val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                val items = json.optJSONArray("items") ?: org.json.JSONArray()
                (0 until items.length()).mapNotNull { i ->
                    val it = items.optJSONObject(i) ?: return@mapNotNull null
                    GifItem(it.optString("id"), it.optString("preview"), it.optString("url"), it.optInt("w"), it.optInt("h"))
                        .takeIf { g -> g.url.startsWith("https://") && g.preview.startsWith("https://") }
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    override suspend fun sendGif(other: ChatUser, gif: ChatGif, replyTo: ChatReply?): Result<Unit> = runCatching {
        require(gif.url.startsWith("https://")) { "gif" }
        val tr = app.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
        val label = if (gif.sticker) (if (tr) "Çıkartma" else "Sticker") else "GIF"
        sendMessage(
            other, "", null, replyTo, images = null, preview = label,
            extra = mapOf("gif" to mapOf("url" to gif.url, "w" to gif.width, "h" to gif.height, "sticker" to gif.sticker)),
        )
    }

    override suspend fun setGroupSettings(groupId: String, settings: GroupSettings): Result<Unit> = runCatching {
        root.child("groups").child(groupId).child("settings").setValue(mapOf("sendAdmins" to settings.sendAdmins, "editAdmins" to settings.editAdmins)).await()
        groupInfo(groupId, fresh = true)
    }

    // ------------------------------------------------------------------ pins, view once, mentions, likes
    //
    //   chats/{id}/pinned/{messageId}        {text, from, at}: at most three, newest kept
    //   chats/{id}/opened/{messageId}/{uid}  when a view-once photo was opened
    //   statusLikes/{owner}/{id}/{uid}       a heart on a status (only its owner reads them)

    /** Returns the longest prefix of [text] whose UTF-8 encoding fits in [maxBytes] without splitting surrogate pairs or multi-byte characters. */
    private fun takeUtf8(text: String, maxBytes: Int): String {
        if (maxBytes <= 0 || text.isEmpty()) return ""
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val cpBytes = when {
                cp <= 0x7F -> 1
                cp <= 0x7FF -> 2
                cp <= 0xFFFF -> 3
                else -> 4
            }
            if (bytes + cpBytes > maxBytes) break
            bytes += cpBytes
            i += Character.charCount(cp)
        }
        return text.substring(0, i)
    }

    private suspend fun chatPeer(chatId: String, me: ChatUser): ChatUser? {
        if (isGroupId(chatId)) {
            val info = groupInfo(chatId) ?: return null
            return ChatUser(chatId, info.name, "", info.photo)
        }
        val parts = chatId.split("_")
        if (parts.size != 2) return null
        val otherUid = if (parts[0] == me.uid) parts[1] else parts[0]
        if (otherUid == me.uid) return me
        liveProfiles.value[otherUid]?.let { p ->
            return ChatUser(otherUid, p.name, p.username, p.photo)
        }
        user(otherUid)?.let { return it }
        val uc = runCatching { root.child("userChats").child(me.uid).child(chatId).get().await() }.getOrNull()
        val name = uc?.child("name")?.getValue(String::class.java) ?: return null
        return ChatUser(
            uid = otherUid,
            name = name,
            username = uc.child("username").getValue(String::class.java).orEmpty(),
            photo = uc.child("photo").getValue(String::class.java),
        )
    }

    override fun pinned(chatId: String): Flow<List<PinnedMessage>> = callbackFlow {
        e2e.watch(chatId)
        val job = launch {
            runCatching {
                combine(listen(root.child("chats").child(chatId).child("pinned")), e2e.changes) { snap, _ -> snap }.collect { snap ->
                    trySend(
                        snap.children.mapNotNull { p ->
                            val id = p.key ?: return@mapNotNull null
                            val pinText = p.child("text").getValue(String::class.java).orEmpty().let { t ->
                                if (t.startsWith("e2e:")) e2e.openText(chatId, t)?.takeIf { it != E2e.UNREADABLE } ?: "🔒" else t
                            }
                            PinnedMessage(id, pinText, p.child("from").getValue(String::class.java).orEmpty(), p.child("at").getValue(Long::class.java) ?: 0L)
                        }.sortedByDescending { it.at },
                    )
                }
            }.onFailure { trySend(emptyList()) }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun pin(chatId: String, message: ChatMessage, preview: String, pinned: Boolean): Result<Unit> = runCatching {
        val ref = root.child("chats").child(chatId).child("pinned")
        if (!pinned) {
            ref.child(message.id).removeValue().await()
            return@runCatching
        }
        // Three at most: the oldest pin makes room.
        val current = ref.get().await().children.mapNotNull { c -> c.key?.let { it to (c.child("at").getValue(Long::class.java) ?: 0L) } }
        val alreadyPinned = current.any { it.first == message.id }
        val updates = HashMap<String, Any?>()
        current.filter { it.first != message.id }.sortedByDescending { it.second }.drop(2).forEach { updates[it.first] = null }
        val version = e2e.versionNow(chatId)
        val pinText = if (version != null) {
            e2e.loadKey(chatId, version) ?: error("e2e-key")
            e2e.sealText(chatId, version, takeUtf8(preview, 180)) ?: error("e2e-key")
        } else {
            if (message.e2e) error("e2e-key")
            preview.take(300)
        }
        updates[message.id] = mapOf("text" to pinText, "from" to message.from, "at" to ServerValue.TIMESTAMP)
        ref.updateChildren(updates).await()
        if (!alreadyPinned) {
            val me = (_account.value as? ChatAccount.Ready)?.me
            if (me != null) {
                runCatching {
                    val peer = chatPeer(chatId, me) ?: return@runCatching
                    if (peer.uid == CLAUDE_BOT_UID) return@runCatching
                    val tr = app.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
                    val who = me.name.substringBefore(' ').ifBlank { me.username }
                    val line = (if (tr) ChatStrings.Turkish else ChatStrings.English).pinnedBy(who)
                    sendMessage(peer, line, null, null, null, line, extra = mapOf("system" to "pin", "who" to who))
                }
            }
        }
        Unit
    }

    override fun opened(chatId: String): Flow<Map<String, Set<String>>> = callbackFlow {
        val job = launch {
            runCatching {
                listen(root.child("chats").child(chatId).child("opened")).collect { snap ->
                    trySend(snap.children.associate { m -> m.key.orEmpty() to m.children.mapNotNull { it.key }.toSet() })
                }
            }.onFailure { trySend(emptyMap()) }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun markOpened(chatId: String, messageId: String) {
        root.child("chats").child(chatId).child("opened").child(messageId).child(me()).setValue(ServerValue.TIMESTAMP)
    }

    override suspend fun sendMentioning(other: ChatUser, text: String, replyTo: ChatReply?, mentions: Set<String>): Result<Unit> = runCatching {
        if (mentions.isEmpty()) {
            send(other, text, null, replyTo).getOrThrow()
            return@runCatching
        }
        sendMessage(other, text, null, replyTo, images = null, preview = null, extra = mapOf("mentions" to mentions.associateWith { true }))
    }

    override fun statusLikes(id: String): Flow<Set<String>> = callbackFlow {
        val job = launch {
            runCatching { listen(root.child("statusLikes").child(me()).child(id)).collect { snap -> trySend(snap.children.mapNotNull { it.key }.toSet()) } }
                .onFailure { trySend(emptySet()) }
        }
        awaitClose { job.cancel() }
    }

    override fun likedStatus(item: StatusItem): Flow<Boolean> = callbackFlow {
        val job = launch {
            runCatching { listen(root.child("statusLikes").child(item.owner.uid).child(item.id).child(me())).collect { trySend(it.exists()) } }
                .onFailure { trySend(false) }
        }
        awaitClose { job.cancel() }
    }

    override fun wallpaper(): Flow<Int> = callbackFlow {
        val job = launch {
            runCatching { listen(root.child("chatPrefs").child(me()).child("wallpaper")).collect { trySend(it.getValue(Int::class.java) ?: 0) } }
                .onFailure { trySend(0) }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun setWallpaper(index: Int) {
        root.child("chatPrefs").child(me()).child("wallpaper").setValue(index.takeIf { it > 0 })
    }

    override suspend fun likeStatus(item: StatusItem, liked: Boolean) {
        root.child("statusLikes").child(item.owner.uid).child(item.id).child(me()).setValue(if (liked) ServerValue.TIMESTAMP else null)
    }

    override suspend fun refreshMyCopies() {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: return
        val updates = HashMap<String, Any?>()
        // Groups: everyone in them sees the public photo.
        runCatching {
            root.child("userChats").child(me.uid).get().await().children.forEach { c ->
                val chatId = c.key ?: return@forEach
                if (isGroupId(chatId)) {
                    updates["groups/$chatId/members/${me.uid}/photo"] = me.publicPhoto()
                    updates["groups/$chatId/members/${me.uid}/name"] = me.name
                } else {
                    val other = c.child("other").getValue(String::class.java) ?: return@forEach
                    updates["userChats/$other/$chatId/photo"] = me.publicPhoto()
                    updates["userChats/$other/$chatId/name"] = me.name
                    updates["userChats/$other/$chatId/username"] = me.username
                }
            }
        }
        // Friends: their card of the person shows the photo unless it is for nobody.
        runCatching {
            root.child("friends").child(me.uid).get().await().children.forEach { f ->
                val uid = f.key ?: return@forEach
                updates["friends/$uid/${me.uid}/photo"] = me.friendPhoto()
                updates["friends/$uid/${me.uid}/name"] = me.name
                updates["friends/$uid/${me.uid}/username"] = me.username
                // A friend's chat with the person shows the friend version too.
                updates["userChats/$uid/${chatIdOf(me.uid, uid)}/photo"] = me.friendPhoto()
                updates["userChats/$uid/${chatIdOf(me.uid, uid)}/name"] = me.name
            }
        }
        if (updates.isEmpty()) return
        // One by one: a line the rules refuse (a conversation that is gone) does not stop the others.
        updates.forEach { (path, value) -> runCatching { root.child(path).setValue(value) } }
    }

    override suspend fun setAdmin(groupId: String, member: ChatUser, admin: Boolean): Result<Unit> = runCatching {
        root.child("groups").child(groupId).child("members").child(member.uid).child("role").setValue(if (admin) "admin" else "member").await()
    }

    /** The group as last seen (kept fresh by [group]); read from the server the first time. */
    private suspend fun groupInfo(groupId: String, fresh: Boolean = false): GroupInfo? {
        if (!fresh) groups[groupId]?.let { return it }
        val info = runCatching { root.child("groups").child(groupId).get().await().toGroup() }.getOrNull()
        if (info != null) groups[groupId] = info
        return info ?: groups[groupId]
    }

    override fun group(groupId: String): Flow<GroupInfo?> = callbackFlow {
        val ref = root.child("groups").child(groupId)
        ref.keepSynced(true)
        val job = launch {
            listen(ref).collect { snap ->
                val info = snap.toGroup()
                if (info != null) groups[groupId] = info else groups.remove(groupId)
                trySend(info)
            }
        }
        awaitClose { job.cancel() }
    }

    private fun ChatUser.asMember(role: String) = mapOf("name" to name, "username" to username, "photo" to photo, "role" to role)

    /** A line about the group itself, sent like a message (who did what to whom). */
    private suspend fun systemMessage(group: ChatUser, code: String, whom: String = "") {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: return
        val tr = app.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
        val who = me.name.substringBefore(' ')
        val preview = when (code) {
            "created" -> if (tr) "$who grubu oluşturdu" else "$who created the group"
            "added" -> if (tr) "$who, $whom kişisini ekledi" else "$who added $whom"
            "removed" -> if (tr) "$who, $whom kişisini çıkardı" else "$who removed $whom"
            "left" -> if (tr) "$who gruptan ayrıldı" else "$who left"
            "joined" -> if (tr) "$who davet bağlantısıyla katıldı" else "$who joined via the invite link"
            "accepted" -> if (tr) "$who daveti kabul etti" else "$who accepted the invitation"
            "renamed" -> if (tr) "$who grup adını \"$whom\" yaptı" else "$who renamed the group to \"$whom\""
            else -> ""
        }
        runCatching { sendMessage(group, preview, null, null, null, preview, extra = mapOf("system" to code, "who" to who, "whom" to whom)) }
    }

    override suspend fun createGroup(name: String, members: List<ChatUser>): Result<ChatUser> = runCatching {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        val clean = name.trim().take(50)
        require(clean.isNotEmpty()) { "name" }
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val random = java.security.SecureRandom()
        val id = GROUP_PREFIX + (1..20).map { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
        val memberMap = HashMap<String, Any?>()
        memberMap[me.uid] = me.copy(photo = me.publicPhoto()).asMember("admin")
        members.filter { it.uid != me.uid && !it.isGroup && it.uid != CLAUDE_BOT_UID }.forEach { memberMap[it.uid] = it.asMember("member") }
        root.child("groups").child(id).setValue(
            mapOf("name" to clean, "createdBy" to me.uid, "createdAt" to ServerValue.TIMESTAMP, "members" to memberMap),
        ).await()
        val group = ChatUser(id, clean, "", null)
        groupInfo(id, fresh = true)
        systemMessage(group, "created")
        group
    }

    override suspend fun addMembers(groupId: String, members: List<ChatUser>): Result<Unit> = runCatching {
        val current = groupInfo(groupId, fresh = true) ?: error("no group")
        val fresh = members.filter { m -> current.members.none { it.user.uid == m.uid } && !m.isGroup && m.uid != CLAUDE_BOT_UID }
        if (fresh.isEmpty()) return@runCatching
        val before = groupInfo(groupId, fresh = true)
        val marks = HashMap<String, Any?>()
        fresh.forEach { marks["groups/$groupId/members/${it.uid}"] = it.asMember("member") + (if (before?.secret == true) mapOf("invited" to true) else emptyMap()) }
        // A secret group: the newcomers have it in their secret space, as an invitation.
        if (before?.secret == true) fresh.forEach { p ->
            marks["userChats/${p.uid}/$groupId/secret"] = true
            marks["userChats/${p.uid}/$groupId/invite"] = true
        }
        root.updateChildren(marks).await()
        runCatching { rekey(groupId, fresh.map { it.uid }) }
        val info = groupInfo(groupId, fresh = true) ?: return@runCatching
        systemMessage(ChatUser(groupId, info.name, "", info.photo), "added", fresh.joinToString(", ") { it.name.substringBefore(' ') })
    }

    override suspend fun removeMember(groupId: String, member: ChatUser): Result<Unit> = runCatching {
        root.updateChildren(mapOf("groups/$groupId/members/${member.uid}" to null, "userChats/${member.uid}/$groupId" to null)).await()
        runCatching { rekey(groupId) }
        val info = groupInfo(groupId, fresh = true) ?: return@runCatching
        systemMessage(ChatUser(groupId, info.name, "", info.photo), "removed", member.name.substringBefore(' '))
    }

    override suspend fun leaveGroup(groupId: String): Result<Unit> = runCatching {
        val uid = me()
        val info = groupInfo(groupId, fresh = true)
        if (info != null) systemMessage(ChatUser(groupId, info.name, "", info.photo), "left")
        root.updateChildren(mapOf("groups/$groupId/members/$uid" to null, "userChats/$uid/$groupId" to null)).await()
        groups.remove(groupId)
    }

    override suspend fun renameGroup(groupId: String, name: String): Result<Unit> = runCatching {
        val clean = name.trim().take(50)
        require(clean.isNotEmpty()) { "name" }
        root.child("groups").child(groupId).child("name").setValue(clean).await()
        val info = groupInfo(groupId, fresh = true) ?: return@runCatching
        systemMessage(ChatUser(groupId, info.name, "", info.photo), "renamed", clean)
    }

    // ------------------------------------------------------------------ together

    override fun together(chatId: String): Flow<TogetherState?> = callbackFlow {
        val job = launch {
            listen(root.child("together").child(chatId)).collect { snap ->
                val kind = runCatching { ShareKind.valueOf(snap.child("kind").getValue(String::class.java).orEmpty()) }.getOrNull()
                val id = snap.child("id").getValue(String::class.java)
                trySend(
                    if (kind == null || id == null) null else TogetherState(
                        chatId = chatId,
                        item = SharedItem(
                            kind = kind,
                            id = id,
                            title = snap.child("title").getValue(String::class.java).orEmpty(),
                            subtitle = snap.child("subtitle").getValue(String::class.java).orEmpty(),
                            thumbnail = snap.child("thumbnail").getValue(String::class.java),
                        ),
                        playing = snap.child("playing").getValue(Boolean::class.java) == true,
                        positionMs = snap.child("pos").getValue(Long::class.java) ?: 0L,
                        at = snap.child("at").getValue(Long::class.java) ?: 0L,
                        by = snap.child("by").getValue(String::class.java).orEmpty(),
                        byName = snap.child("byName").getValue(String::class.java).orEmpty(),
                    ),
                )
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun sendTogetherInvite(other: ChatUser, item: SharedItem, text: String): Result<Unit> = runCatching {
        sendMessage(other, text, item, null, null, (if (item.kind == ShareKind.SONG) "🎧 " else "🍿 ") + text, extra = mapOf("together" to true))
    }

    override suspend fun setTogether(chatId: String, state: TogetherState?) {
        val ref = root.child("together").child(chatId)
        if (state == null) {
            ref.removeValue()
            return
        }
        val me = (_account.value as? ChatAccount.Ready)?.me ?: return
        ref.setValue(
            mapOf(
                "kind" to state.item.kind.name,
                "id" to state.item.id,
                "title" to state.item.title,
                "subtitle" to state.item.subtitle,
                "thumbnail" to state.item.thumbnail,
                "playing" to state.playing,
                "pos" to state.positionMs,
                "at" to ServerValue.TIMESTAMP,
                "by" to me.uid,
                "byName" to me.name.substringBefore(' '),
            ),
        )
    }

    private val _serverOffset = MutableStateFlow(0L)
    override val serverOffset: StateFlow<Long> = _serverOffset.asStateFlow()

    // ------------------------------------------------------------------ friends
    //
    //   friendRequests/{to}/{from} = {name, username, photo, at}   (what {to} sees)
    //   sentRequests/{from}/{to}   = true                          (what {from} sees)
    //   friends/{uid}/{other}      = {name, username, photo, since}

    // ------------------------------------------------------------------ people as they are now
    //
    // The lists keep copies of a person's name and photo made when something happened (a message, a call, a
    // friendship). A name or photo changed since would show old there (and Nuvelo's own messages came without a name,
    // as "?"). So the people in the lists are followed where they are now (users/{uid}; a photo shown to friends only,
    // from privateProfile), the lists show that, and the person's own copies are put right.

    private class LiveProfile(val name: String, val username: String, val photo: String?, val photoHidden: Boolean)

    private val liveProfiles = MutableStateFlow<Map<String, LiveProfile>>(emptyMap())
    private val profileWatch = HashMap<String, Pair<DatabaseReference, ValueEventListener>>()

    private fun watchProfiles(uids: Collection<String>) {
        synchronized(profileWatch) {
            uids.forEach { uid ->
                if (uid.isBlank() || uid in profileWatch || uid == CLAUDE_BOT_UID || isGroupId(uid) || isCallRoom(uid)) return@forEach
                val ref = root.child("users").child(uid)
                val listener = object : ValueEventListener {
                    override fun onDataChange(snap: DataSnapshot) {
                        // An account that is gone keeps what the lists have.
                        val name = snap.child("name").getValue(String::class.java) ?: return
                        val username = snap.child("username").getValue(String::class.java).orEmpty()
                        val photo = snap.child("photo").getValue(String::class.java)
                        val shownTo = snap.child("privacy").child("photo").getValue(String::class.java)
                        fun publish(p: String?) = liveProfiles.update { it + (uid to LiveProfile(name, username, p, photoHidden = p == null && shownTo == "NOBODY")) }
                        if (photo == null && shownTo == "FRIENDS") {
                            // Friends only: readable when this person is one (the rules refuse otherwise).
                            scope.launch { publish(runCatching { root.child("privateProfile").child(uid).child("photo").get().await().getValue(String::class.java) }.getOrNull()) }
                        } else {
                            publish(photo)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {}
                }
                ref.addValueEventListener(listener)
                profileWatch[uid] = ref to listener
            }
        }
    }

    private fun forgetProfiles() {
        synchronized(profileWatch) {
            profileWatch.values.forEach { (ref, listener) -> ref.removeEventListener(listener) }
            profileWatch.clear()
        }
        liveProfiles.value = emptyMap()
    }

    /** This person as they are now: their own name and photo (a photo they hide shows none). */
    private fun ChatUser.fresh(live: Map<String, LiveProfile>): ChatUser {
        val now = live[uid] ?: return this
        return copy(
            name = now.name,
            username = now.username.ifEmpty { username },
            photo = now.photo ?: if (now.photoHidden) null else photo,
        )
    }

    /** The person's own conversation list gets the names and photos right where they are old (or missing). */
    private fun healThreadCopies(mine: String, threads: List<ChatThread>, live: Map<String, LiveProfile>) {
        val fix = HashMap<String, Any?>()
        threads.forEach { t ->
            if (t.isGroup || live[t.other.uid] == null) return@forEach
            val now = t.other.fresh(live)
            if (now.name != t.other.name) fix["userChats/$mine/${t.id}/name"] = now.name
            if (now.username != t.other.username) fix["userChats/$mine/${t.id}/username"] = now.username
            if (now.photo != t.other.photo) fix["userChats/$mine/${t.id}/photo"] = now.photo
        }
        if (fix.isNotEmpty()) runCatching { root.updateChildren(fix) }
    }

    private fun DataSnapshot.toPerson(): ChatUser? {
        val uid = key ?: return null
        return ChatUser(
            uid = uid,
            name = child("name").getValue(String::class.java) ?: return null,
            username = child("username").getValue(String::class.java).orEmpty(),
            photo = child("photo").getValue(String::class.java),
        )
    }

    private fun ChatUser.card(time: String) = mapOf("name" to name, "username" to username, "photo" to photo, time to ServerValue.TIMESTAMP)

    override fun friends(): Flow<List<ChatUser>> = callbackFlow {
        val mine = me()
        val job = launch {
            combine(listen(root.child("friends").child(mine)), liveProfiles) { snap, live -> snap to live }.collect { (snap, live) ->
                val list = snap.children.mapNotNull { it.toPerson() }
                watchProfiles(list.map { it.uid })
                // The person's own card of each friend is put right when it shows an old name or photo.
                val fix = HashMap<String, Any?>()
                list.forEach { f ->
                    val now = f.fresh(live)
                    if (now.name != f.name) fix["friends/$mine/${f.uid}/name"] = now.name
                    if (now.username != f.username) fix["friends/$mine/${f.uid}/username"] = now.username
                    if (now.photo != f.photo) fix["friends/$mine/${f.uid}/photo"] = now.photo
                }
                if (fix.isNotEmpty()) runCatching { root.updateChildren(fix) }
                trySend(list.map { it.fresh(live) }.sortedBy { it.name.lowercase() })
            }
        }
        awaitClose { job.cancel() }
    }

    override fun friendRequests(): Flow<List<ChatUser>> = callbackFlow {
        val job = launch {
            listen(root.child("friendRequests").child(me())).collect { snap ->
                trySend(snap.children.sortedByDescending { it.child("at").getValue(Long::class.java) ?: 0L }.mapNotNull { it.toPerson() })
            }
        }
        awaitClose { job.cancel() }
    }

    override fun sentRequests(): Flow<Set<String>> = callbackFlow {
        val job = launch {
            listen(root.child("sentRequests").child(me())).collect { snap -> trySend(snap.children.mapNotNull { it.key }.toSet()) }
        }
        awaitClose { job.cancel() }
    }

    private fun friendNotify(from: String, to: String, action: String) =
        "notify/${root.child("notify").push().key}" to mapOf(
            "from" to from, "to" to to, "chatId" to chatIdOf(from, to), "type" to "friend", "action" to action, "at" to ServerValue.TIMESTAMP,
        )

    override suspend fun requestFriend(user: ChatUser): Result<Unit> = runCatching {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        // They asked first: this is a yes.
        if (root.child("friendRequests").child(me.uid).child(user.uid).get().await().exists()) {
            acceptFriend(user).getOrThrow()
            return@runCatching
        }
        root.updateChildren(
            mapOf(
                "friendRequests/${user.uid}/${me.uid}" to me.copy(photo = me.friendPhoto()).card("at"),
                "sentRequests/${me.uid}/${user.uid}" to true,
                friendNotify(me.uid, user.uid, "request"),
            ),
        ).await()
    }

    override suspend fun acceptFriend(user: ChatUser): Result<Unit> = runCatching {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        root.updateChildren(
            mapOf(
                "friends/${me.uid}/${user.uid}" to user.card("since"),
                "friends/${user.uid}/${me.uid}" to me.copy(photo = me.friendPhoto()).card("since"),
                "friendRequests/${me.uid}/${user.uid}" to null,
                "sentRequests/${user.uid}/${me.uid}" to null,
                friendNotify(me.uid, user.uid, "accepted"),
            ),
        ).await()
    }

    override suspend fun unfriend(user: ChatUser): Result<Unit> = runCatching {
        val uid = me()
        root.updateChildren(
            mapOf(
                "friendRequests/$uid/${user.uid}" to null,
                "sentRequests/${user.uid}/$uid" to null,
                "friendRequests/${user.uid}/$uid" to null,
                "sentRequests/$uid/${user.uid}" to null,
                "friends/$uid/${user.uid}" to null,
                "friends/${user.uid}/$uid" to null,
            ),
        ).await()
    }

    override suspend fun user(uid: String): ChatUser? {
        val snap = runCatching { root.child("users").child(uid).get().await() }.getOrNull() ?: return null
        var photo = snap.child("photo").getValue(String::class.java)
        var about = snap.child("about").getValue(String::class.java)
        // Shown to friends only: readable from privateProfile when the person is one (the rules say no otherwise).
        if (photo == null) photo = runCatching { root.child("privateProfile").child(uid).child("photo").get().await().getValue(String::class.java) }.getOrNull()
        if (about == null) about = runCatching { root.child("privateProfile").child(uid).child("about").get().await().getValue(String::class.java) }.getOrNull()
        return ChatUser(
            uid = uid,
            name = snap.child("name").getValue(String::class.java) ?: return null,
            username = snap.child("username").getValue(String::class.java).orEmpty(),
            photo = photo,
            about = about.orEmpty(),
        )
    }

    // ------------------------------------------------------------------ the owner's Claude

    override fun claudeState(): Flow<ClaudeState?> = callbackFlow {
        val uid = me()
        val job = launch {
            listen(root.child("claudeState").child(uid)).collect { snap ->
                val model = snap.child("model").getValue(String::class.java)
                trySend(model?.let { ClaudeState(it, snap.child("label").getValue(String::class.java) ?: "Claude") })
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun setClaudeModel(model: String) {
        root.child("claudeSettings").child(me()).child("model").setValue(model)
    }

    override suspend fun resetClaude() {
        root.child("claudeSettings").child(me()).child("reset").setValue(ServerValue.TIMESTAMP)
    }

    /** Photos already fetched: memory first, then the app's cache folder. */
    private val imageCache = android.util.LruCache<String, ByteArray>(24)

    private val mediaPrefs by lazy { app.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE) }
    private val _autoDownload by lazy { MutableStateFlow(mediaPrefs.getString("media_auto_download", "always") ?: "always") }
    override val autoDownload: StateFlow<String> get() = _autoDownload

    override fun setAutoDownload(mode: String) {
        if (mode !in setOf("always", "wifi", "never")) return
        mediaPrefs.edit().putString("media_auto_download", mode).apply()
        _autoDownload.value = mode
    }

    override fun mayAutoDownload(): Boolean = when (_autoDownload.value) {
        "never" -> false
        // Wi-Fi (or any connection that is not counted, like a home network over a cable).
        "wifi" -> runCatching { !(app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered }.getOrDefault(true)
        else -> true
    }

    /** Photos asked for by a tap: they load whatever the setting says. */
    private val forcedImages = java.util.Collections.synchronizedSet(HashSet<String>())

    override fun forceImage(id: String) {
        forcedImages += id
    }

    private fun mediaDirs() = listOf("chat_media", "voice", "video_out", "exports").map { java.io.File(app.cacheDir, it) }

    override suspend fun mediaCacheBytes(): Long = kotlinx.coroutines.withContext(Dispatchers.IO) {
        mediaDirs().sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }

    override suspend fun clearMediaCache() {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            mediaDirs().forEach { dir -> dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } } }
        }
        imageCache.evictAll()
    }

    override suspend fun imageBytes(chatId: String, image: ChatImage): ByteArray? {
        val url = image.url ?: return imageBytes(chatId, image.id)
        imageCache.get(url)?.let { return it }
        // Our own photo, still going up or just sent: the phone's copy.
        imageCache.get("$chatId/${image.id}")?.let { return it }
        val file = java.io.File(java.io.File(app.cacheDir, "chat_media").apply { mkdirs() }, url.substringAfterLast('/'))
        if (file.exists()) return file.readBytes().also { imageCache.put(url, it) }
        // Not on the phone yet: from the network only when the setting allows it now, or it was tapped.
        if (!mayAutoDownload() && image.id !in forcedImages) return null
        val bytes = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val got = MediaUpload.download(url) ?: return@withContext null
            if (image.key.isEmpty()) {
                got
            } else {
                runCatching { java.io.ByteArrayOutputStream().also { o -> E2eStream.open(E2eCrypto.unb64(image.key), got.inputStream(), o) }.toByteArray() }.getOrNull()
            }
        } ?: return null
        runCatching { file.writeBytes(bytes) }
        imageCache.put(url, bytes)
        return bytes
    }

    override suspend fun imageBytes(chatId: String, id: String): ByteArray? {
        val key = "$chatId/$id"
        imageCache.get(key)?.let { return it }
        val file = java.io.File(java.io.File(app.cacheDir, "chat_media").apply { mkdirs() }, key.replace('/', '_') + ".jpg")
        if (file.exists()) return file.readBytes().also { imageCache.put(key, it) }
        // Older photos were copied to the media server under their id.
        kotlinx.coroutines.withContext(Dispatchers.IO) { MediaUpload.download("${MediaUpload.BASE}/m/$chatId/$id.jpg") }?.let { bytes ->
            runCatching { file.writeBytes(bytes) }
            imageCache.put(key, bytes)
            return bytes
        }
        val snap = runCatching { root.child("media").child(chatId).child(id).get().await() }.getOrNull() ?: return null
        val data = snap.child("data").getValue(String::class.java) ?: return null
        val bytes = runCatching { android.util.Base64.decode(data, android.util.Base64.DEFAULT) }.getOrNull() ?: return null
        runCatching { file.writeBytes(bytes) }
        imageCache.put(key, bytes)
        return bytes
    }

    override suspend fun markRead(chatId: String) {
        val uid = auth.currentUser?.uid ?: return
        val updates = hashMapOf<String, Any?>(
            "userChats/$uid/$chatId/read" to ServerValue.TIMESTAMP,
            "userChats/$uid/$chatId/unread" to 0,
            "userChats/$uid/$chatId/mention" to null,
        )
        // Who hides their read marks only marks it for themselves.
        if (!_privacy.value.hideReadReceipts) updates["chats/$chatId/read/$uid"] = ServerValue.TIMESTAMP
        root.updateChildren(updates)
    }

    override suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit> = runCatching {
        root.child("chats").child(chatId).child("messages").child(messageId).removeValue()
        Unit
    }

    // ------------------------------------------------------------------ online, typing, reactions

    private var presenceListener: ValueEventListener? = null

    /**
     * Online while the app is connected: the database itself marks the person offline (with the time) when the
     * connection drops, even if the phone dies or the app is killed.
     */
    @Volatile
    private var presenceConnected = false
    private var presenceWasVisible = false
    private var presenceVisibility: kotlinx.coroutines.Job? = null

    private fun trackPresence(uid: String?) {
        val connected = db.getReference(".info/connected")
        presenceListener?.let { connected.removeEventListener(it) }
        presenceListener = null
        presenceVisibility?.cancel()
        presenceVisibility = null
        if (uid == null) return
        val ref = root.child("presence").child(uid)
        // "Online" only while the messages are on screen: in the background (music playing, a call service), in the
        // videos or the music, or with a call ringing over them, the person is not in their messages, so it says "last
        // seen" from the moment they left them.
        fun write() {
            if (!presenceConnected) return
            if (_privacy.value.hideLastSeen) {
                // Hidden: others see neither "online" nor a time.
                ref.onDisconnect().setValue(mapOf("hidden" to true))
                ref.setValue(mapOf("hidden" to true))
                return
            }
            val visible = CallHooks.appVisible && ChatPresence.inChats.value
            if (visible) {
                ref.onDisconnect().setValue(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP))
                ref.setValue(mapOf("online" to true, "lastSeen" to ServerValue.TIMESTAMP))
            } else if (presenceWasVisible) {
                // Just left the screen: that moment is the "last seen".
                ref.onDisconnect().updateChildren(mapOf("online" to false))
                ref.setValue(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP))
            } else {
                // Woken in the background (a push, a call service): the person was not here, so the time stays.
                ref.onDisconnect().updateChildren(mapOf("online" to false))
                ref.updateChildren(mapOf("online" to false))
            }
            presenceWasVisible = visible
        }
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                presenceConnected = snap.getValue(Boolean::class.java) == true
                write()
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        connected.addValueEventListener(listener)
        presenceListener = listener
        presenceVisibility = scope.launch {
            combine(CallHooks.onScreen, ChatPresence.inChats) { shown, inChats -> shown && inChats }
                .distinctUntilChanged()
                .collect { write() }
        }
        // Privacy changed while connected: written again at once.
        if (_privacy.value.hideLastSeen) ref.setValue(mapOf("hidden" to true))
    }

    override fun presence(uid: String): Flow<Presence?> = combine(presenceOf(uid), _privacy) { p, mine -> if (mine.hideLastSeen) null else p }

    private fun presenceOf(uid: String): Flow<Presence?> = callbackFlow {
        val job = launch {
            listen(root.child("presence").child(uid)).collect { snap ->
                trySend(
                    if (!snap.exists() || snap.child("hidden").getValue(Boolean::class.java) == true) null else Presence(
                        online = snap.child("online").getValue(Boolean::class.java) == true,
                        lastSeen = snap.child("lastSeen").getValue(Long::class.java) ?: 0L,
                    ),
                )
            }
        }
        awaitClose { job.cancel() }
    }

    override fun assist(chatId: String): Flow<ClaudeAssist?> = callbackFlow {
        val job = launch {
            listen(root.child("assist").child(chatId)).collect { snap ->
                val target = snap.child("target").getValue(String::class.java)
                val phase = snap.child("phase").getValue(String::class.java)
                trySend(
                    if (target != null && phase != null) {
                        ClaudeAssist(
                            target,
                            phase,
                            detail = snap.child("detail").getValue(String::class.java).orEmpty(),
                            thought = snap.child("thought").getValue(String::class.java).orEmpty(),
                        )
                    } else {
                        null
                    },
                )
            }
        }
        awaitClose { job.cancel() }
    }

    override fun typing(chatId: String, otherUid: String): Flow<Boolean> = if (isGroupId(chatId)) groupTyping(chatId) else callbackFlow {
        val job = launch {
            listen(root.child("typing").child(chatId).child(otherUid)).collect { snap ->
                // A mark older than a few seconds is from a phone that went away mid-word.
                val at = snap.getValue(Long::class.java)
                trySend(at != null && System.currentTimeMillis() - at < TYPING_FRESH_MS)
            }
        }
        awaitClose { job.cancel() }
    }

    /** Someone other than the person typing in a group. */
    private fun groupTyping(chatId: String): Flow<Boolean> = callbackFlow {
        val uid = me()
        val job = launch {
            listen(root.child("typing").child(chatId)).collect { snap ->
                val now = System.currentTimeMillis()
                trySend(snap.children.any { it.key != uid && now - (it.getValue(Long::class.java) ?: 0L) < TYPING_FRESH_MS })
            }
        }
        awaitClose { job.cancel() }
    }

    override fun recording(chatId: String, otherUid: String): Flow<Boolean> = callbackFlow {
        val uid = me()
        val job = launch {
            listen(root.child("recording").child(chatId)).collect { snap ->
                val now = System.currentTimeMillis()
                // One-to-one: the other person; in a group: anyone but the person.
                trySend(
                    snap.children.any { c ->
                        val who = c.key ?: return@any false
                        (if (isGroupId(chatId)) who != uid else who == otherUid) && now - (c.getValue(Long::class.java) ?: 0L) < TYPING_FRESH_MS
                    },
                )
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun setRecording(chatId: String, recording: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val name = (_account.value as? ChatAccount.Ready)?.me?.name?.substringBefore(' ').orEmpty()
        val ref = root.child("recording").child(chatId).child(uid)
        if (recording) {
            ref.onDisconnect().removeValue()
            ref.setValue(ServerValue.TIMESTAMP)
        } else {
            ref.removeValue()
        }
        // The others' lists say "recording a voice message…" in place of "typing…".
        val others = if (isGroupId(chatId)) groups[chatId]?.members?.map { it.user.uid }.orEmpty() else chatId.split("_")
        val updates = HashMap<String, Any?>()
        others.filter { it != uid && it != CLAUDE_BOT_UID }.forEach { other ->
            updates["userChats/$other/$chatId/typingAt"] = if (recording) ServerValue.TIMESTAMP else null
            updates["userChats/$other/$chatId/typingName"] = if (recording) name else null
            updates["userChats/$other/$chatId/typingRec"] = if (recording) true else null
        }
        if (updates.isNotEmpty()) root.updateChildren(updates)
    }

    override suspend fun setTyping(chatId: String, typing: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val name = (_account.value as? ChatAccount.Ready)?.me?.name?.substringBefore(' ').orEmpty()
        val ref = root.child("typing").child(chatId).child(uid)
        if (typing) {
            ref.onDisconnect().removeValue()
            ref.setValue(ServerValue.TIMESTAMP)
        } else {
            ref.removeValue()
        }
        // The others' conversation lists say "typing…" too.
        val others = if (isGroupId(chatId)) groups[chatId]?.members?.map { it.user.uid }.orEmpty() else chatId.split("_")
        val updates = HashMap<String, Any?>()
        others.filter { it != uid && it != CLAUDE_BOT_UID }.forEach { other ->
            updates["userChats/$other/$chatId/typingAt"] = if (typing) ServerValue.TIMESTAMP else null
            updates["userChats/$other/$chatId/typingName"] = if (typing) name else null
            updates["userChats/$other/$chatId/typingRec"] = null
        }
        if (updates.isNotEmpty()) root.updateChildren(updates)
    }

    override fun reactions(chatId: String): Flow<Map<String, Map<String, String>>> = callbackFlow {
        val job = launch {
            listen(root.child("chats").child(chatId).child("reactions")).collect { snap ->
                trySend(
                    snap.children.associate { message ->
                        message.key.orEmpty() to message.children.mapNotNull { r -> r.getValue(String::class.java)?.let { (r.key ?: "") to it } }.toMap()
                    },
                )
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun react(chatId: String, messageId: String, emoji: String?, author: String?, preview: String) {
        val uid = auth.currentUser?.uid ?: return
        val ref = root.child("chats").child(chatId).child("reactions").child(messageId).child(uid)
        if (emoji == null) {
            ref.removeValue()
            return
        }
        val updates = hashMapOf<String, Any?>("chats/$chatId/reactions/$messageId/$uid" to emoji)
        // The message's writer hears about it (not for our own messages, nor Claude's).
        if (author != null && author != uid && author != CLAUDE_BOT_UID) {
            updates["notify/${root.child("notify").push().key}"] = mapOf(
                "from" to uid, "to" to author, "chatId" to chatId, "type" to "reaction",
                "emoji" to emoji,
                "body" to (e2e.version(chatId)?.let { v -> e2e.sealText(chatId, v, preview.take(120)) } ?: preview.take(120)),
                "at" to ServerValue.TIMESTAMP,
            )
        }
        root.updateChildren(updates)
    }

    // ------------------------------------------------------------------ the list of calls
    //
    //   callLog/{uid}/{callId} = {peer, name, username, photo, video, dir: in|out|group, outcome, at, seconds}

    override fun callLog(): Flow<List<CallLogEntry>> = callbackFlow {
        val job = launch {
            combine(listen(root.child("callLog").child(me()).orderByChild("at").limitToLast(200)), liveProfiles) { snap, live -> snap to live }.collect { (snap, live) ->
                // Each call kept the person's name and photo of its day: they show as they are now.
                watchProfiles(snap.children.mapNotNull { c -> c.child("peer").getValue(String::class.java)?.takeIf { c.child("dir").getValue(String::class.java) != "group" } })
                trySend(
                    snap.children.mapNotNull { c ->
                        val peer = c.child("peer").getValue(String::class.java) ?: return@mapNotNull null
                        CallLogEntry(
                            id = c.key ?: return@mapNotNull null,
                            peer = ChatUser(peer, c.child("name").getValue(String::class.java).orEmpty(), c.child("username").getValue(String::class.java).orEmpty(), c.child("photo").getValue(String::class.java))
                                .let { u -> if (c.child("dir").getValue(String::class.java) == "group") u else u.fresh(live) },
                            video = c.child("video").getValue(Boolean::class.java) == true,
                            direction = when (c.child("dir").getValue(String::class.java)) {
                                "out" -> CallDirection.OUT
                                "group" -> CallDirection.GROUP
                                else -> CallDirection.IN
                            },
                            outcome = runCatching { CallOutcome.valueOf(c.child("outcome").getValue(String::class.java).orEmpty()) }.getOrDefault(CallOutcome.MISSED),
                            at = c.child("at").getValue(Long::class.java) ?: 0L,
                            seconds = c.child("seconds").getValue(Long::class.java) ?: 0L,
                        )
                    }.sortedByDescending { it.at },
                )
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun deleteCalls(ids: List<String>) {
        val uid = me()
        if (ids.isEmpty()) return
        root.child("callLog").child(uid).updateChildren(ids.associateWith { null })
    }

    override suspend fun clearCallLog() {
        val uid = me()
        root.child("callLog").child(uid).removeValue()
        root.child("callLog").child(uid).child("_seen").setValue(ServerValue.TIMESTAMP)
    }

    override fun callsSeenAt(): Flow<Long> = callbackFlow {
        val job = launch {
            listen(root.child("callLog").child(me()).child("_seen")).collect { trySend(it.getValue(Long::class.java) ?: 0L) }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun setCallsSeen() {
        root.child("callLog").child(me()).child("_seen").setValue(ServerValue.TIMESTAMP)
    }

    override suspend fun setFavorite(chatId: String, favorite: Boolean) {
        root.child("userChats").child(me()).child(chatId).child("favorite").setValue(if (favorite) true else null)
    }

    // ------------------------------------------------------------------ status
    //
    //   statuses/{uid}/{id}            {kind: PHOTO|VIDEO|TEXT, url, text, bg, at, expireAt, thumb, w, h, dur}
    //   statusViews/{owner}/{id}/{uid} when they saw it (only the owner reads it)
    //   statusSeen/{uid}/{owner}/{id}  what the person saw (their own, to know what is new)
    //   statusPrivacy/{uid}            {mode: FRIENDS|ONLY|EXCEPT, people: {uid: true}}
    // A status is for friends (as the privacy says); the server removes it, and its file, after 24 hours.

    private fun DataSnapshot.toStatus(owner: ChatUser, now: Long): StatusItem? {
        val id = key ?: return null
        val expireAt = child("expireAt").getValue(Long::class.java) ?: return null
        if (expireAt <= now) return null
        return StatusItem(
            id = id,
            owner = owner,
            kind = runCatching { StatusKind.valueOf(child("kind").getValue(String::class.java).orEmpty()) }.getOrDefault(StatusKind.TEXT),
            url = child("url").getValue(String::class.java).orEmpty(),
            text = child("text").getValue(String::class.java).orEmpty(),
            background = child("bg").getValue(Long::class.java) ?: 0xFF1E1E1E,
            at = child("at").getValue(Long::class.java) ?: 0L,
            thumb = child("thumb").getValue(String::class.java).orEmpty(),
            width = child("w").getValue(Int::class.java) ?: 0,
            height = child("h").getValue(Int::class.java) ?: 0,
            durationMs = child("dur").getValue(Long::class.java) ?: 0L,
            font = child("font").getValue(Int::class.java) ?: 0,
            overlay = child("ov").takeIf { it.exists() }?.let { o ->
                StatusOverlay(
                    text = o.child("t").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: return@let null,
                    x = (o.child("x").getValue(Double::class.java) ?: 0.5).toFloat(),
                    y = (o.child("y").getValue(Double::class.java) ?: 0.5).toFloat(),
                    color = o.child("c").getValue(Long::class.java) ?: 0xFFFFFFFF,
                )
            },
            song = child("song").takeIf { it.exists() }?.let { g ->
                SharedItem(
                    kind = ShareKind.SONG,
                    id = g.child("id").getValue(String::class.java) ?: return@let null,
                    title = g.child("title").getValue(String::class.java).orEmpty(),
                    subtitle = g.child("sub").getValue(String::class.java).orEmpty(),
                    thumbnail = g.child("thumb").getValue(String::class.java),
                    startMs = (g.child("start").getValue(Long::class.java) ?: 0L).coerceIn(0L, 3 * 3600_000L),
                )
            },
        )
    }

    private fun statusesOf(owner: ChatUser): Flow<List<StatusItem>> = callbackFlow {
        val job = launch {
            runCatching {
                listen(root.child("statuses").child(owner.uid)).collect { snap ->
                    val now = System.currentTimeMillis() + _serverOffset.value
                    trySend(snap.children.mapNotNull { it.toStatus(owner, now) }.sortedBy { it.at })
                }
            }.onFailure { trySend(emptyList()) }
        }
        awaitClose { job.cancel() }
    }

    override fun myStatuses(): Flow<List<StatusItem>> {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return statusesOf(me)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun friendStatuses(): Flow<List<StatusGroup>> {
        val uid = auth.currentUser?.uid ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        val seen = callbackFlow {
            val job = launch {
                listen(root.child("statusSeen").child(uid)).collect { snap ->
                    trySend(snap.children.associate { o -> o.key.orEmpty() to o.children.mapNotNull { it.key }.toSet() })
                }
            }
            awaitClose { job.cancel() }
        }
        return friends().flatMapLatest { people ->
            if (people.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                combine(people.map { statusesOf(it) }) { lists -> lists.toList() }.combine(seen) { lists, seenBy ->
                    lists.filter { it.isNotEmpty() }.map { items ->
                        val ids = seenBy[items.first().owner.uid].orEmpty()
                        StatusGroup(items.first().owner, items, items.all { it.id in ids }, ids)
                    }.sortedWith(compareBy<StatusGroup> { it.seenAll }.thenByDescending { it.items.last().at })
                }
            }
        }
    }

    /** The extras of a status as they are kept. */
    private fun StatusExtras.fields(): Map<String, Any?> = buildMap {
        overlay?.takeIf { it.text.isNotBlank() }?.let { o ->
            put("ov", mapOf("t" to o.text.take(200), "x" to o.x.toDouble().coerceIn(0.0, 1.0), "y" to o.y.toDouble().coerceIn(0.0, 1.0), "c" to o.color))
        }
        song?.let { g -> put("song", mapOf("id" to g.id, "title" to g.title.take(120), "sub" to g.subtitle.take(120), "thumb" to g.thumbnail, "start" to g.startMs.takeIf { it > 0 })) }
    }

    private suspend fun postStatus(fields: Map<String, Any?>) {
        val uid = me()
        val id = root.child("statuses").child(uid).push().key ?: error("no id")
        val now = System.currentTimeMillis() + _serverOffset.value
        root.child("statuses").child(uid).child(id).setValue(fields + mapOf("at" to ServerValue.TIMESTAMP, "expireAt" to now + STATUS_LIFE_MS)).await()
    }

    override suspend fun postTextStatus(text: String, background: Long, font: Int, extras: StatusExtras): Result<Unit> = runCatching {
        val body = text.trim().take(700)
        require(body.isNotEmpty()) { "text" }
        postStatus(mapOf("kind" to StatusKind.TEXT.name, "text" to body, "bg" to background, "font" to font) + extras.fields())
    }

    override suspend fun postPhotoStatus(image: PickedImage, caption: String, extras: StatusExtras): Result<Unit> = runCatching {
        val uid = me()
        val url = kotlinx.coroutines.withContext(Dispatchers.IO) {
            MediaUpload.upload(token(), "u_$uid", "jpg", "image/jpeg", image.jpeg.size.toLong(), { image.jpeg.inputStream() }) {}
        }
        imageCache.put(url, image.jpeg)
        postStatus(mapOf("kind" to StatusKind.PHOTO.name, "url" to url, "text" to caption.trim().take(700), "thumb" to image.thumb, "w" to image.width, "h" to image.height) + extras.fields())
    }

    override suspend fun postVideoStatus(file: PickedFile, caption: String, extras: StatusExtras): Result<Unit> = runCatching {
        val uid = me()
        // Made small first, like a video in a message.
        var source = file.location
        var size = file.size
        VideoCompressor.compress(app, file.location, maxMs = if (file.durationMs > STATUS_VIDEO_MS) STATUS_VIDEO_MS else 0L) {}?.let { small ->
            if (small.length() in 1 until size) {
                source = small.absolutePath
                size = small.length()
            }
        }
        require(size <= MediaUpload.MAX_BYTES) { "max 100 MB" }
        val url = kotlinx.coroutines.withContext(Dispatchers.IO) {
            MediaUpload.upload(token(), "u_$uid", "mp4", "video/mp4", size, { openLocation(source) }) {}
        }
        if (source != file.location) runCatching { java.io.File(source).delete() }
        postStatus(
            mapOf(
                "kind" to StatusKind.VIDEO.name, "url" to url, "text" to caption.trim().take(700), "thumb" to file.thumb,
                "w" to file.width, "h" to file.height, "dur" to file.durationMs.coerceAtMost(STATUS_VIDEO_MS),
            ) + extras.fields(),
        )
    }

    override suspend fun deleteStatus(id: String) {
        val uid = me()
        // The status itself first, on its own: it must go even if what comes with it cannot (one write for both
        // was refused as a whole when the views could not be removed, and the status stayed).
        root.child("statuses").child(uid).child(id).removeValue().await()
        runCatching { root.updateChildren(mapOf("statusViews/$uid/$id" to null, "statusLikes/$uid/$id" to null)).await() }
    }

    override suspend fun markStatusSeen(item: StatusItem) {
        val uid = auth.currentUser?.uid ?: return
        if (item.owner.uid == uid) return
        root.updateChildren(
            mapOf(
                "statusSeen/$uid/${item.owner.uid}/${item.id}" to true,
                "statusViews/${item.owner.uid}/${item.id}/$uid" to ServerValue.TIMESTAMP,
            ),
        )
    }

    override fun statusViewers(id: String): Flow<List<Pair<ChatUser, Long>>> = combine(
        callbackFlow {
            val job = launch {
                listen(root.child("statusViews").child(me()).child(id)).collect { snap ->
                    trySend(snap.children.mapNotNull { v -> v.key?.let { it to (v.getValue(Long::class.java) ?: 0L) } })
                }
            }
            awaitClose { job.cancel() }
        },
        friends(),
    ) { views, people ->
        val byId = people.associateBy { it.uid }
        views.map { (uid, at) -> (byId[uid] ?: ChatUser(uid, "?", "")) to at }.sortedByDescending { it.second }
    }

    private val _statusPrivacy = MutableStateFlow(StatusPrivacy())
    override val statusPrivacy: StateFlow<StatusPrivacy> = _statusPrivacy.asStateFlow()
    private var statusPrivacyListener: ValueEventListener? = null
    private var statusPrivacyRef: DatabaseReference? = null

    /** Keeps [statusPrivacy] up to date for the signed-in person. */
    private fun watchStatusPrivacy(uid: String?) {
        statusPrivacyListener?.let { l -> runCatching { statusPrivacyRef?.removeEventListener(l) } }
        statusPrivacyListener = null
        statusPrivacyRef = null
        if (uid == null) return
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                _statusPrivacy.value = StatusPrivacy(
                    audience = runCatching { StatusAudience.valueOf(snap.child("mode").getValue(String::class.java).orEmpty()) }.getOrDefault(StatusAudience.FRIENDS),
                    people = snap.child("people").children.mapNotNull { it.key }.toSet(),
                )
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        val ref = root.child("statusPrivacy").child(uid)
        ref.addValueEventListener(listener)
        statusPrivacyRef = ref
        statusPrivacyListener = listener
    }

    override fun mutedStatuses(): Flow<Set<String>> = callbackFlow {
        val job = launch {
            runCatching { listen(root.child("statusMuted").child(me())).collect { snap -> trySend(snap.children.mapNotNull { it.key }.toSet()) } }
                .onFailure { trySend(emptySet()) }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun setStatusMuted(owner: String, muted: Boolean) {
        root.child("statusMuted").child(me()).child(owner).setValue(if (muted) true else null)
    }

    override suspend fun setLocked(chatId: String, locked: Boolean) {
        root.child("userChats").child(me()).child(chatId).child("locked").setValue(if (locked) true else null)
    }

    // ------------------------------------------------------------------ the secret space
    //
    //   userChats/{uid}/{chatId}/secret   kept in the secret space (for that person)
    //   userChats/{uid}/{chatId}/invite   a secret group's invitation not taken yet
    //   groups/{id}/secret                made in the secret space (every member keeps it there)

    /** Conversations whose members could not all take encryption a moment ago (asked again after a while). */
    private val noKeysUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Conversations started in the secret space whose first message has not gone yet. */
    private val secretPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun markSecretPending(chatId: String) {
        secretPending.add(chatId)
    }

    override suspend fun setSecret(chatId: String, secret: Boolean) {
        val uid = me()
        val ref = root.child("userChats").child(uid).child(chatId)
        val exists = runCatching { ref.child("lastAt").get().await().exists() }.getOrDefault(false)
        if (exists) ref.child("secret").setValue(if (secret) true else null).await() else if (secret) secretPending.add(chatId) else secretPending.remove(chatId)
        // Kept in the secret space: from now on its messages are encrypted (when everyone can take them).
        if (secret) runCatching { encryptIfPossible(chatId) }
    }

    /** Turns encryption on for [chatId] when it is not yet and every member has a key. */
    private suspend fun encryptIfPossible(chatId: String): Boolean {
        if (e2e.versionNow(chatId) != null) return true
        val uid = me()
        val members = if (isGroupId(chatId)) {
            groupInfo(chatId, fresh = true)?.members?.map { it.user.uid } ?: return false
        } else {
            chatId.split("_").takeIf { it.size == 2 } ?: return false
        }
        if (CLAUDE_BOT_UID in members) return false
        if (E2ePolicy.exempt(members)) return false
        if (!e2e.allHaveKeys(members.filter { it != uid })) return false
        return e2e.enable(chatId, members).isSuccess
    }

    override suspend fun enableEncryption(other: ChatUser): Result<Unit> = runCatching {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        val chatId = conversationId(me.uid, other)
        require(other.uid != CLAUDE_BOT_UID) { "claude" }
        check(encryptIfPossible(chatId)) { "e2e-nokeys" }
    }

    override suspend fun createSecretGroup(name: String, members: List<ChatUser>, endsAt: Long): Result<ChatUser> = runCatching {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        val clean = name.trim().take(50)
        require(clean.isNotEmpty()) { "name" }
        val people = members.filter { it.uid != me.uid && !it.isGroup && it.uid != CLAUDE_BOT_UID }
        require(people.isNotEmpty()) { "members" }
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val random = java.security.SecureRandom()
        val id = GROUP_PREFIX + (1..20).map { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
        val memberMap = HashMap<String, Any?>()
        memberMap[me.uid] = me.copy(photo = me.publicPhoto()).asMember("admin")
        // Invited, not in it yet: the others see them apart until they say yes.
        people.forEach { memberMap[it.uid] = it.asMember("member") + ("invited" to true) }
        root.child("groups").child(id).setValue(
            buildMap<String, Any?> {
                put("name", clean)
                put("createdBy", me.uid)
                put("createdAt", ServerValue.TIMESTAMP)
                put("members", memberMap)
                put("secret", true)
                // A group that deletes itself: the server removes it, and all in it, at that time.
                if (endsAt > 0) put("endsAt", endsAt)
            },
        ).await()
        // In everyone's secret space; the others as an invitation until they take it.
        val marks = HashMap<String, Any?>()
        marks["userChats/${me.uid}/$id/secret"] = true
        people.forEach { p ->
            marks["userChats/${p.uid}/$id/secret"] = true
            marks["userChats/${p.uid}/$id/invite"] = true
        }
        root.updateChildren(marks).await()
        val group = ChatUser(id, clean, "", null)
        groupInfo(id, fresh = true)
        // Encrypted from the first line: those who have no key yet get it once they update (the group asks then).
        if (!E2ePolicy.exempt(people.map { it.uid } + me.uid)) runCatching { e2e.enable(id, (people.map { it.uid } + me.uid).filter { e2e.allHaveKeys(listOf(it)) }) }
        systemMessage(group, "created")
        group
    }

    override suspend fun acceptInvite(chatId: String): Result<Unit> = runCatching {
        val uid = me()
        val updates = HashMap<String, Any?>()
        updates["userChats/$uid/$chatId/invite"] = null
        val wasInvited = isGroupId(chatId) && groupInfo(chatId, fresh = true)?.members?.any { it.user.uid == uid && it.invited } == true
        if (wasInvited) updates["groups/$chatId/members/$uid/invited"] = null
        root.updateChildren(updates).await()
        // The others see it: one line in the group.
        if (wasInvited) groupInfo(chatId, fresh = true)?.let { systemMessage(ChatUser(chatId, it.name, "", it.photo), "accepted") }
    }

    override suspend fun declineInvite(chatId: String): Result<Unit> = runCatching {
        val uid = me()
        // Quietly: no line in the group, nobody told.
        root.updateChildren(mapOf("groups/$chatId/members/$uid" to null, "userChats/$uid/$chatId" to null, "chatKeys/$chatId/$uid" to null)).await()
        groups.remove(chatId)
    }

    override suspend fun setShareHistory(groupId: String, share: Boolean): Result<Unit> = runCatching {
        root.child("groups").child(groupId).child("shareHistory").setValue(if (share) true else null).await()
        groupInfo(groupId, fresh = true)
    }

    /** The members changed: an encrypted group gets a new key, so who left reads nothing new (and who came, nothing old). */
    private suspend fun rekey(groupId: String, newcomers: List<String> = emptyList()) {
        if (e2e.versionNow(groupId) == null) return
        val info = groupInfo(groupId, fresh = true) ?: return
        if (E2ePolicy.exempt(info.members.map { it.user.uid })) return
        val members = info.members.map { it.user.uid }.filter { e2e.allHaveKeys(listOf(it)) }
        e2e.enable(groupId, members, if (info.shareHistory) newcomers else emptyList())
    }

    // ------------------------------------------------------------------ group invite links
    //
    //   groups/{id}/invite      the code (admins set it)
    //   groupInvites/{code}     {group, name, photo}: anyone signed in reads it to see what they join
    // Joining writes the person's own member line with "via": the code, which the rules check.

    override suspend fun groupInviteCode(groupId: String, renew: Boolean): Result<String> = runCatching {
        val ref = root.child("groups").child(groupId).child("invite")
        val old = ref.get().await().getValue(String::class.java)
        if (old != null && !renew) return@runCatching old
        val info = groupInfo(groupId, fresh = true) ?: error("no group")
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val random = java.security.SecureRandom()
        val code = (1..22).map { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
        root.child("groupInvites").child(code).setValue(mapOf("group" to groupId, "name" to info.name, "photo" to info.photo)).await()
        ref.setValue(code).await()
        if (old != null) runCatching { root.child("groupInvites").child(old).removeValue().await() }
        code
    }

    override suspend fun groupInvite(code: String): GroupInvite? {
        if (!code.matches(Regex("^[A-Za-z0-9]{10,40}$"))) return null
        val snap = runCatching { root.child("groupInvites").child(code).get().await() }.getOrNull() ?: return null
        val group = snap.child("group").getValue(String::class.java) ?: return null
        return GroupInvite(code, group, snap.child("name").getValue(String::class.java).orEmpty(), snap.child("photo").getValue(String::class.java))
    }

    override suspend fun joinByInvite(code: String): Result<ChatUser> = runCatching {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        val invite = groupInvite(code) ?: error("invite")
        val already = runCatching { root.child("groups").child(invite.groupId).child("members").child(me.uid).get().await().exists() }.getOrDefault(false)
        val group = ChatUser(invite.groupId, invite.name, "", invite.photo)
        if (!already) {
            root.child("groups").child(invite.groupId).child("members").child(me.uid)
                .setValue(me.copy(photo = me.publicPhoto()).asMember("member") + ("via" to code)).await()
            val info = groupInfo(invite.groupId, fresh = true)
            systemMessage(ChatUser(invite.groupId, info?.name ?: invite.name, "", info?.photo), "joined")
        }
        group
    }

    // ------------------------------------------------------------------ broadcast lists
    //
    //   broadcasts/{uid}/{id}  {name, members: {uid: {name, username, photo}}, log: {push: {text, at}}}  (only the owner)

    override fun broadcasts(): Flow<List<Broadcast>> = callbackFlow {
        val job = launch {
            runCatching {
                listen(root.child("broadcasts").child(me())).collect { snap ->
                    trySend(
                        snap.children.mapNotNull { b ->
                            val id = b.key ?: return@mapNotNull null
                            Broadcast(
                                id = id,
                                name = b.child("name").getValue(String::class.java).orEmpty(),
                                members = b.child("members").children.mapNotNull { m ->
                                    val uid = m.key ?: return@mapNotNull null
                                    ChatUser(uid, m.child("name").getValue(String::class.java) ?: "?", m.child("username").getValue(String::class.java).orEmpty(), m.child("photo").getValue(String::class.java))
                                },
                                log = b.child("log").children.mapNotNull { l ->
                                    val text = l.child("text").getValue(String::class.java) ?: return@mapNotNull null
                                    text to (l.child("at").getValue(Long::class.java) ?: 0L)
                                }.sortedBy { it.second },
                            )
                        }.sortedBy { it.name.lowercase() },
                    )
                }
            }.onFailure { trySend(emptyList()) }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun saveBroadcast(id: String?, name: String, members: List<ChatUser>): Result<String> = runCatching {
        val uid = me()
        val ref = root.child("broadcasts").child(uid)
        val key = id ?: ref.push().key ?: error("no id")
        val people = members.filter { !it.isGroup && it.uid != uid && it.uid != CLAUDE_BOT_UID }.associate { it.uid to mapOf("name" to it.name, "username" to it.username, "photo" to it.photo) }
        require(people.isNotEmpty()) { "members" }
        ref.child(key).updateChildren(mapOf("name" to name.trim().take(50), "members" to people)).await()
        key
    }

    override suspend fun deleteBroadcast(id: String) {
        root.child("broadcasts").child(me()).child(id).removeValue()
    }

    override suspend fun logBroadcast(id: String, text: String) {
        root.child("broadcasts").child(me()).child(id).child("log").push().setValue(mapOf("text" to text.take(2000), "at" to ServerValue.TIMESTAMP))
    }

    override suspend fun recentMessages(chatId: String, limit: Int): List<ChatMessage> = runCatching {
        root.child("chats").child(chatId).child("messages").orderByChild("at").limitToLast(limit).get().await()
            .children.mapNotNull { MsgNode.of(chatId, it).toMessage(emptySet()) }
    }.getOrDefault(emptyList())

    override suspend fun allMessages(chatId: String): List<ChatMessage> {
        val snap = root.child("chats").child(chatId).child("messages").orderByChild("at").get().await()
        return snap.children.mapNotNull { MsgNode.of(chatId, it).toMessage(emptySet()) }
    }

    override suspend fun setStatusPrivacy(privacy: StatusPrivacy) {
        root.child("statusPrivacy").child(me()).setValue(
            mapOf("mode" to privacy.audience.name, "people" to privacy.people.associateWith { true }.ifEmpty { null }),
        )
    }

    /** Writes one line of the person's call list (the same call twice only updates it). */
    fun logCall(id: String, peer: ChatUser, video: Boolean, direction: CallDirection, outcome: CallOutcome, seconds: Long) {
        val uid = auth.currentUser?.uid ?: return
        if (id.isBlank()) return
        root.child("callLog").child(uid).child(id.replace(Regex("[.#$\\[\\]/]"), "_")).setValue(
            mapOf(
                "peer" to peer.uid,
                "name" to peer.name,
                "username" to peer.username,
                "photo" to peer.photo,
                "video" to video,
                "dir" to when (direction) {
                    CallDirection.OUT -> "out"
                    CallDirection.GROUP -> "group"
                    CallDirection.IN -> "in"
                },
                "outcome" to outcome.name,
                "at" to ServerValue.TIMESTAMP,
                "seconds" to seconds,
            ),
        )
    }

    // ------------------------------------------------------------------ blocking

    override fun blocked(): Flow<Set<String>> = callbackFlow {
        val job = launch {
            listen(root.child("blocked").child(me())).collect { snap ->
                trySend(snap.children.filter { it.getValue(Boolean::class.java) == true }.mapNotNull { it.key }.toSet())
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun transcribe(voice: ChatAttachment): Result<String> = runCatching {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val audio = if (voice.key.isNotEmpty() || E2eMedia.isSealed(voice.url)) {
                if (voice.key.isNotEmpty()) E2eMedia.remember(voice.url, voice.key)
                E2eMedia.openedFile(app, voice.url, voice.name)?.readBytes() ?: error("cannot open")
            } else {
                MediaUpload.client.newCall(okhttp3.Request.Builder().url(voice.url).build()).execute().use { r ->
                    check(r.isSuccessful) { "http ${r.code}" }
                    r.body?.bytes() ?: error("empty")
                }
            }
            val request = okhttp3.Request.Builder()
                .url(MediaUpload.BASE + "/media/transcribe")
                .header("Authorization", "Bearer " + token())
                .post(okhttp3.RequestBody.Companion.run { audio.toRequestBody(okhttp3.MediaType.Companion.run { "application/octet-stream".toMediaType() }) })
                .build()
            MediaUpload.client.newCall(request).execute().use { r ->
                check(r.isSuccessful) { "transcribe ${r.code}" }
                org.json.JSONObject(r.body?.string() ?: "{}").optString("text")
            }
        }
    }

    @Volatile private var speaker: android.media.MediaPlayer? = null

    override suspend fun speak(text: String): Result<Unit> = runCatching {
        // Reading already: this tap stops it.
        speaker?.let { p ->
            speaker = null
            runCatching { p.stop() }
            p.release()
            return@runCatching
        }
        val file = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val body = org.json.JSONObject().put("text", text.take(1500)).toString()
            val request = okhttp3.Request.Builder()
                .url(MediaUpload.BASE + "/media/speak")
                .header("Authorization", "Bearer " + token())
                .post(okhttp3.RequestBody.Companion.run { body.toRequestBody(okhttp3.MediaType.Companion.run { "application/json".toMediaType() }) })
                .build()
            MediaUpload.client.newCall(request).execute().use { r ->
                check(r.isSuccessful) { "speak ${r.code}" }
                java.io.File(app.cacheDir, "speak.mp3").also { f -> f.writeBytes(r.body?.bytes() ?: error("empty")) }
            }
        }
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            val p = android.media.MediaPlayer()
            p.setAudioAttributes(
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { done -> if (speaker === done) speaker = null; done.release() }
            p.prepare()
            speaker = p
            p.start()
        }
    }

    override suspend fun translate(text: String, to: String): Result<String> = runCatching {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val body = org.json.JSONObject().put("text", text.take(4000)).put("to", to).toString()
            val request = okhttp3.Request.Builder()
                .url(MediaUpload.BASE + "/media/translate")
                .header("Authorization", "Bearer " + token())
                .post(okhttp3.RequestBody.Companion.run { body.toRequestBody(okhttp3.MediaType.Companion.run { "application/json".toMediaType() }) })
                .build()
            MediaUpload.client.newCall(request).execute().use { r ->
                check(r.isSuccessful) { "translate ${r.code}" }
                org.json.JSONObject(r.body?.string() ?: "{}").optString("text")
            }
        }
    }

    override suspend fun report(chatId: String, target: String, reason: String, recent: List<Pair<String, String>>): Result<Unit> = runCatching {
        root.child("reports").push().setValue(
            mapOf(
                "by" to me(),
                "target" to target,
                "chatId" to chatId,
                "reason" to reason.take(40),
                "recent" to recent.takeLast(5).map { (from, text) -> mapOf("from" to from, "text" to text.take(500)) },
                "at" to ServerValue.TIMESTAMP,
            ),
        ).await()
        Unit
    }

    override suspend fun setBlocked(uid: String, blocked: Boolean): Result<Unit> = runCatching {
        val ref = root.child("blocked").child(me()).child(uid)
        if (blocked) ref.setValue(true).await() else ref.removeValue().await()
        Unit
    }

    // ------------------------------------------------------------------ push token

    /** Tokens are kept under a key made from the token itself (keys may not hold '.', '/', '#', '$', '[' or ']'). */
    private fun tokenKey(token: String) = token.hashCode().toUInt().toString(16) + "_" + token.length

    private suspend fun savePushToken(uid: String) {
        val token = FirebaseMessaging.getInstance().token.await()
        root.child("pushTokens").child(uid).child(tokenKey(token)).setValue(token).await()
    }

    private suspend fun removePushToken(uid: String) {
        val token = FirebaseMessaging.getInstance().token.await()
        root.child("pushTokens").child(uid).child(tokenKey(token)).removeValue().await()
    }

    /** A new token for this phone (Firebase changes them now and then): the account's list gets it. */
    fun onNewPushToken() {
        val uid = auth.currentUser?.uid ?: return
        scope.launch { runCatching { savePushToken(uid) } }
    }

    // ------------------------------------------------------------------ deleting the account

    override suspend fun deleteAccount(): Result<Unit> = runCatching {
        val uid = me()
        val username = root.child("users").child(uid).child("username").get().await().getValue(String::class.java)
        // Every message the person sent, conversation by conversation.
        val chats = root.child("userChats").child(uid).get().await().children.mapNotNull { it.key }
        for (chatId in chats) {
            val mine = root.child("chats").child(chatId).child("messages").orderByChild("from").equalTo(uid).get().await()
            val removals = mine.children.mapNotNull { it.key }.associate { "chats/$chatId/messages/$it" to null }
            if (removals.isNotEmpty()) root.updateChildren(removals).await()
        }
        root.child("userChats").child(uid).removeValue().await()
        root.child("pushTokens").child(uid).removeValue().await()
        root.child("blocked").child(uid).removeValue().await()
        root.child("users").child(uid).removeValue().await()
        if (username != null) runCatching { root.child("usernames").child(username).removeValue().await() }
        // Firebase may ask for a fresh sign-in before it deletes the login itself; the data is gone either way.
        runCatching { auth.currentUser?.delete()?.await() }
        auth.signOut()
    }

    // Last, so that everything above (the calls included) exists when the first sign-in state arrives.
    init {
        db.getReference(".info/serverTimeOffset").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                _serverOffset.value = (snap.getValue(Double::class.java) ?: 0.0).toLong()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
        auth.addAuthStateListener { onUser(it.currentUser) }
    }

    companion object {
        const val SETUP_NEEDED = "setup"
        private const val MESSAGES_SHOWN = 300
        private const val PREVIEW_LENGTH = 200

        /** A photo as base64 must stay under this (the rules allow 1.5 MB). */
        private const val MEDIA_MAX_CHARS = 1_450_000
        private const val TYPING_FRESH_MS = 8_000L

        /** A status stays a day. */
        private const val STATUS_LIFE_MS = 24 * 3600_000L

        /** A status video is its first minute. */
        const val STATUS_VIDEO_MS = 60_000L

        /** Where the database is when the config file does not name one (created in Belgium, europe-west1). */
        private const val DATABASE_URL = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app"

        @Volatile private var instance: FirebaseChatBackend? = null

        private fun openDatabase(): FirebaseDatabase {
            val configured = FirebaseApp.getInstance().options.databaseUrl
            val database = if (configured.isNullOrBlank()) FirebaseDatabase.getInstance(DATABASE_URL) else FirebaseDatabase.getInstance()
            // Kept on the phone: conversations open offline, and what is written offline is sent later.
            runCatching { database.setPersistenceEnabled(true) }
            return database
        }

        /** The one backend of the app; null when the app was built without the Firebase config. */
        fun get(context: Context): FirebaseChatBackend? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return instance ?: synchronized(this) { instance ?: FirebaseChatBackend(context.applicationContext).also { instance = it } }
        }

        /** The backend if it was made already (the push service must not start it itself). */
        fun peek(): FirebaseChatBackend? = instance
    }
}
