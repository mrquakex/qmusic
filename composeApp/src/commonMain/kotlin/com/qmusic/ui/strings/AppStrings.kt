package com.qmusic.ui.strings

import androidx.compose.runtime.staticCompositionLocalOf
import com.qmusic.ui.viewmodel.EqualizerPreset

interface AppStrings {
    // Navigation
    val tabHome: String
    val tabForYou: String
    val playFullSong: String
    val preview: String
    val forYouEmpty: String
    val tabSearch: String
    val tabLibrary: String
    val tabSettings: String

    // Home Screen
    val homeSubtitle: String
    val refreshHome: String
    val playAllShelf: String
    val speedDial: String
    val offlineTitle: String
    fun offlineWithDownloads(count: Int): String
    val offlineWithoutDownloads: String
    val goToDownloads: String
    val randomSong: String
    val searchPlaceholder: String
    val signInYouTubeMusic: String
    val signInPromptSubtitle: String
    val signIn: String
    val signOut: String
    val tryAgain: String

    // Search Screen
    val searchFieldPlaceholder: String
    val search: String
    val clearSearch: String
    val filterAll: String
    val filterSongs: String
    val filterAlbums: String
    val filterArtists: String
    val filterPlaylists: String
    val filterVideos: String
    val noResultsFound: String
    val sectionTopResult: String
    val sectionSongs: String
    val sectionAlbums: String
    val sectionArtists: String
    val sectionPlaylists: String
    val searchHistoryEmpty: String
    val recentSearches: String
    val recentlyPlayed: String
    val exploreTitle: String
    val exploreNewReleases: String
    val exploreCharts: String
    val exploreMoods: String
    val moreYouMayLike: String
    val tabAlbums: String
    fun queueSaved(name: String): String
    val saveQueue: String
    val clearQueue: String
    val backupSection: String
    val backupExport: String
    val backupExportSubtitle: String
    val backupImport: String
    val backupImportSubtitle: String
    val backupSaved: String
    val backupFailed: String
    val backupInvalid: String
    fun backupRestored(favorites: Int, playlists: Int): String
    val removedFromPlaylist: String
    val couldNotChangePlaylist: String
    val playlistDeleted: String
    val removeFromThisPlaylist: String
    val deleteYouTubePlaylistTitle: String
    val deleteYouTubePlaylistMessage: String
    val saveToYouTube: String
    val downloadAll: String
    val related: String
    val relatedEmpty: String
    val statsTitle: String
    val statsLast30: String
    val statsLastYear: String
    val statsAllTime: String
    val statsMinutes: String
    val statsPlays: String
    val statsSongs: String
    val statsTopSongs: String
    val statsTopArtists: String
    fun statsTimes(count: Int): String
    val statsEmpty: String
    fun statsShare(period: String, minutes: Int, topSong: String?, topArtist: String?): String
    val tabArtists: String
    val youtubeMusicPlaylists: String
    val devicePlaylists: String
    fun addedToPlaylist(name: String): String
    val couldNotAddToPlaylist: String
    val albumsEmpty: String
    val artistsEmpty: String
    val voiceSearch: String
    val recognizeSong: String
    val clearAll: String
    val removeFromHistory: String
    val useThisSuggestion: String

    // Library Screen
    val yourLibrary: String
    val newPlaylist: String
    val newPlaylistTitle: String
    val playlistNameLabel: String
    val create: String
    val cancel: String
    val save: String
    val delete: String
    val remove: String
    val tabFavorites: String
    val tabPlaylists: String
    val tabDownloads: String
    val tabHistory: String
    val favoritesEmpty: String
    val downloadsEmpty: String
    val historyEmpty: String
    val playlistsEmpty: String
    val removeAllDownloadsTitle: String
    val removeAllDownloadsMessage: String
    val removeAllDownloadsConfirmMessage: String
    val removeAll: String
    val play: String
    val shuffle: String
    val listenOffline: String
    fun listenOfflineSubtitle(songsEmpty: Boolean, pending: Int, saved: Int, total: Int): String
    val cancelDownloads: String
    fun downloadAllCount(missing: Int): String
    val autoDownloadLikedTitle: String
    val autoDownloadLikedSubtitle: String
    val wifiOnlyTitle: String
    val wifiOnlySubtitle: String
    fun downloadsSummaryCount(count: Int, sizeStr: String): String
    fun downloadsSummaryStatus(inProgress: Int): String
    val playlistItemLabel: String

    // Settings Screen
    val settingsTitle: String
    val sectionAccount: String
    val sectionPlayback: String
    val sectionDownloads: String
    val sectionLibrary: String
    val sectionUpdates: String
    val sectionAbout: String
    val sectionLanguage: String
    val selectedLanguage: String
    val languageOptionTurkish: String
    val languageOptionEnglish: String
    val accountSignedInSubtitle: String
    val accountSignedOutSubtitle: String
    val crossfadeTitle: String
    val off: String
    val crossfadeSubtitle: String
    val equalizerTitle: String
    val equalizerSubtitle: String
    val showVideosTitle: String
    val showVideosSubtitle: String
    fun settingsDownloadedSize(bytes: Long): String
    val importSpotifyTitle: String
    val importSpotifySubtitle: String
    fun versionLabel(version: String): String
    val checkUpdate: String
    fun updateToVersion(version: String): String
    val checkOnLaunchTitle: String
    val checkOnLaunchSubtitle: String
    val madeBy: String
    val sourceCodeTitle: String
    val aboutLicense: String

    // Artist / Collection / Local Playlist
    val sectionTopSongs: String
    val subscribe: String
    val subscribedLabel: String
    val back: String
    fun songsCount(n: Int): String
    val renamePlaylist: String
    val deletePlaylist: String
    val localPlaylistEmpty: String
    val removeFromPlaylist: String
    fun deletePlaylistTitle(name: String): String
    val deletePlaylistMessage: String

