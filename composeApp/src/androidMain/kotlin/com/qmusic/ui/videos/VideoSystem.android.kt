package com.qmusic.ui.videos

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

/** What the app does for a video that keeps playing out of sight (see MainActivity): set where the service is. */
object VideoBackground {
    var onState: ((active: Boolean, title: String?) -> Unit)? = null
    var toggle: (() -> Unit)? = null
}

private class AndroidDeviceControls(private val activity: Activity) : DeviceControls {
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override var brightness: Float
        get() {
            val own = activity.window.attributes.screenBrightness
            if (own >= 0f) return own
            return runCatching { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
        }
        set(value) {
            val attributes = activity.window.attributes
            attributes.screenBrightness = value.coerceIn(0.02f, 1f)
            activity.window.attributes = attributes
        }

    override var volume: Float
        get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        set(value) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value.coerceIn(0f, 1f) * max).toInt(), 0)
        }
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
actual fun VideoBackgroundEffect(active: Boolean, title: String?, onToggle: () -> Unit) {
    val latest by rememberUpdatedState(onToggle)
    DisposableEffect(Unit) {
        VideoBackground.toggle = { latest() }
        onDispose {
            VideoBackground.toggle = null
            VideoBackground.onState?.invoke(false, null)
        }
    }
    DisposableEffect(active, title) {
        VideoBackground.onState?.invoke(active, title)
        onDispose { }
    }
}

@Composable
actual fun rememberDeviceControls(): DeviceControls? {
    val activity = LocalContext.current.activity()
    // The window's brightness goes back to the system's when the section is left.
    DisposableEffect(activity) {
        onDispose {
            activity?.let {
                val attributes = it.window.attributes
                attributes.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.window.attributes = attributes
            }
        }
    }
    return remember(activity) { activity?.let(::AndroidDeviceControls) }
}

@Composable
actual fun rememberInPictureInPicture(): Boolean {
    val active by VideoPip.active.collectAsState()
    return active
}
