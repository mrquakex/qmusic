package com.qmusic.ui.chat

import androidx.compose.runtime.Composable

@Composable
actual fun rememberChatBackend(): ChatBackend? = null

@Composable
actual fun CallVideo(service: CallService, remote: Boolean, modifier: androidx.compose.ui.Modifier) = Unit

@Composable
actual fun rememberCallPermissions(): (video: Boolean, onResult: (Boolean) -> Unit) -> Unit = { _, onResult -> onResult(false) }

@Composable
actual fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): () -> Unit = {}

@Composable
actual fun rememberCamera(onPicked: (PickedImage?) -> Unit): () -> Unit = {}