    // Player / Now Playing
    val collapsePlayer: String
    val nowPlaying: String
    val moreOptions: String
    val song: String
    val video: String
    val lyrics: String
    val lyricsUnavailable: String
    val queueEmpty: String
    val share: String
    val startRadio: String
    val reorderQueue: String
    val crashReport: String
    val crashReportHint: String
    val clearReport: String
    val autoUpdateTitle: String
    val audioQualityTitle: String
    val audioQualityWifi: String
    val audioQualityMobile: String
    val qualityHigh: String
    val qualityNormal: String
    val qualityLow: String
    val normalizeTitle: String
    val normalizeSubtitle: String
    val autoUpdateSubtitle: String
    fun updateDialogTitle(version: String): String
    val updateDialogBody: String
    val updateNow: String
    val updateLater: String
    val updatingTitle: String
    val updatingBody: String
    val updateNotificationsTitle: String
    val updateNotificationsSubtitle: String
    val playingNow: String
    val removeFromQueue: String
    val unknownArtist: String
    val liked: String
    val like: String
    val favorited: String
    val favorite: String
    val downloaded: String
    val download: String
    val sleepTimer: String
    val endOfSong: String
    fun eqFadeLabel(crossfadeSeconds: Int): String
    val shuffleOn: String
    val shuffleOff: String
    val previous: String
    val pause: String
    val next: String
    val repeatOff: String
    val repeatAll: String
    val repeatOne: String
    fun upNext(title: String?): String
    val sleepTimerTitle: String
    fun sleepTimerMinutes(minutes: Int): String
    val endOfThisSong: String
    val turnOffTimer: String
    val close: String

    // Song Actions Sheet
    val playNext: String
    val addToQueue: String
    val addToPlaylist: String
    val removeFromFavorites: String
    val addToFavorites: String
    val removeDownload: String
    val goToArtist: String

    // Equalizer & Spotify & YouTube Login
    val applyAndClose: String
    fun presetDisplayName(preset: EqualizerPreset): String
    val spotifyImportDescription: String
    val spotifyUrlLabel: String
    val importing: String
    val startImport: String
    val desktopAuthDescription: String
    val desktopAuthCookieLabel: String
    val desktopAuthSave: String
    val desktopAuthError: String

    // ViewModel Messages
    fun downloadingUpdate(version: String): String
    fun updateAvailable(version: String): String
    fun couldNotInstallUpdate(msg: String): String
    val latestVersion: String
    fun updateCheckFailed(msg: String): String
    val youtubeReturnedNothing: String
    val couldNotLoadYouTube: String
    val searchFailed: String
    val couldNotOpenPage: String
    val couldNotOpenArtist: String
    val spotifyImportFetching: String
    val spotifyImportNoTracks: String
    fun spotifyImportCreating(title: String): String
    fun spotifyImportMatching(current: Int, total: Int, title: String): String
    fun spotifyImportComplete(matched: Int, total: Int): String
    fun spotifyImportFailed(msg: String): String

    // Offline Service Notification
    val downloadingMusicNotificationTitle: String
    fun songsLeft(n: Int): String
    val downloadStarting: String
}

object TurkishStrings : AppStrings {
    override val tabHome: String = "Ana sayfa"
    override val tabForYou: String = "Sana Özel"
    override val playFullSong: String = "Tamamını dinle"
    override val preview: String = "Önizleme"
    override val forYouEmpty: String = "Öneriler ana sayfadan hazırlanıyor. Biraz bekle ya da ana sayfayı aç."
    override val tabSearch: String = "Ara"
    override val tabLibrary: String = "Kitaplık"
    override val tabSettings: String = "Ayarlar"

    override val homeSubtitle: String = "Mrquake'den sana özel"
    override val refreshHome: String = "Ana sayfayı yenile"
    override val playAllShelf: String = "Tümünü oynat"
    override val speedDial: String = "Hızlı arama"
    override val offlineTitle: String = "İnternet bağlantısı yok"
    override fun offlineWithDownloads(count: Int): String = "İndirdiğin $count şarkıyı çevrimdışı dinleyebilirsin."
    override val offlineWithoutDownloads: String = "Bağlandığında ana sayfa kendiliğinden yenilenecek."
    override val goToDownloads: String = "İndirilenlere git"
    override val randomSong: String = "Rastgele şarkı çal"
    override val searchPlaceholder: String = "Şarkı, sanatçı veya albüm ara..."
    override val signInYouTubeMusic: String = "YouTube Music'e giriş yap"
    override val signInPromptSubtitle: String = "Kişisel mikslerini, önerilerini ve dinleme geçmişini al."
    override val signIn: String = "Giriş yap"
    override val signOut: String = "Çıkış yap"
    override val tryAgain: String = "Yeniden dene"

