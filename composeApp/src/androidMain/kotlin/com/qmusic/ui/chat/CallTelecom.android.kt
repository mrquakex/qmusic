package com.qmusic.ui.chat

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Qeyf's calls as the phone's own calls (Jetpack Core-Telecom): Android then knows a call is going on, so
 * - a Bluetooth headset, a car or a watch can answer, hang up and mute it, and its sound goes where the phone sends call
 *   sound (earpiece, speaker, headset, Bluetooth), chosen by the system and not by hand;
 * - a mobile call coming in meanwhile is handled like a second call (the system asks which one to keep);
 * - the app may keep the microphone in the background (the "phone call" kind of foreground service).
 *
 * Everything here is best effort: where the phone does not take the call (an old or unusual system), [available] is
 * false or [start] fails, and the call goes on exactly as without it, with the app routing its own sound.
 */
// Every use of the system's calls goes through [manager], which is there from Android 8 only.
@android.annotation.SuppressLint("NewApi")
internal class CallTelecom(app: Context, private val scope: CoroutineScope) {
    private val manager: CallsManager? = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) null else runCatching {
        CallsManager(app).also { it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING) }
    }.getOrNull()

    private var control: CallControlScope? = null
    private var job: Job? = null

    /**
     * Every call handed to the system and not yet let go (normally one at most). All of them are let go at the end:
     * a call left with the system makes the phone think it is still on a call (its ringtone then stays silent for the
     * next one, and the "on a call" sign stays up).
     */
    private val open = java.util.Collections.synchronizedSet(LinkedHashSet<CallControlScope>())

    /** Which call is the live one: a call that was already over when the system finished taking it is let go at once. */
    @Volatile
    private var generation = 0
    private var endpoints: List<CallEndpointCompat> = emptyList()
    private var current: CallEndpointCompat? = null
    private var wantSpeaker = false

    /** The person hung up from somewhere else (a headset, the car, the system's call screen). */
    var onRemoteHangUp: (() -> Unit)? = null

    /** The system muted or unmuted the microphone (a headset button, the car). */
    var onMuteChanged: ((Boolean) -> Unit)? = null

    /** The system could not take the call: the app routes its own sound. */
    var onUnavailable: (() -> Unit)? = null

    /** Where the call's sound can go now, and where it goes (the system's list). */
    var onRoutes: ((routes: List<AudioRoute>, current: String?) -> Unit)? = null

    /** A place the person picked by hand: kept while it is there (a headset coming does not undo it). */
    private var picked: String? = null

    /** Whether the system has the call now (then the sound's route is its job). */
    val active: Boolean get() = control != null

    val available: Boolean get() = manager != null

    /**
     * Hands a call to the system once it is answered: an outgoing one when the other side picks up ([active]), an
     * incoming one at the moment we answer. The ringing and the dialling are Qeyf's own (they work on every phone), so a
     * call given up before it was answered never sits in the system as "connecting", blocking the next one.
     */
    fun start(peer: ChatUser, video: Boolean, incoming: Boolean, active: Boolean = false) {
        val m = manager
        if (m == null) {
            onUnavailable?.invoke()
            return
        }
        end(DisconnectCause.LOCAL)
        val gen = ++generation
        wantSpeaker = video
        val type = if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        job = scope.launch {
            try {
                m.addCall(
                    CallAttributesCompat(
                        displayName = peer.name,
                        address = Uri.parse("qeyf:${peer.uid}"),
                        direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
                        callType = type,
                        callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
                    ),
                    onAnswer = { },
                    onDisconnect = { onRemoteHangUp?.invoke() },
                    onSetActive = { },
                    onSetInactive = { },
                ) {
                    // Ended while the system was still taking it: told at once, so no call is left behind in the
                    // phone (which would then think it is still on a call).
                    if (gen != generation) {
                        launch { runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000) { disconnect(DisconnectCause(DisconnectCause.LOCAL)) } } }
                        return@addCall
                    }
                    open += this
                    control = this
                    if (incoming) launch { answer(type) } else if (active) launch { runCatching { setActive() } }
                    launch {
                        availableEndpoints.collect { list ->
                            endpoints = list
                            route()
                            publish()
                        }
                    }
                    launch {
                        currentCallEndpoint.collect {
                            current = it
                            publish()
                        }
                    }
                    launch { isMuted.collect { onMuteChanged?.invoke(it) } }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                control = null
                onUnavailable?.invoke()
            }
        }
    }

    /** The other side answered (or we did): the call is live. */
    fun setActive() {
        val c = control ?: return
        scope.launch { runCatching { c.setActive() } }
    }

    /** Speaker on, or back to the headset / earpiece. False when the system does not have the call (route it by hand). */
    fun setSpeaker(on: Boolean): Boolean {
        if (control == null) return false
        wantSpeaker = on
        route()
        return true
    }

    private fun idOf(e: CallEndpointCompat) = e.identifier.toString()

    private fun publish() {
        val routes = endpoints.map { e ->
            AudioRoute(
                id = idOf(e),
                kind = when (e.type) {
                    CallEndpointCompat.TYPE_SPEAKER -> AudioRouteKind.SPEAKER
                    CallEndpointCompat.TYPE_BLUETOOTH -> AudioRouteKind.BLUETOOTH
                    CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioRouteKind.WIRED
                    else -> AudioRouteKind.EARPIECE
                },
                name = e.name.toString(),
            )
        }
        onRoutes?.invoke(routes, current?.let(::idOf))
    }

    /** The person picked where the sound goes. False when the system does not have the call. */
    fun select(id: String): Boolean {
        val c = control ?: return false
        val target = endpoints.firstOrNull { idOf(it) == id } ?: return false
        picked = id
        wantSpeaker = target.type == CallEndpointCompat.TYPE_SPEAKER
        scope.launch {
            val result = runCatching { c.requestEndpointChange(target) }.getOrNull()
            if (result is CallControlResult.Success) {
                current = target
                publish()
            }
        }
        return true
    }

    /** Sends the sound where it should go: speaker if asked, else a headset or Bluetooth if one is there, else the earpiece. */
    private fun route() {
        val c = control ?: return
        val list = endpoints.ifEmpty { return }
        // What the person picked stays as long as it is there.
        picked?.let { id -> if (list.any { idOf(it) == id }) return else picked = null }
        val target = if (wantSpeaker) {
            list.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
        } else {
            list.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
                ?: list.firstOrNull { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
                ?: list.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }
        } ?: return
        if (current?.type == target.type) return
        scope.launch {
            val result = runCatching { c.requestEndpointChange(target) }.getOrNull()
            if (result is CallControlResult.Success) current = target
        }
    }

    /** The call is over: the system is told why ([DisconnectCause] codes), and lets the call go. */
    fun end(cause: Int) {
        generation++
        val c = control
        val j = job
        control = null
        job = null
        endpoints = emptyList()
        current = null
        picked = null
        val all = synchronized(open) { open.toList().also { open.clear() } }
        if (all.isEmpty() && j == null) return
        scope.launch {
            // Each one told it is over (never waiting for ever on a system that does not answer).
            for (x in all) {
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(3_000) { x.disconnect(DisconnectCause(if (x === c) cause else DisconnectCause.LOCAL)) }
                }
            }
            // The call's own work stops only after the system was told; if it was still being added, the check in
            // [start] lets it go as soon as it is in.
            if (all.isNotEmpty()) j?.cancel()
        }
    }

    /** No call should be with the system now (none going on in the app): whatever is left there is let go. */
    fun ensureIdle() {
        if (control != null || open.isNotEmpty()) end(DisconnectCause.LOCAL)
    }
}
