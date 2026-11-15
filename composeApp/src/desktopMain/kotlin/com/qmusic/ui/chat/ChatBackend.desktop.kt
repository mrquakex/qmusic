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

@androidx.compose.runtime.Composable
actual fun PhotoEditor(image: PickedImage, onDone: (PickedImage) -> Unit, onCancel: () -> Unit) = onCancel()

@androidx.compose.runtime.Composable
actual fun GroupCallVideo(service: GroupCallService, uid: String?, modifier: androidx.compose.ui.Modifier) = Unit

@androidx.compose.runtime.Composable
actual fun rememberLocationPicker(onPicked: (ChatLocation) -> Unit, onError: (String) -> Unit): () -> Unit = { onError("none") }

@androidx.compose.runtime.Composable
actual fun rememberContactPicker(onPicked: (ChatContact) -> Unit): () -> Unit = {}

@androidx.compose.runtime.Composable
actual fun rememberContactSaver(): (ChatContact) -> Unit = {}

@androidx.compose.runtime.Composable
actual fun rememberAppLock(): AppLock? = null

@androidx.compose.runtime.Composable
actual fun rememberChatSounds(): ChatSounds? = null

@androidx.compose.runtime.Composable
actual fun CallPictureInPicture(armed: Boolean): Boolean = false

@androidx.compose.runtime.Composable
actual fun QrCode(text: String, modifier: androidx.compose.ui.Modifier) = Unit

@androidx.compose.runtime.Composable
actual fun rememberQrScanner(onResult: (String) -> Unit): (() -> Unit)? = null

@androidx.compose.runtime.Composable
actual fun StatusVideo(url: String, paused: Boolean, modifier: androidx.compose.ui.Modifier, onReady: (Long) -> Unit) = Unit

@androidx.compose.runtime.Composable
actual fun rememberTextFileShare(): (fileName: String, text: String) -> Unit = { _, _ -> }
