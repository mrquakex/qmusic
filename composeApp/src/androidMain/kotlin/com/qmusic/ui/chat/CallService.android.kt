package com.qmusic.ui.chat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.withTimeoutOrNull
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

    /** A call rang out unanswered while the app was out of sight: the "missed call" notification. */
    var onMissed: ((peer: ChatUser, video: Boolean) -> Unit)? = null

    /** Someone called while the person was on another call (they heard "busy"): "X called". */
    var onBusy: ((peer: ChatUser, video: Boolean) -> Unit)? = null

    /** The person joined (or started) a group's call here, or left it (null): that group's ringing notification goes. */
    var onGroupJoined: ((groupId: String?) -> Unit)? = null

    /** A call is going on (keep the app alive with a foreground service), or it is over. */
    var onActive: ((active: Boolean, peer: ChatUser?, video: Boolean) -> Unit)? = null

    /** Observable mirror of [groupActive] for MainActivity (lock screen / screen-on). */
    val groupActiveFlow = MutableStateFlow(false)

    /** A group call is going on here (the call's service stays up for it). */
    @Volatile
    var groupActive: Boolean = false
        set(value) {
            field = value
            groupActiveFlow.value = value
        }

    /** The screen is shown in the call now, or no longer: the foreground service says so to Android (needed first). */
    var onScreenShare: ((sharing: Boolean) -> Unit)? = null

    /**
     * Waits for the foreground service to say "screen" to Android (Android 14 refuses to read the screen before):
     * the service completes it with true once it is so, false when the phone did not take it.
     */
    @Volatile
    var screenServiceReady: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /** The call, for the activity (to show over the lock screen while one rings or goes on). */
    var state: StateFlow<CallState?>? = null
        internal set

    /** The same, to follow (the "online" mark goes with it). */
    val onScreen = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Whether the app is on screen: a call rings in the app then, and in a notification otherwise. */
    @Volatile
    var appVisible: Boolean = false
        private set
    internal var onVisibility: ((Boolean) -> Unit)? = null

    fun setVisible(visible: Boolean) {
        appVisible = visible
        onScreen.value = visible
        onVisibility?.invoke(visible)
        trace?.invoke(if (visible) "app on screen" else "app hidden")
    }

    /** The phone's state that decides whether a ringtone is heard, in one line (for the call's trail). */
    fun ringDiagnosis(context: Context): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val mode = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        val dnd = when (nm?.currentInterruptionFilter) {
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
            android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            android.app.NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
            else -> "?"
        }
        val ring = audio.getStreamVolume(AudioManager.STREAM_RING)
        val ringMax = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
        val notif = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val inCall = runCatching { audio.mode }.getOrDefault(-1)
        return "ringer $mode, dnd $dnd, ring vol $ring/$ringMax, notif vol $notif, audio mode $inCall, " +
            "notifications ${if (nm?.areNotificationsEnabled() == true) "on" else "OFF"}"
    }

    /** Notes something the app did into the call going on (its trail shows why a call ended). */
    @Volatile
    var trace: ((String) -> Unit)? = null
        internal set
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
        val backend = FirebaseChatBackend.get(context.applicationContext) ?: return
        backend.calls.hangUp()
        // The notification is the same for a group call: leaving it is what "hang up" means there.
        if (CallHooks.groupActive) backend.groupCalls.leave()
    }

    /** A call that rang here while the app was not running and the caller gave up: it goes in the list of calls. */
    fun logMissed(context: Context, peer: ChatUser, video: Boolean, callId: String) {
        val backend = FirebaseChatBackend.get(context.applicationContext) ?: return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            backend.awaitMe() ?: return@launch
            backend.logCall(callId, peer, video, CallDirection.IN, CallOutcome.MISSED, 0)
        }
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
    /** Writes the call in the person's list of calls. */
    private val logEntry: (id: String, peer: ChatUser, video: Boolean, direction: CallDirection, outcome: CallOutcome, seconds: Long) -> Unit = { _, _, _, _, _, _ -> },
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

    internal val factory: PeerConnectionFactory by lazy {
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
        var statsJob: Job? = null
        var connectedOnce = false
        var ended = false
        var lastReoffer: String? = null
        var lastReanswer: String? = null
        var unreachableJob: Job? = null
        var lastState: String? = "-"
        /** When this call started (server time): a record of an older call, still in the cache, is not a new one. */
        var at = 0L
        /** Where our picture goes: there from the start, in voice calls too, so the camera can come on mid-call. */
        var videoSender: org.webrtc.RtpSender? = null
        /** A voice call where a camera came on. */
        var upgraded = false
        var audioSource: org.webrtc.AudioSource? = null
        var audioSender: org.webrtc.RtpSender? = null
        /** The last "start the route again" the other side asked for (the caller does it). */
        var lastRestartAsk = 0L
        /** Our picture stopped by itself for a weak line, and since when the line has been bad or good. */
        var autoPaused = false
        var badSince = 0L
        var goodSince = 0L
        /** The other side's last emoji (its stamp). */
        var lastReaction = -1L
        /** Our screen, while it is shown. */
        var screenCapturer: org.webrtc.ScreenCapturerAndroid? = null
        var screenHelper: SurfaceTextureHelper? = null
        var screenSource: VideoSource? = null
        var screenTrack: VideoTrack? = null
        /** The call went on as a group call (a third person was added): it ends here without "ended". */
        var moved = false
        /** Whether the foreground service has already been started with camera type for this call. */
        var serviceHasVideo = video
        /** Prior mute/camera/screen-share state saved while the system puts the call on hold. */
        var wasMutedBeforeHold = false
        var wasCameraOnBeforeHold = true
        var wasScreenSharingBeforeHold = false
        val callRef: DatabaseReference get() = root.child("calls").child(chatId)

        /** What happened in this call, in order (sent to callDebug when it ends, to find why a call failed). */
        val trail = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val startedAt = System.currentTimeMillis()
        fun note(what: String) {
            if (trail.size < 80) trail.add("+${System.currentTimeMillis() - startedAt}ms $what")
            android.util.Log.i("QeyfCall", "${id.take(8)} ${if (caller) "out" else "in"}: $what")
        }
    }

    private var session: Session? = null

    /** A third person was added: the call goes on as this group call (set by the backend, which has the group calls). */
    var onMoveToRoom: ((room: ChatUser, video: Boolean) -> Unit)? = null

    /** The phone's own call system (Core-Telecom), where the phone has one: see [CallTelecom]. */
    private val telecom = CallTelecom(app, scope).also { t ->
        t.onRemoteHangUp = { scope.launch { hangUp() } }
        t.onMuteChanged = { muted ->
            scope.launch {
                val s = session ?: return@launch
                val st = _state.value ?: return@launch
                if (st.held) {
                    s.wasMutedBeforeHold = muted
                } else if (st.muted != muted) {
                    s.audio?.setEnabled(!muted)
                    _state.update { it?.copy(muted = muted) }
                }
            }
        }
        t.onHoldChanged = { held ->
            scope.launch {
                val s = session ?: return@launch
                if (s.ended) return@launch
                val st = _state.value ?: return@launch
                if (held == st.held) return@launch
                if (held) {
                    s.note("call put on hold")
                    s.wasMutedBeforeHold = st.muted
                    s.wasCameraOnBeforeHold = st.cameraOn
                    s.wasScreenSharingBeforeHold = st.sharingScreen
                    s.audio?.setEnabled(false)
                    if (st.video && s.videoTrack != null && st.cameraOn) {
                        s.videoTrack?.setEnabled(false)
                        if (s.screenTrack == null) runCatching { s.capturer?.stopCapture() }
                        me()?.uid?.let { s.callRef.child("cam").child(it).setValue(false) }
                    }
                    if (s.screenTrack != null) s.screenTrack?.setEnabled(false)
                    _state.update {
                        it?.copy(
                            held = true,
                            muted = true,
                            cameraOn = if (it.video && s.videoTrack != null) false else it.cameraOn,
                        )
                    }
                    updateProximity()
                } else {
                    s.note("call resumed from hold")
                    val restoreMuted = s.wasMutedBeforeHold
                    val restoreCam = s.wasCameraOnBeforeHold
                    requestCallAudioFocus()
                    runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
                    s.audio?.setEnabled(!restoreMuted)
                    if (s.screenTrack != null) {
                        s.screenTrack?.setEnabled(true)
                    } else if (st.video && s.videoTrack != null) {
                        s.videoTrack?.setEnabled(restoreCam)
                        if (restoreCam) runCatching { s.capturer?.startCapture(1280, 720, 30) }
                        me()?.uid?.let { s.callRef.child("cam").child(it).setValue(restoreCam) }
                    }
                    _state.update {
                        it?.copy(
                            held = false,
                            muted = restoreMuted,
                            cameraOn = if (it.video && s.videoTrack != null) restoreCam else it.cameraOn,
                        )
                    }
                    if (!telecom.active) {
                        routeAudio(wantSpeaker)
                        publishDevices()
                    }
                    updateProximity()
                }
            }
        }
        t.onUnavailable = {
            scope.launch {
                if (session != null) {
                    routeAudio(wantSpeaker)
                    publishDevices()
                    updateProximity()
                }
            }
        }
        t.onRoutes = { routes, current ->
            scope.launch {
                _state.update { st -> st?.copy(routes = routes, route = current, speaker = routes.firstOrNull { it.id == current }?.kind == AudioRouteKind.SPEAKER) }
                updateProximity()
            }
        }
    }

    /** How far the phone's clock is from the server's: "is this call still ringing" is asked in the server's time. */
    @Volatile
    private var serverOffset = 0L

    private fun serverNow() = System.currentTimeMillis() + serverOffset

    init {
        CallHooks.trace = { what -> session?.note(what) }
        runCatching {
            root.database.getReference(".info/serverTimeOffset").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    serverOffset = snap.getValue(Long::class.java) ?: 0L
                }

                override fun onCancelled(error: DatabaseError) {}
            })
        }
        CallHooks.state = _state
        CallHooks.onVisibility = { visible ->
            scope.launch {
                // Opened from the ringing notification: the app rings now, the notification goes.
                if (visible && _state.value?.phase == CallPhase.INCOMING) {
                    CallHooks.onIncomingGone?.invoke()
                    // Removing the notification stopped the ringing: it goes on here.
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
        var current = session
        // A call that is over (still on screen for a moment) does not make this phone "busy" for the next one.
        if (current != null && current.ended) {
            session = null
            _state.value = null
            current = null
        }
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
        if (serverNow() - at > RING_MS + 15_000) {
            snap.ref.removeValue()
            return
        }
        if (current != null || CallHooks.groupActive) {
            // Already in a call (a group's too): the other one hears "busy", and this person is told who called.
            root.child("calls").child(chatId).child("state").setValue("busy")
            snap.ref.removeValue()
            scope.launch {
                val profile = runCatching { root.child("users").child(from).get().await() }.getOrNull()
                val peer = ChatUser(
                    uid = from,
                    name = profile?.child("name")?.getValue(String::class.java) ?: "?",
                    username = profile?.child("username")?.getValue(String::class.java).orEmpty(),
                    photo = profile?.child("photo")?.getValue(String::class.java),
                )
                CallHooks.onBusy?.invoke(peer, video)
                logEntry(id, peer, video, CallDirection.IN, CallOutcome.MISSED, 0)
            }
            return
        }
        scope.launch {
            val profile = runCatching { root.child("users").child(from).get().await() }.getOrNull()
            val peer = ChatUser(
                uid = from,
                name = profile?.child("name")?.getValue(String::class.java) ?: "?",
                username = profile?.child("username")?.getValue(String::class.java).orEmpty(),
                // A photo shown to friends only is kept in privateProfile (the caller is a friend, so it can be read).
                photo = profile?.child("photo")?.getValue(String::class.java)
                    ?: runCatching { root.child("privateProfile").child(from).child("photo").get().await().getValue(String::class.java) }.getOrNull(),
            )
            if (session != null) return@launch
            // Ringing from a clean start: no call of a moment ago left with the system or in the sound mode (it would
            // keep this ringtone silent).
            if (!CallHooks.groupActive) {
                telecom.ensureIdle()
                if (audioManager.mode != AudioManager.MODE_NORMAL) runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
            }
            val s = Session(id, chatId, peer, video, caller = false)
            s.at = at
            session = s
            s.note("incoming from ${peer.name}, app visible: ${CallHooks.appVisible}")
            _state.value = CallState(id = id, peer = peer, video = video, phase = CallPhase.INCOMING, speaker = video)
            // The caller sees "Ringing…" from here on: this phone has the call.
            s.callRef.child("ringing").setValue(id)
            watchCall(s)
            MusicDuringCall.pause()
            // It rings in any case (the app plays it, open or not); outside the app the call's notification shows too.
            startRinging()
            if (!CallHooks.appVisible) CallHooks.onIncoming?.invoke(id, peer, video)
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
        s.at = serverNow()
        session = s
        s.note("calling ${peer.name}, video: $video")
        _state.value = CallState(id = id, peer = peer, video = video, phase = CallPhase.CALLING, speaker = video)
        CallHooks.onActive?.invoke(true, peer, video)
        startAudio(video)
        // The phone's own call system gets this call only once the other side has answered (see CONNECTED): a call
        // dialled and hung up at once can then never be left "connecting" there, which blocks every next call.
        fetchPeerTone(id, peer)
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
                s.note("call setup failed: ${e.javaClass.simpleName}: ${e.message}")
                finish(CallEnd.FAILED, write = true)
            }
        }
    }

    /**
     * Answered (or they answered) but the two phones never get through: the call ended on the other side while this
     * phone missed it (no connection at that moment), or the other side never sent its part. It does not stay on
     * "Connecting…" for ever: after a while it ends as failed.
     */
    private fun connectWatchdog(s: Session) {
        s.timeout = scope.launch {
            delay(CONNECT_MS)
            if (session === s && !s.ended && _state.value?.phase == CallPhase.CONNECTING) {
                s.note("never connected in ${CONNECT_MS / 1000} s")
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
        s.note("accept, phase ${_state.value?.phase}")
        if (s.caller || _state.value?.phase != CallPhase.INCOMING) return
        stopRinging()
        CallHooks.onIncomingGone?.invoke()
        CallHooks.onActive?.invoke(true, s.peer, s.video)
        s.timeout?.cancel()
        _state.update { it?.copy(phase = CallPhase.CONNECTING) }
        connectWatchdog(s)
        startAudio(s.video)
        telecom.start(s.peer, s.video, incoming = true)
        scope.launch {
            try {
                root.child("incomingCall").child(me()?.uid ?: return@launch).removeValue()
                // The call may have ended a moment before the answer: then there is nothing to answer.
                // Read from the server, at most a few seconds (without a connection it would wait for ever).
                val now = withTimeoutOrNull(8_000L) { s.callRef.get().await() }
                if (now == null) {
                    s.note("answer: call not readable")
                    finish(CallEnd.FAILED, write = false)
                    return@launch
                }
                val nowId = now.child("id").getValue(String::class.java)
                val nowState = now.child("state").getValue(String::class.java)
                if (nowId != s.id || nowState == "ended" || nowState == "declined" || nowState == "busy") {
                    s.note("answer too late: call $nowId is $nowState")
                    finish(CallEnd.CANCELLED, write = false)
                    return@launch
                }
                val offerSnap = waitFor(s.callRef.child("offer"))
                s.note("offer received")
                val pc = createPeer(s)
                pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, offerSnap.child("sdp").getValue(String::class.java)))
                // A voice call: the caller's picture slot is taken both ways, so either camera can come on later.
                if (!s.video) {
                    pc.transceivers.firstOrNull { it.mediaType == org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.let { t ->
                        t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                        s.videoSender = t.sender
                    }
                }
                s.remoteSet = true
                drainIce(s)
                val answer = pc.awaitCreate(offer = false)
                pc.awaitSetLocal(answer)
                s.callRef.updateChildren(
                    mapOf("answer" to mapOf("type" to answer.type.canonicalForm(), "sdp" to answer.description), "state" to "accepted"),
                )
                s.note("answer sent")
            } catch (e: Exception) {
                s.note("answer failed: ${e.javaClass.simpleName}: ${e.message}")
                finish(CallEnd.FAILED, write = true)
            }
        }
    }

    override fun decline() {
        val s = session ?: return
        if (s.caller) return
        s.note("decline")
        s.callRef.child("state").setValue("declined")
        me()?.uid?.let { root.child("incomingCall").child(it).removeValue() }
        finish(CallEnd.DECLINED, write = false)
    }

    override fun hangUp() {
        val s = session ?: return
        val phase = _state.value?.phase
        s.note("hangUp in $phase")
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
                    // The cache can show the conversation's last call for a moment before this one arrives: an older
                    // call is not a new one taking over, so it is waited out.
                    val otherAt = snap.child("at").getValue(Long::class.java) ?: 0L
                    if (s.at > 0 && otherAt < s.at - 2_000) {
                        s.note("older call $id still shown, waiting")
                        return
                    }
                    s.note("another call took the conversation: $id")
                    // Someone started a new call in this conversation: this one is over.
                    finish(CallEnd.HUNG_UP, write = false)
                    return
                }
                // The other camera on or off: their picture or their photo.
                val theirCamera = snap.child("cam").child(s.peer.uid).getValue(Boolean::class.java)
                if (theirCamera != null) {
                    // In a voice call their camera coming on makes it a video call here too.
                    if (theirCamera && !s.video && !s.upgraded) s.upgraded = true
                    _state.update { it?.copy(remoteVideo = theirCamera && remoteTrack.value != null, video = it.video || theirCamera) }
                    if (theirCamera) updateProximity()
                }
                // A third person was added on either phone: both go on in the group call it made.
                val moveTo = snap.child("moveTo").getValue(String::class.java)
                if (moveTo != null && ROOM_ID.matches(moveTo) && s.connectedOnce && !s.moved) {
                    moveToRoom(s, moveTo, _state.value?.video == true)
                    return
                }
                // The other side lost the connection and asks for a new route: the caller starts it (ICE restart).
                val restartAsk = snap.child("restartAsk").getValue(Long::class.java) ?: 0L
                if (s.caller && restartAsk > s.lastRestartAsk) {
                    s.lastRestartAsk = restartAsk
                    if (s.remoteSet) {
                        s.note("restart asked by the other side")
                        runCatching { s.pc?.restartIce() }
                    }
                }
                // Their picture stopped for a weak line, or their screen instead of their camera.
                val theirPause = snap.child("paused").child(s.peer.uid).getValue(Boolean::class.java) == true
                val theirScreen = snap.child("screen").child(s.peer.uid).getValue(Boolean::class.java) == true
                if (_state.value?.remotePaused != theirPause || _state.value?.remoteScreen != theirScreen) {
                    _state.update { it?.copy(remotePaused = theirPause, remoteScreen = theirScreen) }
                }
                // An emoji from them floats up.
                val react = snap.child("react").child(s.peer.uid)
                val reactAt = react.child("n").getValue(Long::class.java)
                val emoji = react.child("e").getValue(String::class.java)
                if (reactAt != null && emoji != null && reactAt != s.lastReaction && emoji in CALL_REACTIONS) {
                    s.lastReaction = reactAt
                    _state.update { it?.copy(reaction = CallReaction(reactAt * 2 + 1, emoji, mine = false)) }
                }
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
                // Calling: their phone rings ("Ringing…"), or the server could not reach it at all ("Unavailable" after a
                // short wait, unless it shows up meanwhile through the open app).
                if (s.caller && !s.connectedOnce) {
                    val reached = snap.child("ringing").getValue(String::class.java) == s.id
                    if (reached && _state.value?.reached != true) _state.update { it?.copy(reached = true) }
                    val push = snap.child("push")
                    val pushFailed = push.child("id").getValue(String::class.java) == s.id && (push.child("ok").getValue(Long::class.java) ?: 1L) == 0L
                    if (!reached && pushFailed && s.unreachableJob == null) {
                        s.unreachableJob = scope.launch {
                            delay(UNREACHABLE_GRACE_MS)
                            if (session === s && _state.value?.phase == CallPhase.CALLING && _state.value?.reached != true) {
                                finish(CallEnd.UNREACHABLE, write = true)
                            }
                        }
                    }
                }
                val st = snap.child("state").getValue(String::class.java)
                if (st != s.lastState) {
                    s.note("state ${s.lastState} -> $st")
                    s.lastState = st
                }
                when (st) {
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
                    connectWatchdog(s)
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

    internal suspend fun iceServers(): List<PeerConnection.IceServer> {
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

        s.audioSender = pc.addTrack(makeAudio(s, _state.value?.noiseSuppression != false), listOf("qm"))

        if (!s.video && s.caller) {
            // A voice call keeps a picture slot open (sending nothing), so a camera can come on without a new offer.
            s.videoSender = pc.addTransceiver(
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV),
            ).sender
        }
        if (s.video) {
            startCamera(s, front = true)
            s.videoTrack?.let { track ->
                val sender = pc.addTrack(track, listOf("qm"))
                s.videoSender = sender
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

    /** Our voice: echo cancelled and evened out always; the noise around cleaned away when [noise] is on. */
    private fun makeAudio(s: Session, noise: Boolean): AudioTrack {
        val source = factory.createAudioSource(
            MediaConstraints().apply {
                mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
                mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", noise.toString())
                mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
                mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", noise.toString())
                mandatory += MediaConstraints.KeyValuePair("googTypingNoiseDetection", noise.toString())
            },
        )
        val audio = factory.createAudioTrack("qm-audio-" + System.nanoTime(), source)
        audio.setEnabled(_state.value?.muted != true)
        s.audioSource = source
        s.audio = audio
        return audio
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
                s.note("connection $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        s.disconnectJob?.cancel()
                        s.disconnectJob = null
                        _state.update { it?.copy(phase = CallPhase.ACTIVE, startedAt = it.startedAt ?: System.currentTimeMillis(), weak = false, reconnecting = false) }
                        // "Connected": once, the first time (not after a reconnect).
                        if (!s.connectedOnce) QeyfSounds.playSmall(app, SmallSound.CONNECTED, call = true)
                        // Our call, answered: handed to the phone's call system now, as a call that is already on.
                        if (!s.connectedOnce && s.caller) telecom.start(s.peer, _state.value?.video ?: s.video, incoming = false, active = true)
                        s.connectedOnce = true
                        if (_state.value?.held != true) telecom.setActive()
                        watchStats(s)
                    }
                    // A tunnel or a switch from Wi-Fi to mobile: "Reconnecting…", a new route asked for again and again,
                    // and only after [RECONNECT_MS] without one the call ends. Before it ever connected, a failure ends it.
                    PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.FAILED -> {
                        if (!s.connectedOnce) {
                            if (newState == PeerConnection.PeerConnectionState.FAILED) finish(CallEnd.FAILED, write = true)
                        } else {
                            reconnect(s, failed = newState == PeerConnection.PeerConnectionState.FAILED)
                        }
                    }
                    else -> Unit
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) {
                scope.launch {
                    remoteTrack.value = track
                    // A voice call's picture slot: shown only once their camera is on (they say so).
                    if (s.video) _state.update { it?.copy(remoteVideo = true) }
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

    // ------------------------------------------------------------------ keeping the call through a bad line

    private fun reconnect(s: Session, failed: Boolean) {
        _state.update { it?.copy(weak = true, reconnecting = true) }
        if (s.disconnectJob?.isActive == true) {
            // Failed for good meanwhile: ask again at once.
            if (failed) askRestart(s)
            return
        }
        s.disconnectJob = scope.launch {
            delay(if (failed) 0L else 1_500L)
            val until = System.currentTimeMillis() + RECONNECT_MS
            while (session === s && !s.ended && System.currentTimeMillis() < until) {
                askRestart(s)
                delay(6_000)
            }
            if (session === s && !s.ended && _state.value?.reconnecting == true) finish(CallEnd.FAILED, write = true)
        }
    }

    /** A new route for the call: the caller starts it; the other side asks the caller to. */
    private fun askRestart(s: Session) {
        if (!s.remoteSet) return
        s.note("ice restart (${if (s.caller) "start" else "ask"})")
        if (s.caller) runCatching { s.pc?.restartIce() } else s.callRef.child("restartAsk").setValue(ServerValue.TIMESTAMP)
    }

    /** The phone moved to another network (Wi-Fi to mobile): the call takes a new route straight away. */
    private var lastNetwork: android.net.Network? = null
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            scope.launch {
                val before = lastNetwork
                lastNetwork = network
                val s = session ?: return@launch
                if (before != null && before != network && s.connectedOnce && !s.ended) askRestart(s)
            }
        }
    }

    private fun watchNetwork(on: Boolean) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        if (on) {
            lastNetwork = null
            runCatching { cm.registerDefaultNetworkCallback(networkCallback) }
        } else {
            runCatching { cm.unregisterNetworkCallback(networkCallback) }
            lastNetwork = null
        }
    }

    /** Our picture stops by itself while the line is too weak for it (the voice goes on), and comes back after. */
    private fun pauseVideo(s: Session, pause: Boolean) {
        s.autoPaused = pause
        s.badSince = 0L
        s.goodSince = 0L
        s.note(if (pause) "picture paused: weak line" else "picture back")
        s.videoSender?.let { sender ->
            runCatching {
                val p = sender.parameters
                p.encodings.forEach { it.active = !pause }
                sender.parameters = p
            }
        }
        me()?.uid?.let { s.callRef.child("paused").child(it).setValue(pause) }
        _state.update { it?.copy(videoPaused = pause) }
    }

    // ------------------------------------------------------------------ the buttons

    override fun react(emoji: String) {
        val s = session ?: return
        if (emoji !in CALL_REACTIONS || _state.value?.phase != CallPhase.ACTIVE) return
        val stamp = System.currentTimeMillis()
        me()?.uid?.let { s.callRef.child("react").child(it).setValue(mapOf("e" to emoji, "n" to stamp)) }
        _state.update { it?.copy(reaction = CallReaction(stamp * 2, emoji, mine = true)) }
    }

    override fun toggleNoiseSuppression() {
        val on = !(_state.value?.noiseSuppression ?: true)
        _state.update { it?.copy(noiseSuppression = on) }
        val s = session ?: return
        val sender = s.audioSender ?: return
        val oldSource = s.audioSource
        // A new voice with the new setting takes the old one's place (the sender lets the old one go).
        sender.setTrack(makeAudio(s, on), true)
        runCatching { oldSource?.dispose() }
    }

    override val canAddPeople: Boolean get() = onMoveToRoom != null

    override fun addPerson(user: ChatUser) {
        val s = session ?: return
        val mine = me() ?: return
        if (_state.value?.phase != CallPhase.ACTIVE || user.uid == s.peer.uid || user.uid == mine.uid || s.moved) return
        val room = "room" + (1..20).map { ROOM_CHARS.random() }.joinToString("")
        val video = _state.value?.video == true
        s.note("adding ${user.name}: group call $room")
        scope.launch {
            runCatching {
                root.child("callRooms").child(room).setValue(
                    mapOf(
                        "by" to mine.uid,
                        "at" to ServerValue.TIMESTAMP,
                        "video" to video,
                        "people" to mapOf(mine.uid to true, s.peer.uid to true, user.uid to true),
                    ),
                ).await()
                // The other phone in this call follows into the group call; the new person's phone rings.
                s.callRef.child("moveTo").setValue(room).await()
                ringRoom(room, mine.uid, user.uid, video)
                moveToRoom(s, room, video)
            }.onFailure { s.note("adding failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** Rings [to] for the group call [room] (the push server checks they were let in). */
    internal fun ringRoom(room: String, from: String, to: String, video: Boolean) {
        root.child("notify").push().setValue(
            mapOf("from" to from, "to" to to, "chatId" to room, "type" to "roomcall", "video" to video, "at" to ServerValue.TIMESTAMP),
        )
    }

    /** This call ends quietly here and goes on as the group call [room]. */
    private fun moveToRoom(s: Session, room: String, video: Boolean) {
        if (s.moved) return
        s.moved = true
        s.note("moving to group call $room")
        finish(CallEnd.HUNG_UP, write = false)
        if (session === s) {
            session = null
            _state.value = null
        }
        val name = if (ChatMusic.english) "Group call" else "Grup araması"
        onMoveToRoom?.invoke(ChatUser(room, name, "", null), video)
    }

    /** Shows our screen instead of the camera ([data]: what the person allowed in the system's question). */
    fun startScreenShare(data: android.content.Intent) {
        val s = session ?: return
        if (s.screenTrack != null) return
        if (_state.value?.phase != CallPhase.ACTIVE || s.videoSender == null) {
            shareFailed()
            return
        }
        scope.launch {
            // Android wants the foreground service to say "screen" before the screen is read: wait until it has
            // (a slow phone takes longer), at most a few seconds.
            val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
            CallHooks.screenServiceReady = ready
            CallHooks.onScreenShare?.invoke(true)
            val ok = kotlinx.coroutines.withTimeoutOrNull(5_000L) { ready.await() }
            if (CallHooks.screenServiceReady === ready) CallHooks.screenServiceReady = null
            if (session !== s || s.ended) return@launch
            if (ok != true) {
                s.note("screen share: service not ready ($ok)")
                CallHooks.onScreenShare?.invoke(false)
                shareFailed()
                return@launch
            }
            runCatching {
                val capturer = org.webrtc.ScreenCapturerAndroid(
                    data,
                    object : android.media.projection.MediaProjection.Callback() {
                        // Stopped from the system (its own "stop sharing"): the call stops showing it too.
                        override fun onStop() {
                            scope.launch { if (session === s && s.screenTrack != null) stopScreenShare() }
                        }
                    },
                )
                val helper = SurfaceTextureHelper.create("qm-screen", egl.eglBaseContext)
                val source = factory.createVideoSource(true)
                capturer.initialize(helper, app, source.capturerObserver)
                val dm = app.resources.displayMetrics
                // Half the screen's size: text still reads, and a phone line carries it.
                capturer.startCapture((dm.widthPixels / 2) and 1.inv(), (dm.heightPixels / 2) and 1.inv(), 15)
                val track = factory.createVideoTrack("qm-screen", source)
                s.screenCapturer = capturer
                s.screenHelper = helper
                s.screenSource = source
                s.screenTrack = track
                // The camera rests meanwhile.
                runCatching { s.capturer?.stopCapture() }
                if (s.autoPaused) pauseVideo(s, false)
                s.videoSender?.let { sender ->
                    sender.setTrack(track, false)
                    runCatching {
                        val p = sender.parameters
                        p.encodings.forEach {
                            it.active = true
                            it.maxBitrateBps = 1_500_000
                        }
                        sender.parameters = p
                    }
                }
                me()?.uid?.let { uid ->
                    s.callRef.child("screen").child(uid).setValue(true)
                    s.callRef.child("cam").child(uid).setValue(true)
                }
                updateProximity()
                _state.update { it?.copy(sharingScreen = true) }
                // A voice call: the phone is looked at, not held to the ear, so the sound comes out of the speaker unless a headset is connected.
                if (_state.value?.speaker != true && !isHeadsetActiveOrConnected()) toggleSpeaker()
                s.note("screen shared")
            }.onFailure {
                s.note("screen share failed: ${it.javaClass.simpleName}: ${it.message}")
                releaseScreen(s)
                CallHooks.onScreenShare?.invoke(false)
                shareFailed()
            }
        }
    }

    /** Showing the screen did not start: the call screen says so. */
    fun shareFailed() {
        _state.update { it?.copy(shareFailures = it.shareFailures + 1) }
    }

    override fun stopScreenShare() {
        val s = session ?: return
        if (s.screenTrack == null) return
        val cameraBack = s.videoTrack != null && _state.value?.cameraOn == true
        s.videoSender?.setTrack(if (cameraBack) s.videoTrack else null, false)
        if (cameraBack) runCatching { s.capturer?.startCapture(1280, 720, 30) }
        releaseScreen(s)
        me()?.uid?.let { uid ->
            s.callRef.child("screen").child(uid).setValue(false)
            s.callRef.child("cam").child(uid).setValue(cameraBack)
        }
        _state.update { it?.copy(sharingScreen = false) }
        CallHooks.onScreenShare?.invoke(false)
        updateProximity()
        s.note("screen share stopped")
    }

    private fun releaseScreen(s: Session) {
        runCatching { s.screenCapturer?.stopCapture() }
        runCatching { s.screenCapturer?.dispose() }
        runCatching { s.screenTrack?.dispose() }
        runCatching { s.screenSource?.dispose() }
        runCatching { s.screenHelper?.dispose() }
        s.screenCapturer = null
        s.screenTrack = null
        s.screenSource = null
        s.screenHelper = null
    }

    override fun toggleMute() {
        if (_state.value?.held == true) return
        val muted = !(_state.value?.muted ?: false)
        session?.audio?.setEnabled(!muted)
        _state.update { it?.copy(muted = muted) }
    }

    override fun toggleSpeaker() {
        val on = !(_state.value?.speaker ?: false)
        pickedDevice = null
        pickedLegacyRoute = null
        userForcedSpeaker = on
        wantSpeaker = on
        if (!telecom.setSpeaker(on)) {
            routeAudio(on)
        } else {
            _state.update { it?.copy(speaker = on) }
        }
        updateProximity()
    }

    override fun toggleCamera() {
        val s = session ?: return
        if (_state.value?.held == true || s.screenTrack != null) return
        if (s.autoPaused) pauseVideo(s, false)
        if (s.videoTrack == null) {
            // A voice call: the camera comes on and its picture goes through the slot kept for it.
            startCamera(s, front = true)
            val track = s.videoTrack ?: return
            s.videoSender?.let { sender ->
                sender.setTrack(track, false)
                runCatching {
                    val p = sender.parameters
                    p.encodings.forEach { it.maxBitrateBps = 2_500_000 }
                    sender.parameters = p
                }
            }
            s.upgraded = true
            me()?.uid?.let { s.callRef.child("cam").child(it).setValue(true) }
            _state.update { it?.copy(video = true, cameraOn = true) }
            if (!s.serviceHasVideo) {
                s.serviceHasVideo = true
                CallHooks.onActive?.invoke(true, s.peer, true)
            }
            updateProximity()
            // A video call is held away from the ear; prefer speaker only when no headset is connected.
            defaultVideoAudio = true
            telecom.onVideoChanged(true)
            if (!telecom.active && !isHeadsetActiveOrConnected() && _state.value?.speaker != true) {
                wantSpeaker = true
                userForcedSpeaker = false
                routeAudio(true)
            }
            return
        }
        val on = !(_state.value?.cameraOn ?: true)
        s.videoTrack?.setEnabled(on)
        if (on) runCatching { s.capturer?.startCapture(1280, 720, 30) } else runCatching { s.capturer?.stopCapture() }
        me()?.uid?.let { s.callRef.child("cam").child(it).setValue(on) }
        _state.update { it?.copy(cameraOn = on) }
        if (on && !s.serviceHasVideo) {
            s.serviceHasVideo = true
            CallHooks.onActive?.invoke(true, s.peer, true)
        }
        updateProximity()
    }

    override fun setAudioRoute(id: String) {
        if (telecom.select(id)) return
        // Without the system's call: the app sends the sound to that device itself.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.id.toString() == id } ?: return
            pickedDevice = device.id
            val isSpeaker = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            userForcedSpeaker = isSpeaker
            wantSpeaker = isSpeaker
            runCatching { audioManager.setCommunicationDevice(device) }
            _state.update { it?.copy(route = id, speaker = isSpeaker) }
            publishDevices(id)
        } else {
            pickedLegacyRoute = id
            val speaker = id == "speaker"
            userForcedSpeaker = speaker
            wantSpeaker = speaker
            routeAudio(speaker)
        }
        updateProximity()
    }

    /** The app's own list of places for the sound (when the system does not have the call). */
    private fun publishDevices(preferredRouteId: String? = null) {
        if (telecom.active) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val routes = audioManager.availableCommunicationDevices.mapNotNull { d ->
                val kind = when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRouteKind.EARPIECE
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRouteKind.SPEAKER
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRouteKind.WIRED
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioRouteKind.BLUETOOTH
                    else -> null
                } ?: return@mapNotNull null
                AudioRoute(d.id.toString(), kind, d.productName?.toString().orEmpty())
            }.distinctBy { it.kind to it.name }
            val currentId = preferredRouteId?.takeIf { pid -> routes.any { it.id == pid } }
                ?: audioManager.communicationDevice?.id?.toString()?.takeIf { cid -> routes.any { it.id == cid } }
                ?: _state.value?.route?.takeIf { rid -> routes.any { it.id == rid } }
            val currentSpeaker = routes.firstOrNull { it.id == currentId }?.kind == AudioRouteKind.SPEAKER
            _state.update { it?.copy(routes = routes, route = currentId, speaker = currentSpeaker) }
        } else {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val btDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            val wiredDevice = outputs.firstOrNull { isWiredHeadsetType(it.type) }
            val hasEarpiece = outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            val routes = buildList {
                if (hasEarpiece || wiredDevice == null) add(AudioRoute("earpiece", AudioRouteKind.EARPIECE))
                add(AudioRoute("speaker", AudioRouteKind.SPEAKER))
                if (wiredDevice != null || wiredPlugged == true) {
                    add(AudioRoute("wired", AudioRouteKind.WIRED, wiredDevice?.productName?.toString().orEmpty()))
                }
                if (btDevice != null) {
                    add(AudioRoute("bluetooth", AudioRouteKind.BLUETOOTH, btDevice.productName?.toString().orEmpty()))
                }
            }
            val currentId = preferredRouteId?.takeIf { pid -> routes.any { it.id == pid } } ?: when {
                audioManager.isSpeakerphoneOn -> "speaker"
                btDevice != null && (scoStarted || audioManager.isBluetoothScoOn) -> "bluetooth"
                wiredDevice != null || wiredPlugged == true -> "wired"
                else -> "earpiece"
            }
            _state.update { it?.copy(routes = routes, route = currentId, speaker = currentId == "speaker") }
        }
    }

    private var pickedDevice: Int? = null
    private var pickedLegacyRoute: String? = null
    private var defaultVideoAudio: Boolean = false
    private var wantSpeaker: Boolean = false
    private var userForcedSpeaker: Boolean = false
    private var scoStarted: Boolean = false
    private var scoConnected: Boolean = false
    private var wiredPlugged: Boolean? = null
    private var knownHeadsetIds: Set<Int> = emptySet()
    private var audioReceiverRegistered: Boolean = false
    private var commListener: Any? = null

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            scope.launch {
                val s = session
                if (s == null || s.ended) return@launch
                val addedHeadsets = added.filter { it.isSink && isHeadsetType(it.type) }.map { it.id }
                val newHeadsets = addedHeadsets - knownHeadsetIds
                knownHeadsetIds = knownHeadsetIds + addedHeadsets
                if (!telecom.active) {
                    if (newHeadsets.isNotEmpty()) {
                        // A headset connected mid-call takes over automatically.
                        pickedDevice = null
                        pickedLegacyRoute = null
                        userForcedSpeaker = false
                        wantSpeaker = defaultVideoAudio
                        routeAudio(wantSpeaker)
                    } else {
                        publishDevices()
                    }
                }
                updateProximity()
            }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            scope.launch {
                val s = session
                if (s == null || s.ended) return@launch
                val removedIds = removed.mapTo(HashSet()) { it.id }
                val hadKnownHeadset = (removedIds intersect knownHeadsetIds).isNotEmpty()
                knownHeadsetIds = knownHeadsetIds - removedIds
                if (pickedDevice != null && pickedDevice in removedIds) pickedDevice = null
                if (!telecom.active) {
                    if (pickedLegacyRoute == "bluetooth" && !hasBtSco()) pickedLegacyRoute = null
                    if (pickedLegacyRoute == "wired" && !hasWiredHeadset()) pickedLegacyRoute = null
                    if (hadKnownHeadset || pickedDevice == null) {
                        routeAudio(wantSpeaker)
                    } else {
                        publishDevices()
                    }
                }
                updateProximity()
            }
        }
    }

    private val audioRoutingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            scope.launch {
                val s = session
                if (s == null || s.ended) return@launch
                when (action) {
                    Intent.ACTION_HEADSET_PLUG -> {
                        val plugged = intent.getIntExtra("state", 0) == 1
                        if (wiredPlugged == plugged) return@launch
                        val hadBefore = wiredPlugged
                        wiredPlugged = plugged
                        if (!telecom.active) {
                            if (plugged && hadBefore != null) {
                                pickedDevice = null
                                pickedLegacyRoute = null
                                userForcedSpeaker = false
                                wantSpeaker = defaultVideoAudio
                                routeAudio(wantSpeaker)
                            } else if (!plugged && hadBefore == true) {
                                if (pickedLegacyRoute == "wired") pickedLegacyRoute = null
                                routeAudio(wantSpeaker)
                            }
                        }
                        updateProximity()
                    }
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                            scoConnected = true
                            if (!telecom.active && Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                                !audioManager.isSpeakerphoneOn && pickedLegacyRoute != "wired" && pickedLegacyRoute != "earpiece"
                            ) {
                                runCatching { audioManager.isBluetoothScoOn = true }
                            }
                        } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                            scoConnected = false
                            scoStarted = false
                            if (!telecom.active && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                runCatching { audioManager.isBluetoothScoOn = false }
                                if (pickedLegacyRoute == "bluetooth") pickedLegacyRoute = null
                                routeAudio(wantSpeaker, allowScoStart = false)
                            }
                        }
                        if (!telecom.active) publishDevices()
                        updateProximity()
                    }
                }
            }
        }
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
        s.statsJob?.cancel()
        releaseScreen(s)
        s.note("finish $reason write=$write from " + Throwable().stackTrace.drop(1).take(3).joinToString(" < ") { "${it.methodName}:${it.lineNumber}" })
        // Who ended it, and how it got there: kept on the server for a while (callDebug/{uid}/{call}).
        runCatching {
            val uid = me()?.uid
            if (uid != null) {
                val where = Throwable().stackTrace.drop(1).take(6).joinToString(" < ") { "${it.methodName}:${it.lineNumber}" }
                root.child("callDebug").child(uid).child(s.id.replace(Regex("[.#$\\[\\]/]"), "_")).setValue(
                    mapOf(
                        "at" to ServerValue.TIMESTAMP,
                        "peer" to s.peer.name,
                        "caller" to s.caller,
                        "video" to s.video,
                        "reason" to reason.name,
                        "write" to write,
                        "phase" to _state.value?.phase?.name,
                        "connectedOnce" to s.connectedOnce,
                        "where" to where,
                        "trail" to s.trail.toList(),
                        "app" to CallHooks.appVisible,
                        "android" to android.os.Build.VERSION.SDK_INT,
                        "model" to android.os.Build.MODEL,
                    ),
                )
            }
        }
        stopRinging()
        stopRingback()
        CallHooks.onIncomingGone?.invoke()
        s.timeout?.cancel()
        s.disconnectJob?.cancel()
        s.unreachableJob?.cancel()
        telecom.end(
            when (reason) {
                CallEnd.DECLINED -> android.telecom.DisconnectCause.REJECTED
                CallEnd.NO_ANSWER -> android.telecom.DisconnectCause.MISSED
                CallEnd.BUSY -> android.telecom.DisconnectCause.BUSY
                CallEnd.FAILED, CallEnd.UNREACHABLE -> android.telecom.DisconnectCause.ERROR
                CallEnd.CANCELLED -> android.telecom.DisconnectCause.CANCELED
                CallEnd.HUNG_UP -> if (write) android.telecom.DisconnectCause.LOCAL else android.telecom.DisconnectCause.REMOTE
            },
        )
        // Rang here and nobody answered (or they gave up) while the app was out of sight: a missed call to call back.
        if (!s.caller && !s.connectedOnce && (reason == CallEnd.NO_ANSWER || reason == CallEnd.CANCELLED) && !CallHooks.appVisible) {
            CallHooks.onMissed?.invoke(s.peer, s.video)
        }
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
        // The person's list of calls: in or out, how it went, how long.
        run {
            val startedAt = _state.value?.startedAt
            val seconds = if (s.connectedOnce && startedAt != null) (System.currentTimeMillis() - startedAt) / 1000 else 0L
            val outcome = when {
                s.connectedOnce -> CallOutcome.ANSWERED
                !s.caller && reason == CallEnd.DECLINED -> CallOutcome.DECLINED
                !s.caller -> CallOutcome.MISSED
                else -> CallOutcome.NOT_ANSWERED
            }
            logEntry(s.id, s.peer, s.video || s.upgraded, if (s.caller) CallDirection.OUT else CallDirection.IN, outcome, seconds)
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
        runCatching { s.audio?.dispose() }
        runCatching { s.audioSource?.dispose() }
        localTrack.value = null
        remoteTrack.value = null
        // "Ended", for a call that was on (still in the call's sound route, before it is let go).
        if (s.connectedOnce && !s.moved) QeyfSounds.playSmall(app, SmallSound.ENDED, call = true)
        stopAudio()
        // Moving on into a group call: the music stays paused and the call's service stays up for it.
        if (!s.moved) {
            MusicDuringCall.resume()
            CallHooks.onActive?.invoke(false, null, false)
        }
        _state.update { it?.copy(phase = CallPhase.ENDED, endReason = reason) }
        scope.launch {
            delay(END_SHOWN_MS)
            if (session === s) {
                session = null
                _state.value = null
                CallUi.minimized.value = false
                settle()
            }
        }
    }

    /**
     * After a call, nothing of it may stay behind, however fast it was dialled and hung up: the phone's own call, the
     * call's sound mode (both keep the phone "on a call", and then the next call rings without sound), and the service
     * that keeps a call alive (started a moment late, it could outlive the call).
     */
    private fun settle() {
        if (session != null || CallHooks.groupActive) return
        telecom.ensureIdle()
        if (audioManager.mode != AudioManager.MODE_NORMAL) runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
        CallHooks.onActive?.invoke(false, null, false)
    }

    // ------------------------------------------------------------------ sound

    /**
     * While connected: how loud the other person is (for the ring on screen) and how good the connection is, read from
     * WebRTC's own figures every third of a second. No sound from them for a long while is noted in the call's trail
     * (with what the connection says), to find why a call had no voice.
     */
    private fun watchStats(s: Session) {
        s.statsJob?.cancel()
        s.statsJob = scope.launch {
            var lastBytes = -1L
            var silentSince = 0L
            var silenceNoted = false
            var lastLost = -1L
            var lastReceived = -1L
            while (session === s && !s.ended) {
                val pc = s.pc ?: break
                val report = kotlinx.coroutines.suspendCancellableCoroutine<org.webrtc.RTCStatsReport?> { cont ->
                    runCatching { pc.getStats { r -> if (cont.isActive) cont.resume(r) } }.onFailure { if (cont.isActive) cont.resume(null) }
                } ?: break
                var level = 0f
                var bytes = 0L
                var lost = 0L
                var received = 0L
                var rtt = -1.0
                var jitter = 0.0
                for (st in report.statsMap.values) {
                    val m = st.members
                    when (st.type) {
                        "inbound-rtp" -> if (m["kind"] == "audio") {
                            (m["audioLevel"] as? Number)?.toFloat()?.let { level = it }
                            (m["bytesReceived"] as? Number)?.toLong()?.let { bytes += it }
                            (m["packetsLost"] as? Number)?.toLong()?.let { lost += it }
                            (m["packetsReceived"] as? Number)?.toLong()?.let { received += it }
                            (m["jitter"] as? Number)?.toDouble()?.let { jitter = it }
                        }
                        "candidate-pair" -> if (m["state"] == "succeeded" && m["nominated"] == true) {
                            (m["currentRoundTripTime"] as? Number)?.toDouble()?.let { rtt = it }
                        }
                    }
                }
                // Lost sound in the last moment, as a share of what came.
                val lossNow = if (lastLost >= 0 && received > lastReceived) (lost - lastLost).toDouble() / (received - lastReceived).coerceAtLeast(1) else 0.0
                lastLost = lost
                lastReceived = received
                val quality = when {
                    rtt < 0 -> -1
                    rtt > 0.6 || lossNow > 0.15 || jitter > 0.1 -> 0
                    rtt > 0.35 || lossNow > 0.06 || jitter > 0.05 -> 1
                    rtt > 0.18 || lossNow > 0.02 -> 2
                    else -> 3
                }
                _state.update { it?.copy(remoteLevel = level.coerceIn(0f, 1f), quality = quality) }
                // A video call on a line too weak for pictures: ours stops after 6 bad seconds, and comes back after 8
                // good ones (not while the screen is shown, nor when the person turned the camera off).
                _state.value?.let { st ->
                    if (st.video && s.videoTrack != null && s.screenTrack == null && (st.cameraOn || s.autoPaused) && quality >= 0) {
                        val t = System.currentTimeMillis()
                        if (quality == 0) {
                            if (s.badSince == 0L) s.badSince = t
                        } else {
                            s.badSince = 0L
                        }
                        if (quality >= 2) {
                            if (s.goodSince == 0L) s.goodSince = t
                        } else {
                            s.goodSince = 0L
                        }
                        if (!s.autoPaused && s.badSince > 0 && t - s.badSince > 6_000) {
                            pauseVideo(s, true)
                        } else if (s.autoPaused && s.goodSince > 0 && t - s.goodSince > 8_000) {
                            pauseVideo(s, false)
                        }
                    }
                }
                // Nothing arriving from them (no bytes) for 6 seconds: noted once, with the connection's figures.
                val now = System.currentTimeMillis()
                if (bytes > lastBytes || _state.value?.held == true) {
                    silentSince = 0L
                } else if (silentSince == 0L) {
                    silentSince = now
                } else if (!silenceNoted && now - silentSince > 6_000) {
                    silenceNoted = true
                    s.note("no sound arriving for 6 s: bytes=$bytes rtt=$rtt lost=$lost received=$received muted=${_state.value?.muted} route=${_state.value?.route}")
                }
                lastBytes = bytes
                delay(330)
            }
        }
    }

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private var proximity: PowerManager.WakeLock? = null

    private fun isBluetoothHeadsetType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun isWiredHeadsetType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && type == AudioDeviceInfo.TYPE_USB_HEADSET)

    private fun isHeadsetType(type: Int): Boolean = isBluetoothHeadsetType(type) || isWiredHeadsetType(type)

    private fun hasBtSco(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    private fun hasWiredHeadset(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isWiredHeadsetType(it.type) }

    private fun currentHeadsetDeviceIds(): Set<Int> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isHeadsetType(it.type) }
            .mapTo(HashSet()) { it.id }

    private fun isHeadsetActiveOrConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val comm = audioManager.communicationDevice
            if (comm != null) return isHeadsetType(comm.type)
            return audioManager.availableCommunicationDevices.any { isHeadsetType(it.type) }
        }
        return scoConnected || scoStarted || audioManager.isBluetoothScoOn || hasBtSco() || hasWiredHeadset() || wiredPlugged == true
    }

    private fun requestCallAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
                .also { audioManager.requestAudioFocus(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun startScoIfNeeded() {
        if (scoStarted && audioManager.isBluetoothScoOn) return
        runCatching {
            audioManager.isBluetoothScoOn = true
            if (!scoStarted) {
                audioManager.startBluetoothSco()
                scoStarted = true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopScoIfNeeded() {
        if (!scoStarted && !audioManager.isBluetoothScoOn) return
        runCatching {
            audioManager.isBluetoothScoOn = false
            if (scoStarted) audioManager.stopBluetoothSco()
        }
        scoStarted = false
        scoConnected = false
    }

    private fun startAudio(video: Boolean) {
        MusicDuringCall.pause()
        requestCallAudioFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        defaultVideoAudio = video
        wantSpeaker = video
        userForcedSpeaker = false
        pickedDevice = null
        pickedLegacyRoute = null
        scoStarted = false
        scoConnected = false
        wiredPlugged = hasWiredHeadset()
        knownHeadsetIds = currentHeadsetDeviceIds()
        watchNetwork(true)
        // Where the phone's call system has the call it sends the sound (see CallTelecom); until it has it (and where
        // there is none), the app does.
        if (!telecom.active) routeAudio(video)
        runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper())) }
        if (!audioReceiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    app,
                    audioRoutingReceiver,
                    IntentFilter().apply {
                        addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                        addAction(Intent.ACTION_HEADSET_PLUG)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                audioReceiverRegistered = true
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && commListener == null) {
            val l = AudioManager.OnCommunicationDeviceChangedListener { device ->
                scope.launch {
                    val s = session
                    if (s == null || s.ended) return@launch
                    if (!telecom.active) publishDevices(device?.id?.toString())
                    updateProximity()
                }
            }
            commListener = l
            runCatching { audioManager.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(app), l) }
        }
        scope.launch {
            // The system's list comes through CallTelecom; this is for when it does not have the call.
            delay(600)
            publishDevices()
            updateProximity()
        }
        updateProximity()
    }

    @Suppress("DEPRECATION")
    private fun routeAudio(speaker: Boolean, allowScoStart: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val picked = pickedDevice?.let { id -> devices.firstOrNull { it.id == id } }
            if (picked == null) pickedDevice = null
            // A headset or Bluetooth device wins over default speaker (even in video calls) unless user explicitly forced speaker.
            val headset = devices.firstOrNull { isBluetoothHeadsetType(it.type) }
                ?: devices.firstOrNull { isWiredHeadsetType(it.type) }
            val target = when {
                picked != null -> picked
                userForcedSpeaker && speaker -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                headset != null -> headset
                speaker -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                else -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            }
            if (target != null) {
                runCatching { audioManager.setCommunicationDevice(target) }
            } else {
                runCatching { audioManager.clearCommunicationDevice() }
            }
            publishDevices(target?.id?.toString())
        } else {
            val btAvailable = hasBtSco()
            val wiredAvailable = hasWiredHeadset() || wiredPlugged == true
            val targetRoute = when {
                pickedLegacyRoute == "speaker" -> "speaker"
                pickedLegacyRoute == "bluetooth" && btAvailable -> "bluetooth"
                pickedLegacyRoute == "wired" && wiredAvailable -> "wired"
                pickedLegacyRoute == "earpiece" -> "earpiece"
                userForcedSpeaker && speaker -> "speaker"
                btAvailable && allowScoStart -> "bluetooth"
                wiredAvailable -> "wired"
                speaker -> "speaker"
                else -> "earpiece"
            }
            when (targetRoute) {
                "bluetooth" -> {
                    audioManager.isSpeakerphoneOn = false
                    startScoIfNeeded()
                }
                "speaker" -> {
                    stopScoIfNeeded()
                    audioManager.isSpeakerphoneOn = true
                }
                else -> {
                    stopScoIfNeeded()
                    audioManager.isSpeakerphoneOn = false
                }
            }
            publishDevices(targetRoute)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopAudio() {
        watchNetwork(false)
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        if (audioReceiverRegistered) {
            runCatching { app.unregisterReceiver(audioRoutingReceiver) }
            audioReceiverRegistered = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (commListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                runCatching { audioManager.removeOnCommunicationDeviceChangedListener(it) }
            }
            commListener = null
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            stopScoIfNeeded()
            runCatching { audioManager.isSpeakerphoneOn = false }
        }
        pickedDevice = null
        pickedLegacyRoute = null
        defaultVideoAudio = false
        wantSpeaker = false
        userForcedSpeaker = false
        wiredPlugged = null
        knownHeadsetIds = emptySet()
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
        releaseProximity()
    }

    /** Only black out the screen during active earpiece voice audio — never on speaker, wired, Bluetooth, video, or hold. */
    private fun shouldHoldProximity(): Boolean {
        val s = session ?: return false
        val st = _state.value ?: return false
        if (s.ended || st.phase == CallPhase.ENDED || st.phase == CallPhase.INCOMING) return false
        if (st.video || st.sharingScreen || st.held || st.speaker) return false
        val currentRoute = st.routes.firstOrNull { it.id == st.route }
        if (currentRoute != null) return currentRoute.kind == AudioRouteKind.EARPIECE
        return !isHeadsetActiveOrConnected()
    }

    private fun updateProximity() {
        if (shouldHoldProximity()) acquireProximity() else releaseProximity()
    }

    private fun acquireProximity() {
        if (proximity?.isHeld == true) return
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "qmusic:call").also {
                it.acquire(4 * 60 * 60 * 1000L)
            }
        }
    }

    /** The screen-off-near-the-ear lock of a voice call let go: a video call, speaker, headset, or hold keeps its screen on. */
    private fun releaseProximity() {
        proximity?.let { if (it.isHeld) runCatching { it.release() } }
        proximity = null
    }

    private var ringback: android.media.MediaPlayer? = null

    private fun startRinging() {
        // Qeyf's own ringtone (the one chosen in Profile and privacy → Sounds), one ringing shared with the notification.
        val s = session ?: return
        IncomingRinger.start(app, s.id, s.peer.uid, RING_MS + 5_000L)
        // For the call's trail: could it be heard (silent mode, Do not disturb, ring volume).
        s.note("ring: ${CallHooks.ringDiagnosis(app)}")
    }

    private fun stopRinging() {
        IncomingRinger.stop()
    }

    private fun startRingback() {
        QeyfSounds.stop(ringback)
        ringback = QeyfSounds.startRingback(app, peerTone?.takeIf { it.first == session?.id }?.second)
    }

    /** The song the person being called chose for their callers (the call's id with it), once it is here. */
    private var peerTone: Pair<String, java.io.File?>? = null
    private var peerToneJob: Job? = null

    /** Fetches the song [peer] chose for their callers; it takes over from the beeps as soon as it is here. */
    private fun fetchPeerTone(callId: String, peer: ChatUser) {
        peerToneJob?.cancel()
        peerTone = null
        peerToneJob = scope.launch {
            val file = CallTonesAndroid.peerTone(app, peer.uid)
            if (session?.id != callId) return@launch
            peerTone = callId to file
            if (file != null && ringback != null && _state.value?.phase == CallPhase.CALLING) startRingback()
        }
    }

    private fun stopRingback() {
        QeyfSounds.stop(ringback)
        ringback = null
    }

    companion object {
        /** How long a call rings before it counts as not answered. */
        const val RING_MS = 45_000L

        /** How long "Connecting…" may last after the answer before the call gives up. */
        private const val CONNECT_MS = 30_000L

        /** The server could not reach the other phone: this long for it to show up anyway, then "Unavailable". */
        private const val UNREACHABLE_GRACE_MS = 8_000L
        private const val END_SHOWN_MS = 1_800L

        /** How long a dropped call keeps trying to come back before it ends. */
        private const val RECONNECT_MS = 30_000L

        /** A group call made by adding someone to a call. */
        internal val ROOM_ID = Regex("^room[A-Za-z0-9]{20}$")
        private const val ROOM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}

// ------------------------------------------------------------------ WebRTC's callbacks as suspend functions

internal suspend fun PeerConnection.awaitCreate(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
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

internal suspend fun PeerConnection.awaitSetLocal(sdp: SessionDescription) = awaitSet(sdp, local = true)
internal suspend fun PeerConnection.awaitSetRemote(sdp: SessionDescription) = awaitSet(sdp, local = false)

// ------------------------------------------------------------------ the screen

@Composable
actual fun CallVideo(service: CallService, remote: Boolean, modifier: Modifier, overlay: Boolean) {
    val calls = service as? FirebaseCallService ?: return
    val track by (if (remote) calls.remoteTrack else calls.localTrack).collectAsState()
    val state by calls.state.collectAsState()
    val mirror = !remote && state?.frontCamera != false
    // A shared screen is shown whole (not cropped to fill), with bars where it does not fit.
    val fit = remote && state?.remoteScreen == true
    var rendererView by remember { androidx.compose.runtime.mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(calls.egl.eglBaseContext, null)
                setEnableHardwareScaler(true)
                // The small picture in the corner is drawn over the big one.
                if (overlay) setZOrderMediaOverlay(true)
                keepScreenOn = true
                rendererView = this
            }
        },
        update = {
            it.setMirror(mirror)
            it.setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        },
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

private val callPipHolders = HashSet<Any>()

@Composable
actual fun CallPictureInPicture(armed: Boolean): Boolean {
    val token = remember { Any() }
    DisposableEffect(armed) {
        if (armed) callPipHolders += token else callPipHolders -= token
        val nowArmed = callPipHolders.isNotEmpty()
        com.qmusic.ui.videos.VideoPip.armed = nowArmed
        com.qmusic.ui.videos.VideoPip.portrait = nowArmed
        onDispose {
            callPipHolders -= token
            val stillArmed = callPipHolders.isNotEmpty()
            com.qmusic.ui.videos.VideoPip.armed = stillArmed
            com.qmusic.ui.videos.VideoPip.portrait = stillArmed
        }
    }
    val inside by com.qmusic.ui.videos.VideoPip.active.collectAsState()
    return inside
}

actual val callBlurWorks: Boolean get() = android.os.Build.VERSION.SDK_INT >= 31

@Composable
actual fun rememberScreenShare(service: CallService): (() -> Unit)? {
    val calls = service as? FirebaseCallService ?: return null
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) calls.startScreenShare(data)
    }
    return remember(context, calls) {
        {
            val projection = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
            // Said no in the system's question: their choice, nothing to tell. The question not shown at all: told.
            val asked = projection != null && runCatching { launcher.launch(projection.createScreenCaptureIntent()) }.isSuccess
            if (!asked) calls.shareFailed()
            Unit
        }
    }
}
