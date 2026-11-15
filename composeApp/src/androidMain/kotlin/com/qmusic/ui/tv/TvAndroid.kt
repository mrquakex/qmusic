package com.qmusic.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.SongItem
import com.qmusic.player.PlayerHolder
import com.qmusic.ui.VideoCast
import com.qmusic.ui.VideoCastState
import com.qmusic.ui.chat.ShareKind
import com.qmusic.ui.chat.SharedItem
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Playing on a TV linked by code. On a TV, Qeyf shows a code and plays what a linked phone sends; on a phone, it
 * links TVs by their code and sends them videos and songs, and drives them (play, pause, seek, next).
 *
 * They meet in the database: tvCodes/{code} names the TV (for a few minutes), tvs/{secret} carries the phone's
 * commands (cmd) and the TV's state (state). The secret is long and random, so only phones the TV's code was given to
 * know where it is. The TV fetches the video or the song itself, so the phone can sleep or leave.
 */
@UnstableApi
object TvAndroid {
    private val scope = MainScope()
    private var context: Context? = null

    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        val db = database(context) ?: return
        if (isTelevision(context)) TvReceiver.start(context.applicationContext, db, scope) else TvSender.init(context.applicationContext, db, scope)
    }

    private fun isTelevision(context: Context): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** The app's database (opened by the messages first, which keeps its offline settings); null without Firebase. */
    private fun database(context: Context): FirebaseDatabase? = runCatching {
        com.qmusic.ui.chat.FirebaseChatBackend.get(context.applicationContext) ?: return null
        val configured = FirebaseApp.getInstance().options.databaseUrl
        if (configured.isNullOrBlank()) FirebaseDatabase.getInstance(DATABASE_URL) else FirebaseDatabase.getInstance()
    }.onFailure { Log.w(TAG, "no database: ${it.message}") }.getOrNull()

    internal fun randomHex(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    internal const val TAG = "QeyfTv"
    private const val DATABASE_URL = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app"
}

// ================================================================================================== the TV

private object TvReceiver {
    private lateinit var ref: DatabaseReference
    private lateinit var db: FirebaseDatabase
    private var secret = ""
    private var name = ""
    private var lastCommand: String? = null
    private var lastState: Map<String, Any?>? = null

    fun start(context: Context, db: FirebaseDatabase, scope: kotlinx.coroutines.CoroutineScope) {
        TvScreen.isTv = true
        this.db = db
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        secret = prefs.getString(KEY_SECRET, null) ?: TvAndroid.randomHex(16).also { prefs.edit().putString(KEY_SECRET, it).apply() }
        name = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        ref = db.getReference("tvs").child(secret)
        ref.child("name").setValue(name)
        // A command left from before is not played again: only what comes from now on.
        ref.child("cmd").removeValue().addOnCompleteListener { listen() }
        // A new code every few minutes (a guessed old one leads nowhere).
        scope.launch {
            while (true) {
                newCode()
                delay(CODE_MS)
            }
        }
        // What plays, for the phone (and for the music screen here).
        scope.launch {
            while (true) {
                report()
                delay(700)
            }
        }
    }

    private fun newCode(attempt: Int = 0) {
        val code = (100000 + SecureRandom().nextInt(900000)).toString()
        val old = TvScreen.code.value
        db.getReference("tvCodes").child(code)
            .setValue(mapOf("s" to secret, "n" to name, "at" to ServerValue.TIMESTAMP))
            .addOnSuccessListener {
                TvScreen.code.value = code
                old?.let { db.getReference("tvCodes").child(it).removeValue() }
            }
            .addOnFailureListener {
                Log.w(TvAndroid.TAG, "code not written: ${it.message}")
                if (attempt < 3) newCode(attempt + 1)
            }
    }

