package com.qmusic.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the app around the call does: notifications, the foreground service, the screen (set by the activity). */
object CallHooks {
    /** A call came in while the app may be in the background: show the ringing notification. */
    var onIncoming: ((callId: String, peer: ChatUser, video: Boolean) -> Unit)? = null

    /** The ringing stopped (answered, declined, cancelled): take the notification away. */
    var onIncomingGone: (() -> Unit)? = null

    /** A call is going on (keep the app alive with a foreground service), or it is over. */
    var onActive: ((active: Boolean, peer: ChatUser?, video: Boolean) -> Unit)? = null

    /** The call, for the activity (to show over the lock screen while one rings or goes on). */
    var state: StateFlow<CallState?>? = null
        internal set

    /** Whether the app is on screen: a call rings in the app then, and in a notification otherwise. */
    @Volatile
    var appVisible: Boolean = false
        private set
    internal var onVisibility: ((Boolean) -> Unit)? = null

    fun setVisible(visible: Boolean) {
        appVisible = visible
        onVisibility?.invoke(visible)
    }
}

/** What a tapped call notification asks for, from outside the app's screens. */
object ChatCallActions {
    /** Starts the messages backend so that it hears the call that is ringing (a push woke the app). */
    fun wake(context: Context) {
        FirebaseChatBackend.get(context.applicationContext)
    }

    fun decline(context: Context) {
        FirebaseChatBackend.get(context.applicationContext)?.calls?.decline()
    }

    fun hangUp(context: Context) {
        FirebaseChatBackend.get(context.applicationContext)?.calls?.hangUp()
    }
}

/**
 * Voice and video calls between two people: WebRTC (Opus, hardware video codecs, echo cancelling, bitrate that follows
 * the connection), with the offer, the answer and the network candidates passed through the Realtime Database:
 *
 *   calls/{chatId}                 {id, from, to, video, state: ringing|accepted|declined|busy|ended, at}
 *   calls/{chatId}/offer, answer   {type, sdp}
 *   calls/{chatId}/ice/{uid}/{id}  {mid, index, sdp}
 *   incomingCall/{uid}             {id, chatId, from, video, at}: what makes the other phone ring
 *   notify/{id}                    {type: call, ...}: the push that wakes a phone where the app is closed
 *   turn                           the relay's address and short-lived password (written by qmusic-push)
 *
 * The relay (coturn on QMusic's server) carries the call when the two phones cannot reach each other directly, which
 * is common on mobile networks.
 */
