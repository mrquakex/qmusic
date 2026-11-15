package com.qmusic.app

import android.util.Log
import com.qmusic.engine.models.CastStreams
import com.qmusic.engine.models.CastTrack
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A small web server on the phone, for the TV: the TV asks the phone for a song or a video, and the phone fetches it
 * from YouTube and passes it on. YouTube then always talks to the phone (which it gave the address to), so it never
 * turns the TV away; and a video's separate picture and sound reach the TV joined by a DASH description, in HD.
 *
 * It listens on the home network only while something is cast, answers only addresses it handed out (long random
 * names, forgotten after a while), and passes byte ranges through so the TV can seek.
 */
object CastProxy {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val random = SecureRandom()

    /** What each handed-out name stands for: a stream to fetch, or a text to give (the DASH description). */
    private sealed interface Entry {
        val at: Long

        data class Stream(val url: String, override val at: Long) : Entry
        data class Text(val mime: String, val body: String, override val at: Long) : Entry
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The phone's address on the home network, or null when it is on none (no Wi-Fi). */
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            // Wi-Fi first (wlan), then any other local network the phone is on.
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    @Synchronized
    private fun ensureRunning(): Int? {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        return runCatching {
            val socket = ServerSocket(0)
            server = socket
            Thread({ accept(socket) }, "qeyf-cast-proxy").apply { isDaemon = true }.start()
            socket.localPort
        }.onFailure { Log.w(TAG, "proxy did not start: ${it.message}") }.getOrNull()
    }

    /** Stops listening (casting is over). */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        entries.clear()
    }

    private fun base(): String? {
        val ip = lanAddress() ?: return null
        val port = ensureRunning() ?: return null
        return "http://$ip:$port"
    }

    private fun name(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun forgetOld() {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        entries.entries.removeIf { it.value.at < cutoff }
    }

    /** An address on the phone for [url] (a song's sound), or null when there is no home network. */
    fun stream(url: String): String? {
        val b = base() ?: return null
        forgetOld()
        val n = name()
        entries[n] = Entry.Stream(url, System.currentTimeMillis())
        return "$b/s/$n"
    }

    /**
     * A DASH description of [streams] on the phone (picture and sound, both through the phone), for the TV's player;
     * null when there is no home network.
     */
    fun dash(streams: CastStreams): String? {
        val b = base() ?: return null
        forgetOld()
        val now = System.currentTimeMillis()
        val v = name().also { entries[it] = Entry.Stream(streams.video.url, now) }
        val a = name().also { entries[it] = Entry.Stream(streams.audio.url, now) }
        val body = manifest(streams, "$b/s/$v", "$b/s/$a")
        val m = name()
        entries[m] = Entry.Text("application/dash+xml", body, now)
        return "$b/t/$m.mpd"
    }

    private fun manifest(s: CastStreams, videoUrl: String, audioUrl: String): String {
        fun segment(t: CastTrack) =
            """<SegmentBase indexRange="${t.indexStart}-${t.indexEnd}"><Initialization range="${t.initStart}-${t.initEnd}"/></SegmentBase>"""
        val v = s.video
        val a = s.audio
        val fps = if (v.fps > 0) """ frameRate="${v.fps}"""" else ""
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" mediaPresentationDuration="PT${s.durationSec.coerceAtLeast(1)}S" minBufferTime="PT2S">
  <Period start="PT0S">
    <AdaptationSet id="0" contentType="video" mimeType="video/mp4" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
      <Representation id="video" codecs="${v.codecs}" bandwidth="${v.bitrate}" width="${v.width}" height="${v.height}"$fps>
        <BaseURL>$videoUrl</BaseURL>
        ${segment(v)}
      </Representation>
    </AdaptationSet>
    <AdaptationSet id="1" contentType="audio" mimeType="audio/mp4" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
      <Representation id="audio" codecs="${a.codecs}" bandwidth="${a.bitrate}" audioSamplingRate="44100">
        <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/>
        <BaseURL>$audioUrl</BaseURL>
        ${segment(a)}
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
"""
    }

    // ------------------------------------------------------------------ serving

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            pool.execute { runCatching { serve(client) }.onFailure { Log.d(TAG, "request ended: ${it.message}") }; runCatching { client.close() } }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1]
        val out = client.getOutputStream()
        // The TV's player (a web page) asks first whether it may read across addresses.
        if (method == "OPTIONS") {
            respond(out, 204, "No Content", cors() + mapOf("Content-Length" to "0"))
            return
        }
        val key = path.removePrefix("/s/").removePrefix("/t/").substringBefore('.').substringBefore('?')
        when (val entry = entries[key]) {
            is Entry.Text -> {
                val bytes = entry.body.toByteArray()
                respond(out, 200, "OK", cors() + mapOf("Content-Type" to entry.mime, "Content-Length" to bytes.size.toString()))
                if (method != "HEAD") out.write(bytes)
                out.flush()
            }
            is Entry.Stream -> pass(entry.url, method, headers["range"], out)
            null -> respond(out, 404, "Not Found", cors() + mapOf("Content-Length" to "0"))
        }
    }

    /** Fetches [url] (the range asked for) from YouTube and passes it on as it comes. */
    private fun pass(url: String, method: String, range: String?, out: OutputStream) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (range != null) setRequestProperty("Range", range)
            if (method == "HEAD") requestMethod = "HEAD"
        }
        try {
            val code = conn.responseCode
            val headers = LinkedHashMap<String, String>()
            headers.putAll(cors())
            headers["Accept-Ranges"] = "bytes"
            conn.contentType?.let { headers["Content-Type"] = it }
            conn.getHeaderField("Content-Length")?.let { headers["Content-Length"] = it }
            conn.getHeaderField("Content-Range")?.let { headers["Content-Range"] = it }
            respond(out, code, conn.responseMessage ?: "OK", headers)
            if (method != "HEAD" && code in 200..299) {
                conn.inputStream.use { it.copyTo(out, 64 * 1024) }
            }
            out.flush()
        } finally {
            conn.disconnect()
        }
    }

    private fun cors() = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers" to "Range, Content-Type",
        "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
        "Connection" to "close",
    )

    private fun respond(out: OutputStream, code: Int, message: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray())
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private const val TAG = "QMCastProxy"

    /** How long a handed-out address works (a long film included). */
    private const val KEEP_MS = 6 * 3600_000L
}