    private fun listen() {
        ref.child("cmd").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val id = snapshot.child("n").getValue(String::class.java) ?: return
                if (id == lastCommand) return
                lastCommand = id
                runCatching { handle(snapshot) }.onFailure { Log.w(TvAndroid.TAG, "command failed: ${it.message}") }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TvAndroid.TAG, "commands closed: ${error.message}")
            }
        })
    }

    private fun handle(c: DataSnapshot) {
        val pos = c.child("pos").getValue(Long::class.java) ?: 0L
        when (c.child("k").getValue(String::class.java)) {
            "video" -> {
                val id = c.child("id").getValue(String::class.java) ?: return
                TvScreen.browsing.value = false
                TvScreen.music?.pause()
                TvScreen.mode.value = TvMode.VIDEO
                TvScreen.video?.open(
                    SharedItem(ShareKind.VIDEO, id, c.child("title").getValue(String::class.java).orEmpty(), "", c.child("thumb").getValue(String::class.java)),
                    pos,
                    true,
                )
            }
            "music" -> {
                val songs = c.child("items").children.mapNotNull { s ->
                    val id = s.child("id").getValue(String::class.java) ?: return@mapNotNull null
                    SongItem(
                        id = id,
                        title = s.child("t").getValue(String::class.java).orEmpty(),
                        artists = s.child("a").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { listOf(Artist(it)) }.orEmpty(),
                        thumbnail = s.child("th").getValue(String::class.java),
                    )
                }
                if (songs.isEmpty()) return
                TvScreen.browsing.value = false
                TvScreen.video?.pause()
                TvScreen.mode.value = TvMode.MUSIC
                val index = (c.child("idx").getValue(Long::class.java) ?: 0L).toInt().coerceIn(0, songs.size - 1)
                TvScreen.playQueue?.invoke(songs, index, pos)
            }
            "play" -> current()?.play()
            "pause" -> current()?.pause()
            "seek" -> current()?.seekTo(pos)
            "jump" -> TvScreen.jump?.invoke((c.child("idx").getValue(Long::class.java) ?: 0L).toInt())
            "stop" -> TvScreen.stop()
        }
    }

    private fun current() = when (TvScreen.mode.value) {
        TvMode.VIDEO -> TvScreen.video
        TvMode.MUSIC -> TvScreen.music
        TvMode.IDLE -> null
    }

    private fun report() {
        val mode = TvScreen.mode.value
        val p = current()
        p?.tick()
        val item = p?.current()
        val state: Map<String, Any?> = if (p == null || item == null) {
            TvScreen.now.value = null
            mapOf("k" to "idle")
        } else {
            val duration = (if (mode == TvMode.VIDEO) TvScreen.videoDuration?.invoke() else TvScreen.musicDuration?.invoke()) ?: 0L
            val position = p.positionMs()
            val playing = p.isPlaying()
            TvScreen.now.value = TvNow(item.title, item.subtitle, item.thumbnail, position, duration, playing)
            mapOf(
                "k" to if (mode == TvMode.VIDEO) "video" else "music",
                "id" to item.id,
                "title" to item.title,
                "pos" to position,
                "dur" to duration,
                "playing" to playing,
                "idx" to (if (mode == TvMode.MUSIC) TvScreen.musicIndex?.invoke() ?: 0 else 0),
            )
        }
        // Written only when it changed (the position changes every moment while playing).
        if (state == lastState) return
        lastState = state
        ref.child("state").setValue(state + ("at" to ServerValue.TIMESTAMP))
    }

    private const val PREFS = "qeyf_tv"
    private const val KEY_SECRET = "secret"
    private const val CODE_MS = 10 * 60_000L
}

// ================================================================================================== the phone

@UnstableApi
object TvSender {
    private lateinit var db: FirebaseDatabase
    private lateinit var context: Context
    private var stateRef: DatabaseReference? = null
    private var stateListener: ValueEventListener? = null
    private val remote by lazy { TvRemotePlayer(::send) }

    private enum class Mode { NONE, VIDEO, MUSIC }

    private var mode = Mode.NONE

    /** A TV plays for this phone now. */
    val active: Boolean get() = TvLink.active.value != null

    internal fun init(context: Context, db: FirebaseDatabase, scope: kotlinx.coroutines.CoroutineScope) {
        this.db = db
        this.context = context
        TvLink.tvs.value = load()
        TvLink.link = { code -> link(code) }
        TvLink.use = { tv -> use(tv) }
        TvLink.forget = { tv ->
            if (TvLink.active.value?.id == tv.id) use(null)
            save(TvLink.tvs.value.filter { it.id != tv.id })
        }
    }

    private fun english() = TvLink.english

    private suspend fun link(code: String): String? {
        val snap = withTimeoutOrNull(12_000) { runCatching { db.getReference("tvCodes").child(code).get().await() }.getOrNull() }
            ?: return if (english()) "Could not reach it, check the connection" else "Bağlanılamadı, interneti kontrol et"
        val secret = snap.child("s").getValue(String::class.java)
            ?: return if (english()) "No TV shows this code" else "Bu kodu gösteren bir TV yok"
        val name = snap.child("n").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: "TV"
        val tv = LinkedTv(secret, name)
        save(TvLink.tvs.value.filter { it.id != secret } + tv)
        use(tv)
        return null
    }

