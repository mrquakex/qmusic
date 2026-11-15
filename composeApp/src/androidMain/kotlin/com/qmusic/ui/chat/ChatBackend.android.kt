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
internal class FirebaseChatBackend private constructor(private val app: Context) : ChatBackend {
    private val auth = FirebaseAuth.getInstance()
    private val db: FirebaseDatabase = openDatabase()
    private val root: DatabaseReference = db.reference
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The screen Google's account picker opens over. */
    var activity: WeakReference<Activity>? = null

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
        runCatching { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setUserId(user?.uid.orEmpty()) }
        runCatching { calls.listenFor(user?.uid) }
        trackPresence(user?.uid)
        runCatching { watchDeliveries(user?.uid) }
        if (user == null) {
            _account.value = if (webClientId == null) ChatAccount.Unavailable(SETUP_NEEDED) else ChatAccount.SignedOut
            return
        }
        if (_account.value !is ChatAccount.Ready) _account.value = ChatAccount.Loading
        val ref = root.child("users").child(user.uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val name = snap.child("name").getValue(String::class.java)
                val username = snap.child("username").getValue(String::class.java)
                _account.value = if (name != null && username != null) {
                    ChatAccount.Ready(ChatUser(user.uid, name, username, snap.child("photo").getValue(String::class.java), snap.child("about").getValue(String::class.java).orEmpty()))
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
                val next = Privacy(
                    hideLastSeen = snap.child("hideLastSeen").getValue(Boolean::class.java) == true,
                    hideReadReceipts = snap.child("hideReadReceipts").getValue(Boolean::class.java) == true,
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
        root.child("users").child(me()).child("privacy").setValue(mapOf("hideLastSeen" to privacy.hideLastSeen, "hideReadReceipts" to privacy.hideReadReceipts))
    }

    override suspend fun updateProfile(name: String, about: String): Result<Unit> = runCatching {
        val clean = name.trim().take(40)
        require(clean.isNotEmpty()) { "name" }
        root.child("users").child(me()).updateChildren(mapOf("name" to clean, "about" to about.trim().take(140))).await()
    }

    override suspend fun setProfilePhoto(image: PickedImage): Result<Unit> = runCatching {
        val uid = me()
        // "u_<uid>": a place on the media server only this person can put files in.
        val url = kotlinx.coroutines.withContext(Dispatchers.IO) {
            MediaUpload.upload(token(), "u_$uid", "jpg", "image/jpeg", image.jpeg.size.toLong(), { image.jpeg.inputStream() }) {}
        }
        imageCache.put(url, image.jpeg)
        root.child("users").child(uid).child("photo").setValue(url).await()
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

    override fun threads(): Flow<List<ChatThread>> {
        val uid = me()
        val ref = root.child("userChats").child(uid)
        ref.keepSynced(true)
        return callbackFlow {
            val job = launch {
                listen(ref).collect { snap ->
                    trySend(snap.children.mapNotNull { it.toThread() }.sortedByDescending { it.lastAt })
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
            lastText = child("lastText").getValue(String::class.java).orEmpty(),
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
        )
    }

    override fun messages(chatId: String): Flow<List<ChatMessage>> = messages(chatId, MESSAGES_SHOWN)

    override fun messages(chatId: String, limit: Int): Flow<List<ChatMessage>> {
        val ref = root.child("chats").child(chatId).child("messages")
        ref.keepSynced(true)
        val query = ref.orderByChild("at").limitToLast(limit)
        return combine(listen(query), pendingIds, outbox, _uploads, hidden(chatId)) { snap, pending, out, progress, hidden ->
            val now = System.currentTimeMillis() + _serverOffset.value
            val sent = snap.children.mapNotNull { it.toMessage(pending) }.filter { it.id !in hidden && (it.expireAt == 0L || it.expireAt > now) }
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
        root.child("chats").child(chatId).child("messages").child(messageId).updateChildren(mapOf("text" to body, "edited" to true)).await()
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
            ),
        ).await()
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
            listen(root.child("starred").child(me())).collect { snap ->
                trySend(
                    snap.children.mapNotNull { m ->
                        StarredMessage(
                            chatId = m.child("chatId").getValue(String::class.java) ?: return@mapNotNull null,
                            messageId = m.child("messageId").getValue(String::class.java) ?: return@mapNotNull null,
                            text = m.child("text").getValue(String::class.java).orEmpty(),
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
        val text = message.text.ifBlank {
            when {
                message.attachment?.kind == AttachmentKind.VOICE -> "🎤"
                message.attachment?.kind == AttachmentKind.VIDEO -> "🎬"
                message.attachment != null -> "📄 " + message.attachment.name
                message.images.isNotEmpty() -> "📷"
                message.share != null -> "🎵 " + message.share.title
                message.poll != null -> "📊 " + message.poll.question
                message.location != null -> "📍"
                message.contact != null -> "👤 " + message.contact.name
                else -> ""
            }
        }
        ref.setValue(mapOf("chatId" to chatId, "messageId" to message.id, "text" to text.take(300), "from" to message.from, "fromName" to fromName, "chatName" to chatName, "at" to message.at))
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
            extra = mapOf("location" to mapOf("lat" to location.lat, "lng" to location.lng, "label" to location.label.take(120))),
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
        FirebaseGroupCallService(app, root, { (_account.value as? ChatAccount.Ready)?.me }, calls)
    }

    private fun DataSnapshot.toMessage(pending: Set<String>): ChatMessage? {
        val id = key ?: return null
        val from = child("from").getValue(String::class.java) ?: return null
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
                )
            }
        }
        val replySnap = child("replyTo")
        val reply = replySnap.child("id").getValue(String::class.java)?.let { replyId ->
            ChatReply(replyId, replySnap.child("from").getValue(String::class.java).orEmpty(), replySnap.child("text").getValue(String::class.java).orEmpty())
        }
        fun DataSnapshot.toImage(): ChatImage? = child("id").getValue(String::class.java)?.let { imageId ->
            ChatImage(
                id = imageId,
                width = child("w").getValue(Int::class.java) ?: 0,
                height = child("h").getValue(Int::class.java) ?: 0,
                thumb = child("thumb").getValue(String::class.java).orEmpty(),
                url = child("url").getValue(String::class.java),
            )
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
                )
            }
        }
        // An album ("images"), or one photo as the first version wrote it ("image").
        val images = child("images").children.mapNotNull { it.toImage() }.ifEmpty { listOfNotNull(child("image").toImage()) }
        return ChatMessage(
            id = id,
            from = from,
            text = child("text").getValue(String::class.java).orEmpty(),
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
            location = child("location").takeIf { it.exists() }?.let { l ->
                val lat = l.child("lat").getValue(Double::class.java) ?: return@let null
                val lng = l.child("lng").getValue(Double::class.java) ?: return@let null
                ChatLocation(lat, lng, l.child("label").getValue(String::class.java).orEmpty())
            },
            contact = child("contact").takeIf { it.exists() }?.let { c ->
                ChatContact(c.child("name").getValue(String::class.java).orEmpty(), c.child("phone").getValue(String::class.java) ?: return@let null)
            },
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
        images: List<Map<String, Any>>?,
        preview: String?,
        extra: Map<String, Any?> = emptyMap(),
        fixedId: String? = null,
        noExpiry: Boolean = false,
    ) {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        val chatId = conversationId(me.uid, other)
        val messageId = fixedId ?: root.child("chats").child(chatId).child("messages").push().key ?: error("no id")
        val ttl = if (noExpiry || other.uid == CLAUDE_BOT_UID) 0L else ttlOf(chatId)
        val message = buildMap<String, Any?> {
            putAll(extra)
            if (ttl > 0) put("expireAt", System.currentTimeMillis() + _serverOffset.value + ttl)
            put("from", me.uid)
            put("text", text)
            put("at", ServerValue.TIMESTAMP)
            if (replyTo != null) put("replyTo", mapOf("id" to replyTo.id, "from" to replyTo.from, "text" to replyTo.text.take(PREVIEW_LENGTH)))
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
                    ),
                )
            }
        }
        val shown = preview ?: text.ifBlank { share?.let { (if (it.kind == ShareKind.SONG) "🎵 " else "🎬 ") + it.title }.orEmpty() }
        val preview = shown.take(PREVIEW_LENGTH)
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
            "$theirs/photo" to me.photo,
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
        )
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
        updates["notify/${root.child("notify").push().key}"] = mapOf(
            "from" to me.uid, "to" to groupId, "chatId" to groupId, "type" to "chat", "body" to preview, "at" to ServerValue.TIMESTAMP,
        )
        pendingIds.update { it + messageId }
        root.updateChildren(updates).addOnCompleteListener { pendingIds.update { it - messageId } }
    }

