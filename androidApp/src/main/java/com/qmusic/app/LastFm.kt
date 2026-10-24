package com.qmusic.app

import android.content.Context
import com.qmusic.engine.models.SongItem
import com.qmusic.player.PlayerController
import com.qmusic.settings.ScrobbleAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Last.fm scrobbling: "now playing" when a song starts, and a scrobble once half of it (or four minutes) was heard,
 * as Last.fm asks. The key and secret come from local.properties (`lastfm.key`, `lastfm.secret`) at build time; a
 * build without them shows the row as not set up. The password is only sent once, to get a session key.
 */
class LastFm(context: Context) : ScrobbleAccount {
    private val prefs = context.getSharedPreferences("qmusic_lastfm", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val available: Boolean = BuildConfig.LASTFM_KEY.isNotEmpty() && BuildConfig.LASTFM_SECRET.isNotEmpty()

    private val _user = MutableStateFlow(prefs.getString(KEY_USER, null))
    override val user: StateFlow<String?> = _user.asStateFlow()

    private val session: String? get() = prefs.getString(KEY_SESSION, null)

    override suspend fun signIn(user: String, password: String): Result<Unit> =
        runCatching {
            check(available) { "Last.fm is not set up in this build" }
            val answer = JSONObject(call(mapOf("method" to "auth.getMobileSession", "username" to user, "password" to password)))
            val key = answer.optJSONObject("session")?.optString("key")?.takeIf { it.isNotEmpty() }
                ?: error(answer.optString("message"))
            prefs.edit().putString(KEY_SESSION, key).putString(KEY_USER, user).apply()
            _user.value = user
        }

    override fun signOut() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_USER).apply()
        _user.value = null
    }

    /** Follows [player] for as long as the process lives. */
    fun follow(player: PlayerController) {
        if (!available) return
        scope.launch {
            var currentId: String? = null
            var startedAt = 0L
            var heardMs = 0L
            var sent = false
            while (isActive) {
                delay(TICK_MS)
                val track = player.currentTrack.value
                if (track?.id != currentId) {
                    currentId = track?.id
                    startedAt = System.currentTimeMillis() / 1000
                    heardMs = 0
                    sent = false
                    if (track != null) send(track, "track.updateNowPlaying", startedAt)
                }
                if (track == null || !player.isPlaying.value) continue
                heardMs += TICK_MS
                val length = player.duration.value.takeIf { it > 0 } ?: ((track.duration ?: 0) * 1000L)
                if (!sent && length > MIN_LENGTH_MS && heardMs >= minOf(length / 2, MAX_NEEDED_MS)) {
                    sent = true
                    send(track, "track.scrobble", startedAt)
                }
            }
        }
    }

    private suspend fun send(track: SongItem, method: String, startedAt: Long) {
        val sk = session ?: return
        val artist = track.artists.firstOrNull()?.name?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() } ?: return
        val params = buildMap {
            put("method", method)
            put("artist", artist)
            put("track", track.title)
            put("sk", sk)
            track.album?.name?.let { put("album", it) }
            track.duration?.let { put("duration", it.toString()) }
            if (method == "track.scrobble") put("timestamp", startedAt.toString())
        }
        runCatching { call(params) }
    }

    /** A signed POST to the Last.fm API, answered as JSON. */
    private suspend fun call(params: Map<String, String>): String = withContext(Dispatchers.IO) {
        val signed = params + ("api_key" to BuildConfig.LASTFM_KEY)
        val signature = md5(signed.toSortedMap().entries.joinToString("") { it.key + it.value } + BuildConfig.LASTFM_SECRET)
        val body = (signed + ("api_sig" to signature) + ("format" to "json")).entries
            .joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val connection = URL(API).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val API = "https://ws.audioscrobbler.com/2.0/"
        const val KEY_SESSION = "session"
        const val KEY_USER = "user"
        const val TICK_MS = 1_000L
        const val MIN_LENGTH_MS = 30_000L
        const val MAX_NEEDED_MS = 240_000L
    }
}
