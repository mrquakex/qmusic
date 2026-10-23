package com.qmusic.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences the app keeps between launches. */
interface AppSettings {
    /** Language code: "tr" (Turkish, default) or "en" (English). */
    val language: StateFlow<String>

    /** Every song that is liked (on this device or on YouTube Music) is downloaded automatically. */
    val autoDownloadLiked: StateFlow<Boolean>

    /** Automatic downloads wait for an unmetered connection (Wi-Fi) instead of using mobile data. */
    val wifiOnly: StateFlow<Boolean>

    /** The player opens on the music video (when a song has one) instead of the cover. */
    val videoByDefault: StateFlow<Boolean>

    /** Look for a new version each time the app opens. */
    val autoCheckUpdates: StateFlow<Boolean>

    fun setLanguage(code: String)

    /** Show a notification when a new version is released. */
    val updateNotifications: StateFlow<Boolean>

    fun setUpdateNotifications(enabled: Boolean)

    /** A new version is downloaded and installed without asking first (Android may still show its own sheet). */
    val autoUpdate: StateFlow<Boolean>

    fun setAutoUpdate(enabled: Boolean)

    fun setAutoDownloadLiked(enabled: Boolean)

    fun setVideoByDefault(enabled: Boolean)

    fun setAutoCheckUpdates(enabled: Boolean)

    fun setWifiOnly(enabled: Boolean)
}

/** Keeps the values for the life of the process only (tests, desktop previews). */
class InMemoryAppSettings(
    language: String = "tr",
    autoDownloadLiked: Boolean = false,
    wifiOnly: Boolean = true,
) : AppSettings {
    private val _language = MutableStateFlow(language)
    override val language: StateFlow<String> = _language.asStateFlow()

    private val _auto = MutableStateFlow(autoDownloadLiked)
    override val autoDownloadLiked: StateFlow<Boolean> = _auto.asStateFlow()

    private val _wifi = MutableStateFlow(wifiOnly)
    override val wifiOnly: StateFlow<Boolean> = _wifi.asStateFlow()

    private val _video = MutableStateFlow(false)
    override val videoByDefault: StateFlow<Boolean> = _video.asStateFlow()

    private val _check = MutableStateFlow(true)
    override val autoCheckUpdates: StateFlow<Boolean> = _check.asStateFlow()

    override fun setLanguage(code: String) {
        _language.value = code
    }

    override fun setVideoByDefault(enabled: Boolean) {
        _video.value = enabled
    }

    override fun setAutoCheckUpdates(enabled: Boolean) {
        _check.value = enabled
    }

    private val _autoUpdate = MutableStateFlow(true)
    override val autoUpdate: StateFlow<Boolean> = _autoUpdate.asStateFlow()

    override fun setAutoUpdate(enabled: Boolean) {
        _autoUpdate.value = enabled
    }

    private val _notify = MutableStateFlow(true)
    override val updateNotifications: StateFlow<Boolean> = _notify.asStateFlow()

    override fun setUpdateNotifications(enabled: Boolean) {
        _notify.value = enabled
    }

    override fun setAutoDownloadLiked(enabled: Boolean) {
        _auto.value = enabled
    }

    override fun setWifiOnly(enabled: Boolean) {
        _wifi.value = enabled
    }
}
