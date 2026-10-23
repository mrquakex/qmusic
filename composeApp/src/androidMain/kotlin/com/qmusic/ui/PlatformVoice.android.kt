package com.qmusic.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberVoiceSearch(onResult: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(latest)
    }
    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    val available = remember { intent.resolveActivity(context.packageManager) != null }
    return if (available) {
        { runCatching { launcher.launch(intent) } }
    } else {
        null
    }
}

@Composable
actual fun rememberSongRecognition(): (() -> Unit)? {
    val context = LocalContext.current
    val intent = remember {
        listOf(
            Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH"),
            Intent("com.shazam.android.intent.actions.START_TAGGING"),
        ).firstOrNull { it.resolveActivity(context.packageManager) != null }
    } ?: return null
    return { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
