package com.qmusic.app

import android.content.Context
import java.io.File

/**
 * Keeps the signed-in YouTube cookies across app restarts.
 *
 * The file lives in [Context.getNoBackupFilesDir] because the cookies are a live session: they must not
 * end up in cloud backups or device transfers (the manifest keeps allowBackup on for the rest of the app).
 */
class YouTubeSessionStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "yt_session")

    fun load(): String? =
        runCatching { file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()

    fun save(cookies: String?) {
        runCatching {
            if (cookies.isNullOrBlank()) file.delete() else file.writeText(cookies)
        }
    }
}