    override suspend fun sendImages(other: ChatUser, images: List<PickedImage>, caption: String, replyTo: ChatReply?): Result<Unit> = runCatching {
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
            val entries = kotlinx.coroutines.coroutineScope {
                images.mapIndexed { i, image ->
                    async(Dispatchers.IO) {
                        val url = MediaUpload.upload(token(), chatId, "jpg", "image/jpeg", image.jpeg.size.toLong(), { image.jpeg.inputStream() }) { done ->
                            sent[i] = done
                            _uploads.update { it + (messageId to sent.sum().toFloat() / total) }
                        }
                        imageCache.put(url, image.jpeg)
                        mapOf("id" to local[i].id, "url" to url, "w" to image.width, "h" to image.height, "thumb" to image.thumb)
                    }
                }.map { it.await() }
            }
            val label = if (entries.size == 1) "📷" else "📷 ×${entries.size}"
            sendMessage(
                other = other,
                text = caption,
                share = null,
                replyTo = replyTo,
                images = entries,
                preview = if (caption.isBlank()) label else "$label $caption",
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
            val url = kotlinx.coroutines.withContext(Dispatchers.IO) {
                MediaUpload.upload(token(), chatId, ext, file.mime.ifBlank { "application/octet-stream" }, size, { openLocation(source) }) { done ->
                    _uploads.update { it + (messageId to share + (1 - share) * done.toFloat() / size.coerceAtLeast(1)) }
                }
            }
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
            )
        }.sortedWith(compareByDescending<GroupMember> { it.admin }.thenBy { it.user.name.lowercase() })
        return GroupInfo(
            id, name, child("photo").getValue(String::class.java), members, child("createdBy").getValue(String::class.java),
            description = child("description").getValue(String::class.java).orEmpty(),
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
        memberMap[me.uid] = me.asMember("admin")
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
        root.updateChildren(fresh.associate { "groups/$groupId/members/${it.uid}" to it.asMember("member") }).await()
        val info = groupInfo(groupId, fresh = true) ?: return@runCatching
        systemMessage(ChatUser(groupId, info.name, "", info.photo), "added", fresh.joinToString(", ") { it.name.substringBefore(' ') })
    }

