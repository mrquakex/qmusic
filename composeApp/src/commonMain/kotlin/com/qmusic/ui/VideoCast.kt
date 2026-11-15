package com.qmusic.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** A video playing on the TV: which, and how far it has got (read from the TV). */
data class VideoCastState(val videoId: String, val title: String, val positionMs: Long, val durationMs: Long, val playing: Boolean)

/**
 * Casting a QVideo video to a TV (Chromecast). The app's caster sets the functions and fills the state; the watch page
 * reads them. While a video is open, connecting to a TV sends that video (not the music).
 */
object VideoCast {
    /** A TV is connected now. */
    val connected = MutableStateFlow(false)

    /** The video open on the watch page (its id, title and picture), or null: what a new connection sends. */
    val watching = MutableStateFlow<Triple<String, String, String?>?>(null)

    /** The video on the TV now, or null when none is. */
    val state = MutableStateFlow<VideoCastState?>(null)

    /** A short message for the person ("could not open on the TV"). */
    val message = MutableStateFlow<String?>(null)

    var cast: ((videoId: String, title: String, thumbnail: String?, startMs: Long) -> Unit)? = null
    var playPause: (() -> Unit)? = null
    var seekTo: ((Long) -> Unit)? = null

    /** Ends the TV's playback and brings the video back to the phone at the same moment. */
    var stop: (() -> Unit)? = null

    /** Where the video was on the TV when it came back to the phone (the watch page goes on from there). */
    val backAt = MutableStateFlow<Pair<String, Long>?>(null)

    /** The video the person keeps on the phone while a TV is connected (brought back, or the TV could not play it). */
    val onPhone = MutableStateFlow<String?>(null)

    /** Where the phone's own player is in the open video (where the TV starts). */
    var phonePosition: (() -> Long)? = null
}