    private fun load(): List<LinkedTv> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TVS, "").orEmpty()
            .lines().mapNotNull { line ->
                val (id, name) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                LinkedTv(id, name)
            }

    private fun save(tvs: List<LinkedTv>) {
        TvLink.tvs.value = tvs
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TVS, tvs.joinToString("\n") { "${it.id}\t${it.name.replace('\n', ' ').replace('\t', ' ')}" })
            .apply()
    }

    private fun use(tv: LinkedTv?) {
        val old = TvLink.active.value
        if (old != null && old.id != tv?.id) disconnect()
        if (tv == null || old?.id == tv.id) return
        TvLink.active.value = tv
        VideoCast.connected.value = true
        listenState(tv)
        PlayerHolder.peek()?.onRemotePlayAt = { index -> if (active) castMusicFrom(index, 0L) }
        // The video open on the phone goes to the TV; else the music.
        VideoCast.watching.value?.let { (id, title, thumb) ->
            castVideo(id, title, thumb, VideoCast.phonePosition?.invoke() ?: 0L)
            return
        }
        val player = PlayerHolder.peek() ?: return
        if (player.queueIndex.value >= 0 && player.queue.value.isNotEmpty()) {
            castMusicFrom(player.queueIndex.value, player.playbackPosition.value)
        } else {
            // Nothing playing yet: the next song chosen goes to the TV.
            mode = Mode.MUSIC
            remote.setSongs(emptyList(), 0L)
            player.attachRemote(remote, 0)
        }
    }

    /** Back to the phone: the TV stops, and what it played goes on here. */
    private fun disconnect() {
        send(mapOf("k" to "stop"))
        stateListener?.let { stateRef?.removeEventListener(it) }
        stateListener = null
        when (mode) {
            Mode.MUSIC -> PlayerHolder.peek()?.let { p ->
                val index = p.queueIndex.value
                val position = p.playbackPosition.value
                val wasPlaying = remote.isPlaying
                p.attachRemote(null)
                if (index >= 0 && remote.mediaItemCount > 0) {
                    p.resumeLocally(index, position)
                    if (!wasPlaying) p.pause()
                }
            }
            Mode.VIDEO -> VideoCast.state.value?.let { VideoCast.backAt.value = it.videoId to it.positionMs }
            Mode.NONE -> Unit
        }
        VideoCast.state.value = null
        VideoCast.onPhone.value = null
        VideoCast.connected.value = false
        TvLink.active.value = null
        mode = Mode.NONE
    }

    fun castVideo(videoId: String, title: String, thumbnail: String?, startMs: Long) {
        if (!active) return
        PlayerHolder.peek()?.let { p ->
            if (p.isCasting) p.attachRemote(null)
            p.pause()
        }
        mode = Mode.VIDEO
        VideoCast.onPhone.value = null
        VideoCast.backAt.value = null
        VideoCast.state.value = null
        castingVideo = videoId
        send(mapOf("k" to "video", "id" to videoId, "title" to title, "thumb" to thumbnail, "pos" to startMs))
    }

    private var castingVideo: String? = null

    fun castMusicFrom(index: Int, positionMs: Long) {
        if (!active) return
        val player = PlayerHolder.peek() ?: return
        val songs = player.queue.value.drop(index).take(MUSIC_QUEUE)
        if (songs.isEmpty()) return
        mode = Mode.MUSIC
        castingVideo = null
        VideoCast.state.value = null
        remote.setSongs(songs, positionMs)
        player.attachRemote(remote, index)
        send(
            mapOf(
                "k" to "music",
                "idx" to 0,
                "pos" to positionMs,
                "items" to songs.map { s ->
                    mapOf("id" to s.id, "t" to s.title, "a" to s.artists.joinToString(", ") { it.name }, "th" to (s.thumbnail ?: s.thumbnails.lastOrNull()?.url))
                },
            ),
        )
    }

    fun playPause() {
        val playing = VideoCast.state.value?.playing ?: return
        send(mapOf("k" to if (playing) "pause" else "play"))
        VideoCast.state.value = VideoCast.state.value?.copy(playing = !playing)
    }

    fun seekTo(ms: Long) {
        send(mapOf("k" to "seek", "pos" to ms.coerceAtLeast(0)))
        VideoCast.state.value = VideoCast.state.value?.copy(positionMs = ms.coerceAtLeast(0))
    }

    /** The video comes back to the phone; the TV stays linked (and shows its code). */
    fun stopVideo() {
        val state = VideoCast.state.value
        send(mapOf("k" to "stop"))
        val id = state?.videoId ?: castingVideo ?: return
        VideoCast.backAt.value = id to (state?.positionMs ?: 0L)
        VideoCast.onPhone.value = id
        VideoCast.state.value = null
        castingVideo = null
        mode = Mode.NONE
    }

    private fun listenState(tv: LinkedTv) {
        val ref = db.getReference("tvs").child(tv.id).child("state")
        val listener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (TvLink.active.value?.id != tv.id) return
                val kind = s.child("k").getValue(String::class.java)
                val id = s.child("id").getValue(String::class.java)
                val pos = s.child("pos").getValue(Long::class.java) ?: 0L
                val dur = s.child("dur").getValue(Long::class.java) ?: 0L
                val playing = s.child("playing").getValue(Boolean::class.java) ?: false
                when {
                    mode == Mode.VIDEO && kind == "video" && id != null && id == castingVideo ->
                        VideoCast.state.value = VideoCastState(id, s.child("title").getValue(String::class.java).orEmpty(), pos, dur, playing)
                    mode == Mode.MUSIC && kind == "music" ->
                        remote.update((s.child("idx").getValue(Long::class.java) ?: 0L).toInt(), pos, dur, playing)
                    // The TV was stopped with its remote: the video goes on here.
                    mode == Mode.VIDEO && kind == "idle" && VideoCast.state.value != null -> stopVideo()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TvAndroid.TAG, "TV state closed: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        stateRef = ref
        stateListener = listener
    }

    private fun send(command: Map<String, Any?>) {
        val tv = TvLink.active.value ?: return
        db.getReference("tvs").child(tv.id).child("cmd")
            .setValue(command + mapOf("n" to TvAndroid.randomHex(8), "at" to ServerValue.TIMESTAMP))
            .addOnFailureListener { Log.w(TvAndroid.TAG, "command not sent: ${it.message}") }
    }

    private const val PREFS = "qeyf_tv"
    private const val KEY_TVS = "tvs"
    private const val MUSIC_QUEUE = 50
}

