package com.qmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color

/**
 * Back on the web: the browser's own back (a button, the phone's back gesture, an edge swipe on the iPhone). Every
 * page that wants Back puts a step in the browser's history; Back takes the newest wanting one off.
 */
object WebBack {
    internal class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()

    internal fun register(entry: Entry) {
        entries.add(entry)
        sync()
    }

    internal fun unregister(entry: Entry) {
        entries.remove(entry)
        sync()
    }

    internal fun changed() = sync()

    /** How many steps of our own the browser's history has above the page itself. */
    private var steps = 0

    /** Our steps follow how many pages want Back: one step for as long as any wants it. */
    private fun sync() {
        val want = if (entries.any { it.enabled }) 1 else 0
        if (want > steps) {
            pushHistoryStep()
            steps = want
        }
    }

    /** The browser went back (its button or gesture): the newest page wanting it gets it. */
    fun onBrowserBack() {
        steps = 0
        val target = entries.lastOrNull { it.enabled }
        target?.onBack?.invoke()
        sync()
    }

    fun dispatchBack(): Boolean {
        val target = entries.lastOrNull { it.enabled } ?: return false
        target.onBack()
        return true
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    val entry = remember { WebBack.Entry(enabled) { currentOnBack() } }
    SideEffect {
        if (entry.enabled != enabled) {
            entry.enabled = enabled
            WebBack.changed()
        }
    }
    DisposableEffect(entry) {
        WebBack.register(entry)
        onDispose { WebBack.unregister(entry) }
    }
}

@Composable
actual fun PlatformPredictiveBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    PlatformBackHandler(enabled, onBack)
}

@Composable
actual fun rememberShare(): (String) -> Unit = { text -> shareText(text) }

@Composable
actual fun rememberDominantColor(url: String?): Color? = null

@JsFun("() => history.pushState({ nuvelo: Date.now() }, '')")
private external fun pushHistoryStep()

@JsFun(
    """(text) => {
        if (navigator.share) { navigator.share({ text }).catch(() => {}); return; }
        if (navigator.clipboard) navigator.clipboard.writeText(text).catch(() => {});
    }""",
)
private external fun shareText(text: String)
