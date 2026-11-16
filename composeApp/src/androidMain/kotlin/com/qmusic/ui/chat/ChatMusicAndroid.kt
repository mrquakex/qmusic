package com.qmusic.ui.chat

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's part of [ChatMusic], in the database:
 *  - listening/{uid}: the song playing now (gone when the phone goes offline, or the person turned it off);
 *  - users/{uid}/song: the profile song;
 *  - chatLists/{chatId}/{key}: a conversation's shared playlist;
 *  - reacts/{chatId}/{key}: reactions while watching together (each removed by its sender a few seconds later).
 */
object ChatMusicAndroid {
    private const val PREFS = "qmusic_settings"
    private const val KEY_SHARE = "share_listening"
    private const val TAG = "QeyfChatMusic"

    /** What plays is written again this often, so a stale one (a phone that died) can be told apart. */
    private const val HEARTBEAT_MS = 5 * 60_000L
    /** Older than this, what someone was playing is no longer shown. */
    private const val STALE_MS = 12 * 60_000L
    private const val REACTION_KEEP_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun database(context: Context): FirebaseDatabase? = runCatching {
        // The messages open the database first (keeping its offline settings).
        FirebaseChatBackend.get(context) ?: return null
        val configured = FirebaseApp.getInstance().options.databaseUrl
        if (configured.isNullOrBlank()) FirebaseDatabase.getInstance(DATABASE_URL) else FirebaseDatabase.getInstance()
    }.getOrNull()

    private fun root(): DatabaseReference? = app?.let { database(it)?.reference }

    private fun me(): ChatUser? = (app?.let { FirebaseChatBackend.get(it) }?.account?.value as? ChatAccount.Ready)?.me

