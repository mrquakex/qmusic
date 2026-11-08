package com.qmusic.engine.extractor

import com.qmusic.engine.models.AudioStream
import com.qmusic.engine.models.PreviewFrames
import com.qmusic.engine.video.VideoQuality
import com.qmusic.engine.models.VideoSource
import dev.maxrave.pipepipe.extractor.NewPipe
import dev.maxrave.pipepipe.extractor.downloader.CancellableCall
import dev.maxrave.pipepipe.extractor.downloader.Downloader
import dev.maxrave.pipepipe.extractor.downloader.Request
import dev.maxrave.pipepipe.extractor.downloader.Response
import dev.maxrave.pipepipe.extractor.exceptions.ReCaptchaException
import dev.maxrave.pipepipe.extractor.localization.Localization
import dev.maxrave.pipepipe.extractor.stream.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Request as OkRequest
import okhttp3.Response as OkResponse

actual fun createExtractorStreamResolver(): ExtractorStreamResolver? = PipePipeStreamResolver

private object PipePipeStreamResolver : ExtractorStreamResolver {
    private val initialised by lazy {
        NewPipe.init(OkHttpExtractorDownloader(), Localization("en", "US"))
        true
    }

    /** Extracting is the slow part (a few requests), and audio and picture both need it: share the result briefly. */
    private val infoCache = LinkedHashMap<String, Pair<Long, StreamInfo>>()

    override fun invalidate(videoId: String) {
        synchronized(infoCache) { infoCache.remove(videoId) }
    }

    private fun info(videoId: String): StreamInfo {
        val now = System.currentTimeMillis()
        synchronized(infoCache) { infoCache[videoId] }?.let { (at, cached) -> if (now - at < INFO_TTL_MS) return cached }
        val fresh = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
        synchronized(infoCache) {
            infoCache.remove(videoId)
            infoCache[videoId] = now to fresh
            // Insertion order: the first key is the oldest.
            while (infoCache.size > INFO_CACHE_SIZE) infoCache.remove(infoCache.keys.first())
        }
        return fresh
    }

    override suspend fun videoSource(videoId: String, maxHeight: Int): VideoSource? =
        withContext(Dispatchers.IO) {
            check(initialised)
            pickVideoSource(info(videoId), maxHeight)
        }

    override suspend fun videoQualities(videoId: String): List<VideoQuality> =
        withContext(Dispatchers.IO) {
            check(initialised)
            qualitiesOf(info(videoId))
        }

    override suspend fun previewFrames(videoId: String): PreviewFrames? =
        withContext(Dispatchers.IO) {
            check(initialised)
            // The sets differ in picture size; the largest up to PREVIEW_MAX_WIDTH is sharp enough for the bubble.
            val sets = info(videoId).previewFrames.orEmpty().filter { it.urls.isNotEmpty() && it.frameWidth > 0 }
            val set = sets.filter { it.frameWidth <= PREVIEW_MAX_WIDTH }.maxByOrNull { it.frameWidth }
                ?: sets.minByOrNull { it.frameWidth }
                ?: return@withContext null
            PreviewFrames(
                sheetUrls = set.urls,
                frameWidth = set.frameWidth,
                frameHeight = set.frameHeight,
                framesPerRow = set.framesPerPageX,
                framesPerColumn = set.framesPerPageY,
                durationPerFrameMs = set.durationPerFrame,
                totalFrames = set.totalCount,
            )
        }

    override suspend fun audioStreams(videoId: String): List<AudioStream> =
        withContext(Dispatchers.IO) {
            check(initialised)
            info(videoId).audioStreams.mapNotNull { stream ->
                if (!stream.isUrl) return@mapNotNull null
                val bitrate = if (stream.bitrate > 0) stream.bitrate else stream.averageBitrate * 1000
                AudioStream(
                    itag = stream.itag,
                    url = stream.content,
                    bitrate = bitrate,
                    mimeType = stream.format?.mimeType?.let { "audio/${it.substringAfter('/')}" } ?: "audio/mp4",
                )
            }
        }
}

private const val INFO_TTL_MS = 4 * 60 * 1000L
private const val PREVIEW_MAX_WIDTH = 320
private const val INFO_CACHE_SIZE = 8

/**
 * Each height the video is offered at, best first. Above 360p picture and sound are separate streams (H.264 where
 * there is one, since every phone decodes it in hardware; VP9 only for heights that have nothing else); the streams
 * that carry their own sound are the way to play at 360p and below.
 */
