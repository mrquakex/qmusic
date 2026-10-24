package com.qmusic.ui.strings

/**
 * The texts for the account in use: with Spotify, nothing says "YouTube Music" (the sound still comes from YouTube,
 * but that is how the app plays, not something the user chose).
 */
fun AppStrings.forService(spotify: Boolean): AppStrings = if (spotify) SpotifyStrings(this) else this

private class SpotifyStrings(private val base: AppStrings) : AppStrings by base {
    private val tr = base === TurkishStrings

    override val signInYouTubeMusic: String get() = base.signInSpotify
    override val searchHistoryEmpty: String get() = if (tr) "Aramak için bir şeyler yaz" else "Type something to search"
    override val deleteYouTubePlaylistMessage: String
        get() = if (tr) "Liste Spotify kitaplığından da kaldırılır." else "It is removed from your Spotify library too."
    override val saveToYouTube: String get() = if (tr) "Spotify'a da kaydet" else "Save on Spotify too"
    override val youtubeMusicPlaylists: String get() = if (tr) "Spotify listelerin" else "Your Spotify playlists"
    override val albumsEmpty: String get() = if (tr) "Spotify'da kaydettiğin albümler burada görünür." else "Albums you saved on Spotify show up here."
    override val artistsEmpty: String get() = if (tr) "Takip ettiğin sanatçılar burada görünür." else "Artists you follow show up here."
    override val youtubeReturnedNothing: String
        get() = if (tr) "Spotify şu anda gösterilecek bir şey döndürmedi." else "Spotify returned nothing to show right now."
    override val couldNotLoadYouTube: String get() = if (tr) "Spotify yüklenemedi" else "Could not load Spotify"
    override val subscribe: String get() = if (tr) "Takip et" else "Follow"
    override val subscribedLabel: String get() = if (tr) "Takip ediliyor" else "Following"
    override val accountSignedInSubtitle: String get() = base.spotifySignedInSubtitle
    override val accountSignedOutSubtitle: String get() = base.spotifySignedOutSubtitle
}
