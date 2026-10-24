package com.qmusic.app

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.qmusic.player.PlayerHolder
import com.qmusic.player.service.SimpleMediaService
import com.qmusic.settings.AppSettings
import com.qmusic.settings.SettingKeys

/**
 * Settings › Play when headphones connect: when Bluetooth or wired headphones arrive and a song is paused, it
 * carries on. (Unplugging pauses already: the player handles "audio becoming noisy".) Listens to the audio devices,
 * which needs no Bluetooth permission.
 */
object HeadsetResume {
    private val HEADSET_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        BLE_HEADSET,
    )

    fun start(context: Context, settings: AppSettings) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val main = Handler(Looper.getMainLooper())
        var ready = false
        audio.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    // The first call lists what is connected already; only devices that arrive later count.
                    if (!ready || !settings.bool(SettingKeys.HEADSET_RESUME, false).value) return
                    if (addedDevices.none { it.isSink && it.type in HEADSET_TYPES }) return
                    val player = PlayerHolder.peek() ?: return
                    if (player.currentTrack.value == null || player.isPlaying.value) return
                    runCatching { context.startService(Intent(context, SimpleMediaService::class.java)) }
                    player.resume()
                }
            },
            main,
        )
        main.postDelayed({ ready = true }, SETTLE_MS)
    }

    /** AudioDeviceInfo.TYPE_BLE_HEADSET (Android 12); the number works on older versions too, it just never shows. */
    private const val BLE_HEADSET = 26
    private const val SETTLE_MS = 2_000L
}