private fun qualitiesOf(info: StreamInfo): List<VideoQuality> {
    val live = info.hlsUrl
    if (!live.isNullOrBlank() && info.streamType.name.contains("LIVE")) {
        return listOf(VideoQuality(height = 0, fps = 0, videoUrl = live, audioUrl = null, isLive = true))
    }
    val audio = info.audioStreams
        .filter { it.isUrl }
        .sortedWith(compareByDescending<dev.maxrave.pipepipe.extractor.stream.AudioStream> { it.format?.mimeType == "audio/mp4" }
            .thenByDescending { it.averageBitrate })
        .firstOrNull()
    val pictures = info.videoOnlyStreams.filter { it.isUrl && it.height > 0 }
    val separate = if (audio == null) emptyList() else pictures
        .groupBy { it.height }
        .map { (_, streams) ->
            val best = streams.sortedWith(
                compareByDescending<dev.maxrave.pipepipe.extractor.stream.VideoStream> { it.codec?.startsWith("avc1") == true }
                    .thenByDescending { it.fps }
                    .thenByDescending { it.bitrate },
            ).first()
            VideoQuality(best.height, best.fps, best.content, audio.content)
        }
    val withSound = info.videoStreams
        .filter { it.isUrl && it.height > 0 }
        .map { VideoQuality(it.height, it.fps, it.content, null) }
    // Heights offered both ways keep the separate (sharper, steadier) streams; the rest come from the muxed ones.
    val heights = separate.mapTo(HashSet()) { it.height }
    return (separate + withSound.filter { it.height !in heights }).sortedByDescending { it.height }
}

private fun pickVideoSource(info: StreamInfo, maxHeight: Int): VideoSource? {
    // Picture and sound come separately above 360p: H.264 plays on every phone's hardware decoder, so prefer it.
    val pictureOnly = info.videoOnlyStreams
        .filter { it.isUrl && it.height in 1..maxHeight }
        .sortedWith(compareByDescending<dev.maxrave.pipepipe.extractor.stream.VideoStream> { it.codec?.startsWith("avc1") == true }
            .thenByDescending { it.height }
            .thenByDescending { it.bitrate })
        .firstOrNull()
    if (pictureOnly != null) {
        val audio = info.audioStreams
            .filter { it.isUrl }
            .sortedWith(compareByDescending<dev.maxrave.pipepipe.extractor.stream.AudioStream> { it.format?.mimeType == "audio/mp4" }
                .thenByDescending { it.averageBitrate })
            .firstOrNull()
        if (audio != null) return VideoSource(pictureOnly.content, audio.content, pictureOnly.height)
    }
    val withSound = info.videoStreams
        .filter { it.isUrl && it.height in 1..maxHeight }
        .maxByOrNull { it.height }
        ?: return null
    return VideoSource(withSound.content, null, withSound.height)
}

private class OkHttpExtractorDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun Request.toOkHttp(): OkRequest {
        val builder = OkRequest.Builder().url(url())
        headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        val body = dataToSend()
        val method = httpMethod()
        builder.method(method, body?.toRequestBody() ?: if (method == "POST") ByteArray(0).toRequestBody() else null)
        return builder.build()
    }

    private fun OkResponse.toExtractor(): Response {
        val bytes = body?.bytes() ?: ByteArray(0)
        return Response(
            code,
            message,
            headers.toMultimap(),
            String(bytes, Charsets.UTF_8),
            bytes,
            request.url.toString(),
        )
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val call = client.newBuilder().followRedirects(request.followRedirects()).build().newCall(request.toOkHttp())
        call.execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return response.toExtractor()
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun executeAsync(request: Request, callback: Downloader.AsyncCallback): CancellableCall {
        val call = client.newBuilder().followRedirects(request.followRedirects()).build().newCall(request.toOkHttp())
        val cancellable = CancellableCall(call)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    try {
                        callback.onError(e)
                    } finally {
                        cancellable.setFinished()
                    }
                }

                // setFinished() must come last: the extractor stops waiting on it and reads what the callback stored.
                override fun onResponse(call: Call, response: OkResponse) {
                    try {
                        response.use {
                            try {
                                callback.onSuccess(it.toExtractor())
                            } catch (e: Exception) {
                                callback.onError(e)
                            }
                        }
                    } finally {
                        cancellable.setFinished()
                    }
                }
            },
        )
        return cancellable
    }
}
