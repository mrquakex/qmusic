package com.qmusic.app

import android.content.Context
import com.qmusic.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [AppSettings] kept in a private SharedPreferences file. */
class SharedPreferencesAppSettings(context: Context) : AppSettings {
    private val prefs = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE)

    private val _auto = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DOWNLOAD, false))
    override val autoDownloadLiked: StateFlow<Boolean> = _auto.asStateFlow()

    private val _wifi = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY, true))
    override val wifiOnly: StateFlow<Boolean> = _wifi.asStateFlow()

    override fun setAutoDownloadLiked(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, enabled).apply()
        _auto.value = enabled
    }

    override fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        _wifi.value = enabled
    }

    private companion object {
        const val KEY_AUTO_DOWNLOAD = "auto_download_liked"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}
