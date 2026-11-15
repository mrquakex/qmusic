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
internal class CallTelecom(app: Context, private val scope: CoroutineScope) {
    private val manager: CallsManager? = runCatching {
        CallsManager(app).also { it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING) }
    }.getOrNull()

    private var control: CallControlScope? = null
    private var job: Job? = null
    private var endpoints: List<CallEndpointCompat> = emptyList()
    private var current: CallEndpointCompat? = null
    private var wantSpeaker = false

    /** The person hung up from somewhere else (a headset, the car, the system's call screen). */
    var onRemoteHangUp: (() -> Unit)? = null

    /** The system muted or unmuted the microphone (a headset button, the car). */
    var onMuteChanged: ((Boolean) -> Unit)? = null

    /** The system could not take the call: the app routes its own sound. */
    var onUnavailable: (() -> Unit)? = null

    /** Whether the system has the call now (then the sound's route is its job). */
    val active: Boolean get() = control != null

    val available: Boolean get() = manager != null

    /**
     * Hands a call to the system: an outgoing one as we dial, an incoming one at the moment it is answered (the ringing
     * itself is Qeyf's own notification, which works on every phone).
     */
    fun start(peer: ChatUser, video: Boolean, incoming: Boolean) {
        val m = manager
        if (m == null) {
            onUnavailable?.invoke()
            return
        }
        end(DisconnectCause.LOCAL)
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
                    control = this
                    if (incoming) launch { answer(type) }
                    launch {
                        availableEndpoints.collect { list ->
                            endpoints = list
                            route()
                        }
                    }
                    launch { currentCallEndpoint.collect { current = it } }
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

    /** Sends the sound where it should go: speaker if asked, else a headset or Bluetooth if one is there, else the earpiece. */
    private fun route() {
        val c = control ?: return
        val list = endpoints.ifEmpty { return }
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
        val c = control
        val j = job
        control = null
        job = null
        endpoints = emptyList()
        current = null
        if (c == null && j == null) return
        scope.launch {
            if (c != null) runCatching { c.disconnect(DisconnectCause(cause)) }
            j?.cancel()
        }
    }
}