    override val searchFieldPlaceholder: String = "Şarkı, albüm veya sanatçı ara"
    override val search: String = "Ara"
    override val clearSearch: String = "Aramayı temizle"
    override val filterAll: String = "Tümü"
    override val filterSongs: String = "Şarkılar"
    override val filterAlbums: String = "Albümler"
    override val filterArtists: String = "Sanatçılar"
    override val filterPlaylists: String = "Çalma listeleri"
    override val filterVideos: String = "Videolar"
    override val noResultsFound: String = "Sonuç bulunamadı"
    override val sectionTopResult: String = "En iyi sonuç"
    override val sectionSongs: String = "Şarkılar"
    override val sectionAlbums: String = "Albümler"
    override val sectionArtists: String = "Sanatçılar"
    override val sectionPlaylists: String = "Çalma listeleri"
    override val searchHistoryEmpty: String = "YouTube Music'te aramak için bir şeyler yaz"
    override val recentSearches: String = "Son aramalar"
    override val recentlyPlayed: String = "Son dinlenenler"
    override val exploreTitle: String = "Keşfet"
    override val exploreNewReleases: String = "Yeni çıkanlar"
    override val exploreCharts: String = "Listeler"
    override val exploreMoods: String = "Ruh halleri ve türler"
    override val moreYouMayLike: String = "Beğenebileceğiniz diğer içerikler"
    override val tabAlbums: String = "Albümler"
    override fun queueSaved(name: String): String = "Sıra \"$name\" olarak kaydedildi"
    override val saveQueue: String = "Sırayı kaydet"
    override val clearQueue: String = "Temizle"
    override val backupSection: String = "Yedekleme"
    override val backupExport: String = "Yedek al"
    override val backupExportSubtitle: String = "Favoriler, listeler ve geçmiş tek bir dosyaya."
    override val backupImport: String = "Yedekten geri yükle"
    override val backupImportSubtitle: String = "Bir yedek dosyasındakileri bu cihaza ekle."
    override val backupSaved: String = "Yedek kaydedildi"
    override val backupFailed: String = "Yedek kaydedilemedi"
    override val backupInvalid: String = "Bu dosya bir QMusic yedeği değil"
    override fun backupRestored(favorites: Int, playlists: Int): String = "Geri yüklendi: $favorites favori, $playlists liste"
    override val removedFromPlaylist: String = "Listeden çıkarıldı"
    override val couldNotChangePlaylist: String = "Liste değiştirilemedi"
    override val playlistDeleted: String = "Liste silindi"
    override val removeFromThisPlaylist: String = "Bu listeden çıkar"
    override val deleteYouTubePlaylistTitle: String = "Liste silinsin mi?"
    override val deleteYouTubePlaylistMessage: String = "Liste YouTube Music hesabından da silinir."
    override val saveToYouTube: String = "YouTube Music'e de kaydet"
    override val downloadAll: String = "Hepsini indir"
    override val related: String = "İlgili"
    override val relatedEmpty: String = "Bu şarkı için öneri bulunamadı"
    override val statsTitle: String = "Dinleme istatistiklerin"
    override val statsLast30: String = "Son 30 gün"
    override val statsLastYear: String = "Son 1 yıl"
    override val statsAllTime: String = "Tüm zamanlar"
    override val statsMinutes: String = "dakika dinledin"
    override val statsPlays: String = "çalma"
    override val statsSongs: String = "farklı şarkı"
    override val statsTopSongs: String = "En çok dinlediğin şarkılar"
    override val statsTopArtists: String = "En çok dinlediğin sanatçılar"
    override fun statsTimes(count: Int): String = "$count kez"
    override val statsEmpty: String = "Henüz yeterince dinleme yok. Biraz müzik dinle, burası dolsun."
    override fun statsShare(period: String, minutes: Int, topSong: String?, topArtist: String?): String = buildString {
        append("QMusic ile ").append(period.lowercase()).append(" içinde ").append(minutes).append(" dakika müzik dinledim.")
        topArtist?.let { append(" En çok dinlediğim sanatçı: ").append(it).append('.') }
        topSong?.let { append(" En çok dinlediğim şarkı: ").append(it).append('.') }
    }
    override val tabArtists: String = "Sanatçılar"
    override val youtubeMusicPlaylists: String = "YouTube Music listelerin"
    override val devicePlaylists: String = "Cihazdaki listeler"
    override fun addedToPlaylist(name: String): String = "\"$name\" listesine eklendi"
    override val couldNotAddToPlaylist: String = "Listeye eklenemedi (bu liste senin olmayabilir)"
    override val albumsEmpty: String = "YouTube Music'te kaydettiğin albümler burada görünür."
    override val artistsEmpty: String = "Abone olduğun sanatçılar burada görünür."
    override val voiceSearch: String = "Sesle ara"
    override val recognizeSong: String = "Çalan şarkıyı tanı"
    override val clearAll: String = "Tümünü temizle"
    override val removeFromHistory: String = "Geçmişten kaldır"
    override val useThisSuggestion: String = "Bu öneriyi kullan"

    override val yourLibrary: String = "Kitaplığın"
    override val newPlaylist: String = "Yeni çalma listesi"
    override val newPlaylistTitle: String = "Yeni Çalma Listesi"
    override val playlistNameLabel: String = "Çalma listesi adı"
    override val create: String = "Oluştur"
    override val cancel: String = "İptal"
    override val save: String = "Kaydet"
    override val delete: String = "Sil"
    override val remove: String = "Kaldır"
    override val tabFavorites: String = "Favoriler"
    override val tabPlaylists: String = "Çalma listeleri"
    override val tabDownloads: String = "İndirilenler"
    override val tabHistory: String = "Geçmiş"
    override val favoritesEmpty: String = "Henüz favori şarkı yok.\nHerhangi bir şarkıdaki kalp simgesine dokunarak buraya ekleyebilirsin."
    override val downloadsEmpty: String = "Henüz indirilmiş şarkı yok.\nBeğendiğin şarkıları Favoriler sekmesinden veya şarkı menüsünden indirebilirsin."
    override val historyEmpty: String = "Henüz dinleme geçmişi yok.\nDinlediğin şarkılar burada görünecektir."
    override val playlistsEmpty: String = "Henüz özel çalma listesi yok.\n+ düğmesiyle yeni bir tane oluşturabilirsin."
    override val removeAllDownloadsTitle: String = "Tüm indirilenler kaldırılsın mı?"
    override val removeAllDownloadsMessage: String = "Şarkılar kitaplığında kalır; sadece bu cihazdaki kopyaları silinir."
    override val removeAllDownloadsConfirmMessage: String = "Şarkılar kitaplığında kalır; tekrar çalmak için internet bağlantısı gerekir."
    override val removeAll: String = "Tümünü kaldır"
    override val play: String = "Oynat"
    override val shuffle: String = "Karıştır"
    override val listenOffline: String = "Çevrimdışı dinle"

