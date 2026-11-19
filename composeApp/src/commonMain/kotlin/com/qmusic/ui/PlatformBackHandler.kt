package com.qmusic.ui

import androidx.compose.runtime.Composable

/** Intercepts the system Back gesture/button while [enabled]; a no-op where there is no system Back. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * As [PlatformBackHandler], with the back gesture as it happens: [onProgress] (0-1) while the finger moves, [onCancel]
 * if it is let go without going back, [onBack] when it goes back (a plain Back press too).
 */
@Composable
expect fun PlatformPredictiveBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onCancel: () -> Unit, onBack: () -> Unit)
