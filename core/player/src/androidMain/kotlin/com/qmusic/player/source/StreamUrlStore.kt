package com.qmusic.player.source

import android.content.Context

/**
 * Stream addresses kept on the device while they are valid (YouTube's last a few hours), so a song played or
 * prepared before starts without looking its address up again, even after the app was closed.
 */
class StreamUrlStore(context: Context) {
    private val prefs = context.getSharedPreferences("stream_urls", Context.MODE_PRIVATE)

    /** The address saved under [key], unless it runs out within the next few minutes. */
    fun get(key: String): String? {
        val url = prefs.getString(key, null) ?: return null
        val soon = System.currentTimeMillis() / 1000L + EXPIRY_MARGIN_SEC
        return url.takeUnless { ResolvingDataSource.isUrlExpired(it, soon) }
    }

    fun put(key: String, url: String) {
        prefs.edit().putString(key, url).apply()
        if (prefs.all.size > MAX_ENTRIES) prune()
    }

    /** Forgets every saved address of [songId] (one turned out not to play any more). */
    fun remove(songId: String) {
        val stale = prefs.all.keys.filter { it.endsWith(":$songId") }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
    }

    private fun prune() {
        val now = System.currentTimeMillis() / 1000L
        val expired = prefs.all.filter { (_, value) -> (value as? String)?.let { ResolvingDataSource.isUrlExpired(it, now) } != false }.keys
        prefs.edit().apply {
            expired.forEach(::remove)
            // Still too many valid ones: start over rather than grow without end.
            if (prefs.all.size - expired.size > MAX_ENTRIES) clear()
        }.apply()
    }

    private companion object {
        const val EXPIRY_MARGIN_SEC = 10 * 60L
        const val MAX_ENTRIES = 500
    }
}
