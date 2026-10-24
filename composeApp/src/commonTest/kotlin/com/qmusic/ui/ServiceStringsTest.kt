package com.qmusic.ui

import com.qmusic.ui.strings.EnglishStrings
import com.qmusic.ui.strings.TurkishStrings
import com.qmusic.ui.strings.forService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** With a Spotify account nothing on screen names YouTube Music; with YouTube Music the texts stay as they are. */
class ServiceStringsTest {
    @Test
    fun spotifyTextsNeverNameYouTube() {
        for (base in listOf(TurkishStrings, EnglishStrings)) {
            val s = base.forService(spotify = true)
            listOf(
                s.signInYouTubeMusic, s.searchHistoryEmpty, s.deleteYouTubePlaylistMessage, s.saveToYouTube,
                s.youtubeMusicPlaylists, s.albumsEmpty, s.artistsEmpty, s.youtubeReturnedNothing, s.couldNotLoadYouTube,
                s.accountSignedInSubtitle, s.accountSignedOutSubtitle, s.subscribe, s.subscribedLabel,
            ).forEach { text -> assertFalse("YouTube" in text, text) }
            // Everything else is the language's own text.
            assertEquals(base.tabHome, s.tabHome)
        }
    }

    @Test
    fun youTubeTextsAreUnchanged() {
        assertSame(TurkishStrings, TurkishStrings.forService(spotify = false))
    }
}
