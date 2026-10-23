package com.qmusic.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun YouTubeLoginView(
    onCookiesCaptured: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