/**
 * The music player's stand-in for a linked TV: the app's player drives it as it drives a Chromecast (play, pause,
 * seek, next), and it shows what the TV reports. The TV plays the same list of songs, from the same place.
 */
@UnstableApi
private class TvRemotePlayer(private val send: (Map<String, Any?>) -> Unit) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var songs: List<SongItem> = emptyList()
    private var index = 0
    private var positionMs = 0L
    private var durationMs = 0L
    private var playing = false

    /** When [positionMs] was true (it runs on from there while playing). */
    private var at = android.os.SystemClock.elapsedRealtime()

    fun setSongs(list: List<SongItem>, startMs: Long) {
        songs = list
        index = 0
        positionMs = startMs
        durationMs = 0L
        playing = list.isNotEmpty()
        at = android.os.SystemClock.elapsedRealtime()
        invalidateState()
    }

    fun update(i: Int, pos: Long, dur: Long, isPlaying: Boolean) {
        index = i.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        positionMs = pos
        durationMs = dur
        playing = isPlaying
        at = android.os.SystemClock.elapsedRealtime()
        invalidateState()
    }

    private fun position(): Long =
        positionMs + if (playing) android.os.SystemClock.elapsedRealtime() - at else 0L

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_STOP,
        ).build()
        if (songs.isEmpty()) {
            return State.Builder()
                .setAvailableCommands(commands)
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .build()
        }
        val playlist = songs.mapIndexed { i, s ->
            MediaItemData.Builder(i)
                .setMediaItem(MediaItem.Builder().setMediaId(s.id).build())
                .setDurationUs(if (i == index && durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                .build()
        }
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs { position() }
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(Player.STATE_READY)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        positionMs = position()
        at = android.os.SystemClock.elapsedRealtime()
        playing = playWhenReady
        send(mapOf("k" to if (playWhenReady) "play" else "pause"))
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs
        if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != index && mediaItemIndex in songs.indices) {
            index = mediaItemIndex
            durationMs = 0L
            send(mapOf("k" to "jump", "idx" to mediaItemIndex))
        } else {
            send(mapOf("k" to "seek", "pos" to target))
        }
        this.positionMs = target
        at = android.os.SystemClock.elapsedRealtime()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playing = false
        send(mapOf("k" to "pause"))
        return Futures.immediateVoidFuture()
    }
}
