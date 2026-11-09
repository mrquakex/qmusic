package com.qmusic.ui.chat

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
        runCatching { calls.listenFor(user?.uid) }
        trackPresence(user?.uid)
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
                    ChatAccount.Ready(ChatUser(user.uid, name, username, snap.child("photo").getValue(String::class.java)))
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
        scope.launch { runCatching { savePushToken(user.uid) } }
    }

    private fun me(): String = auth.currentUser?.uid ?: error("not signed in")

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
        )
    }

    override fun messages(chatId: String): Flow<List<ChatMessage>> {
        val ref = root.child("chats").child(chatId).child("messages")
        ref.keepSynced(true)
        val query = ref.orderByChild("at").limitToLast(MESSAGES_SHOWN)
        return combine(listen(query), pendingIds) { snap, pending ->
            snap.children.mapNotNull { it.toMessage(pending) }
        }
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
        val imageSnap = child("image")
        val image = imageSnap.child("id").getValue(String::class.java)?.let { imageId ->
            ChatImage(
                id = imageId,
                width = imageSnap.child("w").getValue(Int::class.java) ?: 0,
                height = imageSnap.child("h").getValue(Int::class.java) ?: 0,
                thumb = imageSnap.child("thumb").getValue(String::class.java).orEmpty(),
            )
        }
        return ChatMessage(
            id = id,
            from = from,
            text = child("text").getValue(String::class.java).orEmpty(),
            replyTo = reply,
            image = image,
            model = child("model").getValue(String::class.java),
            streaming = child("streaming").getValue(Boolean::class.java) == true,
            at = child("at").getValue(Long::class.java) ?: System.currentTimeMillis(),
            share = share,
            pending = id in pending,
        )
    }

    override fun otherReadAt(chatId: String, otherUid: String): Flow<Long> = callbackFlow {
        val job = launch {
            listen(root.child("chats").child(chatId).child("read").child(otherUid)).collect { trySend(it.getValue(Long::class.java) ?: 0L) }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun send(other: ChatUser, text: String, share: SharedItem?, replyTo: ChatReply?): Result<Unit> = runCatching {
        sendMessage(other, text, share, replyTo, image = null, preview = null)
    }

    private fun sendMessage(
        other: ChatUser,
        text: String,
        share: SharedItem?,
        replyTo: ChatReply?,
        image: Map<String, Any>?,
        preview: String?,
    ) {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        val chatId = chatIdOf(me.uid, other.uid)
        val messageId = root.child("chats").child(chatId).child("messages").push().key ?: error("no id")
        val message = buildMap<String, Any?> {
            put("from", me.uid)
            put("text", text)
            put("at", ServerValue.TIMESTAMP)
            if (replyTo != null) put("replyTo", mapOf("id" to replyTo.id, "from" to replyTo.from, "text" to replyTo.text.take(PREVIEW_LENGTH)))
            if (image != null) put("image", image)
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

    override suspend fun sendImage(other: ChatUser, image: PickedImage, caption: String, replyTo: ChatReply?): Result<Unit> = runCatching {
        val me = (_account.value as? ChatAccount.Ready)?.me ?: error("not signed in")
        val chatId = chatIdOf(me.uid, other.uid)
        val mediaId = root.child("media").child(chatId).push().key ?: error("no id")
        val data = android.util.Base64.encodeToString(image.jpeg, android.util.Base64.NO_WRAP)
        require(data.length < MEDIA_MAX_CHARS) { "photo too large" }
        // Kept here at once, so our own photo shows without downloading it back.
        imageCache.put("$chatId/$mediaId", image.jpeg)
        root.child("media").child(chatId).child(mediaId).setValue(mapOf("data" to data, "mime" to "image/jpeg")).await()
        sendMessage(
            other = other,
            text = caption,
            share = null,
            replyTo = replyTo,
            image = mapOf("id" to mediaId, "w" to image.width, "h" to image.height, "thumb" to image.thumb),
            preview = if (caption.isBlank()) "📷" else "📷 $caption",
        )
    }

    /** Photos already fetched: memory first, then the app's cache folder. */
    private val imageCache = android.util.LruCache<String, ByteArray>(24)

    override suspend fun imageBytes(chatId: String, id: String): ByteArray? {
        val key = "$chatId/$id"
        imageCache.get(key)?.let { return it }
        val file = java.io.File(java.io.File(app.cacheDir, "chat_media").apply { mkdirs() }, key.replace('/', '_') + ".jpg")
        if (file.exists()) return file.readBytes().also { imageCache.put(key, it) }
        val snap = runCatching { root.child("media").child(chatId).child(id).get().await() }.getOrNull() ?: return null
        val data = snap.child("data").getValue(String::class.java) ?: return null
        val bytes = runCatching { android.util.Base64.decode(data, android.util.Base64.DEFAULT) }.getOrNull() ?: return null
        runCatching { file.writeBytes(bytes) }
        imageCache.put(key, bytes)
        return bytes
    }

    override suspend fun markRead(chatId: String) {
        val uid = auth.currentUser?.uid ?: return
        root.updateChildren(
            mapOf(
                "userChats/$uid/$chatId/read" to ServerValue.TIMESTAMP,
                "chats/$chatId/read/$uid" to ServerValue.TIMESTAMP,
            ),
        )
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
                ref.onDisconnect().setValue(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP))
                ref.setValue(mapOf("online" to true, "lastSeen" to ServerValue.TIMESTAMP))
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        connected.addValueEventListener(listener)
        presenceListener = listener
    }

    override fun presence(uid: String): Flow<Presence?> = callbackFlow {
        val job = launch {
            listen(root.child("presence").child(uid)).collect { snap ->
                trySend(
                    if (!snap.exists()) null else Presence(
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

    override fun typing(chatId: String, otherUid: String): Flow<Boolean> = callbackFlow {
        val job = launch {
            listen(root.child("typing").child(chatId).child(otherUid)).collect { snap ->
                // A mark older than a few seconds is from a phone that went away mid-word.
                val at = snap.getValue(Long::class.java)
                trySend(at != null && System.currentTimeMillis() - at < TYPING_FRESH_MS)
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun setTyping(chatId: String, typing: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val ref = root.child("typing").child(chatId).child(uid)
        if (typing) {
            ref.onDisconnect().removeValue()
            ref.setValue(ServerValue.TIMESTAMP)
        } else {
            ref.removeValue()
        }
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
