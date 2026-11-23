package com.qmusic.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.nuvelo.web.rtcAnswer
import com.nuvelo.web.rtcCamera
import com.nuvelo.web.rtcClose
import com.nuvelo.web.rtcIce
import com.nuvelo.web.rtcMute
import com.nuvelo.web.rtcNew
import com.nuvelo.web.rtcOffer
import com.nuvelo.web.rtcRemote
import com.nuvelo.web.rtcRestart
import com.nuvelo.web.rtcState
import com.nuvelo.web.rtcVideo
import com.nuvelo.web.rtcVideoDrop
import com.nuvelo.web.text
import com.nuvelo.web.toneStart
import com.nuvelo.web.toneStop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Voice and video calls in the browser, the phones' protocol (FirebaseCallService) step by step, so a browser and a
 * phone call each other:
 *
 *   calls/{chatId}                 {id, from, to, video, state: ringing|accepted|declined|busy|ended, at}
 *   calls/{chatId}/offer, answer   {type, sdp}
 *   calls/{chatId}/ice/{uid}/{id}  {mid, index, sdp}
 *   incomingCall/{uid}             {id, chatId, from, video, at}: what makes the other side ring
 *   notify/{id}                    {type: call, ...}: the push that wakes a phone where the app is closed
 *
 * The connection itself (WebRTC) is the browser's (web/bridge/calls.mjs).
 */
