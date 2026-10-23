package com.qmusic.ui

import androidx.compose.runtime.Composable

/** Writing and reading the backup file through the system's file picker. */
class BackupFiles(val save: () -> Unit, val open: () -> Unit)

/**
 * [content] makes the backup text; [onSaved] says whether it was written; [onOpened] gets the text of a chosen
 * file. Null where there is no file picker.
 */
@Composable
expect fun rememberBackupFiles(
    content: suspend () -> String,
    onSaved: (Boolean) -> Unit,
    onOpened: (String) -> Unit,
): BackupFiles?
