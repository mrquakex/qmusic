package com.qmusic.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.qmusic.engine.models.SongItem

/**
 * The moment a finger lands on a song (before it lifts and the tap counts), its stream starts being looked up and
 * its first seconds fetched: the tap then finds it (nearly) ready. Set by the app to the player's warm-up.
 */
object SongPress {
    var warm: (SongItem) -> Unit = {}
}

/** Gets [song] ready as soon as it is touched; the touch itself goes on to the click as before. */
fun Modifier.warmOnPress(song: SongItem): Modifier = pointerInput(song.id) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        SongPress.warm(song)
    }
}
