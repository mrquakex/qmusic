package com.qmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun PlatformPredictiveBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    val progress = androidx.compose.runtime.rememberUpdatedState(onProgress)
    val cancel = androidx.compose.runtime.rememberUpdatedState(onCancel)
    val back = androidx.compose.runtime.rememberUpdatedState(onBack)
    androidx.activity.compose.PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { progress.value(it.progress) }
            back.value()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            cancel.value()
            throw e
        }
    }
}