    override fun listenOfflineSubtitle(songsEmpty: Boolean, pending: Int, saved: Int, total: Int): String = when {
        songsEmpty -> "Şarkıları buraya kaydetmek için beğen."
        pending > 0 -> "$pending şarkı indiriliyor · $saved / $total kaydedildi"
        saved == total -> "Tüm $total şarkı bu cihazda."
        else -> "$saved / $total şarkı bu cihazda."
    }

    override val cancelDownloads: String = "İndirmeleri iptal et"
    override fun downloadAllCount(missing: Int): String = "Tümünü indir ($missing)"
    override val autoDownloadLikedTitle: String = "Beğenilenleri otomatik indir"
    override val autoDownloadLikedSubtitle: String = "Beğendiğin her şarkı bu cihaza kaydedilir."
    override val wifiOnlyTitle: String = "Yalnızca kablosuz ağ (Wi-Fi)"
    override val wifiOnlySubtitle: String = "Mobil veri kullanmak yerine Wi-Fi bağlantısını bekle."
    override fun downloadsSummaryCount(count: Int, sizeStr: String): String = "$count şarkı · $sizeStr"
    override fun downloadsSummaryStatus(inProgress: Int): String =
        if (inProgress > 0) "$inProgress şarkı indiriliyor" else "Çevrimdışı kullanılabilir"
    override val playlistItemLabel: String = "Çalma listesi"

    override val settingsTitle: String = "Ayarlar"
    override val sectionAccount: String = "Hesap"
    override val sectionPlayback: String = "Çalma"
    override val sectionDownloads: String = "İndirilenler"
    override val sectionLibrary: String = "Kitaplık"
    override val sectionUpdates: String = "Güncellemeler"
    override val sectionAbout: String = "Hakkında"
    override val sectionLanguage: String = "Dil / Language"
    override val selectedLanguage: String = "Seçili"
    override val languageOptionTurkish: String = "Türkçe"
    override val languageOptionEnglish: String = "English"
    override val accountSignedInSubtitle: String = "Giriş yapıldı. Ana sayfan, beğenilerin ve kitaplığın senkronize ediliyor."
    override val accountSignedOutSubtitle: String = "Kişisel mikslerin, beğenilerin ve kitaplığın için giriş yap."
    override val crossfadeTitle: String = "Geçiş efekti (Crossfade)"
    override val off: String = "Kapalı"
    override val crossfadeSubtitle: String = "Şarkılar biterken birbiri ardına yumuşakça geçer. Bir şarkıya doğrudan dokunulduğunda efekt uygulanmaz."
    override val equalizerTitle: String = "Ekolayzır"
    override val equalizerSubtitle: String = "10 bant"
    override val showVideosTitle: String = "Müzik videolarını göster"
    override val showVideosSubtitle: String = "Şarkının video klibi varsa oynatıcıyı kliple açar. Kapalıyken kapak resmi gösterilir."

    override fun settingsDownloadedSize(bytes: Long): String {
        val mb = bytes / 1_048_576
        val sizeStr = if (mb >= 1024) "${mb / 1024}.${(mb % 1024) * 10 / 1024} GB" else "$mb MB"
        return if (bytes > 0) "$sizeStr bu cihazda" else "İndirilen yok"
    }

    override val importSpotifyTitle: String = "Spotify'dan İçe Aktar"
    override val importSpotifySubtitle: String = "Herkese açık bir Spotify çalma listesini kitaplığına ekle."
    override fun versionLabel(version: String): String = "Sürüm $version"
    override val checkUpdate: String = "Denetle"
    override fun updateToVersion(version: String): String = "v$version sürümüne güncelle"
    override val checkOnLaunchTitle: String = "Açılışta denetle"
    override val checkOnLaunchSubtitle: String = "Uygulama her açıldığında yeni bir sürüm olup olmadığını kontrol et."
    override val madeBy: String = "Mrquake tarafından yapıldı"
    override val sourceCodeTitle: String = "Kaynak kodu"
    override val aboutLicense: String = "GNU GPL v3 kapsamında açık kaynaklıdır. Ses akışları açık kaynaklı PipePipe ayıklayıcısı ile çözülür."

    override val sectionTopSongs: String = "En çok dinlenen şarkılar"
    override val subscribe: String = "Abone ol"
    override val subscribedLabel: String = "Abone olundu"
    override val back: String = "Geri"
    override fun songsCount(n: Int): String = "$n şarkı"
    override val renamePlaylist: String = "Çalma listesini yeniden adlandır"
    override val deletePlaylist: String = "Çalma listesini sil"
    override val localPlaylistEmpty: String = "Bu çalma listesi boş.\nŞarkı menüsünü kullanarak buraya şarkı ekleyebilirsin."
    override val removeFromPlaylist: String = "Çalma listesinden kaldır"
    override fun deletePlaylistTitle(name: String): String = "\"$name\" çalma listesini sil?"
    override val deletePlaylistMessage: String = "Şarkılar kitaplığında kalır; yalnızca bu çalma listesi silinir."

