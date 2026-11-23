package com.qmusic.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Someone in a group call: connected to us yet, and whether their camera is on. */
data class GroupCallMember(val user: ChatUser, val connected: Boolean, val cameraOn: Boolean, val hasVideo: Boolean)

data class GroupCallState(
    val group: ChatUser,
    val video: Boolean,
    /** The others in the call. */
    val members: List<GroupCallMember>,
    val muted: Boolean = false,
    val speaker: Boolean = true,
    val cameraOn: Boolean = true,
    val frontCamera: Boolean = true,
    /** When the first other person connected (for the timer). */
    val startedAt: Long? = null,
)

/**
 * Group voice and video calls: everyone connects to everyone (up to [MAX_GROUP_CALL] people), through the same
 * relay as the one-to-one calls when phones cannot reach each other directly.
 */
interface GroupCallService {
    val state: StateFlow<GroupCallState?>

    /** Who is in the call of [groupId] now (empty when there is none). */
    fun participants(groupId: String): Flow<List<ChatUser>>

    /** Starts the group's call, or joins the one going on. */
    fun join(group: ChatUser, video: Boolean)
    fun leave()
    fun toggleMute()
    fun toggleSpeaker()
    fun toggleCamera()
    fun switchCamera()

    /** Whether someone can be added to the call going on (a call made by adding people to a call). */
    val canAddPeople: Boolean get() = false

    /** Lets [user] into the call going on and rings their phone. */
    fun addPerson(user: ChatUser) {}
}

/** A group call made by adding a person to a call (not a group's own call): it has no conversation of its own. */
fun isCallRoom(id: String): Boolean = id.startsWith("room") && id.length == 24

const val MAX_GROUP_CALL = 6

/** Draws a member's picture ([uid]), or our own camera (null). */
@Composable
expect fun GroupCallVideo(service: GroupCallService, uid: String?, modifier: Modifier)

/** A ringing group call's notification was tapped: open the group, and with [join] join its call. */
data class GroupCallRequest(val group: ChatUser, val video: Boolean, val join: Boolean)

object GroupCallLaunch {
    val request = kotlinx.coroutines.flow.MutableStateFlow<GroupCallRequest?>(null)
}
