package com.qmusic.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import androidx.core.graphics.drawable.IconCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * The round pictures of the notifications: the person's (or group's) photo, fetched once and kept; their initial on a
 * colour of their own when there is none or it cannot be had in time.
 */
object NotificationAvatars {
    private const val SIZE = 192
    private val cache = LruCache<String, Bitmap>(48)
    private val palette = intArrayOf(0xFF5E81AC.toInt(), 0xFFB48EAD.toInt(), 0xFFD08770.toInt(), 0xFFA3BE8C.toInt(), 0xFF88C0D0.toInt(), 0xFFEBCB8B.toInt(), 0xFFBF616A.toInt(), 0xFF8FBCBB.toInt())

    /** Blocks for up to a few seconds to fetch [photo]: call it off the main thread (a push arrives on one already). */
    fun icon(key: String, name: String, photo: String?): IconCompat = IconCompat.createWithBitmap(bitmap(key, name, photo))

    fun bitmap(key: String, name: String, photo: String?): Bitmap {
        val id = photo?.takeIf { it.isNotBlank() } ?: "letter:$key:$name"
        cache.get(id)?.let { return it }
        val made = photo?.takeIf { it.startsWith("http") }?.let { download(it) }?.let(::circle) ?: letter(key, name)
        cache.put(id, made)
        return made
    }

    private fun download(url: String): Bitmap? = runCatching {
        // Google photos come small (=s96-c): a sharper size is asked for.
        val address = url.replace("=s96-c", "=s256-c")
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun circle(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, paint)
        return out
    }

    private fun letter(key: String, name: String): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette[(key.hashCode() and 0x7fffffff) % palette.size] }
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = SIZE * 0.44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val y = SIZE / 2f - (text.descent() + text.ascent()) / 2
        canvas.drawText(initial, SIZE / 2f, y, text)
        return out
    }
}