    override val collapsePlayer: String = "Oynatıcıyı küçült"
    override val nowPlaying: String = "ŞU AN ÇALIYOR"
    override val moreOptions: String = "Diğer seçenekler"
    override val song: String = "Şarkı"
    override val video: String = "Video"
    override val lyrics: String = "Şarkı sözleri"
    override val lyricsUnavailable: String = "Bu şarkı için şarkı sözü bulunamadı"
    override val queueEmpty: String = "Sıra boş"
    override val share: String = "Paylaş"
    override val startRadio: String = "Radyo başlat"
    override val reorderQueue: String = "Sırayı değiştir"
    override val crashReport: String = "Son çökme raporu"
    override val crashReportHint: String = "Uygulama beklenmedik şekilde kapandı. Raporu geliştiriciyle paylaşabilirsin."
    override val clearReport: String = "Temizle"
    override val autoUpdateTitle: String = "Otomatik güncelle"
    override val audioQualityTitle: String = "Ses kalitesi"
    override val audioQualityWifi: String = "Wi-Fi'da"
    override val audioQualityMobile: String = "Mobil veride"
    override val qualityHigh: String = "Yüksek"
    override val qualityNormal: String = "Normal"
    override val qualityLow: String = "Veri tasarrufu"
    override val normalizeTitle: String = "Ses seviyesini eşitle"
    override val normalizeSubtitle: String = "Her şarkı aynı yükseklikte çalar; biri bağırıp diğeri fısıldamaz."
    override val autoUpdateSubtitle: String = "Yeni sürümü sormadan indirip kur."
    override fun updateDialogTitle(version: String): String = "QMusic $version hazır"
    override val updateDialogBody: String = "Yeni sürümde iyileştirmeler ve yeni özellikler var."
    override val updateNow: String = "Güncelle"
    override val updateLater: String = "Sonra"
    override val updatingTitle: String = "Güncelleniyor"
    override val updatingBody: String = "Yeni sürüm indiriliyor. Bitince kurulum başlayacak."
    override val updateNotificationsTitle: String = "Güncelleme bildirimleri"
    override val updateNotificationsSubtitle: String = "Yeni sürüm çıkınca haber ver."
    override val playingNow: String = "Şu an çalıyor"
    override val removeFromQueue: String = "Sıradan kaldır"
    override val unknownArtist: String = "Bilinmeyen sanatçı"
    override val liked: String = "Beğenildi"
    override val like: String = "Beğen"
    override val favorited: String = "Favorilere eklendi"
    override val favorite: String = "Favori"
    override val downloaded: String = "İndirildi"
    override val download: String = "İndir"
    override val sleepTimer: String = "Uyku"
    override val endOfSong: String = "Şarkı bitiminde"
    override fun eqFadeLabel(crossfadeSeconds: Int): String =
        if (crossfadeSeconds > 0) "EQ · Geçiş ${crossfadeSeconds}sn" else "Ekolayzır"
    override val shuffleOn: String = "Karışık çalma açık"
    override val shuffleOff: String = "Karışık çalma kapalı"
    override val previous: String = "Önceki"
    override val pause: String = "Duraklat"
    override val next: String = "Sonraki"
    override val repeatOff: String = "Tekrarlama kapalı"
    override val repeatAll: String = "Tümünü tekrarla"
    override val repeatOne: String = "Tekrar çal"
    override fun upNext(title: String?): String = title?.let { "Sıradaki · $it" } ?: "Sıradaki"
    override val sleepTimerTitle: String = "Uyku zamanlayıcı"
    override fun sleepTimerMinutes(minutes: Int): String = "$minutes dakika"
    override val endOfThisSong: String = "Bu şarkının sonunda"
    override val turnOffTimer: String = "Zamanlayıcıyı kapat"
    override val close: String = "Kapat"

    override val playNext: String = "Sıradakini oynat"
    override val addToQueue: String = "Sıraya ekle"
    override val addToPlaylist: String = "Çalma listesine ekle"
    override val removeFromFavorites: String = "Favorilerden kaldır"
    override val addToFavorites: String = "Favorilere ekle"
    override val removeDownload: String = "İndirmeyi kaldır"
    override val goToArtist: String = "Sanatçıya git"

    override val applyAndClose: String = "Uygula ve Kapat"
    override fun presetDisplayName(preset: EqualizerPreset): String = when (preset) {
        EqualizerPreset.FLAT -> "Düz"
        EqualizerPreset.BASS_BOOST -> "Bas Güçlendirme"
        EqualizerPreset.ROCK -> "Rock"
        EqualizerPreset.VOCAL -> "Vokal"
    }
    override val spotifyImportDescription: String =
        "Herkese açık bir Spotify çalma listesi bağlantısı girin. Parçalar YouTube Music ses akışlarıyla eşleştirilip doğrudan yerel kitaplığınıza kaydedilecektir."
    override val spotifyUrlLabel: String = "Spotify Çalma Listesi Bağlantısı"
    override val importing: String = "İçe aktarılıyor..."
    override val startImport: String = "İçe Aktarmayı Başlat"
    override val desktopAuthDescription: String =
        "YouTube Music hesabınızı doğrulamak için tarayıcınızdan SAPISID veya __Secure-3PAPISID içeren çerezlerinizi yapıştırın."
    override val desktopAuthCookieLabel: String = "Çerez Dizesi (ör. SAPISID=...; SID=...)"
    override val desktopAuthSave: String = "Çerezleri Kaydet"
    override val desktopAuthError: String = "Geçerli bir SAPISID veya __Secure-3PAPISID çerezi bulunamadı."

    override fun downloadingUpdate(version: String): String = "v$version indiriliyor..."
    override fun updateAvailable(version: String): String = "Güncelleme mevcut: v$version"
    override fun couldNotInstallUpdate(msg: String): String =
        if (msg.isNotBlank()) "Güncelleme yüklenemedi: $msg" else "Güncelleme yüklenemedi"
    override val latestVersion: String = "En son sürümü kullanıyorsunuz"
    override fun updateCheckFailed(msg: String): String = "Güncelleme denetimi başarısız: $msg"
    override val youtubeReturnedNothing: String = "YouTube Music şu anda gösterilecek bir içerik döndürmedi."
    override val couldNotLoadYouTube: String = "YouTube Music yüklenemedi"
    override val searchFailed: String = "Arama sonuçları yüklenemedi"
    override val couldNotOpenPage: String = "Bu sayfa açılamadı"
    override val couldNotOpenArtist: String = "Bu sanatçı açılamadı"
    override val spotifyImportFetching: String = "Spotify çalma listesi bilgileri alınıyor..."
    override val spotifyImportNoTracks: String = "Çalma listesinde parça bulunamadı."
    override fun spotifyImportCreating(title: String): String = "'$title' çalma listesi oluşturuluyor..."
    override fun spotifyImportMatching(current: Int, total: Int, title: String): String =
        "Parça eşleştiriliyor $current/$total: $title"
    override fun spotifyImportComplete(matched: Int, total: Int): String =
        "İçe aktarma tamamlandı! $matched / $total parça aktarıldı."
    override fun spotifyImportFailed(msg: String): String = "İçe aktarma başarısız: $msg"

