package com.qmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.util.concurrent.CopyOnWriteArrayList

object DesktopBackDispatcher {
    internal class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = CopyOnWriteArrayList<Entry>()

    internal fun register(entry: Entry) {
        entries.add(entry)
    }

    internal fun unregister(entry: Entry) {
        entries.remove(entry)
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
    val entry = remember { DesktopBackDispatcher.Entry(enabled) { currentOnBack() } }
    SideEffect {
        entry.enabled = enabled
        entry.onBack = { currentOnBack() }
    }
    DisposableEffect(entry) {
        DesktopBackDispatcher.register(entry)
        onDispose { DesktopBackDispatcher.unregister(entry) }
    }
}