internal class WebCallService(
    private val root: DatabaseReference,
    private val me: () -> ChatUser?,
    private val logCall: (peer: ChatUser, video: Boolean, seconds: Long?) -> Unit,
    private val logEntry: (id: String, peer: ChatUser, video: Boolean, direction: CallDirection, outcome: CallOutcome, seconds: Long) -> Unit,
    private val serverOffset: () -> Long,
) : CallService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<CallState?>(null)
    override val state: StateFlow<CallState?> = _state.asStateFlow()

    /** The browser's connection of the call in progress (for its pictures). */
    val rtc = MutableStateFlow<Int?>(null)

    private inner class Session(val id: String, val chatId: String, val peer: ChatUser, val video: Boolean, val caller: Boolean) {
        var conn: Int? = null
        val pendingIce = ArrayList<String>()
        var remoteSet = false
        val listeners = ArrayList<Pair<DatabaseReference, Any>>()
        var timeout: Job? = null
        var disconnectJob: Job? = null
        var connectedOnce = false
        var ended = false
        var lastReoffer: String? = null
        var lastReanswer: String? = null
        var at = 0L
        var upgraded = false
        var cameraStarted = video
        val callRef: DatabaseReference get() = root.child("calls").child(chatId)
    }

    private var session: Session? = null

    private fun now() = System.currentTimeMillis()
    private fun serverNow() = now() + serverOffset()

    private fun uuid(): String {
        val b = E2eCrypto.bytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = E2eCrypto.hex(b)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    // ------------------------------------------------------------------ incoming

    private var inboxRef: DatabaseReference? = null
    private var inboxListener: ValueEventListener? = null

    fun listenFor(uid: String?) {
        inboxListener?.let { inboxRef?.removeEventListener(it) }
        inboxListener = null
        if (uid == null) {
            hangUp()
            return
        }
        val ref = root.child("incomingCall").child(uid)
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onInbox(snapshot)
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(l)
        inboxRef = ref
        inboxListener = l
    }

    private suspend fun person(uid: String): ChatUser {
        val p = runCatching { root.child("users").child(uid).get().await() }.getOrNull()
        return ChatUser(
            uid = uid,
            name = p?.child("name")?.getValue(String::class) ?: "?",
            username = p?.child("username")?.getValue(String::class).orEmpty(),
            photo = p?.child("photo")?.getValue(String::class)
                ?: runCatching { root.child("privateProfile").child(uid).child("photo").get().await().getValue(String::class) }.getOrNull(),
        )
    }

    private fun onInbox(snap: DataSnapshot) {
        var current = session
        if (current != null && current.ended) {
            session = null
            _state.value = null
            current = null
        }
        if (!snap.exists()) {
            if (current != null && !current.caller && _state.value?.phase == CallPhase.INCOMING) finish(CallEnd.CANCELLED, write = false)
            return
        }
        val id = snap.child("id").getValue(String::class) ?: return
        val chatId = snap.child("chatId").getValue(String::class) ?: return
        val from = snap.child("from").getValue(String::class) ?: return
        val at = snap.child("at").getValue(Long::class) ?: 0L
        val video = snap.child("video").getValue(Boolean::class) == true
        if (current?.id == id) return
        if (serverNow() - at > RING_MS + 15_000) {
            snap.ref.removeValue()
            return
        }
        if (current != null) {
            root.child("calls").child(chatId).child("state").setValue("busy")
            snap.ref.removeValue()
            scope.launch { logEntry(id, person(from), video, CallDirection.IN, CallOutcome.MISSED, 0) }
            return
        }
        scope.launch {
            val peer = person(from)
            if (session != null) return@launch
            val s = Session(id, chatId, peer, video, caller = false)
            s.at = at
            session = s
            _state.value = CallState(id = id, peer = peer, video = video, phase = CallPhase.INCOMING, speaker = true)
            s.callRef.child("ringing").setValue(id)
            watchCall(s)
            toneStart("ring")
            s.timeout = scope.launch {
                delay(RING_MS)
                if (_state.value?.phase == CallPhase.INCOMING) finish(CallEnd.NO_ANSWER, write = false)
            }
        }
    }

    // ------------------------------------------------------------------ calling, answering

    private suspend fun iceServers(): String {
        val list = ArrayList<JsonObject>()
        runCatching {
            val turn = root.child("turn").get().await()
            val urls = turn.child("urls").children.mapNotNull { it.getValue(String::class) }
            val user = turn.child("username").getValue(String::class)
            val pass = turn.child("credential").getValue(String::class)
            if (urls.isNotEmpty() && user != null && pass != null) {
                list += JsonObject(mapOf("urls" to JsonArray(urls.map { JsonPrimitive(it) }), "username" to JsonPrimitive(user), "credential" to JsonPrimitive(pass)))
            }
        }
        list += JsonObject(mapOf("urls" to JsonArray(listOf(JsonPrimitive("stun:stun.l.google.com:19302"), JsonPrimitive("stun:stun1.l.google.com:19302")))))
        return JsonArray(list).toString()
    }

    private suspend fun connect(s: Session): Int {
        val id = rtcNew(iceServers(), s.video, s.caller) { e -> scope.launch { onRtc(s, e) } }.await<kotlin.js.JsAny>().toString().toDouble().toInt()
        s.conn = id
        rtc.value = id
        return id
    }

    private fun onRtc(s: Session, e: String) {
        if (session !== s) return
        when {
            e.startsWith("ice:") -> {
                val uid = me()?.uid ?: return
                val o = Json.parseToJsonElement(e.removePrefix("ice:")).jsonObject
                s.callRef.child("ice").child(uid).push().setValue(
                    mapOf("mid" to o["mid"]?.jsonPrimitive?.content, "index" to (o["index"]?.jsonPrimitive?.intOrNull ?: 0), "sdp" to o["sdp"]?.jsonPrimitive?.content),
                )
            }
            e.startsWith("level:") -> _state.update { it?.copy(remoteLevel = e.removePrefix("level:").toInt() / 100f) }
            e == "track:video" -> if (s.video) _state.update { it?.copy(remoteVideo = true) }
            e == "state:connected" -> {
                s.disconnectJob?.cancel()
                toneStop()
                _state.update { it?.copy(phase = CallPhase.ACTIVE, startedAt = it.startedAt ?: now(), weak = false) }
                s.connectedOnce = true
            }
            e == "state:disconnected" -> {
                _state.update { it?.copy(weak = true) }
                s.disconnectJob?.cancel()
                s.disconnectJob = scope.launch {
                    delay(3_000)
                    if (s.caller) s.conn?.let { rtcRestart(it) }
                    delay(12_000)
                    if (session === s) finish(CallEnd.FAILED, write = true)
                }
            }
            e == "state:failed" -> finish(CallEnd.FAILED, write = true)
        }
    }

    override fun call(peer: ChatUser, video: Boolean) {
        val mine = me() ?: return
        if (session != null) return
        val chatId = chatIdOf(mine.uid, peer.uid)
        val id = uuid()
        val s = Session(id, chatId, peer, video, caller = true)
        s.at = serverNow()
        session = s
        _state.value = CallState(id = id, peer = peer, video = video, phase = CallPhase.CALLING, speaker = true)
        scope.launch {
            try {
                s.callRef.setValue(mapOf("id" to id, "from" to mine.uid, "to" to peer.uid, "video" to video, "state" to "ringing", "at" to ServerValue.TIMESTAMP)).await()
                root.updateChildren(
                    mapOf(
                        "incomingCall/${peer.uid}" to mapOf("id" to id, "chatId" to chatId, "from" to mine.uid, "video" to video, "at" to ServerValue.TIMESTAMP),
                        "notify/${root.child("notify").push().key}" to notifyEntry(mine.uid, peer.uid, chatId, id, video, "ring"),
                    ),
                ).await()
                watchCall(s)
                val conn = connect(s)
                val sdp = rtcOffer(conn).text()
                s.callRef.child("offer").setValue(mapOf("type" to "offer", "sdp" to sdp))
                toneStart("ringback")
                s.timeout = scope.launch {
                    delay(RING_MS)
                    if (_state.value?.phase == CallPhase.CALLING) finish(CallEnd.NO_ANSWER, write = true)
                }
            } catch (e: Throwable) {
                finish(CallEnd.FAILED, write = true)
            }
        }
    }

    private fun notifyEntry(from: String, to: String, chatId: String, callId: String, video: Boolean, action: String) = mapOf(
        "from" to from, "to" to to, "chatId" to chatId, "type" to "call", "callId" to callId, "video" to video, "action" to action, "at" to ServerValue.TIMESTAMP,
    )

    override fun accept() {
        val s = session ?: return
        if (s.caller || _state.value?.phase != CallPhase.INCOMING) return
        toneStop()
        s.timeout?.cancel()
        _state.update { it?.copy(phase = CallPhase.CONNECTING) }
        scope.launch {
            try {
                root.child("incomingCall").child(me()?.uid ?: return@launch).removeValue()
                val nowSnap = s.callRef.get().await()
                val nowId = nowSnap.child("id").getValue(String::class)
                val nowState = nowSnap.child("state").getValue(String::class)
                if (nowId != s.id || nowState == "ended" || nowState == "declined" || nowState == "busy") {
                    finish(CallEnd.CANCELLED, write = false)
                    return@launch
                }
                val offer = waitFor(s.callRef.child("offer"))
                val conn = connect(s)
                rtcRemote(conn, "offer", offer.child("sdp").getValue(String::class) ?: error("no offer")).await<kotlin.js.JsAny?>()
                s.remoteSet = true
                drainIce(s)
                val sdp = rtcAnswer(conn).text()
                s.callRef.updateChildren(mapOf("answer" to mapOf("type" to "answer", "sdp" to sdp), "state" to "accepted"))
            } catch (e: Throwable) {
                finish(CallEnd.FAILED, write = true)
            }
        }
    }

    override fun decline() {
        val s = session ?: return
        if (s.caller) return
        s.callRef.child("state").setValue("declined")
        me()?.uid?.let { root.child("incomingCall").child(it).removeValue() }
        finish(CallEnd.DECLINED, write = false)
    }

    override fun hangUp() {
        val s = session ?: return
        val phase = _state.value?.phase
        if (!s.caller && phase == CallPhase.INCOMING) {
            decline()
            return
        }
        finish(if (s.caller && phase == CallPhase.CALLING) CallEnd.CANCELLED else CallEnd.HUNG_UP, write = true)
    }

    private suspend fun waitFor(ref: DatabaseReference): DataSnapshot {
        val done = kotlinx.coroutines.CompletableDeferred<DataSnapshot>()
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) done.complete(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {
                done.completeExceptionally(error.toException())
            }
        }
        ref.addValueEventListener(l)
        try {
            return done.await()
        } finally {
            ref.removeEventListener(l)
        }
    }

    private fun drainIce(s: Session) {
        val conn = s.conn ?: return
        s.pendingIce.forEach { rtcIce(conn, it) }
        s.pendingIce.clear()
    }

    private fun watchCall(s: Session) {
        val stateListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val snap = snapshot
                if (session !== s) return
                val id = snap.child("id").getValue(String::class)
                if (id != null && id != s.id) {
                    val otherAt = snap.child("at").getValue(Long::class) ?: 0L
                    if (s.at > 0 && otherAt < s.at - 2_000) return
                    finish(CallEnd.HUNG_UP, write = false)
                    return
                }
                val theirCamera = snap.child("cam").child(s.peer.uid).getValue(Boolean::class)
                if (theirCamera != null) {
                    if (theirCamera && !s.video) s.upgraded = true
                    _state.update { it?.copy(remoteVideo = theirCamera, video = it.video || theirCamera) }
                }
                val reoffer = snap.child("reoffer").child("sdp").getValue(String::class)
                if (!s.caller && reoffer != null && reoffer != s.lastReoffer && s.remoteSet) {
                    s.lastReoffer = reoffer
                    scope.launch {
                        runCatching {
                            val conn = s.conn ?: return@runCatching
                            rtcRemote(conn, "offer", reoffer).await<kotlin.js.JsAny?>()
                            val sdp = rtcAnswer(conn).text()
                            s.callRef.child("reanswer").setValue(mapOf("type" to "answer", "sdp" to sdp))
                        }
                    }
                }
                val reanswer = snap.child("reanswer").child("sdp").getValue(String::class)
                if (s.caller && reanswer != null && reanswer != s.lastReanswer && s.remoteSet) {
                    s.lastReanswer = reanswer
                    scope.launch {
                        runCatching {
                            val conn = s.conn ?: return@runCatching
                            if (rtcState(conn) == "have-local-offer") rtcRemote(conn, "answer", reanswer).await<kotlin.js.JsAny?>()
                        }
                    }
                }
                if (s.caller && !s.connectedOnce) {
                    val reached = snap.child("ringing").getValue(String::class) == s.id
                    if (reached && _state.value?.reached != true) _state.update { it?.copy(reached = true) }
                }
                when (snap.child("state").getValue(String::class)) {
                    "declined" -> if (s.caller) finish(CallEnd.DECLINED, write = false)
                    "busy" -> if (s.caller) finish(CallEnd.BUSY, write = false)
                    "ended" -> finish(CallEnd.HUNG_UP, write = false)
                    null -> if (s.connectedOnce || !s.caller) finish(CallEnd.HUNG_UP, write = false)
                }
                if (s.caller && !s.remoteSet) {
                    val sdp = snap.child("answer").child("sdp").getValue(String::class) ?: return
                    s.remoteSet = true
                    toneStop()
                    s.timeout?.cancel()
                    _state.update { it?.copy(phase = CallPhase.CONNECTING) }
                    scope.launch {
                        runCatching {
                            val conn = s.conn ?: error("no connection")
                            rtcRemote(conn, "answer", sdp).await<kotlin.js.JsAny?>()
                            drainIce(s)
                        }.onFailure { finish(CallEnd.FAILED, write = true) }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        s.callRef.addValueEventListener(stateListener)
        s.listeners += s.callRef to stateListener
        val iceRef = s.callRef.child("ice").child(s.peer.uid)
        val iceListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (session !== s) return
                val sdp = snapshot.child("sdp").getValue(String::class) ?: return
                val json = JsonObject(
                    mapOf(
                        "mid" to JsonPrimitive(snapshot.child("mid").getValue(String::class)),
                        "index" to JsonPrimitive(snapshot.child("index").getValue(Int::class) ?: 0),
                        "sdp" to JsonPrimitive(sdp),
                    ),
                ).toString()
                val conn = s.conn
                if (s.remoteSet && conn != null) rtcIce(conn, json) else s.pendingIce += json
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        iceRef.addChildEventListener(iceListener)
        s.listeners += iceRef to iceListener
    }

    // ------------------------------------------------------------------ the buttons

    override fun toggleMute() {
        val muted = !(_state.value?.muted ?: false)
        session?.conn?.let { rtcMute(it, muted) }
        _state.update { it?.copy(muted = muted) }
    }

    /** A computer has one speaker: nothing to switch. */
    override fun toggleSpeaker() {}

    override fun toggleCamera() {
        val s = session ?: return
        val conn = s.conn ?: return
        val on = if (!s.cameraStarted) true else !(_state.value?.cameraOn ?: true)
        scope.launch {
            runCatching { rtcCamera(conn, on).await<kotlin.js.JsAny?>() }.onFailure { return@launch }
            if (!s.cameraStarted) {
                s.cameraStarted = true
                s.upgraded = true
            }
            me()?.uid?.let { s.callRef.child("cam").child(it).setValue(on) }
            _state.update { it?.copy(video = true, cameraOn = on) }
        }
    }

    override fun switchCamera() {}

    // ------------------------------------------------------------------ the end

    private fun finish(reason: CallEnd, write: Boolean) {
        val s = session ?: return
        if (s.ended) return
        s.ended = true
        toneStop()
        s.timeout?.cancel()
        s.disconnectJob?.cancel()
        val mine = me()
        if (write) {
            s.callRef.child("state").setValue("ended")
            if (s.caller && mine != null) {
                root.child("incomingCall").child(s.peer.uid).removeValue()
                if (!s.connectedOnce) root.child("notify").push().setValue(notifyEntry(mine.uid, s.peer.uid, s.chatId, s.id, s.video, "cancel"))
            }
        }
        val startedAt = _state.value?.startedAt
        val seconds = if (s.connectedOnce && startedAt != null) (now() - startedAt) / 1000 else 0L
        val outcome = when {
            s.connectedOnce -> CallOutcome.ANSWERED
            !s.caller && reason == CallEnd.DECLINED -> CallOutcome.DECLINED
            !s.caller -> CallOutcome.MISSED
            else -> CallOutcome.NOT_ANSWERED
        }
        logEntry(s.id, s.peer, s.video || s.upgraded, if (s.caller) CallDirection.OUT else CallDirection.IN, outcome, seconds)
        if (s.caller && mine != null) logCall(s.peer, s.video, startedAt?.let { (now() - it) / 1000 })
        s.listeners.forEach { (ref, l) ->
            when (l) {
                is ValueEventListener -> ref.removeEventListener(l)
                is ChildEventListener -> ref.removeEventListener(l)
            }
        }
        s.conn?.let { rtcClose(it) }
        rtc.value = null
        _state.update { it?.copy(phase = CallPhase.ENDED, endReason = reason) }
        scope.launch {
            delay(1_800)
            if (session === s) {
                session = null
                _state.value = null
            }
        }
    }

    companion object {
        const val RING_MS = 45_000L
    }
}

/** A browser does not share its screen in a call here. */
@Composable
actual fun rememberScreenShare(service: CallService): (() -> Unit)? = null

/** The other person's picture ([remote]) or our own camera, laid over the page where [modifier] puts it. */
@Composable
actual fun CallVideo(service: CallService, remote: Boolean, modifier: Modifier, overlay: Boolean) {
    val web = service as? WebCallService ?: return
    val conn by web.rtc.collectAsState()
    val id = conn ?: return
    val density = LocalDensity.current.density
    DisposableEffect(id, remote) { onDispose { rtcVideoDrop(id, remote) } }
    Box(
        modifier.onGloballyPositioned { c ->
            val r = c.boundsInWindow()
            var top = r.top / density
            var height = r.height / density
            // A picture filling the screen is laid over the drawing (the browser draws it), so it leaves the name at
            // the top and the buttons at the bottom clear.
            if (height > 420f) {
                top += 120f
                height -= 120f + 230f
            }
            rtcVideo(id, remote, (r.left / density).toDouble(), top.toDouble(), (r.width / density).toDouble(), height.toDouble(), !remote)
        },
    )
}