    override val downloadingMusicNotificationTitle: String = "Müzik indiriliyor"
    override fun songsLeft(n: Int): String = "$n şarkı kaldı"
    override val downloadStarting: String = "Başlatılıyor…"
}

object EnglishStrings : AppStrings {
    override val tabHome: String = "Home"
    override val tabForYou: String = "For you"
    override val playFullSong: String = "Play full song"
    override val preview: String = "Preview"
    override val forYouEmpty: String = "Picks come from your home page. Give it a moment, or open Home."
    override val tabSearch: String = "Search"
    override val tabLibrary: String = "Library"
    override val tabSettings: String = "Settings"

    override val homeSubtitle: String = "Made for you by Mrquake"
    override val refreshHome: String = "Refresh Home"
    override val playAllShelf: String = "Play all"
    override val speedDial: String = "Speed dial"
    override val offlineTitle: String = "No internet connection"
    override fun offlineWithDownloads(count: Int): String =
        "You can listen to your $count downloaded ${if (count == 1) "song" else "songs"} offline."
    override val offlineWithoutDownloads: String = "Home will refresh by itself once you are back online."
    override val goToDownloads: String = "Go to downloads"
    override val randomSong: String = "Play a random song"
    override val searchPlaceholder: String = "Search tracks, artists, albums..."
    override val signInYouTubeMusic: String = "Sign in to YouTube Music"
    override val signInPromptSubtitle: String = "Get your own mixes, recommendations and listening history."
    override val signIn: String = "Sign in"
    override val signOut: String = "Sign out"
    override val tryAgain: String = "Try again"

    override val searchFieldPlaceholder: String = "Search songs, albums, artists"
    override val search: String = "Search"
    override val clearSearch: String = "Clear search"
    override val filterAll: String = "All"
    override val filterSongs: String = "Songs"
    override val filterAlbums: String = "Albums"
    override val filterArtists: String = "Artists"
    override val filterPlaylists: String = "Playlists"
    override val filterVideos: String = "Videos"
    override val noResultsFound: String = "No results found"
    override val sectionTopResult: String = "Top result"
    override val sectionSongs: String = "Songs"
    override val sectionAlbums: String = "Albums"
    override val sectionArtists: String = "Artists"
    override val sectionPlaylists: String = "Playlists"
    override val searchHistoryEmpty: String = "Type something to search YouTube Music"
    override val recentSearches: String = "Recent searches"
    override val recentlyPlayed: String = "Recently played"
    override val exploreTitle: String = "Explore"
    override val exploreNewReleases: String = "New releases"
    override val exploreCharts: String = "Charts"
    override val exploreMoods: String = "Moods & genres"
    override val moreYouMayLike: String = "More you may like"
    override val tabAlbums: String = "Albums"
    override fun queueSaved(name: String): String = "Queue saved as \"$name\""
    override val saveQueue: String = "Save queue"
    override val clearQueue: String = "Clear"
    override val backupSection: String = "Backup"
    override val backupExport: String = "Back up"
    override val backupExportSubtitle: String = "Favorites, playlists and history in one file."
    override val backupImport: String = "Restore from a backup"
    override val backupImportSubtitle: String = "Add what a backup file holds to this device."
    override val backupSaved: String = "Backup saved"
    override val backupFailed: String = "Could not save the backup"
    override val backupInvalid: String = "This file is not a QMusic backup"
    override fun backupRestored(favorites: Int, playlists: Int): String = "Restored $favorites favorites and $playlists playlists"
    override val removedFromPlaylist: String = "Removed from the playlist"
    override val couldNotChangePlaylist: String = "Could not change the playlist"
    override val playlistDeleted: String = "Playlist deleted"
    override val removeFromThisPlaylist: String = "Remove from this playlist"
    override val deleteYouTubePlaylistTitle: String = "Delete the playlist?"
    override val deleteYouTubePlaylistMessage: String = "It is deleted from your YouTube Music account too."
    override val saveToYouTube: String = "Save on YouTube Music too"
    override val downloadAll: String = "Download all"
    override val related: String = "Related"
    override val relatedEmpty: String = "Nothing related was found for this song"
    override val statsTitle: String = "Your listening"
    override val statsLast30: String = "Last 30 days"
    override val statsLastYear: String = "Last year"
    override val statsAllTime: String = "All time"
    override val statsMinutes: String = "minutes listened"
    override val statsPlays: String = "plays"
    override val statsSongs: String = "different songs"
    override val statsTopSongs: String = "Your top songs"
    override val statsTopArtists: String = "Your top artists"
    override fun statsTimes(count: Int): String = if (count == 1) "1 play" else "$count plays"
    override val statsEmpty: String = "Not much listening yet. Play some music and this fills up."
    override fun statsShare(period: String, minutes: Int, topSong: String?, topArtist: String?): String = buildString {
        append("I listened to ").append(minutes).append(" minutes of music on QMusic (").append(period.lowercase()).append(").")
        topArtist?.let { append(" Top artist: ").append(it).append('.') }
        topSong?.let { append(" Top song: ").append(it).append('.') }
    }
    override val tabArtists: String = "Artists"
    override val youtubeMusicPlaylists: String = "Your YouTube Music playlists"
    override val devicePlaylists: String = "On this device"
    override fun addedToPlaylist(name: String): String = "Added to \"$name\""
    override val couldNotAddToPlaylist: String = "Could not add it (the playlist may not be yours)"
    override val albumsEmpty: String = "Albums you saved on YouTube Music show up here."
    override val artistsEmpty: String = "Artists you subscribe to show up here."
    override val voiceSearch: String = "Search by voice"
    override val recognizeSong: String = "Identify a song"
    override val clearAll: String = "Clear all"
    override val removeFromHistory: String = "Remove from history"
    override val useThisSuggestion: String = "Use this suggestion"

