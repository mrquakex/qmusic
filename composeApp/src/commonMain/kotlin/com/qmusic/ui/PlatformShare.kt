package com.qmusic.ui

import androidx.compose.runtime.Composable

/** Returns a function that offers [text] to the system share sheet (or the clipboard where there is none). */
@Composable
expect fun rememberShare(): (String) -> Unit