    private fun uid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    fun init(context: Context) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        val m = ChatMusic
        m.shareListening.value = prefs(c).getBoolean(KEY_SHARE, true)
        m.setShareListening = { on ->
            prefs(c).edit().putBoolean(KEY_SHARE, on).apply()
            m.shareListening.value = on
            publish(if (on) playing else null, remember = false)
        }
        m.publishListening = { item -> publish(item, remember = true) }
        m.listenTo = { uids -> listenTo(uids) }
        m.profileSong = { uid -> profileSong(uid) }
        m.setProfileSong = { item -> setProfileSong(item) }
        m.sharedList = { chatId -> sharedList(chatId) }
        m.addToList = { chatId, item -> addToList(chatId, item) }
        m.removeFromList = { chatId, key -> runCatching { root()?.child("chatLists")?.child(chatId)?.child(key)?.removeValue()?.await() } }
        m.reactions = { chatId -> reactions(chatId) }
        m.react = { chatId, text -> react(chatId, text) }
    }

    // ---------------------------------------------------------------- what plays here now

    /** What plays here (kept even while it is not shared, to share it at once when turned on). */
    private var playing: SharedItem? = null
    private var written: String? = null
    private var heartbeat: Job? = null

    private fun publish(item: SharedItem?, remember: Boolean) {
        if (remember) playing = item
        val uid = uid() ?: return
        val ref = root()?.child("listening")?.child(uid) ?: return
        val shown = item?.takeIf { ChatMusic.shareListening.value }
        if (shown == null) {
            heartbeat?.cancel()
            heartbeat = null
            if (written != null || !remember) ref.removeValue()
            written = null
            return
        }
        if (written == shown.id && heartbeat?.isActive == true) return
        written = shown.id
        heartbeat?.cancel()
        heartbeat = scope.launch {
            runCatching { ref.onDisconnect().removeValue() }
            while (isActive) {
                ref.setValue(
                    mapOf(
                        "id" to shown.id.take(64),
                        "title" to shown.title.take(200),
                        "subtitle" to shown.subtitle.take(200),
                        "thumbnail" to shown.thumbnail?.take(500),
                        "at" to ServerValue.TIMESTAMP,
                    ),
                )
                delay(HEARTBEAT_MS)
            }
        }
    }

    private val listeners = HashMap<String, ValueEventListener>()
    private val raw = HashMap<String, Pair<SharedItem, Long>>()
    private var ticker: Job? = null

    private fun listenTo(uids: Set<String>) {
        val base = root()?.child("listening") ?: return
        val me = uid()
        val wanted = uids.filter { it != me && it != CLAUDE_BOT_UID && !isGroupId(it) && it.isNotBlank() }.toSet()
        (listeners.keys - wanted).forEach { uid ->
            listeners.remove(uid)?.let { base.child(uid).removeEventListener(it) }
            raw.remove(uid)
        }
        (wanted - listeners.keys).forEach { uid ->
            val listener = object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    val id = snap.child("id").getValue(String::class.java)
                    if (id == null) {
                        raw.remove(uid)
                    } else {
                        raw[uid] = SharedItem(
                            kind = ShareKind.SONG,
                            id = id,
                            title = snap.child("title").getValue(String::class.java).orEmpty(),
                            subtitle = snap.child("subtitle").getValue(String::class.java).orEmpty(),
                            thumbnail = snap.child("thumbnail").getValue(String::class.java),
                        ) to (snap.child("at").getValue(Long::class.java) ?: 0L)
                    }
                    refresh()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "listening $uid: ${error.message}")
                }
            }
            listeners[uid] = listener
            base.child(uid).addValueEventListener(listener)
        }
        refresh()
        if (listeners.isEmpty()) {
            ticker?.cancel()
            ticker = null
        } else if (ticker?.isActive != true) {
            // What went stale without a word (a phone that died) drops out.
            ticker = scope.launch {
                while (isActive) {
                    delay(60_000)
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        ChatMusic.listening.value = raw.filterValues { (_, at) -> now - at < STALE_MS }.mapValues { it.value.first }
    }

    // ---------------------------------------------------------------- profile song

    private fun DataSnapshot.toItem(): SharedItem? {
        val id = child("id").getValue(String::class.java) ?: return null
        return SharedItem(
            kind = ShareKind.SONG,
            id = id,
            title = child("title").getValue(String::class.java).orEmpty(),
            subtitle = child("subtitle").getValue(String::class.java).orEmpty(),
            thumbnail = child("thumbnail").getValue(String::class.java),
        )
    }

    private fun SharedItem.toMap() = mapOf(
        "kind" to ShareKind.SONG.name,
        "id" to id.take(64),
        "title" to title.take(200),
        "subtitle" to subtitle.take(200),
        "thumbnail" to thumbnail?.take(500),
    )

    private fun profileSong(uid: String) = callbackFlow {
        val ref = root()?.child("users")?.child(uid)?.child("song")
        if (ref == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                trySend(snap.toItem())
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(null)
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    private suspend fun setProfileSong(item: SharedItem?): Boolean {
        val uid = uid() ?: return false
        val ref = root()?.child("users")?.child(uid)?.child("song") ?: return false
        return withTimeoutOrNull(15_000) {
            runCatching { if (item == null) ref.removeValue().await() else ref.setValue(item.toMap()).await() }.isSuccess
        } == true
    }

    // ---------------------------------------------------------------- shared playlist

    private fun sharedList(chatId: String) = callbackFlow {
        val ref = root()?.child("chatLists")?.child(chatId)
        if (ref == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val query = ref.orderByChild("at")
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                trySend(
                    snap.children.mapNotNull { c ->
                        val item = c.toItem() ?: return@mapNotNull null
                        SharedListEntry(
                            key = c.key ?: return@mapNotNull null,
                            item = item,
                            by = c.child("by").getValue(String::class.java).orEmpty(),
                            byName = c.child("byName").getValue(String::class.java).orEmpty(),
                            at = c.child("at").getValue(Long::class.java) ?: 0L,
                        )
                    },
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "shared list: ${error.message}")
                trySend(emptyList())
            }
        }
        query.addValueEventListener(listener)
        awaitClose { query.removeEventListener(listener) }
    }

    private suspend fun addToList(chatId: String, item: SharedItem): Boolean {
        val me = me() ?: return false
        val ref = root()?.child("chatLists")?.child(chatId)?.push() ?: return false
        val value = item.toMap() + mapOf("by" to me.uid, "byName" to me.name.substringBefore(' ').take(40), "at" to ServerValue.TIMESTAMP)
        return withTimeoutOrNull(15_000) { runCatching { ref.setValue(value).await() }.isSuccess } == true
    }

    // ---------------------------------------------------------------- reactions while watching together

    private fun reactions(chatId: String) = callbackFlow {
        val ref = root()?.child("reacts")?.child(chatId)
        if (ref == null) {
            awaitClose { }
            return@callbackFlow
        }
        // Only what comes from now on (with room for a clock that is a little off).
        val query = ref.orderByChild("at").startAt((System.currentTimeMillis() - 30_000).toDouble())
        val listener = object : ChildEventListener {
            override fun onChildAdded(snap: DataSnapshot, previousChildName: String?) {
                val text = snap.child("t").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: return
                trySend(
                    LiveReaction(
                        key = snap.key ?: return,
                        text = text.take(60),
                        by = snap.child("by").getValue(String::class.java).orEmpty(),
                        name = snap.child("name").getValue(String::class.java).orEmpty(),
                    ),
                )
            }

            override fun onChildChanged(snap: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "reactions: ${error.message}")
            }
        }
        query.addChildEventListener(listener)
        awaitClose { query.removeEventListener(listener) }
    }

    private fun react(chatId: String, text: String) {
        val me = me() ?: return
        val ref = root()?.child("reacts")?.child(chatId)?.push() ?: return
        ref.setValue(mapOf("t" to text.take(60), "by" to me.uid, "name" to me.name.substringBefore(' ').take(30), "at" to ServerValue.TIMESTAMP))
        scope.launch {
            delay(REACTION_KEEP_MS)
            ref.removeValue()
        }
    }

    private const val DATABASE_URL = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app"
}
