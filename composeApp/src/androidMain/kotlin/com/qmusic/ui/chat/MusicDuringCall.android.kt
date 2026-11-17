package com.qmusic.ui.chat

import com.qmusic.player.PlayerHolder

/**
 * The music stops for a call (ringing, calling out, answered, a group call) and comes back after it, if the call
 * stopped it: the player keeps its own audio focus handling off (for its crossfades), so it does not stop by itself.
 */
internal object MusicDuringCall {
    @Volatile private var pausedByCall = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The player is driven from the main thread only (a call can begin from a push, on another thread). */
    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else main.post(block)
    }

    fun pause() = onMain {
        val player = PlayerHolder.peek() ?: return@onMain
        if (player.isPlaying.value) {
            player.pause()
            pausedByCall = true
        }
    }

    /** After the call: plays on where it was, only when it was the call that stopped it. */
    fun resume() = onMain {
        if (!pausedByCall) return@onMain
        pausedByCall = false
        PlayerHolder.peek()?.resume()
    }
}
