package com.qmusic.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/** The music player or the video player, as listening / watching together sees it. */
interface TogetherPlayer {
    /** What plays now (null when nothing). */
    fun current(): SharedItem?
    fun isPlaying(): Boolean
    fun positionMs(): Long

    /** Plays [item] from [positionMs] (playing or paused as [play] says). */
    fun open(item: SharedItem, positionMs: Long, play: Boolean)
    fun seekTo(ms: Long)
    fun play()
    fun pause()

    /** Called twice a second while together: a chance to finish an [open] once the player is ready. */
    fun tick() {}
}

/** The video player of the Videos section while it exists (set by the section). */
object TogetherVideo {
    var playback: com.qmusic.ui.videos.VideoPlayback? = null
}

/**
 * Listening to a song or watching a video together in a conversation. Whoever plays, pauses, seeks or changes the song
 * writes the session (together/{chatId}); everyone else's player follows it, where it is *now* on the server's clock
 * (so a song that went on for 3 seconds since is joined 3 seconds later, not at the written place).
 */
class TogetherController(
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val myUid: () -> String?,
    private val players: (ShareKind) -> TogetherPlayer?,
) {
    private val _active = MutableStateFlow<TogetherState?>(null)

    /** The session this phone takes part in (null when none). */
    val active: StateFlow<TogetherState?> = _active.asStateFlow()

    private var job: Job? = null
    private var quietUntil = 0L

    private fun now() = Clock.System.now().toEpochMilliseconds()
    private fun serverNow() = now() + backend.serverOffset.value

    /** Where the session is now: its written place, plus the time since when it plays. */
    private fun expected(state: TogetherState): Long =
        if (state.playing) state.positionMs + (serverNow() - state.at).coerceIn(0, 6 * 3600_000L) else state.positionMs

    /** Starts listening / watching [item] together in [chatId], from where it plays here, and invites the others. */
    fun start(chatId: String, item: SharedItem, invite: suspend () -> Unit) {
        val player = players(item.kind) ?: return
        val pos = if (player.current()?.id == item.id) player.positionMs() else 0L
        val state = TogetherState(chatId, item, playing = true, positionMs = pos, at = serverNow(), by = myUid().orEmpty())
        scope.launch {
            backend.setTogether(chatId, state)
            runCatching { invite() }
        }
        if (player.current()?.id != item.id) player.open(item, 0, true) else if (!player.isPlaying()) player.play()
        follow(chatId)
    }

    /** Joins the session of [chatId]: plays what it plays, where it is now. */
    fun join(chatId: String) = follow(chatId)

    /** Stops following; the last one to leave ends the session. */
    fun leave(endForAll: Boolean = false) {
        val chatId = _active.value?.chatId
        job?.cancel()
        job = null
        _active.value = null
        if (endForAll && chatId != null) scope.launch { backend.setTogether(chatId, null) }
    }

    private fun follow(chatId: String) {
        job?.cancel()
        job = scope.launch {
            // What arrives from the others.
            val listening = launch {
                var first = true
                backend.together(chatId).collect { state ->
                    if (state == null) {
                        if (!first || _active.value != null) {
                            _active.value = null
                            job?.cancel()
                        }
                        return@collect
                    }
                    first = false
                    _active.value = state
                    if (state.by != myUid()) apply(state)
                }
            }
            // What this phone does, sent to the others.
            var last: Snapshot? = null
            while (isActive) {
                delay(TICK_MS)
                val state = _active.value ?: continue
                val player = players(state.item.kind) ?: continue
                player.tick()
                val item = player.current() ?: continue
                val snap = Snapshot(item.id, player.isPlaying(), player.positionMs(), now())
                val before = last
                last = snap
                if (before == null || now() < quietUntil) continue
                val drift = snap.pos - (before.pos + if (before.playing) snap.time - before.time else 0)
                val changed = snap.id != before.id || snap.playing != before.playing || kotlin.math.abs(drift) > SEEK_MS
                // A change that is only the session arriving here is not sent back.
                val same = snap.id == state.item.id && snap.playing == state.playing && kotlin.math.abs(snap.pos - expected(state)) < SEEK_MS
                if (changed && !same) {
                    val next = TogetherState(chatId, item, snap.playing, snap.pos, serverNow(), myUid().orEmpty())
                    _active.value = next
                    backend.setTogether(chatId, next)
                }
            }
            listening.cancel()
        }
    }

    private fun apply(state: TogetherState) {
        val player = players(state.item.kind) ?: return
        quietUntil = now() + QUIET_MS
        val target = expected(state)
        if (player.current()?.id != state.item.id) {
            player.open(state.item, target, state.playing)
            return
        }
        if (kotlin.math.abs(player.positionMs() - target) > DRIFT_MS) player.seekTo(target)
        if (state.playing && !player.isPlaying()) player.play()
        if (!state.playing && player.isPlaying()) player.pause()
    }

    private data class Snapshot(val id: String, val playing: Boolean, val pos: Long, val time: Long)

    private companion object {
        const val TICK_MS = 500L
        const val SEEK_MS = 2_500L
        const val DRIFT_MS = 1_500L
        const val QUIET_MS = 2_500L
    }
}
