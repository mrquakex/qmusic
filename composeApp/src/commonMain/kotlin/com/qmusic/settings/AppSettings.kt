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

    /** Crossfade length in seconds. */
    val crossfadeSeconds: StateFlow<Int>

    /** The ten equalizer gains as "g0,g1,...", empty for flat; and the name of the chosen preset. */
    val equalizerBands: StateFlow<String>
    val equalizerPreset: StateFlow<String>

    fun setCrossfadeSeconds(seconds: Int)

    fun setEqualizer(bands: String, preset: String)

    /** The queue, the song in it and the moment it was at, so the next launch can carry on (JSON); null if none. */
    fun loadSession(): String?

    fun saveSession(json: String)

    /** Stream quality on Wi-Fi and on mobile data: "HIGH", "NORMAL" or "LOW". */
    val audioQualityWifi: StateFlow<String>
    val audioQualityMobile: StateFlow<String>

    /** Every song at the same loudness. */
    val normalizeVolume: StateFlow<Boolean>

    fun setAudioQualityWifi(quality: String)

    fun setAudioQualityMobile(quality: String)

    fun setNormalizeVolume(enabled: Boolean)

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

    private val _crossfade = MutableStateFlow(3)
    override val crossfadeSeconds: StateFlow<Int> = _crossfade.asStateFlow()

    private val _bands = MutableStateFlow("")
    override val equalizerBands: StateFlow<String> = _bands.asStateFlow()

    private val _preset = MutableStateFlow("FLAT")
    override val equalizerPreset: StateFlow<String> = _preset.asStateFlow()

    private var session: String? = null

    override fun setCrossfadeSeconds(seconds: Int) {
        _crossfade.value = seconds
    }

    override fun setEqualizer(bands: String, preset: String) {
        _bands.value = bands
        _preset.value = preset
    }

    override fun loadSession(): String? = session

    override fun saveSession(json: String) {
        session = json
    }

    private val _qualityWifi = MutableStateFlow("HIGH")
    override val audioQualityWifi: StateFlow<String> = _qualityWifi.asStateFlow()

    private val _qualityMobile = MutableStateFlow("NORMAL")
    override val audioQualityMobile: StateFlow<String> = _qualityMobile.asStateFlow()

    private val _normalize = MutableStateFlow(true)
    override val normalizeVolume: StateFlow<Boolean> = _normalize.asStateFlow()

    override fun setAudioQualityWifi(quality: String) {
        _qualityWifi.value = quality
    }

    override fun setAudioQualityMobile(quality: String) {
        _qualityMobile.value = quality
    }

    override fun setNormalizeVolume(enabled: Boolean) {
        _normalize.value = enabled
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
