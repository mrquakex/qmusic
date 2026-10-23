package com.qmusic.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences the app keeps between launches. */
interface AppSettings {
    /** Every song that is liked (on this device or on YouTube Music) is downloaded automatically. */
    val autoDownloadLiked: StateFlow<Boolean>

    /** Automatic downloads wait for an unmetered connection (Wi-Fi) instead of using mobile data. */
    val wifiOnly: StateFlow<Boolean>

    fun setAutoDownloadLiked(enabled: Boolean)

    fun setWifiOnly(enabled: Boolean)
}

/** Keeps the values for the life of the process only (tests, desktop previews). */
class InMemoryAppSettings(
    autoDownloadLiked: Boolean = false,
    wifiOnly: Boolean = true,
) : AppSettings {
    private val _auto = MutableStateFlow(autoDownloadLiked)
    override val autoDownloadLiked: StateFlow<Boolean> = _auto.asStateFlow()

    private val _wifi = MutableStateFlow(wifiOnly)
    override val wifiOnly: StateFlow<Boolean> = _wifi.asStateFlow()

    override fun setAutoDownloadLiked(enabled: Boolean) {
        _auto.value = enabled
    }

    override fun setWifiOnly(enabled: Boolean) {
        _wifi.value = enabled
    }
}
