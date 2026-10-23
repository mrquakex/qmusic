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
        const val KEY_VIDEO = "video_by_default"
        const val KEY_CHECK_UPDATES = "check_updates_on_launch"
    }
}