    override val yourLibrary: String = "Your Library"
    override val newPlaylist: String = "New playlist"
    override val newPlaylistTitle: String = "New Playlist"
    override val playlistNameLabel: String = "Playlist name"
    override val create: String = "Create"
    override val cancel: String = "Cancel"
    override val save: String = "Save"
    override val delete: String = "Delete"
    override val remove: String = "Remove"
    override val tabFavorites: String = "Favorites"
    override val tabPlaylists: String = "Playlists"
    override val tabDownloads: String = "Downloads"
    override val tabHistory: String = "History"
    override val favoritesEmpty: String = "No favorite songs yet.\nTap the heart on any song to add it here."
    override val downloadsEmpty: String = "Nothing downloaded yet.\nDownload your liked songs from the Favorites tab, or use a song's menu."
    override val historyEmpty: String = "No playback history yet.\nSongs you listen to will appear here."
    override val playlistsEmpty: String = "No custom playlists yet.\nCreate one with the + button."
    override val removeAllDownloadsTitle: String = "Remove all downloads?"
    override val removeAllDownloadsMessage: String = "The songs stay in your library; only the copies on this device are deleted."
    override val removeAllDownloadsConfirmMessage: String = "The songs stay in your library; they just need a connection to play again."
    override val removeAll: String = "Remove all"
    override val play: String = "Play"
    override val shuffle: String = "Shuffle"
    override val listenOffline: String = "Listen offline"

    override fun listenOfflineSubtitle(songsEmpty: Boolean, pending: Int, saved: Int, total: Int): String = when {
        songsEmpty -> "Like some songs to save them here."
        pending > 0 -> "Downloading $pending ${if (pending == 1) "song" else "songs"} · $saved of $total saved"
        saved == total -> "All $total songs are on this device."
        else -> "$saved of $total songs are on this device."
    }

    override val cancelDownloads: String = "Cancel downloads"
    override fun downloadAllCount(missing: Int): String = "Download all ($missing)"
    override val autoDownloadLikedTitle: String = "Auto-download liked songs"
    override val autoDownloadLikedSubtitle: String = "Every song you like is saved on this device."
    override val wifiOnlyTitle: String = "Wi-Fi only"
    override val wifiOnlySubtitle: String = "Wait for Wi-Fi instead of using mobile data."
    override fun downloadsSummaryCount(count: Int, sizeStr: String): String =
        "$count ${if (count == 1) "song" else "songs"} · $sizeStr"
    override fun downloadsSummaryStatus(inProgress: Int): String =
        if (inProgress > 0) "$inProgress still downloading" else "Available without a connection"
    override val playlistItemLabel: String = "Playlist"

    override val settingsTitle: String = "Settings"
    override val sectionAccount: String = "Account"
    override val sectionPlayback: String = "Playback"
    override val sectionDownloads: String = "Downloads"
    override val sectionLibrary: String = "Library"
    override val sectionUpdates: String = "Updates"
    override val sectionAbout: String = "About"
    override val sectionLanguage: String = "Language"
    override val selectedLanguage: String = "Selected"
    override val languageOptionTurkish: String = "Türkçe"
    override val languageOptionEnglish: String = "English"
    override val accountSignedInSubtitle: String = "Signed in. Your home, likes and library are synced."
    override val accountSignedOutSubtitle: String = "Sign in for your own mixes, likes and library."
    override val crossfadeTitle: String = "Crossfade"
    override val off: String = "Off"
    override val crossfadeSubtitle: String = "Songs blend into each other as one ends. Tapping a song never fades."
    override val equalizerTitle: String = "Equalizer"
    override val equalizerSubtitle: String = "10 bands"
    override val showVideosTitle: String = "Show music videos"
    override val showVideosSubtitle: String = "Open the player on the video when a song has one. Off: the cover comes first."

    override fun settingsDownloadedSize(bytes: Long): String {
        val mb = bytes / 1_048_576
        val sizeStr = if (mb >= 1024) "${mb / 1024}.${(mb % 1024) * 10 / 1024} GB" else "$mb MB"
        return if (bytes > 0) "$sizeStr on this device" else "Nothing downloaded"
    }

    override val importSpotifyTitle: String = "Import from Spotify"
    override val importSpotifySubtitle: String = "Bring a public Spotify playlist into your library."
    override fun versionLabel(version: String): String = "Version $version"
    override val checkUpdate: String = "Check"
    override fun updateToVersion(version: String): String = "Update to v$version"
    override val checkOnLaunchTitle: String = "Check on launch"
    override val checkOnLaunchSubtitle: String = "Look for a new version whenever the app opens."
    override val madeBy: String = "Made by Mrquake"
    override val sourceCodeTitle: String = "Source code"
    override val aboutLicense: String = "Open source under the GNU GPL v3. Streams are resolved with the open-source PipePipe extractor."

    override val sectionTopSongs: String = "Top songs"
    override val subscribe: String = "Subscribe"
    override val subscribedLabel: String = "Subscribed"
    override val back: String = "Back"
    override fun songsCount(n: Int): String = if (n == 1) "1 song" else "$n songs"
    override val renamePlaylist: String = "Rename playlist"
    override val deletePlaylist: String = "Delete playlist"
    override val localPlaylistEmpty: String = "This playlist is empty.\nUse a song's menu to add it here."
    override val removeFromPlaylist: String = "Remove from playlist"
    override fun deletePlaylistTitle(name: String): String = "Delete \"$name\"?"
    override val deletePlaylistMessage: String = "The songs stay in your library; only this playlist is removed."

