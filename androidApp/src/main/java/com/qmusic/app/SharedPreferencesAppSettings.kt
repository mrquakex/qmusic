package com.qmusic.app

import android.content.Context
import com.qmusic.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [AppSettings] kept in a private SharedPreferences file. */
class SharedPreferencesAppSettings(context: Context) : AppSettings {
    private val prefs = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(prefs.getString(KEY_LANGUAGE, "tr") ?: "tr")
    override val language: StateFlow<String> = _language.asStateFlow()

    private val _auto = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DOWNLOAD, false))
    override val autoDownloadLiked: StateFlow<Boolean> = _auto.asStateFlow()

    private val _wifi = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY, true))
    override val wifiOnly: StateFlow<Boolean> = _wifi.asStateFlow()

    private val _video = MutableStateFlow(prefs.getBoolean(KEY_VIDEO, false))
    override val videoByDefault: StateFlow<Boolean> = _video.asStateFlow()

    private val _check = MutableStateFlow(prefs.getBoolean(KEY_CHECK_UPDATES, true))
    override val autoCheckUpdates: StateFlow<Boolean> = _check.asStateFlow()

    override fun setLanguage(code: String) {
        prefs.edit().putString(KEY_LANGUAGE, code).apply()
        _language.value = code
    }

    override fun setVideoByDefault(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIDEO, enabled).apply()
        _video.value = enabled
    }

    override fun setAutoCheckUpdates(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHECK_UPDATES, enabled).apply()
        _check.value = enabled
    }

    private val _qualityWifi = MutableStateFlow(prefs.getString(KEY_QUALITY_WIFI, null) ?: "HIGH")
    override val audioQualityWifi: StateFlow<String> = _qualityWifi.asStateFlow()

    private val _qualityMobile = MutableStateFlow(prefs.getString(KEY_QUALITY_MOBILE, null) ?: "NORMAL")
    override val audioQualityMobile: StateFlow<String> = _qualityMobile.asStateFlow()

    private val _normalize = MutableStateFlow(prefs.getBoolean(KEY_NORMALIZE, true))
    override val normalizeVolume: StateFlow<Boolean> = _normalize.asStateFlow()

    override fun setAudioQualityWifi(quality: String) {
        prefs.edit().putString(KEY_QUALITY_WIFI, quality).apply()
        _qualityWifi.value = quality
    }

    override fun setAudioQualityMobile(quality: String) {
        prefs.edit().putString(KEY_QUALITY_MOBILE, quality).apply()
        _qualityMobile.value = quality
    }

    override fun setNormalizeVolume(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NORMALIZE, enabled).apply()
        _normalize.value = enabled
    }

    private val _autoUpdate = MutableStateFlow(prefs.getBoolean(KEY_AUTO_UPDATE, true))
    override val autoUpdate: StateFlow<Boolean> = _autoUpdate.asStateFlow()

    override fun setAutoUpdate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
        _autoUpdate.value = enabled
    }

    private val _notify = MutableStateFlow(prefs.getBoolean(KEY_UPDATE_NOTIFICATIONS, true))
    override val updateNotifications: StateFlow<Boolean> = _notify.asStateFlow()

    override fun setUpdateNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_NOTIFICATIONS, enabled).apply()
        _notify.value = enabled
    }

    override fun setAutoDownloadLiked(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, enabled).apply()
        _auto.value = enabled
    }

    override fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        _wifi.value = enabled
    }

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_AUTO_DOWNLOAD = "auto_download_liked"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_UPDATE_NOTIFICATIONS = "update_notifications"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_QUALITY_WIFI = "audio_quality_wifi"
        const val KEY_QUALITY_MOBILE = "audio_quality_mobile"
        const val KEY_NORMALIZE = "normalize_volume"
        const val KEY_VIDEO = "video_by_default"
        const val KEY_CHECK_UPDATES = "check_updates_on_launch"
    }
}
