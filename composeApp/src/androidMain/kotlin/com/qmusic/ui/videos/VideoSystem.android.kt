package com.qmusic.ui.videos

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** What the activity needs to know about the video (it asks for the floating window when the person leaves). */
object VideoPip {
    @Volatile
    var armed: Boolean = false
    val active = MutableStateFlow(false)
}

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
actual fun FullscreenEffect(enabled: Boolean) {
    val activity = LocalContext.current.activity()
    DisposableEffect(enabled, activity) {
        val window = activity?.window
        if (activity != null && window != null && enabled) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            onDispose { }
        }
    }
}

@Composable
actual fun PictureInPictureEffect(armed: Boolean) {
    DisposableEffect(armed) {
        VideoPip.armed = armed
        onDispose { VideoPip.armed = false }
    }
}

@Composable
actual fun rememberInPictureInPicture(): Boolean {
    val active by VideoPip.active.collectAsState()
    return active
}
