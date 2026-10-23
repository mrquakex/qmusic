package com.qmusic.ui

import androidx.compose.runtime.Composable

/** Intercepts the system Back gesture/button while [enabled]; a no-op where there is no system Back. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
