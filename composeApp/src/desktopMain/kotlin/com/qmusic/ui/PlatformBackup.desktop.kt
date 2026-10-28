package com.qmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberBackupFiles(
    content: suspend () -> String,
    onSaved: (Boolean) -> Unit,
    onOpened: (String) -> Unit,
): BackupFiles? {
    val scope = rememberCoroutineScope()
    val makeContent by rememberUpdatedState(content)
    val saved by rememberUpdatedState(onSaved)
    val opened by rememberUpdatedState(onOpened)

    return remember {
        BackupFiles(
            save = {
                scope.launch {
                    val dialog = FileDialog(null as Frame?, "QMusic Yedek Kaydet", FileDialog.SAVE).apply {
                        file = "qmusic-yedek.json"
                        isVisible = true
                    }
                    val dir = dialog.directory
                    val fileName = dialog.file
                    if (dir == null || fileName == null) return@launch
                    val ok = runCatching {
                        val text = makeContent()
                        withContext(Dispatchers.IO) {
                            File(dir, fileName).writeText(text, Charsets.UTF_8)
                        }
                    }.isSuccess
                    saved(ok)
                }
            },
            open = {
                scope.launch {
                    val dialog = FileDialog(null as Frame?, "QMusic Yedek Yükle", FileDialog.LOAD).apply {
                        isVisible = true
                    }
                    val dir = dialog.directory
                    val fileName = dialog.file
                    if (dir == null || fileName == null) return@launch
                    val text = withContext(Dispatchers.IO) {
                        runCatching { File(dir, fileName).readText(Charsets.UTF_8) }.getOrNull()
                    }
                    if (text != null) opened(text) else saved(false)
                }
            },
        )
    }
}
