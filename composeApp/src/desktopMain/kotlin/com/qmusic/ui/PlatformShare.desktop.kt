package com.qmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

@Composable
actual fun rememberShare(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) { { text -> clipboard.setText(AnnotatedString(text)) } }
}
