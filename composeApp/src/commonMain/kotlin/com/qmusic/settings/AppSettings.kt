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

    /** Further choices by name (see [SettingKeys]); each is a flow, so screens follow it as it changes. */
    fun bool(key: String, default: Boolean): StateFlow<Boolean>

    fun setBool(key: String, value: Boolean)

    fun string(key: String, default: String): StateFlow<String>

    fun setString(key: String, value: String)
}

/** Names of the choices kept through [AppSettings.bool] and [AppSettings.string]. */
object SettingKeys {
    /** Pure black instead of near-black, for OLED screens. */
    const val AMOLED = "amoled_theme"

    /** Playback carries on when headphones (Bluetooth or wired) connect. */
    const val HEADSET_RESUME = "resume_on_headset"

    /** The line being sung shows in a notification of its own. */
    const val NOTIFICATION_LYRICS = "notification_lyrics"

    /** The player reads these two itself (core:player), under the same names. */
    const val CACHE_LIMIT_MB = "cache_limit_mb"
    const val DOWNLOAD_QUALITY = "download_quality"

    const val DEFAULT_CACHE_MB = "512"

    /** Smart downloads: on/off, the songs it fetched (comma-separated ids) and when it last refreshed (epoch ms). */
    const val SMART_DOWNLOADS = "smart_downloads"
    const val SMART_IDS = "smart_download_ids"
    const val SMART_REFRESHED_AT = "smart_downloads_at"
}

/** The Settings rows that have no property of their own in [AppSettings]. */
data class MoreSettings(
    val amoled: Boolean = false,
    val headsetResume: Boolean = false,
    val notificationLyrics: Boolean = false,
    val cacheLimitMb: String = SettingKeys.DEFAULT_CACHE_MB,
    val cacheBytes: Long = 0L,
    val downloadQuality: String = "HIGH",
    val lastFmAvailable: Boolean = false,
    val lastFmUser: String? = null,
    val smartDownloads: Boolean = false,
)

/** A change made on one of the [MoreSettings] rows. */
sealed interface MoreSettingsChange {
    data class Amoled(val on: Boolean) : MoreSettingsChange
    data class HeadsetResume(val on: Boolean) : MoreSettingsChange
    data class NotificationLyrics(val on: Boolean) : MoreSettingsChange
    data class CacheLimit(val mb: String) : MoreSettingsChange
    data class DownloadQuality(val quality: String) : MoreSettingsChange
    data class SmartDownloads(val on: Boolean) : MoreSettingsChange
    data object ClearCache : MoreSettingsChange
    data class LastFmSignIn(val user: String, val password: String) : MoreSettingsChange
    data object LastFmSignOut : MoreSettingsChange
}

/** A Last.fm account that what is listened to is sent to ("scrobbled"). */
interface ScrobbleAccount {
    /** False when this build has no Last.fm key: the row says so instead of offering to sign in. */
    val available: Boolean

    /** The signed-in user name, or null. */
    val user: StateFlow<String?>

    suspend fun signIn(user: String, password: String): Result<Unit>

    fun signOut()
}

/** Used where nothing is scrobbled (tests, desktop). */
object NoScrobbling : ScrobbleAccount {
    override val available: Boolean = false
    override val user: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    override suspend fun signIn(user: String, password: String): Result<Unit> = Result.failure(IllegalStateException("Last.fm is not available"))

    override fun signOut() = Unit
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

    private val bools = HashMap<String, MutableStateFlow<Boolean>>()
    private val strings = HashMap<String, MutableStateFlow<String>>()

    override fun bool(key: String, default: Boolean): StateFlow<Boolean> = bools.getOrPut(key) { MutableStateFlow(default) }

    override fun setBool(key: String, value: Boolean) {
        bools.getOrPut(key) { MutableStateFlow(value) }.value = value
    }

    override fun string(key: String, default: String): StateFlow<String> = strings.getOrPut(key) { MutableStateFlow(default) }

    override fun setString(key: String, value: String) {
        strings.getOrPut(key) { MutableStateFlow(value) }.value = value
    }
}
