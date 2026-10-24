package com.qmusic.ui

import com.qmusic.ui.viewmodel.AppLink
import com.qmusic.ui.viewmodel.AppLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLinksTest {
    @Test
    fun songLinksOfEveryKindAreRead() {
        assertEquals(AppLink.Song("kOn-HdEg6AQ"), AppLinks.parse("https://music.youtube.com/watch?v=kOn-HdEg6AQ&si=abc"))
        assertEquals(AppLink.Song("kOn-HdEg6AQ"), AppLinks.parse("https://www.youtube.com/watch?feature=share&v=kOn-HdEg6AQ"))
        assertEquals(AppLink.Song("kOn-HdEg6AQ"), AppLinks.parse("https://youtu.be/kOn-HdEg6AQ?si=xyz"))
        assertEquals(AppLink.Song("kOn-HdEg6AQ"), AppLinks.parse("https://m.youtube.com/shorts/kOn-HdEg6AQ"))
    }

    @Test
    fun aLinkInsideSharedTextIsFound() {
        assertEquals(
            AppLink.Song("kOn-HdEg6AQ"),
            AppLinks.parse("Beat It - Michael Jackson dinle: https://music.youtube.com/watch?v=kOn-HdEg6AQ."),
        )
    }

    @Test
    fun playlistsAlbumsAndArtistsOpenTheirPages() {
        assertEquals(AppLink.Playlist("PLabc123"), AppLinks.parse("https://music.youtube.com/playlist?list=PLabc123"))
        assertEquals(AppLink.Playlist("PLabc123"), AppLinks.parse("https://music.youtube.com/browse/VLPLabc123"))
        assertEquals(AppLink.Album("MPREb_xyz"), AppLinks.parse("https://music.youtube.com/browse/MPREb_xyz"))
        assertEquals(AppLink.Artist("UCabc"), AppLinks.parse("https://music.youtube.com/channel/UCabc"))
    }

    @Test
    fun spotifyLinksAreRead() {
        assertEquals(AppLink.Spotify("track", "4uLU6hMCjMI75M1A2tKUQC"), AppLinks.parse("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=x"))
        assertEquals(AppLink.Spotify("album", "abc"), AppLinks.parse("https://open.spotify.com/intl-tr/album/abc"))
        assertEquals(AppLink.Spotify("playlist", "p1"), AppLinks.parse("Dinle https://open.spotify.com/playlist/p1"))
        assertNull(AppLinks.parse("https://open.spotify.com/user/someone"))
    }

    @Test
    fun otherLinksAndPlainTextAreIgnored() {
        assertNull(AppLinks.parse("https://example.com/watch?v=kOn-HdEg6AQ"))
        assertNull(AppLinks.parse("no link here"))
        assertNull(AppLinks.parse("https://music.youtube.com/watch?v=short"))
    }
}