internal class FirebaseCallService(
    private val app: Context,
    private val root: DatabaseReference,
    private val me: () -> ChatUser?,
    /** Writes the call's line in the conversation: [seconds] it lasted, or null when it was not answered. */
    private val logCall: (peer: ChatUser, video: Boolean, seconds: Long?) -> Unit,
) : CallService {
    private val auth = FirebaseAuth.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<CallState?>(null)
    override val state: StateFlow<CallState?> = _state.asStateFlow()

    /** The other person's picture (for [CallVideo]). */
    val remoteTrack = MutableStateFlow<VideoTrack?>(null)

    /** Our own camera (for [CallVideo]). */
    val localTrack = MutableStateFlow<VideoTrack?>(null)

    val egl: EglBase by lazy { EglBase.create() }

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(
                JavaAudioDeviceModule.builder(app)
                    .setUseHardwareAcousticEchoCanceler(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    .setUseHardwareNoiseSuppressor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    .createAudioDeviceModule(),
            )
            .createPeerConnectionFactory()
    }

    // ------------------------------------------------------------------ the call in progress

    private inner class Session(
        val id: String,
        val chatId: String,
        val peer: ChatUser,
        val video: Boolean,
        val caller: Boolean,
    ) {
        var pc: PeerConnection? = null
        var audio: AudioTrack? = null
        var videoSource: VideoSource? = null
        var videoTrack: VideoTrack? = null
        var capturer: CameraVideoCapturer? = null
        var helper: SurfaceTextureHelper? = null
        val pendingIce = ArrayList<IceCandidate>()
        var remoteSet = false
        val listeners = ArrayList<Pair<com.google.firebase.database.Query, Any>>()
        var timeout: Job? = null
        var disconnectJob: Job? = null
        var connectedOnce = false
        var ended = false
        var lastReoffer: String? = null
        var lastReanswer: String? = null
        val callRef: DatabaseReference get() = root.child("calls").child(chatId)
    }

    private var session: Session? = null

    init {
        CallHooks.state = _state
        CallHooks.onVisibility = { visible ->
            scope.launch {
                // Opened from the ringing notification: the app rings now, the notification goes.
                if (visible && _state.value?.phase == CallPhase.INCOMING) {
                    CallHooks.onIncomingGone?.invoke()
                    startRinging()
                }
            }
        }
    }

    private var inboxRef: DatabaseReference? = null
    private var inboxListener: ValueEventListener? = null

    /** Starts listening for calls to this account (called when the person signs in). */
    fun listenFor(uid: String?) {
        inboxListener?.let { inboxRef?.removeEventListener(it) }
        inboxListener = null
        if (uid == null) {
            hangUp()
            return
        }
        val ref = root.child("incomingCall").child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) = onInbox(snap)
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        inboxRef = ref
        inboxListener = listener
    }

    private fun onInbox(snap: DataSnapshot) {
        val current = session
        if (!snap.exists()) {
            // The caller gave up before it was answered.
            if (current != null && !current.caller && _state.value?.phase == CallPhase.INCOMING) finish(CallEnd.CANCELLED, write = false)
            return
        }
        val id = snap.child("id").getValue(String::class.java) ?: return
        val chatId = snap.child("chatId").getValue(String::class.java) ?: return
        val from = snap.child("from").getValue(String::class.java) ?: return
        val at = snap.child("at").getValue(Long::class.java) ?: 0L
        val video = snap.child("video").getValue(Boolean::class.java) == true
        if (current?.id == id) return
        if (System.currentTimeMillis() - at > RING_MS + 15_000) {
            snap.ref.removeValue()
            return
        }
        if (current != null) {
            // Already in a call: the other one hears "busy".
            root.child("calls").child(chatId).child("state").setValue("busy")
            snap.ref.removeValue()
            return
        }
        scope.launch {
            val profile = runCatching { root.child("users").child(from).get().await() }.getOrNull()
            val peer = ChatUser(
                uid = from,
                name = profile?.child("name")?.getValue(String::class.java) ?: "?",
                username = profile?.child("username")?.getValue(String::class.java).orEmpty(),
                photo = profile?.child("photo")?.getValue(String::class.java),
            )
            if (session != null) return@launch
            val s = Session(id, chatId, peer, video, caller = false)
            session = s
            _state.value = CallState(id = id, peer = peer, video = video, phase = CallPhase.INCOMING, speaker = video)
            watchCall(s)
            if (CallHooks.appVisible) startRinging() else CallHooks.onIncoming?.invoke(id, peer, video)
            s.timeout = scope.launch {
                delay(RING_MS)
                if (_state.value?.phase == CallPhase.INCOMING) finish(CallEnd.NO_ANSWER, write = false)
            }
        }
    }

    override fun call(peer: ChatUser, video: Boolean) {
        val mine = me() ?: return
        if (session != null) return
        val chatId = chatIdOf(mine.uid, peer.uid)
        val id = UUID.randomUUID().toString()
        val s = Session(id, chatId, peer, video, caller = true)
        session = s
        _state.value = CallState(id = id, peer = peer, video = video, phase = CallPhase.CALLING, speaker = video)
        CallHooks.onActive?.invoke(true, peer, video)
        startAudio(video)
        scope.launch {
            try {
                // A new call replaces what was left of the last one in this conversation.
                s.callRef.setValue(
                    mapOf("id" to id, "from" to mine.uid, "to" to peer.uid, "video" to video, "state" to "ringing", "at" to ServerValue.TIMESTAMP),
                ).await()
                root.updateChildren(
                    mapOf(
                        "incomingCall/${peer.uid}" to mapOf("id" to id, "chatId" to chatId, "from" to mine.uid, "video" to video, "at" to ServerValue.TIMESTAMP),
                        "notify/${root.child("notify").push().key}" to notifyEntry(mine.uid, peer.uid, chatId, id, video, "ring"),
                    ),
                ).await()
                watchCall(s)
                val pc = createPeer(s)
                val offer = pc.awaitCreate(offer = true)
                pc.awaitSetLocal(offer)
                s.callRef.child("offer").setValue(mapOf("type" to offer.type.canonicalForm(), "sdp" to offer.description))
                startRingback()
                s.timeout = scope.launch {
                    delay(RING_MS)
                    if (_state.value?.phase == CallPhase.CALLING) finish(CallEnd.NO_ANSWER, write = true)
                }
            } catch (e: Exception) {
                finish(CallEnd.FAILED, write = true)
            }
        }
    }

    private fun notifyEntry(from: String, to: String, chatId: String, callId: String, video: Boolean, action: String) = mapOf(
        "from" to from,
        "to" to to,
        "chatId" to chatId,
        "type" to "call",
        "callId" to callId,
        "video" to video,
        "action" to action,
        "at" to ServerValue.TIMESTAMP,
    )

    override fun accept() {
        val s = session ?: return
        if (s.caller || _state.value?.phase != CallPhase.INCOMING) return
        stopRinging()
        CallHooks.onIncomingGone?.invoke()
        CallHooks.onActive?.invoke(true, s.peer, s.video)
        s.timeout?.cancel()
        _state.update { it?.copy(phase = CallPhase.CONNECTING) }
        startAudio(s.video)
        scope.launch {
            try {
                root.child("incomingCall").child(me()?.uid ?: return@launch).removeValue()
                val offerSnap = waitFor(s.callRef.child("offer"))
                val pc = createPeer(s)
                pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, offerSnap.child("sdp").getValue(String::class.java)))
                s.remoteSet = true
                drainIce(s)
                val answer = pc.awaitCreate(offer = false)
                pc.awaitSetLocal(answer)
                s.callRef.updateChildren(
                    mapOf("answer" to mapOf("type" to answer.type.canonicalForm(), "sdp" to answer.description), "state" to "accepted"),
                )
            } catch (e: Exception) {
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

    /** Waits until [ref] has a value (the offer, when the answer comes faster than it). */
    private suspend fun waitFor(ref: DatabaseReference): DataSnapshot = suspendCancellableCoroutine { cont ->
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists() && cont.isActive) {
                    ref.removeEventListener(this)
                    cont.resume(snapshot)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (cont.isActive) cont.resumeWithException(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        cont.invokeOnCancellation { ref.removeEventListener(listener) }
    }

    /** The other side's moves: the answer, the state, and their network candidates. */
    private fun watchCall(s: Session) {
        val stateListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (session !== s) return
                val id = snap.child("id").getValue(String::class.java)
                if (id != null && id != s.id) {
                    // Someone started a new call in this conversation: this one is over.
                    finish(CallEnd.HUNG_UP, write = false)
                    return
                }
                // The other camera on or off: their picture or their photo.
                val theirCamera = snap.child("cam").child(s.peer.uid).getValue(Boolean::class.java)
                if (theirCamera != null) _state.update { it?.copy(remoteVideo = theirCamera && remoteTrack.value != null) }
                // After a network change the caller sends a new offer (ICE restart); the other side answers it.
                val reoffer = snap.child("reoffer").child("sdp").getValue(String::class.java)
                if (!s.caller && reoffer != null && reoffer != s.lastReoffer && s.remoteSet) {
                    s.lastReoffer = reoffer
                    scope.launch {
                        runCatching {
                            val pc = s.pc ?: return@runCatching
                            pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, reoffer))
                            val answer = pc.awaitCreate(offer = false)
                            pc.awaitSetLocal(answer)
                            s.callRef.child("reanswer").setValue(mapOf("type" to answer.type.canonicalForm(), "sdp" to answer.description))
                        }
                    }
                }
                val reanswer = snap.child("reanswer").child("sdp").getValue(String::class.java)
                if (s.caller && reanswer != null && reanswer != s.lastReanswer && s.remoteSet) {
                    s.lastReanswer = reanswer
                    scope.launch {
                        runCatching {
                            val pc = s.pc ?: return@runCatching
                            if (pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                                pc.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, reanswer))
                            }
                        }
                    }
                }
                when (snap.child("state").getValue(String::class.java)) {
                    "declined" -> if (s.caller) finish(CallEnd.DECLINED, write = false)
                    "busy" -> if (s.caller) finish(CallEnd.BUSY, write = false)
                    "ended" -> finish(CallEnd.HUNG_UP, write = false)
                    null -> if (s.connectedOnce || !s.caller) finish(CallEnd.HUNG_UP, write = false)
                }
                if (s.caller && !s.remoteSet) {
                    val sdp = snap.child("answer").child("sdp").getValue(String::class.java) ?: return
                    s.remoteSet = true
                    stopRingback()
                    s.timeout?.cancel()
                    _state.update { it?.copy(phase = CallPhase.CONNECTING) }
                    scope.launch {
                        runCatching {
                            s.pc?.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
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
            override fun onChildAdded(snap: DataSnapshot, previous: String?) {
                if (session !== s) return
                val sdp = snap.child("sdp").getValue(String::class.java) ?: return
                val candidate = IceCandidate(
                    snap.child("mid").getValue(String::class.java),
                    snap.child("index").getValue(Int::class.java) ?: 0,
                    sdp,
                )
                if (s.remoteSet && s.pc != null) s.pc?.addIceCandidate(candidate) else s.pendingIce += candidate
            }

            override fun onChildChanged(snap: DataSnapshot, previous: String?) {}
            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, previous: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        iceRef.addChildEventListener(iceListener)
        s.listeners += iceRef to iceListener
    }

    private fun drainIce(s: Session) {
        val pc = s.pc ?: return
        s.pendingIce.forEach { pc.addIceCandidate(it) }
        s.pendingIce.clear()
    }

    // ------------------------------------------------------------------ WebRTC

    private suspend fun iceServers(): List<PeerConnection.IceServer> {
        val servers = ArrayList<PeerConnection.IceServer>()
        runCatching {
            val turn = root.child("turn").get().await()
            val urls = turn.child("urls").children.mapNotNull { it.getValue(String::class.java) }
            val user = turn.child("username").getValue(String::class.java)
            val pass = turn.child("credential").getValue(String::class.java)
            if (urls.isNotEmpty() && user != null && pass != null) {
                servers += PeerConnection.IceServer.builder(urls).setUsername(user).setPassword(pass).createIceServer()
            }
        }
        servers += PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer()
        return servers
    }

    private suspend fun createPeer(s: Session): PeerConnection {
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        val pc = factory.createPeerConnection(config, observer(s)) ?: error("no peer connection")
        s.pc = pc

        val audioSource = factory.createAudioSource(
            MediaConstraints().apply {
                mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
                mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
                mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
                mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
            },
        )
        val audio = factory.createAudioTrack("qm-audio", audioSource)
        audio.setEnabled(_state.value?.muted != true)
        s.audio = audio
        pc.addTrack(audio, listOf("qm"))

        if (s.video) {
            startCamera(s, front = true)
            s.videoTrack?.let { track ->
                val sender = pc.addTrack(track, listOf("qm"))
                // Up to 2.5 Mbit/s for a sharp picture; WebRTC lowers it by itself when the line is weaker.
                runCatching {
                    val p = sender.parameters
                    p.encodings.forEach { it.maxBitrateBps = 2_500_000 }
                    sender.parameters = p
                }
            }
        }
        return pc
    }

    private fun startCamera(s: Session, front: Boolean) {
        val enumerator = Camera2Enumerator(app)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == front } ?: enumerator.deviceNames.firstOrNull() ?: return
        val capturer = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("qm-camera", egl.eglBaseContext)
        val source = factory.createVideoSource(false)
        capturer.initialize(helper, app, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        val track = factory.createVideoTrack("qm-video", source)
        s.capturer = capturer
        s.helper = helper
        s.videoSource = source
        s.videoTrack = track
        localTrack.value = track
    }

    private fun observer(s: Session) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val uid = me()?.uid ?: return
            s.callRef.child("ice").child(uid).push().setValue(
                mapOf("mid" to candidate.sdpMid, "index" to candidate.sdpMLineIndex, "sdp" to candidate.sdp),
            )
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            scope.launch {
                if (session !== s) return@launch
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        s.disconnectJob?.cancel()
                        _state.update { it?.copy(phase = CallPhase.ACTIVE, startedAt = it.startedAt ?: System.currentTimeMillis(), weak = false) }
                        s.connectedOnce = true
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        _state.update { it?.copy(weak = true) }
                        // A tunnel or a switch from Wi-Fi to mobile: give it a moment, ask for a new route, then give up.
                        s.disconnectJob?.cancel()
                        s.disconnectJob = scope.launch {
                            delay(3_000)
                            if (s.caller) s.pc?.restartIce()
                            delay(12_000)
                            if (session === s) finish(CallEnd.FAILED, write = true)
                        }
                    }
                    PeerConnection.PeerConnectionState.FAILED -> finish(CallEnd.FAILED, write = true)
                    else -> Unit
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) {
                scope.launch {
                    remoteTrack.value = track
                    _state.update { it?.copy(remoteVideo = true) }
                }
            }
        }

        override fun onRenegotiationNeeded() {
            // The ICE restart after a network change needs a new offer from the caller.
            if (!s.caller || !s.remoteSet) return
            scope.launch {
                runCatching {
                    val pc = s.pc ?: return@runCatching
                    if (pc.signalingState() != PeerConnection.SignalingState.STABLE) return@runCatching
                    val offer = pc.awaitCreate(offer = true)
                    pc.awaitSetLocal(offer)
                    s.callRef.child("reoffer").setValue(mapOf("type" to offer.type.canonicalForm(), "sdp" to offer.description))
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    // ------------------------------------------------------------------ the buttons

    override fun toggleMute() {
        val muted = !(_state.value?.muted ?: false)
        session?.audio?.setEnabled(!muted)
        _state.update { it?.copy(muted = muted) }
    }

    override fun toggleSpeaker() {
        val on = !(_state.value?.speaker ?: false)
        routeAudio(on)
        _state.update { it?.copy(speaker = on) }
    }

    override fun toggleCamera() {
        val s = session ?: return
        val on = !(_state.value?.cameraOn ?: true)
        s.videoTrack?.setEnabled(on)
        if (on) runCatching { s.capturer?.startCapture(1280, 720, 30) } else runCatching { s.capturer?.stopCapture() }
        me()?.uid?.let { s.callRef.child("cam").child(it).setValue(on) }
        _state.update { it?.copy(cameraOn = on) }
    }

    override fun switchCamera() {
        val s = session ?: return
        s.capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) {
                scope.launch { _state.update { it?.copy(frontCamera = front) } }
            }

            override fun onCameraSwitchError(error: String?) {}
        })
    }

    // ------------------------------------------------------------------ the end

    private fun finish(reason: CallEnd, write: Boolean) {
        val s = session ?: return
        if (s.ended) return
        s.ended = true
        stopRinging()
        stopRingback()
        CallHooks.onIncomingGone?.invoke()
        s.timeout?.cancel()
        s.disconnectJob?.cancel()
        val mine = me()
        if (write) {
            s.callRef.child("state").setValue("ended")
            if (s.caller && mine != null) {
                root.child("incomingCall").child(s.peer.uid).removeValue()
                if (!s.connectedOnce) {
                    root.child("notify").push().setValue(notifyEntry(mine.uid, s.peer.uid, s.chatId, s.id, s.video, "cancel"))
                }
            }
        }
        // The caller writes the line in the conversation ("Voice call · 2:31", or a missed call).
        if (s.caller && mine != null) {
            val started = _state.value?.startedAt
            logCall(s.peer, s.video, started?.let { (System.currentTimeMillis() - it) / 1000 })
        }
        s.listeners.forEach { (ref, listener) ->
            when (listener) {
                is ValueEventListener -> ref.removeEventListener(listener)
                is ChildEventListener -> ref.removeEventListener(listener)
            }
        }
        runCatching { s.capturer?.stopCapture() }
        runCatching { s.capturer?.dispose() }
        runCatching { s.videoSource?.dispose() }
        runCatching { s.helper?.dispose() }
        runCatching { s.pc?.close() }
        runCatching { s.pc?.dispose() }
        localTrack.value = null
        remoteTrack.value = null
        stopAudio()
        CallHooks.onActive?.invoke(false, null, false)
        _state.update { it?.copy(phase = CallPhase.ENDED, endReason = reason) }
        scope.launch {
            delay(END_SHOWN_MS)
            if (session === s) {
                session = null
                _state.value = null
            }
        }
    }

    // ------------------------------------------------------------------ sound

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private var proximity: PowerManager.WakeLock? = null

    private fun startAudio(video: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Taking the focus pauses the music (and a video) for the call.
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
                .also { audioManager.requestAudioFocus(it) }
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeAudio(video)
        if (!video) {
            // A voice call held to the ear turns the screen off (no cheek on the buttons).
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "qmusic:call").also { it.acquire(4 * 60 * 60 * 1000L) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun routeAudio(speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            // A headset or Bluetooth device in use wins over the speaker and the earpiece.
            val headset = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            val target = when {
                speaker -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                headset != null -> headset
                else -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (target != null) audioManager.setCommunicationDevice(target) else audioManager.clearCommunicationDevice()
        } else {
            audioManager.isSpeakerphoneOn = speaker
        }
    }

    private fun stopAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
        proximity?.let { if (it.isHeld) it.release() }
        proximity = null
    }

    private var ringtone: android.media.Ringtone? = null
    private var ringback: ToneGenerator? = null

    private fun startRinging() {
        runCatching {
            ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }
        val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0)) }
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel() }
    }

    private fun startRingback() {
        runCatching { ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) } }
    }

    private fun stopRingback() {
        runCatching { ringback?.stopTone() }
        runCatching { ringback?.release() }
        ringback = null
    }

    companion object {
        /** How long a call rings before it counts as not answered. */
        const val RING_MS = 45_000L
        private const val END_SHOWN_MS = 1_800L
    }
}

