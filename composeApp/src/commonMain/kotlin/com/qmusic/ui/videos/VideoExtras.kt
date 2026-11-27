package com.qmusic.ui.videos

import androidx.compose.runtime.Composable
import com.qmusic.engine.video.VideoCaption
import com.qmusic.engine.video.VideoItem
import com.qmusic.engine.video.VideoQuality
import kotlinx.coroutines.flow.StateFlow

/** A stretch of a video that SponsorBlock's community marked as something to skip (a sponsor, a plug, "like and subscribe"). */
data class SkipSegment(val startMs: Long, val endMs: Long, val category: String)

internal val DEFAULT_SPONSOR_CATEGORIES: Set<String> = setOf("sponsor", "selfpromo", "interaction")
internal val ALL_SPONSOR_CATEGORIES: List<String> = listOf(
    "sponsor",
    "selfpromo",
    "interaction",
    "intro",
    "outro",
    "music_offtopic",
    "filler",
)

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
    val caption: VideoCaption? = null,
) {
    val percent: Int get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
}

/** Videos kept on the device, to watch without the network. Lives as long as the app, not the page. */
interface VideoDownloads {
    val items: StateFlow<List<DownloadedVideo>>

    /** Starts keeping [quality] of [item] (and optional [caption] for offline subtitles). */
    fun start(item: VideoItem, quality: VideoQuality, caption: VideoCaption? = null)

    /** Stops a download on its way, and forgets what it had read. */
    fun cancel(id: String)

    /** Deletes a downloaded video from the device. */
    fun remove(id: String)
}

/** Null where this device cannot keep videos. */
@Composable
expect fun rememberVideoDownloads(): VideoDownloads?

/** The parts of [videoId] to skip, from SponsorBlock (empty when there are none or it cannot be reached). */
expect suspend fun fetchSponsorSegments(
    videoId: String,
    categories: Set<String> = DEFAULT_SPONSOR_CATEGORIES,
): List<SkipSegment>

