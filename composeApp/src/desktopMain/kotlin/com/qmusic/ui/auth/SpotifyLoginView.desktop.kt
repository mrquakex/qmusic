package com.qmusic.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.DarkBackground

@Composable
actual fun SpotifyLoginView(
    onSignedIn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val s = LocalStrings.current
    Box(modifier.fillMaxSize().background(DarkBackground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.spotifyNotSupported, color = Color.White)
            TextButton(onClick = onDismiss) { Text(s.close) }
        }
    }
}
