package com.qmusic.ui

import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.player.NoOpPlayerController
import com.qmusic.settings.InMemoryAppSettings
import com.qmusic.ui.strings.AppStrings
import com.qmusic.ui.strings.EnglishStrings
import com.qmusic.ui.strings.TurkishStrings
import com.qmusic.ui.viewmodel.EqualizerPreset
import com.qmusic.ui.viewmodel.MainViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LanguageTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val fakeRepo = FakeMusicRepository()
    private val playerController = NoOpPlayerController()
    private val fakeUpdater = FakeUpdateChecker()
    private val youTubeAuth = YouTubeAuth()

    private fun createViewModel(
        settings: InMemoryAppSettings = InMemoryAppSettings(),
    ): MainViewModel {
        return MainViewModel(
            playerController = playerController,
            repository = fakeRepo,
            settings = settings,
            youTubeAuth = youTubeAuth,
            updateChecker = fakeUpdater,
            externalScope = testScope,
            autoLoadHome = false,
            autoRefillQueue = false,
        )
    }

    @Test
    fun testDefaultLanguageIsTurkish() {
        val settings = InMemoryAppSettings()
        assertEquals("tr", settings.language.value, "Default settings language should be 'tr'")

        val vm = createViewModel(settings)
        assertEquals("tr", vm.language.value, "ViewModel default language should be 'tr'")
    }

    @Test
    fun testSetLanguageUpdatesViewModelAndSettings() {
        val settings = InMemoryAppSettings()
        val vm = createViewModel(settings)

        assertEquals("tr", vm.language.value)
        vm.setLanguage("en")

        assertEquals("en", vm.language.value, "ViewModel language should be updated to 'en'")
        assertEquals("en", settings.language.value, "Settings language should be updated to 'en'")

        vm.setLanguage("tr")
        assertEquals("tr", vm.language.value)
        assertEquals("tr", settings.language.value)
    }

    @Test
    fun testTurkishStringsAreAllNonEmpty() {
        assertAllStringsNonEmpty(TurkishStrings)
    }

    @Test
    fun testEnglishStringsAreAllNonEmpty() {
        assertAllStringsNonEmpty(EnglishStrings)
    }

    @Test
    fun testGrammarAndPluralizationRules() {
        // Turkish: numbers do not use plural suffixes
        assertEquals("5 şarkı kaldı", TurkishStrings.songsLeft(5))
        assertEquals("1 şarkı kaldı", TurkishStrings.songsLeft(1))
        assertEquals("5 şarkı", TurkishStrings.songsCount(5))
        assertEquals("1 şarkı", TurkishStrings.songsCount(1))
        assertEquals("Tümünü indir (5)", TurkishStrings.downloadAllCount(5))
        assertEquals("5 şarkı · 25 MB", TurkishStrings.downloadsSummaryCount(5, "25 MB"))
        assertEquals("1 şarkı · 5 MB", TurkishStrings.downloadsSummaryCount(1, "5 MB"))

        // English: 1 is singular, >1 is plural
        assertEquals("1 song left", EnglishStrings.songsLeft(1))
        assertEquals("5 songs left", EnglishStrings.songsLeft(5))
        assertEquals("1 song", EnglishStrings.songsCount(1))
        assertEquals("5 songs", EnglishStrings.songsCount(5))
        assertEquals("Download all (5)", EnglishStrings.downloadAllCount(5))
        assertEquals("1 song · 5 MB", EnglishStrings.downloadsSummaryCount(1, "5 MB"))
        assertEquals("5 songs · 25 MB", EnglishStrings.downloadsSummaryCount(5, "25 MB"))
    }

    private fun assertAllStringsNonEmpty(s: AppStrings) {
        // Navigation
        assertTrue(s.tabHome.isNotBlank())
        assertTrue(s.tabSearch.isNotBlank())
        assertTrue(s.tabLibrary.isNotBlank())
        assertTrue(s.tabSettings.isNotBlank())

        // Home
        assertTrue(s.homeSubtitle.isNotBlank())
        assertTrue(s.refreshHome.isNotBlank())
        assertTrue(s.searchPlaceholder.isNotBlank())
        assertTrue(s.signInYouTubeMusic.isNotBlank())
        assertTrue(s.signInPromptSubtitle.isNotBlank())
        assertTrue(s.signIn.isNotBlank())
        assertTrue(s.signOut.isNotBlank())
        assertTrue(s.tryAgain.isNotBlank())

        // Search
        assertTrue(s.searchFieldPlaceholder.isNotBlank())
        assertTrue(s.search.isNotBlank())
        assertTrue(s.clearSearch.isNotBlank())
        assertTrue(s.filterAll.isNotBlank())
        assertTrue(s.filterSongs.isNotBlank())
        assertTrue(s.filterAlbums.isNotBlank())
        assertTrue(s.filterArtists.isNotBlank())
        assertTrue(s.filterPlaylists.isNotBlank())
        assertTrue(s.filterVideos.isNotBlank())
        assertTrue(s.noResultsFound.isNotBlank())
        assertTrue(s.sectionTopResult.isNotBlank())
        assertTrue(s.sectionSongs.isNotBlank())
        assertTrue(s.sectionAlbums.isNotBlank())
        assertTrue(s.sectionArtists.isNotBlank())
        assertTrue(s.sectionPlaylists.isNotBlank())
        assertTrue(s.searchHistoryEmpty.isNotBlank())
        assertTrue(s.recentSearches.isNotBlank())
        assertTrue(s.clearAll.isNotBlank())
        assertTrue(s.removeFromHistory.isNotBlank())
        assertTrue(s.useThisSuggestion.isNotBlank())

        // Library
        assertTrue(s.yourLibrary.isNotBlank())
        assertTrue(s.newPlaylist.isNotBlank())
        assertTrue(s.newPlaylistTitle.isNotBlank())
        assertTrue(s.playlistNameLabel.isNotBlank())
        assertTrue(s.create.isNotBlank())
        assertTrue(s.cancel.isNotBlank())
        assertTrue(s.save.isNotBlank())
        assertTrue(s.delete.isNotBlank())
        assertTrue(s.remove.isNotBlank())
        assertTrue(s.tabFavorites.isNotBlank())
        assertTrue(s.tabPlaylists.isNotBlank())
        assertTrue(s.tabDownloads.isNotBlank())
        assertTrue(s.tabHistory.isNotBlank())
        assertTrue(s.favoritesEmpty.isNotBlank())
        assertTrue(s.downloadsEmpty.isNotBlank())
        assertTrue(s.historyEmpty.isNotBlank())
        assertTrue(s.playlistsEmpty.isNotBlank())
        assertTrue(s.removeAllDownloadsTitle.isNotBlank())
        assertTrue(s.removeAllDownloadsMessage.isNotBlank())
        assertTrue(s.removeAllDownloadsConfirmMessage.isNotBlank())
        assertTrue(s.removeAll.isNotBlank())
        assertTrue(s.play.isNotBlank())
        assertTrue(s.shuffle.isNotBlank())
        assertTrue(s.listenOffline.isNotBlank())
        assertTrue(s.listenOfflineSubtitle(songsEmpty = false, pending = 2, saved = 3, total = 5).isNotBlank())
        assertTrue(s.listenOfflineSubtitle(songsEmpty = true, pending = 0, saved = 0, total = 0).isNotBlank())
        assertTrue(s.cancelDownloads.isNotBlank())
        assertTrue(s.downloadAllCount(3).isNotBlank())
        assertTrue(s.autoDownloadLikedTitle.isNotBlank())
        assertTrue(s.autoDownloadLikedSubtitle.isNotBlank())
        assertTrue(s.wifiOnlyTitle.isNotBlank())
        assertTrue(s.wifiOnlySubtitle.isNotBlank())
        assertTrue(s.downloadsSummaryCount(5, "25 MB").isNotBlank())
        assertTrue(s.downloadsSummaryStatus(2).isNotBlank())
        assertTrue(s.playlistItemLabel.isNotBlank())

        // Settings
        assertTrue(s.settingsTitle.isNotBlank())
        assertTrue(s.sectionAccount.isNotBlank())
        assertTrue(s.sectionPlayback.isNotBlank())
        assertTrue(s.sectionDownloads.isNotBlank())
        assertTrue(s.sectionLibrary.isNotBlank())
        assertTrue(s.sectionUpdates.isNotBlank())
        assertTrue(s.sectionAbout.isNotBlank())
        assertTrue(s.sectionLanguage.isNotBlank())
        assertTrue(s.selectedLanguage.isNotBlank())
        assertTrue(s.languageOptionTurkish.isNotBlank())
        assertTrue(s.languageOptionEnglish.isNotBlank())
        assertTrue(s.accountSignedInSubtitle.isNotBlank())
        assertTrue(s.accountSignedOutSubtitle.isNotBlank())
        assertTrue(s.crossfadeTitle.isNotBlank())
        assertTrue(s.off.isNotBlank())
        assertTrue(s.crossfadeSubtitle.isNotBlank())
        assertTrue(s.equalizerTitle.isNotBlank())
        assertTrue(s.equalizerSubtitle.isNotBlank())
        assertTrue(s.showVideosTitle.isNotBlank())
        assertTrue(s.showVideosSubtitle.isNotBlank())
        assertTrue(s.settingsDownloadedSize(1024L * 1024L * 50).isNotBlank())
        assertTrue(s.importSpotifyTitle.isNotBlank())
        assertTrue(s.importSpotifySubtitle.isNotBlank())
        assertTrue(s.versionLabel("1.0.0").isNotBlank())
        assertTrue(s.checkUpdate.isNotBlank())
        assertTrue(s.updateToVersion("1.1.0").isNotBlank())
        assertTrue(s.checkOnLaunchTitle.isNotBlank())
        assertTrue(s.checkOnLaunchSubtitle.isNotBlank())
        assertTrue(s.madeBy.isNotBlank())
        assertTrue(s.sourceCodeTitle.isNotBlank())
        assertTrue(s.aboutLicense.isNotBlank())

        // Artist & Collection & Local Playlist
        assertTrue(s.sectionTopSongs.isNotBlank())
        assertTrue(s.back.isNotBlank())
        assertTrue(s.songsCount(10).isNotBlank())
        assertTrue(s.renamePlaylist.isNotBlank())
        assertTrue(s.deletePlaylist.isNotBlank())
        assertTrue(s.localPlaylistEmpty.isNotBlank())
        assertTrue(s.removeFromPlaylist.isNotBlank())
        assertTrue(s.deletePlaylistTitle("Favoriler").isNotBlank())
        assertTrue(s.deletePlaylistMessage.isNotBlank())

        // Player
        assertTrue(s.collapsePlayer.isNotBlank())
        assertTrue(s.nowPlaying.isNotBlank())
        assertTrue(s.moreOptions.isNotBlank())
        assertTrue(s.song.isNotBlank())
        assertTrue(s.video.isNotBlank())
        assertTrue(s.lyrics.isNotBlank())
        assertTrue(s.lyricsUnavailable.isNotBlank())
        assertTrue(s.queueEmpty.isNotBlank())
        assertTrue(s.playingNow.isNotBlank())
        assertTrue(s.removeFromQueue.isNotBlank())
        assertTrue(s.unknownArtist.isNotBlank())
        assertTrue(s.liked.isNotBlank())
        assertTrue(s.like.isNotBlank())
        assertTrue(s.favorited.isNotBlank())
        assertTrue(s.favorite.isNotBlank())
        assertTrue(s.downloaded.isNotBlank())
        assertTrue(s.download.isNotBlank())
        assertTrue(s.sleepTimer.isNotBlank())
        assertTrue(s.endOfSong.isNotBlank())
        assertTrue(s.eqFadeLabel(5).isNotBlank())
        assertTrue(s.shuffleOn.isNotBlank())
        assertTrue(s.shuffleOff.isNotBlank())
        assertTrue(s.previous.isNotBlank())
        assertTrue(s.pause.isNotBlank())
        assertTrue(s.next.isNotBlank())
        assertTrue(s.repeatOff.isNotBlank())
        assertTrue(s.repeatAll.isNotBlank())
        assertTrue(s.repeatOne.isNotBlank())
        assertTrue(s.upNext("Track").isNotBlank())
        assertTrue(s.upNext(null).isNotBlank())
        assertTrue(s.sleepTimerTitle.isNotBlank())
        assertTrue(s.sleepTimerMinutes(30).isNotBlank())
        assertTrue(s.endOfThisSong.isNotBlank())
        assertTrue(s.turnOffTimer.isNotBlank())
        assertTrue(s.close.isNotBlank())

        // Actions
        assertTrue(s.playNext.isNotBlank())
        assertTrue(s.addToQueue.isNotBlank())
        assertTrue(s.addToPlaylist.isNotBlank())
        assertTrue(s.removeFromFavorites.isNotBlank())
        assertTrue(s.addToFavorites.isNotBlank())
        assertTrue(s.removeDownload.isNotBlank())
        assertTrue(s.goToArtist.isNotBlank())

        // Equalizer & Spotify & YouTube Auth
        assertTrue(s.applyAndClose.isNotBlank())
        EqualizerPreset.entries.forEach { preset ->
            assertTrue(s.presetDisplayName(preset).isNotBlank())
        }
        assertTrue(s.spotifyImportDescription.isNotBlank())
        assertTrue(s.spotifyUrlLabel.isNotBlank())
        assertTrue(s.importing.isNotBlank())
        assertTrue(s.startImport.isNotBlank())
        assertTrue(s.desktopAuthDescription.isNotBlank())
        assertTrue(s.desktopAuthCookieLabel.isNotBlank())
        assertTrue(s.desktopAuthSave.isNotBlank())
        assertTrue(s.desktopAuthError.isNotBlank())

        // ViewModel Messages
        assertTrue(s.downloadingUpdate("1.1.0").isNotBlank())
        assertTrue(s.updateAvailable("1.1.0").isNotBlank())
        assertTrue(s.couldNotInstallUpdate("Failed").isNotBlank())
        assertTrue(s.latestVersion.isNotBlank())
        assertTrue(s.updateCheckFailed("Failed").isNotBlank())
        assertTrue(s.youtubeReturnedNothing.isNotBlank())
        assertTrue(s.couldNotLoadYouTube.isNotBlank())
        assertTrue(s.searchFailed.isNotBlank())
        assertTrue(s.couldNotOpenPage.isNotBlank())
        assertTrue(s.couldNotOpenArtist.isNotBlank())
        assertTrue(s.spotifyImportFetching.isNotBlank())
        assertTrue(s.spotifyImportNoTracks.isNotBlank())
        assertTrue(s.spotifyImportCreating("List").isNotBlank())
        assertTrue(s.spotifyImportMatching(1, 10, "Title").isNotBlank())
        assertTrue(s.spotifyImportComplete(10, 10).isNotBlank())
        assertTrue(s.spotifyImportFailed("Failed").isNotBlank())

        // Notification
        assertTrue(s.downloadingMusicNotificationTitle.isNotBlank())
        assertTrue(s.songsLeft(3).isNotBlank())
        assertTrue(s.downloadStarting.isNotBlank())
    }
}
