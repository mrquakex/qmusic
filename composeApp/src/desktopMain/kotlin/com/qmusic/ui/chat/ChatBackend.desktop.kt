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

@androidx.compose.runtime.Composable
actual fun rememberVoiceRecorder(): VoiceRecorder? = null

@androidx.compose.runtime.Composable
actual fun rememberVoicePlayer(): VoicePlayer? = null

@androidx.compose.runtime.Composable
actual fun rememberFilePicker(video: Boolean, onPicked: (PickedFile?) -> Unit): () -> Unit = {}

@androidx.compose.runtime.Composable
actual fun rememberAttachmentOpener(): (ChatAttachment) -> Unit = {}

@androidx.compose.runtime.Composable
actual fun VideoMessagePlayer(url: String, modifier: androidx.compose.ui.Modifier) = Unit
