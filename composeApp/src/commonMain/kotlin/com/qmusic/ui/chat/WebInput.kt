package com.qmusic.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A mouse is what points (a computer's browser): rows light up under it, a right click opens what a long press opens
 * on the phone, and what can be clicked shows a hand. Off on the phone, where nothing of this changes.
 */
val LocalMouse = staticCompositionLocalOf { false }

/** Running in a browser (the web page): the phone's own settings (sounds, the music, the start screen) are left out. */
object ChatPlatform {
    var web: Boolean = false
}

/** A right click runs [action] (the long press's menu on a computer). */
@Composable
fun Modifier.rightClick(action: () -> Unit): Modifier {
    if (!LocalMouse.current) return this
    val latest by rememberUpdatedState(action)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                    e.changes.forEach { it.consume() }
                    latest()
                }
            }
        }
    }
}

/** Under the mouse: a faint light over it, and a hand for the pointer. */
@Composable
fun Modifier.hoverLight(color: Color = Color.White.copy(alpha = 0.05f)): Modifier {
    if (!LocalMouse.current) return this
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return hoverable(source).pointerHoverIcon(PointerIcon.Hand).then(if (hovered) Modifier.background(color) else Modifier)
}
