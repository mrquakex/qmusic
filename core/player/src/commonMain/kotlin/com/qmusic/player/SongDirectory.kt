package com.qmusic.player

import com.qmusic.engine.models.SongItem

/**
 * The songs the player has been handed, by id. A Spotify song is only an id when its stream is looked up; this is
 * where its title, artist and length are found, to find the same recording to play.
 */
object SongDirectory {
    private val songs = HashMap<String, SongItem>()

    fun remember(list: List<SongItem>) {
        synchronized(songs) { list.forEach { songs[it.id] = it } }
    }

    fun get(id: String): SongItem? = synchronized(songs) { songs[id] }
}
