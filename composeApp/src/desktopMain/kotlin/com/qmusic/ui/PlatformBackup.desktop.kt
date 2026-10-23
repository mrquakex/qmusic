package com.qmusic.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberBackupFiles(
    content: suspend () -> String,
    onSaved: (Boolean) -> Unit,
    onOpened: (String) -> Unit,
): BackupFiles? = null
