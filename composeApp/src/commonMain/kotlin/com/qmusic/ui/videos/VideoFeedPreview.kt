package com.qmusic.ui.videos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qmusic.engine.video.VideoItem
import com.qmusic.engine.video.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The silent preview of a list, as on YouTube's home: when the list stops, the first video wholly in view plays
 * without sound over its picture; a scroll stops it. Only on Wi-Fi (or an unmetered network) and when the setting is
 * on. The controller fills [resolve]/[allowed]; the phone's part fills [play]/[stop] and draws [FeedPreviewSurface].
 */
object FeedPreview {
    /** The video playing silently now, and whether its first picture is on screen. */
    val playing = MutableStateFlow<String?>(null)
    val showing = MutableStateFlow(false)

    /** The light stream to preview [VideoItem] with (null: it has none, or it is not allowed now). */
    var resolve: suspend (VideoItem) -> VideoQuality? = { null }
    var allowed: () -> Boolean = { false }

    var play: (VideoQuality) -> Unit = {}
    var stop: () -> Unit = {}

    /** How far the preview has played (0-1) and what is left of the video, for the line and the time on its picture. */
    val progress = MutableStateFlow(0f)
    val remainingMs = MutableStateFlow(0L)

    /** Where the preview is now (opening the video goes on from there, as YouTube does). */
    var position: () -> Long = { 0L }

    /** Silent unless the person turns its sound on (kept while the app is open, as on YouTube). */
    val muted = MutableStateFlow(true)
    var applyMuted: (Boolean) -> Unit = {}

    fun setMuted(on: Boolean) {
        muted.value = on
        applyMuted(on)
    }

    fun stopAll() {
        if (playing.value == null) return
        playing.value = null
        showing.value = false
        progress.value = 0f
        remainingMs.value = 0L
        stop()
    }
}

/** The preview player's picture, drawn over the card that plays it. */
@Composable
expect fun FeedPreviewSurface(modifier: Modifier)