    override val collapsePlayer: String = "Collapse player"
    override val nowPlaying: String = "NOW PLAYING"
    override val moreOptions: String = "More"
    override val song: String = "Song"
    override val video: String = "Video"
    override val lyrics: String = "Lyrics"
    override val lyricsUnavailable: String = "No lyrics for this song"
    override val queueEmpty: String = "The queue is empty"
    override val share: String = "Share"
    override val startRadio: String = "Start radio"
    override val reorderQueue: String = "Reorder"
    override val crashReport: String = "Last crash report"
    override val crashReportHint: String = "The app closed unexpectedly. You can share the report with the developer."
    override val clearReport: String = "Clear"
    override val autoUpdateTitle: String = "Update automatically"
    override val audioQualityTitle: String = "Audio quality"
    override val audioQualityWifi: String = "On Wi-Fi"
    override val audioQualityMobile: String = "On mobile data"
    override val qualityHigh: String = "High"
    override val qualityNormal: String = "Normal"
    override val qualityLow: String = "Data saver"
    override val normalizeTitle: String = "Normalize volume"
    override val normalizeSubtitle: String = "Every song plays at the same loudness."
    override val autoUpdateSubtitle: String = "Download and install new versions without asking."
    override fun updateDialogTitle(version: String): String = "QMusic $version is ready"
    override val updateDialogBody: String = "The new version has improvements and new features."
    override val updateNow: String = "Update"
    override val updateLater: String = "Later"
    override val updatingTitle: String = "Updating"
    override val updatingBody: String = "Downloading the new version. Installing starts when it finishes."
    override val updateNotificationsTitle: String = "Update notifications"
    override val updateNotificationsSubtitle: String = "Tell me when a new version is released."
    override val playingNow: String = "Playing now"
    override val removeFromQueue: String = "Remove from queue"
    override val unknownArtist: String = "Unknown artist"
    override val liked: String = "Liked"
    override val like: String = "Like"
    override val favorited: String = "Favorited"
    override val favorite: String = "Favorite"
    override val downloaded: String = "Downloaded"
    override val download: String = "Download"
    override val sleepTimer: String = "Sleep"
    override val endOfSong: String = "End of song"
    override fun eqFadeLabel(crossfadeSeconds: Int): String =
        if (crossfadeSeconds > 0) "EQ · Fade ${crossfadeSeconds}s" else "Equalizer"
    override val shuffleOn: String = "Shuffle on"
    override val shuffleOff: String = "Shuffle off"
    override val previous: String = "Previous"
    override val pause: String = "Pause"
    override val next: String = "Next"
    override val repeatOff: String = "Repeat off"
    override val repeatAll: String = "Repeat all"
    override val repeatOne: String = "Repeat one"
    override fun upNext(title: String?): String = title?.let { "Up next · $it" } ?: "Up next"
    override val sleepTimerTitle: String = "Sleep timer"
    override fun sleepTimerMinutes(minutes: Int): String = "$minutes minutes"
    override val endOfThisSong: String = "End of this song"
    override val turnOffTimer: String = "Turn off timer"
    override val close: String = "Close"

    override val playNext: String = "Play next"
    override val addToQueue: String = "Add to queue"
    override val addToPlaylist: String = "Add to playlist"
    override val removeFromFavorites: String = "Remove from favorites"
    override val addToFavorites: String = "Add to favorites"
    override val removeDownload: String = "Remove download"
    override val goToArtist: String = "Go to artist"

    override val applyAndClose: String = "Apply & Close"
    override fun presetDisplayName(preset: EqualizerPreset): String = preset.displayName
    override val spotifyImportDescription: String =
        "Enter a public Spotify playlist link. Tracks will be resolved to YouTube Music audio streams and saved directly to your local library."
    override val spotifyUrlLabel: String = "Spotify Playlist URL"
    override val importing: String = "Importing..."
    override val startImport: String = "Start Import"
    override val desktopAuthDescription: String =
        "Paste your browser cookies containing SAPISID or __Secure-3PAPISID to authenticate your YouTube Music account."
    override val desktopAuthCookieLabel: String = "Cookie String (e.g. SAPISID=...; SID=...)"
    override val desktopAuthSave: String = "Save Cookies"
    override val desktopAuthError: String = "No valid SAPISID or __Secure-3PAPISID cookie found."

    override fun downloadingUpdate(version: String): String = "Downloading v$version..."
    override fun updateAvailable(version: String): String = "Update available: v$version"
    override fun couldNotInstallUpdate(msg: String): String =
        if (msg.isNotBlank()) msg else "Could not install the update"
    override val latestVersion: String = "You are using the latest version"
    override fun updateCheckFailed(msg: String): String = "Check update failed: $msg"
    override val youtubeReturnedNothing: String = "YouTube Music returned nothing to show right now."
    override val couldNotLoadYouTube: String = "Could not load YouTube Music"
    override val searchFailed: String = "Failed to load search results"
    override val couldNotOpenPage: String = "Could not open this page"
    override val couldNotOpenArtist: String = "Could not open this artist"
    override val spotifyImportFetching: String = "Fetching Spotify playlist metadata..."
    override val spotifyImportNoTracks: String = "No tracks found in playlist."
    override fun spotifyImportCreating(title: String): String = "Creating playlist '$title'..."
    override fun spotifyImportMatching(current: Int, total: Int, title: String): String =
        "Matching track $current/$total: $title"
    override fun spotifyImportComplete(matched: Int, total: Int): String =
        "Import complete! $matched of $total tracks imported."
    override fun spotifyImportFailed(msg: String): String = "Import failed: $msg"

    override val downloadingMusicNotificationTitle: String = "Downloading music"
    override fun songsLeft(n: Int): String = if (n == 1) "1 song left" else "$n songs left"
    override val downloadStarting: String = "Starting…"
}

val LocalStrings = staticCompositionLocalOf<AppStrings> { TurkishStrings }
