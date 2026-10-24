package com.qmusic.engine.models

/**
 * Ids of Spotify things inside the app. A Spotify track keeps its own id (with a prefix) so it can be liked or added
 * to a playlist on Spotify; the player turns it into a YouTube stream when it is played.
 */
object SpotifyIds {
    private const val PREFIX = "sp:"
    private const val ARTIST = "sp:artist:"
    private const val ALBUM = "sp:album:"
    private const val PLAYLIST = "sp:playlist:"

    fun track(id: String) = "$PREFIX$id"
    fun artist(id: String) = "$ARTIST$id"
    fun album(id: String) = "$ALBUM$id"
    fun playlist(id: String) = "$PLAYLIST$id"

    fun isSpotify(id: String) = id.startsWith(PREFIX)

    /** The Spotify track id of an app song id, or null when it is not a Spotify track. */
    fun trackId(id: String): String? = id.takeIf { isSpotify(it) }?.removePrefix(PREFIX)?.takeIf { ':' !in it }

    fun artistId(id: String): String? = id.takeIf { it.startsWith(ARTIST) }?.removePrefix(ARTIST)
    fun albumId(id: String): String? = id.takeIf { it.startsWith(ALBUM) }?.removePrefix(ALBUM)
    fun playlistId(id: String): String? = id.takeIf { it.startsWith(PLAYLIST) }?.removePrefix(PLAYLIST)

    fun uri(trackId: String) = "spotify:track:$trackId"
}
