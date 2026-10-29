package com.qmusic

import com.qmusic.settings.SpotifySession
import com.qmusic.ui.auth.DesktopWebAuth
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DesktopSpotifySession(private val baseDir: File) : SpotifySession {
    private val markerFile = File(baseDir, "spotify_signed_in.flag")
    override val available: Boolean = DesktopWebAuth.findChromiumExecutable() != null

    private val _signedIn = MutableStateFlow(markerFile.exists())
    override val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val lock = Mutex()
    @Volatile private var token: String? = null
    @Volatile private var expiresAt: Long = 0L

    override suspend fun token(refresh: Boolean): String? = lock.withLock {
        if (!markerFile.exists()) {
            _signedIn.value = false
            return@withLock null
        }
        val now = System.currentTimeMillis()
        if (!refresh && token != null && now < expiresAt - 120_000L) {
            return@withLock token
        }
        val fresh = DesktopWebAuth.fetchSpotifyWebPlayerToken()
        if (fresh == null) {
            if (refresh) signOut()
            return@withLock null
        }
        token = fresh.first
        expiresAt = fresh.second
        _signedIn.value = true
        fresh.first
    }

    override fun onSignedIn() {
        runCatching { markerFile.writeText("1") }
        token = null
        expiresAt = 0L
        _signedIn.value = true
    }

    override fun signOut() {
        runCatching { markerFile.delete() }
        runCatching { File(baseDir, "auth_profile_spotify").deleteRecursively() }
        token = null
        expiresAt = 0L
        _signedIn.value = false
    }
}
