package com.qmusic.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberBackupFiles(
    content: suspend () -> String,
    onSaved: (Boolean) -> Unit,
    onOpened: (String) -> Unit,
): BackupFiles? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val makeContent by rememberUpdatedState(content)
    val saved by rememberUpdatedState(onSaved)
    val opened by rememberUpdatedState(onOpened)

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = makeContent()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("No stream")
                }
            }.isSuccess
            saved(ok)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text != null) opened(text) else saved(false)
        }
    }
    return remember {
        BackupFiles(
            save = { create.launch("qmusic-yedek.json") },
            open = { pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        )
    }
}