    override suspend fun removeMember(groupId: String, member: ChatUser): Result<Unit> = runCatching {
        root.updateChildren(mapOf("groups/$groupId/members/${member.uid}" to null, "userChats/${member.uid}/$groupId" to null)).await()
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
        val job = launch {
            listen(root.child("friends").child(me())).collect { snap ->
                trySend(snap.children.mapNotNull { it.toPerson() }.sortedBy { it.name.lowercase() })
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
                "friendRequests/${user.uid}/${me.uid}" to me.card("at"),
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
                "friends/${user.uid}/${me.uid}" to me.card("since"),
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
        return ChatUser(
            uid = uid,
            name = snap.child("name").getValue(String::class.java) ?: return null,
            username = snap.child("username").getValue(String::class.java).orEmpty(),
            photo = snap.child("photo").getValue(String::class.java),
            about = snap.child("about").getValue(String::class.java).orEmpty(),
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

    override suspend fun imageBytes(chatId: String, image: ChatImage): ByteArray? {
        val url = image.url ?: return imageBytes(chatId, image.id)
        imageCache.get(url)?.let { return it }
        // Our own photo, still going up or just sent: the phone's copy.
        imageCache.get("$chatId/${image.id}")?.let { return it }
        val file = java.io.File(java.io.File(app.cacheDir, "chat_media").apply { mkdirs() }, url.substringAfterLast('/'))
        if (file.exists()) return file.readBytes().also { imageCache.put(url, it) }
        val bytes = kotlinx.coroutines.withContext(Dispatchers.IO) { MediaUpload.download(url) } ?: return null
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
    private fun trackPresence(uid: String?) {
        val connected = db.getReference(".info/connected")
        presenceListener?.let { connected.removeEventListener(it) }
        presenceListener = null
        if (uid == null) return
        val ref = root.child("presence").child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (snap.getValue(Boolean::class.java) != true) return
                if (_privacy.value.hideLastSeen) {
                    // Hidden: others see neither "online" nor a time.
                    ref.onDisconnect().setValue(mapOf("hidden" to true))
                    ref.setValue(mapOf("hidden" to true))
                    return
                }
                ref.onDisconnect().setValue(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP))
                ref.setValue(mapOf("online" to true, "lastSeen" to ServerValue.TIMESTAMP))
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        connected.addValueEventListener(listener)
        presenceListener = listener
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
                trySend(if (target != null && phase != null) ClaudeAssist(target, phase) else null)
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

    override suspend fun react(chatId: String, messageId: String, emoji: String?) {
        val uid = auth.currentUser?.uid ?: return
        val ref = root.child("chats").child(chatId).child("reactions").child(messageId).child(uid)
        if (emoji == null) ref.removeValue() else ref.setValue(emoji)
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
