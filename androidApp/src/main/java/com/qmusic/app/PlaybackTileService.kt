package com.qmusic.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.qmusic.player.PlayerHolder
import com.qmusic.player.service.SimpleMediaService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile: shows the song that is playing and plays / pauses it; with nothing playing it opens the
 * app. It follows the player while the panel is open.
 */
class PlaybackTileService : TileService() {
    private val scope = MainScope()
    private var watcher: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val player = PlayerHolder.peek()
        if (player == null) {
            draw(playing = false, title = null)
            return
        }
        watcher?.cancel()
        watcher = scope.launch {
            combine(player.currentTrack, player.isPlaying) { track, playing -> track to playing }
                .collect { (track, playing) -> draw(playing, track?.title) }
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val player = PlayerHolder.peek()
        if (player?.currentTrack?.value == null) {
            openApp()
            return
        }
        if (player.isPlaying.value) {
            player.pause()
        } else {
            runCatching { startService(Intent(this, SimpleMediaService::class.java)) }
            player.resume()
        }
    }

    private fun draw(playing: Boolean, title: String?) {
        val tile = qsTile ?: return
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = title
        tile.updateTile()
    }

    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }
}
