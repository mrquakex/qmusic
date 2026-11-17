package com.qmusic.ui.videos

import androidx.compose.runtime.Composable
import com.qmusic.engine.video.VideoItem
import com.qmusic.engine.video.VideoQuality
import kotlinx.coroutines.flow.StateFlow

/** A stretch of a video that SponsorBlock's community marked as something to skip (a sponsor, a plug, "like and subscribe"). */
data class SkipSegment(val startMs: Long, val endMs: Long, val category: String)

/** What the sleep timer waits for: a number of minutes, or the end of the video that is playing. */
enum class SleepMode { OFF, TIMER, VIDEO_END }

/** [token] changes with every setting, so a timer that is set again starts again. */
data class SleepTimer(val mode: SleepMode = SleepMode.OFF, val minutes: Int = 0, val token: Int = 0)

enum class DownloadState { QUEUED, DOWNLOADING, DONE, FAILED }

/** A video kept on the device (or on its way there): [quality] is what was downloaded, [bytes] of [total] so far. */
data class DownloadedVideo(
    val item: VideoItem,
    val quality: VideoQuality,
    val state: DownloadState,
    val bytes: Long = 0,
    val total: Long = 0,
) {
    val percent: Int get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
}

/** Videos kept on the device, to watch without the network. Lives as long as the app, not the page. */
interface VideoDownloads {
    val items: StateFlow<List<DownloadedVideo>>

    /** Starts keeping [quality] of [item] (again, after a failure). */
    fun start(item: VideoItem, quality: VideoQuality)

    /** Stops a download on its way, and forgets what it had read. */
    fun cancel(id: String)

    /** Deletes a downloaded video from the device. */
    fun remove(id: String)
}

/** Null where this device cannot keep videos. */
@Composable
expect fun rememberVideoDownloads(): VideoDownloads?

/** The parts of [videoId] to skip, from SponsorBlock (empty when there are none or it cannot be reached). */
expect suspend fun fetchSponsorSegments(videoId: String): List<SkipSegment>

/** The words of the features added to the player later: kept apart so [VideoStrings] stays as it is. */
data class VideoExtraStrings(
    val autoplay: String,
    val on: String,
    val off: String,
    val sleepTimer: String,
    val sleepMinutes: (Int) -> String,
    val sleepVideoEnd: String,
    val sleepOffLabel: String,
    val sponsorBlock: String,
    val sponsorBlockHint: String,
    val feedPreview: String = "",
    val feedPreviewHint: String = "",
    val sponsorSkipped: String,
    val download: String,
    val downloaded: String,
    val downloadFailed: String,
    val downloads: String,
    val noDownloads: String,
    val removeDownload: String,
    val cancelDownload: String,
    val downloadLive: String,
    val upNext: String,
    val playNow: String,
    val seconds: String,
    val lock: String,
    val unlock: String,
    val zoomFill: String,
    val zoomFit: String,
    val playerSettings: String,
) {
    companion object {
        val Turkish = VideoExtraStrings(
            autoplay = "Otomatik oynat",
            on = "Açık",
            off = "Kapalı",
            sleepTimer = "Uyku zamanlayıcısı",
            sleepMinutes = { "$it dk" },
            sleepVideoEnd = "Video bitince",
            sleepOffLabel = "Kapalı",
            sponsorBlock = "Sponsorları atla",
            sponsorBlockHint = "SponsorBlock: reklam ve tanıtım bölümlerini kendisi atlar",
            feedPreview = "Listede önizleme",
            feedPreviewHint = "Wi-Fi'dayken listede duran video sessiz oynar",
            sponsorSkipped = "Sponsor bölümü atlandı",
            download = "İndir",
            downloaded = "İndirildi",
            downloadFailed = "İndirilemedi",
            downloads = "İndirilenler",
            noDownloads = "Henüz indirilen video yok. Bir videonun altındaki İndir düğmesine bas.",
            removeDownload = "Sil",
            cancelDownload = "İptal",
            downloadLive = "Canlı yayın indirilemez",
            upNext = "Sıradaki",
            playNow = "Şimdi oynat",
            seconds = "sn",
            lock = "Ekranı kilitle",
            unlock = "Kilidi açmak için dokun",
            zoomFill = "Ekranı doldur",
            zoomFit = "Ekrana sığdır",
            playerSettings = "Oynatıcı ayarları",
        )
        val English = VideoExtraStrings(
            autoplay = "Autoplay",
            on = "On",
            off = "Off",
            sleepTimer = "Sleep timer",
            sleepMinutes = { "$it min" },
            sleepVideoEnd = "End of video",
            sleepOffLabel = "Off",
            sponsorBlock = "Skip sponsors",
            sponsorBlockHint = "SponsorBlock: skips ads and plugs by itself",
            feedPreview = "Previews in lists",
            feedPreviewHint = "On Wi-Fi, the video a list stops at plays without sound",
            sponsorSkipped = "Skipped a sponsor segment",
            download = "Download",
            downloaded = "Downloaded",
            downloadFailed = "Download failed",
            downloads = "Downloads",
            noDownloads = "No downloaded videos yet. Press Download under a video.",
            removeDownload = "Delete",
            cancelDownload = "Cancel",
            downloadLive = "A live stream cannot be downloaded",
            upNext = "Up next",
            playNow = "Play now",
            seconds = "s",
            lock = "Lock screen",
            unlock = "Tap to unlock",
            zoomFill = "Fill screen",
            zoomFit = "Fit to screen",
            playerSettings = "Player settings",
        )
    }
}

/** The extra words that go with these strings' language. */
internal fun VideoStrings.extras(): VideoExtraStrings =
    if (this == VideoStrings.English) VideoExtraStrings.English else VideoExtraStrings.Turkish