/** Public like/dislike counts from Return YouTube Dislike (null when unavailable or offline). */
data class VideoDislikeStats(val likes: Long, val dislikes: Long) {
    /** Share of likes in `[0f, 1f]`, or null when both counts are zero. */
    val likeRatio: Float?
        get() {
            val total = likes + dislikes
            return if (total > 0L) (likes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else null
        }
}

/** Formats a raw vote count compactly in Turkish ("12,5 B", "1,4 Mn") or English ("12.5K", "1.4M"). */
internal fun formatCompactCount(count: Long, english: Boolean): String {
    val n = count.coerceAtLeast(0L)
    fun oneDecimal(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        val whole = rounded.toLong()
        val frac = ((rounded - whole) * 10.0 + 0.0001).toInt()
        return if (frac == 0) whole.toString() else if (english) "$whole.$frac" else "$whole,$frac"
    }
    return when {
        n >= 1_000_000_000L -> "${oneDecimal(n / 1_000_000_000.0)}${if (english) "B" else " Mr"}"
        n >= 1_000_000L -> "${oneDecimal(n / 1_000_000.0)}${if (english) "M" else " Mn"}"
        n >= 1_000L -> "${oneDecimal(n / 1_000.0)}${if (english) "K" else " B"}"
        else -> n.toString()
    }
}

/** Fetches Return YouTube Dislike counts for [videoId] (null on failure or offline). */
expect suspend fun fetchVideoDislikes(videoId: String): VideoDislikeStats?

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
    val replay: String = "Tekrar izle",
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
            feedPreview = "Akışlarda oynatma",
            feedPreviewHint = "Listede durduğun video sessiz oynamaya başlar; sesini açabilir, dokununca kaldığı yerden izleyebilirsin",
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
            feedPreview = "Playback in feeds",
            feedPreviewHint = "The video a list stops at plays silently; turn its sound on, or tap to watch on from there",
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
            replay = "Watch again",
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

/** Under "2x" while the picture is held. */
internal val VideoExtraStrings.holdToSeekHint: String get() = if (this == VideoExtraStrings.English) "Slide left or right to seek" else "Sarmak için sağa sola kaydır"

/** The empty search page: "You may like", and taking a past search away. */
internal val VideoExtraStrings.mayLike: String get() = if (this == VideoExtraStrings.English) "You may like" else "Hoşunuza gidebilecekler"
internal val VideoExtraStrings.fromWatchedChannels: String get() = if (this == VideoExtraStrings.English) "From channels you watched" else "İzlediğiniz kanallardan"
internal val VideoExtraStrings.removeSearch: String get() = if (this == VideoExtraStrings.English) "Remove from search history?" else "Arama geçmişinden kaldırılsın mı?"
internal val VideoExtraStrings.removeLabel: String get() = if (this == VideoExtraStrings.English) "Remove" else "Kaldır"
internal val VideoExtraStrings.cancelLabel: String get() = if (this == VideoExtraStrings.English) "Cancel" else "İptal"

/** The choices of "Playback in feeds", and its sound button. */
internal val VideoExtraStrings.previewAlways: String get() = if (this == VideoExtraStrings.English) "Always" else "Her zaman"
internal val VideoExtraStrings.previewWifi: String get() = if (this == VideoExtraStrings.English) "Wi-Fi only" else "Yalnızca Wi-Fi"
internal val VideoExtraStrings.previewOff: String get() = if (this == VideoExtraStrings.English) "Off" else "Kapalı"
internal val VideoExtraStrings.previewSoundOn: String get() = if (this == VideoExtraStrings.English) "Turn sound on" else "Sesi aç"
internal val VideoExtraStrings.previewSoundOff: String get() = if (this == VideoExtraStrings.English) "Mute" else "Sesi kapat"

internal val VideoExtraStrings.loopVideo: String get() = if (this == VideoExtraStrings.English) "Loop video" else "Videoyu tekrarla"
internal val VideoExtraStrings.audioTrack: String get() = if (this == VideoExtraStrings.English) "Audio track" else "Ses dili"
internal val VideoExtraStrings.originalAudio: String get() = if (this == VideoExtraStrings.English) "Original" else "Orijinal"
internal val VideoExtraStrings.autoTranslateCaption: String get() = if (this == VideoExtraStrings.English) "English (auto-translate)" else "Türkçe (otomatik çeviri)"
internal val VideoExtraStrings.sponsorUndo: String get() = if (this == VideoExtraStrings.English) "Undo" else "Geri al"
internal val VideoExtraStrings.sponsorCategoriesTitle: String get() = if (this == VideoExtraStrings.English) "Categories to skip" else "Atlanacak bölümler"
internal fun VideoExtraStrings.sponsorCategoryLabel(cat: String): String {
    val en = this == VideoExtraStrings.English
    return when (cat) {
        "sponsor" -> if (en) "Sponsor" else "Sponsor"
        "selfpromo" -> if (en) "Self-promo" else "Kendi tanıtımı"
        "interaction" -> if (en) "Like & sub" else "Beğen/abone"
        "intro" -> if (en) "Intro" else "Giriş"
        "outro" -> if (en) "Outro" else "Kapanış"
        "music_offtopic" -> if (en) "Non-music" else "Müzik dışı"
        "filler" -> if (en) "Filler" else "Gereksiz sohbet"
        else -> cat
    }
}
internal val VideoExtraStrings.playNext: String get() = if (this == VideoExtraStrings.English) "Play next" else "Sıradaki oynat"
internal val VideoExtraStrings.addToQueue: String get() = if (this == VideoExtraStrings.English) "Add to queue" else "Sıraya ekle"
internal val VideoExtraStrings.addedToQueue: String get() = if (this == VideoExtraStrings.English) "Added to queue" else "Sıraya eklendi"
internal val VideoExtraStrings.playingNext: String get() = if (this == VideoExtraStrings.English) "Playing next" else "Sıradaki olarak eklendi"
internal val VideoExtraStrings.downloadQualityTitle: String get() = if (this == VideoExtraStrings.English) "Download quality" else "İndirme kalitesi"
internal val VideoExtraStrings.deleteDownloadTitle: String get() = if (this == VideoExtraStrings.English) "Remove download?" else "İndirilen video silinsin mi?"
internal val VideoExtraStrings.deleteDownloadConfirm: String get() = if (this == VideoExtraStrings.English) "This video will be deleted from your device." else "Bu video cihazdan silinecek."
internal val VideoExtraStrings.shareVideoPlain: String get() = if (this == VideoExtraStrings.English) "Share from start" else "Baştan paylaş"
internal fun VideoExtraStrings.shareFromTime(time: String): String = if (this == VideoExtraStrings.English) "Share from $time" else "$time anından itibaren paylaş"
internal val VideoExtraStrings.searchInChannel: String get() = if (this == VideoExtraStrings.English) "Search in channel" else "Kanalda ara"
internal val VideoExtraStrings.searchFiltersLabel: String get() = if (this == VideoExtraStrings.English) "Filters" else "Filtreler"
internal val VideoExtraStrings.sortNewest: String get() = if (this == VideoExtraStrings.English) "Newest" else "En yeni"
internal val VideoExtraStrings.sortMostViewed: String get() = if (this == VideoExtraStrings.English) "Most viewed" else "En çok izlenen"
internal val VideoExtraStrings.dateToday: String get() = if (this == VideoExtraStrings.English) "Today" else "Bugün"
internal val VideoExtraStrings.dateWeek: String get() = if (this == VideoExtraStrings.English) "This week" else "Bu hafta"
internal val VideoExtraStrings.dateMonth: String get() = if (this == VideoExtraStrings.English) "This month" else "Bu ay"
internal val VideoExtraStrings.durShort: String get() = if (this == VideoExtraStrings.English) "Short (<4m)" else "Kısa (<4 dk)"
internal val VideoExtraStrings.durMedium: String get() = if (this == VideoExtraStrings.English) "4–20 min" else "4–20 dk"
internal val VideoExtraStrings.durLong: String get() = if (this == VideoExtraStrings.English) "Long (>20m)" else "Uzun (>20 dk)"
internal val VideoExtraStrings.liveChat: String get() = if (this == VideoExtraStrings.English) "Live chat" else "Canlı sohbet"
internal val VideoExtraStrings.liveChatEmpty: String get() = if (this == VideoExtraStrings.English) "Waiting for chat messages…" else "Sohbet mesajları bekleniyor…"
internal val VideoExtraStrings.showDislikes: String get() = if (this == VideoExtraStrings.English) "Show dislike count" else "Beğenmeme sayısını göster"
internal val VideoExtraStrings.showDislikesHint: String get() = if (this == VideoExtraStrings.English) "Return YouTube Dislike: shows dislikes and the like ratio bar" else "Return YouTube Dislike: beğenmeme sayısını ve beğeni oran çubuğunu gösterir"
internal val VideoExtraStrings.showHeatmap: String get() = if (this == VideoExtraStrings.English) "Most replayed graph" else "En çok izlenen yer grafiği"
internal val VideoExtraStrings.showHeatmapHint: String get() = if (this == VideoExtraStrings.English) "Draws the replay intensity wave above the progress bar" else "İlerleme çubuğunun üstünde en çok tekrar izlenen anları gösterir"
internal val VideoExtraStrings.dislikeLabel: String get() = if (this == VideoExtraStrings.English) "Dislikes" else "Beğenmeme"
internal val VideoExtraStrings.chatShort: String get() = if (this == VideoExtraStrings.English) "Chat" else "Sohbet"
internal val VideoExtraStrings.tabMovies: String get() = if (this == VideoExtraStrings.English) "Movies" else "Film"
internal val VideoExtraStrings.watchTogether: String get() = if (this == VideoExtraStrings.English) "Watch together" else "Birlikte izle"
internal val VideoExtraStrings.movieLinkTitle: String get() = if (this == VideoExtraStrings.English) "Play from link" else "Linkten izle"
internal val VideoExtraStrings.movieLinkHint: String get() = if (this == VideoExtraStrings.English) "Paste .m3u8, .mp4, YouTube or film page URL…" else ".m3u8, .mp4, YouTube veya film sayfası URL'si yapıştır…"
internal val VideoExtraStrings.moviePlaySolo: String get() = if (this == VideoExtraStrings.English) "Watch" else "İzle"
internal val VideoExtraStrings.movieSearchHint: String get() = if (this == VideoExtraStrings.English) "Search in source…" else "Kaynakta film ara…"
internal val VideoExtraStrings.movieNoSources: String get() = if (this == VideoExtraStrings.English) "No film sources added yet. Admins can add domains from Admin Panel › Settings › Film sources, or paste a direct link above." else "Henüz film kaynağı eklenmemiş. Yönetim ekibi Yönetici Paneli › Ayarlar › Film kaynakları bölümünden domain ekleyebilir ya da yukarıya direkt link yapıştırabilirsin."
internal val VideoExtraStrings.movieNoResults: String get() = if (this == VideoExtraStrings.English) "No movies found on this source." else "Bu kaynakta film bulunamadı."
internal val VideoExtraStrings.movieInvalidUrl: String get() = if (this == VideoExtraStrings.English) "Enter a valid https:// link." else "Geçerli bir https:// bağlantısı gir."

/** The extra words that go with these strings' language. */
internal fun VideoStrings.extras(): VideoExtraStrings =
    if (this == VideoStrings.English) VideoExtraStrings.English else VideoExtraStrings.Turkish
