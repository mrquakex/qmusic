package com.qmusic.ui.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Group calls: everyone connects to everyone. Whoever joins sends an offer to each one already in the call; they
 * answer. All of it goes through the database:
 *
 *   groupCalls/{groupId}/members/{uid}          {name, photo, video, cam, at}   (gone when the phone leaves or drops)
 *   groupCalls/{groupId}/sig/{to}/{from}/{id}   {t: offer|answer|ice, sdp | mid, index, c}
 */
internal class FirebaseGroupCallService(
    private val app: Context,
    private val root: DatabaseReference,
    private val me: () -> ChatUser?,
    private val calls: FirebaseCallService,
    /** Writes the call in the person's list of calls (how long it lasted; 0 when nobody came). */
    private val logEntry: (id: String, group: ChatUser, video: Boolean, seconds: Long) -> Unit = { _, _, _, _ -> },
) : GroupCallService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<GroupCallState?>(null)
    override val state: StateFlow<GroupCallState?> = _state.asStateFlow()

    /** Each member's picture, and our own. */
    val remoteTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val localTrack = MutableStateFlow<VideoTrack?>(null)
    val egl get() = calls.egl

    private class Peer(val uid: String, val pc: PeerConnection) {
        val pendingIce = ArrayList<IceCandidate>()
        var remoteSet = false
        var connected = false
        @Volatile
        var disposed = false
    }

    private val peers = HashMap<String, Peer>()
    private var joinedAt = 0L
    private var groupId: String? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var audio: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var ice: List<PeerConnection.IceServer> = emptyList()
    private val listeners = ArrayList<Pair<com.google.firebase.database.Query, Any>>()
    private val people = HashMap<String, ChatUser>()
    private val cams = HashMap<String, Boolean>()
    private val videos = HashMap<String, Boolean>()

    /** A group's call, or a call made by adding people to a call (its own place, open only to those let in). */
    private fun callRef(id: String) = if (isCallRoom(id)) root.child("callRooms").child(id) else root.child("groupCalls").child(id)

    override val canAddPeople: Boolean get() = groupId?.let { isCallRoom(it) } == true

    override fun addPerson(user: ChatUser) {
        val id = groupId ?: return
        val mine = me() ?: return
        if (!isCallRoom(id) || user.uid == mine.uid || people.containsKey(user.uid)) return
        if (people.size + 1 >= MAX_GROUP_CALL) return
        scope.launch {
            runCatching {
                callRef(id).child("people").child(user.uid).setValue(true).await()
                calls.ringRoom(id, mine.uid, user.uid, _state.value?.video == true)
            }
        }
    }

    override fun participants(groupId: String): Flow<List<ChatUser>> = callbackFlow {
        val ref = callRef(groupId).child("members")
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                trySend(
                    snap.children.mapNotNull { m ->
                        val uid = m.key ?: return@mapNotNull null
                        ChatUser(uid, m.child("name").getValue(String::class.java) ?: "?", "", m.child("photo").getValue(String::class.java))
                    },
                )
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(emptyList())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    override fun join(group: ChatUser, video: Boolean) {
        val mine = me() ?: return
        if (groupId != null) return
        // A one-to-one call going on comes first.
        if (calls.state.value != null) return
        groupId = group.uid
        GroupCallUi.minimized.value = false
        CallHooks.groupActive = true
        joinedAt = System.currentTimeMillis()
        _state.value = GroupCallState(group = group, video = video, members = emptyList(), cameraOn = video)
        scope.launch {
            ice = calls.iceServers()
            if (groupId != group.uid) return@launch
            startMedia(video)
            startAudio()
            CallHooks.onActive?.invoke(true, group, video)
            CallHooks.onGroupJoined?.invoke(group.uid)
            val ref = callRef(group.uid)
            val others = runCatching { ref.child("members").get().await() }.getOrNull()
                ?.children?.mapNotNull { it.key }?.filter { it != mine.uid }.orEmpty()
            if (groupId != group.uid) return@launch
            if (others.size >= MAX_GROUP_CALL - 1) {
                leave()
                return@launch
            }
            val entry = ref.child("members").child(mine.uid)
            entry.onDisconnect().removeValue()
            ref.child("sig").child(mine.uid).onDisconnect().removeValue()
            entry.setValue(mapOf("name" to mine.name, "photo" to mine.photo, "video" to video, "cam" to video, "at" to ServerValue.TIMESTAMP))
            // The first one rings the others (a call made by adding people rang each one itself).
            if (others.isEmpty() && !isCallRoom(group.uid)) {
                root.child("notify").push().setValue(
                    mapOf("from" to mine.uid, "to" to group.uid, "chatId" to group.uid, "type" to "groupcall", "body" to "", "video" to video, "at" to ServerValue.TIMESTAMP),
                )
            }
            listenSignals(group.uid, mine.uid)
            listenMembers(group.uid, mine.uid)
        }
    }

    private fun listenMembers(id: String, myUid: String) {
        val ref = callRef(id).child("members")
        val listener = object : ChildEventListener {
            fun seen(snap: DataSnapshot) {
                val uid = snap.key ?: return
                if (uid == myUid) return
                people[uid] = ChatUser(uid, snap.child("name").getValue(String::class.java) ?: "?", "", snap.child("photo").getValue(String::class.java))
                cams[uid] = snap.child("cam").getValue(Boolean::class.java) == true
                videos[uid] = snap.child("video").getValue(Boolean::class.java) == true
                // Of each two, the one with the larger id offers: never both at once, never neither.
                if (peers[uid] == null && myUid > uid) scope.launch { if (groupId == id && peers[uid] == null) offerTo(id, myUid, uid) }
                publish()
            }

            override fun onChildAdded(snap: DataSnapshot, previous: String?) = seen(snap)
            override fun onChildChanged(snap: DataSnapshot, previous: String?) = seen(snap)
            override fun onChildRemoved(snap: DataSnapshot) {
                val uid = snap.key ?: return
                if (uid == myUid) {
                    // Our entry went (the connection dropped and came back): put it back.
                    if (groupId == id) me()?.let { m ->
                        callRef(id).child("members").child(uid).setValue(
                            mapOf("name" to m.name, "photo" to m.photo, "video" to (_state.value?.video == true), "cam" to (_state.value?.cameraOn == true), "at" to ServerValue.TIMESTAMP),
                        )
                    }
                    return
                }
                dropPeer(uid)
            }

            override fun onChildMoved(snap: DataSnapshot, previous: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addChildEventListener(listener)
        listeners += ref to listener
    }

    private fun listenSignals(id: String, myUid: String) {
        val inbox = callRef(id).child("sig").child(myUid)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snap: DataSnapshot, previous: String?) = fromPeer(snap)
            override fun onChildChanged(snap: DataSnapshot, previous: String?) = fromPeer(snap)
            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, previous: String?) {}
            override fun onCancelled(error: DatabaseError) {}

            /** One sender's box: each new entry in it is handled once. */
            val done = HashSet<String>()
            fun fromPeer(box: DataSnapshot) {
                val from = box.key ?: return
                box.children.forEach { item ->
                    val key = from + "/" + item.key
                    if (!done.add(key)) return@forEach
                    scope.launch { onSignal(id, myUid, from, item) }
                }
            }
        }
        inbox.addChildEventListener(listener)
        listeners += inbox to listener
    }

    private suspend fun onSignal(id: String, myUid: String, from: String, item: DataSnapshot) {
        if (groupId != id) return
        when (item.child("t").getValue(String::class.java)) {
            "offer" -> {
                val sdp = item.child("sdp").getValue(String::class.java) ?: return
                closePeerConnection(from)
                val peer = createPeer(id, myUid, from)
                runCatching {
                    peer.pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
                    if (groupId != id || peers[from] !== peer || peer.disposed) return
                    peer.remoteSet = true
                    drain(peer)
                    val answer = peer.pc.awaitCreate(offer = false)
                    if (groupId != id || peers[from] !== peer || peer.disposed) return
                    peer.pc.awaitSetLocal(answer)
                    if (groupId != id || peers[from] !== peer || peer.disposed) return
                    send(id, from, myUid, mapOf("t" to "answer", "sdp" to answer.description))
                }
            }
            "answer" -> {
                val sdp = item.child("sdp").getValue(String::class.java) ?: return
                val peer = peers[from] ?: return
                if (peer.remoteSet || peer.disposed) return
                runCatching {
                    peer.pc.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }.onSuccess {
                    if (groupId != id || peers[from] !== peer || peer.disposed) return
                    peer.remoteSet = true
                    drain(peer)
                }
            }
            "ice" -> {
                val candidate = IceCandidate(
                    item.child("mid").getValue(String::class.java),
                    item.child("index").getValue(Int::class.java) ?: 0,
                    item.child("c").getValue(String::class.java) ?: return,
                )
                val peer = peers[from] ?: return
                if (peer.disposed) return
                if (peer.remoteSet) peer.pc.addIceCandidate(candidate) else peer.pendingIce += candidate
            }
        }
    }

    private fun drain(peer: Peer) {
        if (peer.disposed) return
        peer.pendingIce.forEach { peer.pc.addIceCandidate(it) }
        peer.pendingIce.clear()
    }

    private fun send(id: String, to: String, from: String, value: Map<String, Any?>) {
        callRef(id).child("sig").child(to).child(from).push().setValue(value)
    }

    private suspend fun offerTo(id: String, myUid: String, uid: String) {
        if (groupId != id) return
        val peer = createPeer(id, myUid, uid)
        runCatching {
            val offer = peer.pc.awaitCreate(offer = true)
            if (groupId != id || peers[uid] !== peer || peer.disposed) return
            peer.pc.awaitSetLocal(offer)
            if (groupId != id || peers[uid] !== peer || peer.disposed) return
            send(id, uid, myUid, mapOf("t" to "offer", "sdp" to offer.description))
        }
    }

    private fun createPeer(id: String, myUid: String, uid: String): Peer {
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        lateinit var peer: Peer
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (peer.disposed || groupId != id) return
                send(id, uid, myUid, mapOf("t" to "ice", "mid" to candidate.sdpMid, "index" to candidate.sdpMLineIndex, "c" to candidate.sdp))
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                scope.launch {
                    if (peer.disposed || peers[uid] !== peer || groupId != id) return@launch
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            peer.connected = true
                            if (_state.value?.startedAt == null) QeyfSounds.playSmall(app, SmallSound.CONNECTED, call = true)
                            _state.update { it?.copy(startedAt = it.startedAt ?: System.currentTimeMillis()) }
                            publish()
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            peer.connected = false
                            publish()
                        }
                        // Lost for good: the one who joined later offers again without removing the member entry.
                        PeerConnection.PeerConnectionState.FAILED -> {
                            closePeerConnection(uid)
                            publish()
                            if (myUid > uid && groupId == id) offerTo(id, myUid, uid)
                        }
                        else -> Unit
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    scope.launch {
                        if (!peer.disposed && peers[uid] === peer && groupId == id) {
                            remoteTracks.update { it + (uid to track) }
                        }
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
            override fun onRenegotiationNeeded() {}
        }
        val pc = calls.factory.createPeerConnection(config, observer) ?: error("no peer connection")
        peer = Peer(uid, pc)
        peers[uid] = peer
        audio?.let { pc.addTrack(it, listOf("qm")) }
        videoTrack?.let { track ->
            val sender = pc.addTrack(track, listOf("qm"))
            // Everyone sends to everyone: a picture that fits several at once on a mobile line.
            runCatching {
                val p = sender.parameters
                p.encodings.forEach { it.maxBitrateBps = 700_000 }
                sender.parameters = p
            }
        }
        publish()
        return peer
    }

    private fun closePeerConnection(uid: String) {
        val p = peers.remove(uid) ?: return
        p.disposed = true
        p.connected = false
        p.pendingIce.clear()
        remoteTracks.update { it - uid }
        runCatching {
            p.pc.senders.forEach { sender -> runCatching { p.pc.removeTrack(sender) } }
            p.pc.close()
        }
        runCatching { p.pc.dispose() }
    }

    private fun dropPeer(uid: String) {
        closePeerConnection(uid)
        people.remove(uid)
        cams.remove(uid)
        videos.remove(uid)
        publish()
    }

    private fun publish() {
        _state.update { s ->
            s?.copy(
                members = people.values.map { u ->
                    GroupCallMember(u, connected = peers[u.uid]?.connected == true, cameraOn = cams[u.uid] == true, hasVideo = videos[u.uid] == true)
                }.sortedBy { it.user.name.lowercase() },
            )
        }
    }

    private fun startMedia(video: Boolean) {
        val source = calls.factory.createAudioSource(
            MediaConstraints().apply {
                mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
                mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
                mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            },
        )
        audioSource = source
        audio = calls.factory.createAudioTrack("qm-gaudio", source)
        if (!video) return
        val enumerator = Camera2Enumerator(app)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: return
        val cap = enumerator.createCapturer(name, null) ?: return
        val h = SurfaceTextureHelper.create("qm-gcamera", calls.egl.eglBaseContext)
        val vs = calls.factory.createVideoSource(false)
        cap.initialize(h, app, vs.capturerObserver)
        cap.startCapture(960, 540, 24)
        val track = calls.factory.createVideoTrack("qm-gvideo", vs)
        capturer = cap
        helper = h
        videoSource = vs
        videoTrack = track
        localTrack.value = track
    }

    override fun leave() {
        val id = groupId ?: return
        groupId = null
        GroupCallUi.minimized.value = false
        CallHooks.groupActive = false
        val myUid = me()?.uid
        listeners.forEach { (ref, l) ->
            when (l) {
                is ValueEventListener -> ref.removeEventListener(l)
                is ChildEventListener -> ref.removeEventListener(l)
            }
        }
        listeners.clear()
        if (myUid != null) {
            callRef(id).child("members").child(myUid).removeValue()
            callRef(id).child("sig").child(myUid).removeValue()
        }
        localTrack.value = null
        remoteTracks.value = emptyMap()
        peers.keys.toList().forEach { closePeerConnection(it) }
        people.clear()
        cams.clear()
        videos.clear()
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { videoTrack?.setEnabled(false) }
        runCatching { videoSource?.dispose() }
        runCatching { helper?.dispose() }
        runCatching { audio?.setEnabled(false) }
        runCatching { audio?.dispose() }
        runCatching { audioSource?.dispose() }
        capturer = null
        helper = null
        videoSource = null
        videoTrack = null
        audio = null
        audioSource = null
        if (_state.value?.startedAt != null) QeyfSounds.playSmall(app, SmallSound.ENDED, call = true)
        _state.value?.let { st ->
            val seconds = st.startedAt?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L
            logEntry("g" + id + "_" + (joinedAt / 1000), st.group, st.video, seconds)
        }
        stopAudio()
        CallHooks.onActive?.invoke(false, null, false)
        CallHooks.onGroupJoined?.invoke(null)
        _state.value = null
    }

    override fun toggleMute() {
        val muted = !(_state.value?.muted ?: false)
        audio?.setEnabled(!muted)
        _state.update { it?.copy(muted = muted) }
    }

    override fun toggleSpeaker() {
        val on = !(_state.value?.speaker ?: true)
        userForcedSpeaker = on
        pickedLegacyRoute = null
        route(on)
    }

    override fun toggleCamera() {
        val on = !(_state.value?.cameraOn ?: true)
        videoTrack?.setEnabled(on)
        if (on) runCatching { capturer?.startCapture(960, 540, 24) } else runCatching { capturer?.stopCapture() }
        val id = groupId
        val uid = me()?.uid
        if (id != null && uid != null) callRef(id).child("members").child(uid).child("cam").setValue(on)
        _state.update { it?.copy(cameraOn = on) }
    }

    override fun switchCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) {
                scope.launch { _state.update { it?.copy(frontCamera = front) } }
            }

            override fun onCameraSwitchError(error: String?) {}
        })
    }

    // ------------------------------------------------------------------ sound: headset if connected, else speaker

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private var userForcedSpeaker = false
    private var pickedLegacyRoute: String? = null
    private var scoStarted = false
    private var scoConnected = false
    private var wiredPlugged: Boolean? = null
    private var knownHeadsetIds: Set<Int> = emptySet()
    private var audioReceiverRegistered = false
    private var commListener: Any? = null

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

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            scope.launch {
                if (groupId == null) return@launch
                val addedHeadsets = added.filter { it.isSink && isHeadsetType(it.type) }.map { it.id }
                val newHeadsets = addedHeadsets - knownHeadsetIds
                knownHeadsetIds = knownHeadsetIds + addedHeadsets
                if (newHeadsets.isNotEmpty()) {
                    userForcedSpeaker = false
                    pickedLegacyRoute = null
                    route(true)
                }
            }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            scope.launch {
                if (groupId == null) return@launch
                val removedIds = removed.mapTo(HashSet()) { it.id }
                val hadKnownHeadset = (removedIds intersect knownHeadsetIds).isNotEmpty()
                knownHeadsetIds = knownHeadsetIds - removedIds
                if (pickedLegacyRoute == "bluetooth" && !hasBtSco()) pickedLegacyRoute = null
                if (pickedLegacyRoute == "wired" && !hasWiredHeadset()) pickedLegacyRoute = null
                if (hadKnownHeadset) route(_state.value?.speaker ?: true)
            }
        }
    }

    private val audioRoutingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            scope.launch {
                if (groupId == null) return@launch
                when (action) {
                    Intent.ACTION_HEADSET_PLUG -> {
                        val plugged = intent.getIntExtra("state", 0) == 1
                        if (wiredPlugged == plugged) return@launch
                        val hadBefore = wiredPlugged
                        wiredPlugged = plugged
                        if (plugged && hadBefore != null) {
                            userForcedSpeaker = false
                            pickedLegacyRoute = null
                            route(true)
                        } else if (!plugged && hadBefore == true) {
                            if (pickedLegacyRoute == "wired") pickedLegacyRoute = null
                            route(_state.value?.speaker ?: true)
                        }
                    }
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                            scoConnected = true
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !audioManager.isSpeakerphoneOn && pickedLegacyRoute != "wired") {
                                runCatching { audioManager.isBluetoothScoOn = true }
                            }
                        } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                            scoConnected = false
                            scoStarted = false
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                runCatching { audioManager.isBluetoothScoOn = false }
                                if (pickedLegacyRoute == "bluetooth") pickedLegacyRoute = null
                                route(_state.value?.speaker ?: true, allowScoStart = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startAudio() {
        MusicDuringCall.pause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
                .also { audioManager.requestAudioFocus(it) }
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        userForcedSpeaker = false
        pickedLegacyRoute = null
        scoStarted = false
        scoConnected = false
        wiredPlugged = hasWiredHeadset()
        knownHeadsetIds = currentHeadsetDeviceIds()
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
                    if (groupId == null) return@launch
                    val isSpeaker = device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    _state.update { it?.copy(speaker = isSpeaker) }
                }
            }
            commListener = l
            runCatching { audioManager.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(app), l) }
        }
        route(true)
    }

    @Suppress("DEPRECATION")
    private fun route(speaker: Boolean, allowScoStart: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val headset = devices.firstOrNull { isBluetoothHeadsetType(it.type) }
                ?: devices.firstOrNull { isWiredHeadsetType(it.type) }
            val target = when {
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
            _state.update { it?.copy(speaker = target?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) }
        } else {
            val btAvailable = hasBtSco()
            val wiredAvailable = hasWiredHeadset() || wiredPlugged == true
            val routeSpeaker: Boolean
            when {
                userForcedSpeaker && speaker -> {
                    stopScoIfNeeded()
                    audioManager.isSpeakerphoneOn = true
                    routeSpeaker = true
                }
                btAvailable && allowScoStart -> {
                    audioManager.isSpeakerphoneOn = false
                    startScoIfNeeded()
                    routeSpeaker = false
                }
                wiredAvailable -> {
                    stopScoIfNeeded()
                    audioManager.isSpeakerphoneOn = false
                    routeSpeaker = false
                }
                speaker -> {
                    stopScoIfNeeded()
                    audioManager.isSpeakerphoneOn = true
                    routeSpeaker = true
                }
                else -> {
                    stopScoIfNeeded()
                    audioManager.isSpeakerphoneOn = false
                    routeSpeaker = false
                }
            }
            _state.update { it?.copy(speaker = routeSpeaker) }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopAudio() {
        MusicDuringCall.resume()
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
        userForcedSpeaker = false
        pickedLegacyRoute = null
        wiredPlugged = null
        knownHeadsetIds = emptySet()
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
    }
}

@Composable
actual fun GroupCallVideo(service: GroupCallService, uid: String?, modifier: Modifier) {
    val calls = service as? FirebaseGroupCallService ?: return
    val tracks by calls.remoteTracks.collectAsState()
    val local by calls.localTrack.collectAsState()
    val state by calls.state.collectAsState()
    val track = if (uid == null) local else tracks[uid]
    val mirror = uid == null && state?.frontCamera != false
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(calls.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                // A group's video keeps the screen on while it shows.
                keepScreenOn = true
                view = this
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = { v ->
            track?.let { t -> runCatching { t.removeSink(v) } }
            v.release()
        },
    )
    DisposableEffect(track, view) {
        val v = view
        val t = track
        if (v != null && t != null) t.addSink(v)
        onDispose { if (v != null && t != null) runCatching { t.removeSink(v) } }
    }
}
