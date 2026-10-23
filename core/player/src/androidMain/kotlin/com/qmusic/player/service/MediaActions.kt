package com.qmusic.player.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The bridge between the notification's like button and the app: the app says whether the playing song is liked
 * and what a tap should do; the media service draws the button and forwards taps.
 */
object MediaActions {
    const val LIKE = "com.qmusic.action.LIKE"

    /** Whether the song that is playing is liked. */
    val liked = MutableStateFlow(false)

    /** What tapping the heart does; null while the app is not around to do it. */
    @Volatile
    var onToggleLike: (() -> Unit)? = null
}