// ------------------------------------------------------------------ WebRTC's callbacks as suspend functions

private suspend fun PeerConnection.awaitCreate(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
    val observer = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            if (cont.isActive) cont.resume(sdp)
        }

        override fun onCreateFailure(error: String?) {
            if (cont.isActive) cont.resumeWithException(IllegalStateException(error))
        }

        override fun onSetSuccess() {}
        override fun onSetFailure(error: String?) {}
    }
    if (offer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
}

private suspend fun PeerConnection.awaitSet(sdp: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { cont ->
    val observer = object : SdpObserver {
        override fun onSetSuccess() {
            if (cont.isActive) cont.resume(Unit)
        }

        override fun onSetFailure(error: String?) {
            if (cont.isActive) cont.resumeWithException(IllegalStateException(error))
        }

        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onCreateFailure(error: String?) {}
    }
    if (local) setLocalDescription(observer, sdp) else setRemoteDescription(observer, sdp)
}

private suspend fun PeerConnection.awaitSetLocal(sdp: SessionDescription) = awaitSet(sdp, local = true)
private suspend fun PeerConnection.awaitSetRemote(sdp: SessionDescription) = awaitSet(sdp, local = false)

// ------------------------------------------------------------------ the screen

@Composable
actual fun CallVideo(service: CallService, remote: Boolean, modifier: Modifier) {
    val calls = service as? FirebaseCallService ?: return
    val track by (if (remote) calls.remoteTrack else calls.localTrack).collectAsState()
    val state by calls.state.collectAsState()
    val mirror = !remote && state?.frontCamera != false
    var rendererView by remember { androidx.compose.runtime.mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(calls.egl.eglBaseContext, null)
                setScalingType(if (remote) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (!remote) setZOrderMediaOverlay(true)
                rendererView = this
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = { view ->
            track?.let { t -> runCatching { t.removeSink(view) } }
            view.release()
        },
    )
    DisposableEffect(track, rendererView) {
        val view = rendererView
        val t = track
        if (view != null && t != null) t.addSink(view)
        onDispose { if (view != null && t != null) runCatching { t.removeSink(view) } }
    }
}

private class Ref<T>(var value: T)

private fun <T> mutableRef(initial: T) = Ref(initial)

@Composable
actual fun rememberCallPermissions(): (video: Boolean, onResult: (Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableRef<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pending.value?.invoke(result.values.all { it })
        pending.value = null
    }
    return remember(context) {
        { video, onResult ->
            val needed = listOfNotNull(Manifest.permission.RECORD_AUDIO, if (video) Manifest.permission.CAMERA else null)
                .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isEmpty()) {
                onResult(true)
            } else {
                pending.value = onResult
                launcher.launch(needed.toTypedArray())
            }
        }
    }
}
