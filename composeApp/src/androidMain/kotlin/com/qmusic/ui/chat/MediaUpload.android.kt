package com.qmusic.ui.chat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Qeyf's media server (server/qmusic-push/media.js): photos, voice messages, videos and files of the messages go up
 * there with the person's Firebase sign-in, and come back from a link nobody can guess.
 */
internal object MediaUpload {
    const val BASE = "https://qeyf.159.195.147.140.sslip.io"
    const val MAX_BYTES = 100L * 1024 * 1024

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }

    /** Sends [size] bytes from [open] to the conversation [chatId]; [onProgress] hears how many went. Returns the link. */
    fun upload(token: String, chatId: String, ext: String, mime: String, size: Long, open: () -> InputStream, onProgress: (Long) -> Unit): String {
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        sent += n
                        if (sent - lastReport > 128 * 1024 || sent == size) {
                            lastReport = sent
                            onProgress(sent)
                        }
                    }
                }
            }
        }
        val request = Request.Builder()
            .url("$BASE/media/upload?chat=$chatId&ext=$ext")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "upload ${response.code}" }
            return org.json.JSONObject(text).getString("url")
        }
    }

    /** The bytes at [url], or null when they cannot be had. */
    fun download(url: String): ByteArray? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()
}
