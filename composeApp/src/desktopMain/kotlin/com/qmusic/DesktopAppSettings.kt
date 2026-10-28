package com.qmusic

import com.qmusic.settings.AppSettings
import java.io.File
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * File-backed [AppSettings] for Desktop, stored in `~/.qmusic/settings.properties`
 * (and `~/.qmusic/last_session.json` for the saved playback queue).
 */
class DesktopAppSettings(
    private val dir: File = File(System.getProperty("user.home"), ".qmusic"),
) : AppSettings {
    private val prefsFile = File(dir, "settings.properties")
    private val sessionFile = File(dir, "last_session.json")
    private val props = Properties()

    init {
        dir.mkdirs()
        if (prefsFile.exists()) {
            runCatching {
                prefsFile.inputStream().use { props.load(it) }
            }
        }
    }

    @Synchronized
    private fun saveProps() {
        runCatching {
            dir.mkdirs()
            prefsFile.outputStream().use { props.store(it, "QMusic Desktop Settings") }
        }
    }

    private fun getStringProp(key: String, default: String): String =
        synchronized(this) { props.getProperty(key) ?: default }

    private fun getBoolProp(key: String, default: Boolean): Boolean =
        synchronized(this) { props.getProperty(key)?.toBooleanStrictOrNull() ?: default }

    private fun getIntProp(key: String, default: Int): Int =
        synchronized(this) { props.getProperty(key)?.toIntOrNull() ?: default }

    private fun putProp(key: String, value: String) {
        synchronized(this) {
            props.setProperty(key, value)
            saveProps()
        }
    }

    private val _language = MutableStateFlow(getStringProp(KEY_LANGUAGE, "tr"))
    override val language: StateFlow<String> = _language.asStateFlow()

    private val _auto = MutableStateFlow(getBoolProp(KEY_AUTO_DOWNLOAD, false))
    override val autoDownloadLiked: StateFlow<Boolean> = _auto.asStateFlow()

    private val _wifi = MutableStateFlow(getBoolProp(KEY_WIFI_ONLY, true))
    override val wifiOnly: StateFlow<Boolean> = _wifi.asStateFlow()

    private val _video = MutableStateFlow(getBoolProp(KEY_VIDEO, false))
    override val videoByDefault: StateFlow<Boolean> = _video.asStateFlow()

    private val _check = MutableStateFlow(getBoolProp(KEY_CHECK_UPDATES, true))
    override val autoCheckUpdates: StateFlow<Boolean> = _check.asStateFlow()

    override fun setLanguage(code: String) {
        putProp(KEY_LANGUAGE, code)
        _language.value = code
    }

    override fun setVideoByDefault(enabled: Boolean) {
        putProp(KEY_VIDEO, enabled.toString())
        _video.value = enabled
    }

    override fun setAutoCheckUpdates(enabled: Boolean) {
        putProp(KEY_CHECK_UPDATES, enabled.toString())
        _check.value = enabled
    }

    private val _crossfade = MutableStateFlow(getIntProp(KEY_CROSSFADE, 3))
    override val crossfadeSeconds: StateFlow<Int> = _crossfade.asStateFlow()

    private val _bands = MutableStateFlow(getStringProp(KEY_EQ_BANDS, ""))
    override val equalizerBands: StateFlow<String> = _bands.asStateFlow()

    private val _preset = MutableStateFlow(getStringProp(KEY_EQ_PRESET, "FLAT"))
    override val equalizerPreset: StateFlow<String> = _preset.asStateFlow()

    override fun setCrossfadeSeconds(seconds: Int) {
        putProp(KEY_CROSSFADE, seconds.toString())
        _crossfade.value = seconds
    }

    override fun setEqualizer(bands: String, preset: String) {
        synchronized(this) {
            props.setProperty(KEY_EQ_BANDS, bands)
            props.setProperty(KEY_EQ_PRESET, preset)
            saveProps()
        }
        _bands.value = bands
        _preset.value = preset
    }

    override fun loadSession(): String? =
        runCatching { sessionFile.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() } }.getOrNull()

    override fun saveSession(json: String) {
        runCatching {
            dir.mkdirs()
            sessionFile.writeText(json, Charsets.UTF_8)
        }
    }

    private val _qualityWifi = MutableStateFlow(getStringProp(KEY_QUALITY_WIFI, "HIGH"))
    override val audioQualityWifi: StateFlow<String> = _qualityWifi.asStateFlow()

    private val _qualityMobile = MutableStateFlow(getStringProp(KEY_QUALITY_MOBILE, "NORMAL"))
    override val audioQualityMobile: StateFlow<String> = _qualityMobile.asStateFlow()

    private val _normalize = MutableStateFlow(getBoolProp(KEY_NORMALIZE, true))
    override val normalizeVolume: StateFlow<Boolean> = _normalize.asStateFlow()

    override fun setAudioQualityWifi(quality: String) {
        putProp(KEY_QUALITY_WIFI, quality)
        _qualityWifi.value = quality
    }

    override fun setAudioQualityMobile(quality: String) {
        putProp(KEY_QUALITY_MOBILE, quality)
        _qualityMobile.value = quality
    }

    override fun setNormalizeVolume(enabled: Boolean) {
        putProp(KEY_NORMALIZE, enabled.toString())
        _normalize.value = enabled
    }

    private val _autoUpdate = MutableStateFlow(getBoolProp(KEY_AUTO_UPDATE, true))
    override val autoUpdate: StateFlow<Boolean> = _autoUpdate.asStateFlow()

    override fun setAutoUpdate(enabled: Boolean) {
        putProp(KEY_AUTO_UPDATE, enabled.toString())
        _autoUpdate.value = enabled
    }

    private val _notify = MutableStateFlow(getBoolProp(KEY_UPDATE_NOTIFICATIONS, true))
    override val updateNotifications: StateFlow<Boolean> = _notify.asStateFlow()

    override fun setUpdateNotifications(enabled: Boolean) {
        putProp(KEY_UPDATE_NOTIFICATIONS, enabled.toString())
        _notify.value = enabled
    }

    override fun setAutoDownloadLiked(enabled: Boolean) {
        putProp(KEY_AUTO_DOWNLOAD, enabled.toString())
        _auto.value = enabled
    }

    override fun setWifiOnly(enabled: Boolean) {
        putProp(KEY_WIFI_ONLY, enabled.toString())
        _wifi.value = enabled
    }

    private val bools = HashMap<String, MutableStateFlow<Boolean>>()
    private val strings = HashMap<String, MutableStateFlow<String>>()

    override fun bool(key: String, default: Boolean): StateFlow<Boolean> =
        synchronized(bools) { bools.getOrPut(key) { MutableStateFlow(getBoolProp(key, default)) } }

    override fun setBool(key: String, value: Boolean) {
        putProp(key, value.toString())
        synchronized(bools) { bools.getOrPut(key) { MutableStateFlow(value) } }.value = value
    }

    override fun string(key: String, default: String): StateFlow<String> =
        synchronized(strings) { strings.getOrPut(key) { MutableStateFlow(getStringProp(key, default)) } }

    override fun setString(key: String, value: String) {
        putProp(key, value)
        synchronized(strings) { strings.getOrPut(key) { MutableStateFlow(value) } }.value = value
    }

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_AUTO_DOWNLOAD = "auto_download_liked"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_UPDATE_NOTIFICATIONS = "update_notifications"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_QUALITY_WIFI = "audio_quality_wifi"
        const val KEY_CROSSFADE = "crossfade_seconds"
        const val KEY_EQ_BANDS = "equalizer_bands"
        const val KEY_EQ_PRESET = "equalizer_preset"
        const val KEY_QUALITY_MOBILE = "audio_quality_mobile"
        const val KEY_NORMALIZE = "normalize_volume"
        const val KEY_VIDEO = "video_by_default"
        const val KEY_CHECK_UPDATES = "check_updates_on_launch"
    }
}

class DesktopYouTubeSessionStore(
    dir: File = File(System.getProperty("user.home"), ".qmusic"),
) {
    private val file = File(dir, "yt_session")

    fun load(): String? =
        runCatching { file.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() } }.getOrNull()

    fun save(cookies: String?) {
        runCatching {
            file.parentFile?.mkdirs()
            if (cookies.isNullOrBlank()) file.delete() else file.writeText(cookies, Charsets.UTF_8)
        }
    }
}
