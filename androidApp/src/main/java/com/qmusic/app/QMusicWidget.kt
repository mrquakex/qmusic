package com.qmusic.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.qmusic.engine.models.SongItem
import com.qmusic.player.PlayerController
import com.qmusic.player.PlayerHolder
import com.qmusic.player.service.SimpleMediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The home-screen player. Its buttons talk to the player that is already running; when nothing is (the app was
 * closed), a tap opens the app instead. [WidgetUpdater] keeps every placed widget showing the playing song.
 */
class QMusicWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val player: PlayerController? = PlayerHolder.peek()
        val hasSong = player?.currentTrack?.value != null
        // The playback notification belongs to the media service: once it has stopped (a long pause, the
        // notification swiped away), playing from here alone would sound without one. Taps on a widget may start it.
        if (hasSong && intent.action in WIDGET_ACTIONS) {
            runCatching { context.startService(Intent(context, SimpleMediaService::class.java)) }
        }
        when (intent.action) {
            ACTION_PLAY_PAUSE -> if (hasSong) {
                if (player!!.isPlaying.value) player.pause() else player.resume()
            } else {
                openApp(context)
            }
            ACTION_NEXT -> if (hasSong) player!!.next() else openApp(context)
            ACTION_PREVIOUS -> if (hasSong) player!!.previous() else openApp(context)
        }
    }

    private fun openApp(context: Context) {
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let(context::startActivity)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.qmusic.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.qmusic.widget.NEXT"
        const val ACTION_PREVIOUS = "com.qmusic.widget.PREVIOUS"
        private val WIDGET_ACTIONS = setOf(ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREVIOUS)
    }
}

/** Draws the widget from the player's state, and follows that state for as long as the process lives. */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var coverFor: String? = null
    private var cover: Bitmap? = null

    /** Starts following [player]; called once the player exists. */
    fun start(context: Context, player: PlayerController) {
        if (watcher?.isActive == true) return
        val app = context.applicationContext
        watcher = scope.launch {
            combine(player.currentTrack, player.isPlaying) { track, playing -> track to playing }
                .distinctUntilChanged()
                .collect { (track, playing) ->
                    push(app, track, playing)
                    if (track != null && coverFor != track.id) loadCover(app, track, playing)
                }
        }
    }

    /** Redraws with whatever is known now (a widget was just placed, or the system asks). */
    fun refresh(context: Context) {
        val player = PlayerHolder.peek()
        push(context.applicationContext, player?.currentTrack?.value, player?.isPlaying?.value == true)
    }

    private fun loadCover(context: Context, track: SongItem, playing: Boolean) {
        val url = track.thumbnail ?: track.thumbnails.lastOrNull()?.url ?: return
        scope.launch {
            val request = ImageRequest.Builder(context).data(url).size(256).allowHardware(false).build()
            val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            coverFor = track.id
            cover = bitmap
            push(context, track, playing)
        }
    }

    private fun push(context: Context, track: SongItem?, playing: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, QMusicWidget::class.java)
        if (manager.getAppWidgetIds(component).isEmpty()) return

        val views = RemoteViews(context.packageName, R.layout.widget_player)
        views.setTextViewText(R.id.widget_title, track?.title ?: context.getString(R.string.widget_nothing_playing))
        views.setTextViewText(
            R.id.widget_artist,
            track?.artists?.joinToString { it.name }?.ifBlank { null } ?: context.getString(R.string.widget_tap_to_open),
        )
        val art = cover.takeIf { track != null && coverFor == track.id }
        if (art != null) views.setImageViewBitmap(R.id.widget_cover, art) else views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
        views.setImageViewResource(R.id.widget_play_pause, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)

        views.setOnClickPendingIntent(R.id.widget_play_pause, action(context, QMusicWidget.ACTION_PLAY_PAUSE, 1))
        views.setOnClickPendingIntent(R.id.widget_next, action(context, QMusicWidget.ACTION_NEXT, 2))
        views.setOnClickPendingIntent(R.id.widget_previous, action(context, QMusicWidget.ACTION_PREVIOUS, 3))
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 4, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        manager.updateAppWidget(component, views)
    }

    private fun action(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, QMusicWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
