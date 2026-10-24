package com.qmusic.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Spotify's own sign-in page; [onSignedIn] once the account's session is there. */
@Composable
expect fun SpotifyLoginView(
    onSignedIn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
