package com.qmusic.engine.extractor

import com.qmusic.engine.models.AudioStream
import com.qmusic.engine.models.VideoSource

/**
 * Resolves playable audio streams through a maintained third-party extractor (PipePipe, a NewPipe fork).
 *
 * YouTube gates googlevideo URLs behind a PO token and rotating player ciphers; the extractor keeps
 * up with both, so it is tried before the hand-rolled Innertube path in [com.qmusic.engine.YouTube].
 */
interface ExtractorStreamResolver {
    suspend fun audioStreams(videoId: String): List<AudioStream>

    /** The best music video of [videoId] up to [maxHeight] pixels tall, or null when there is none. */
    suspend fun videoSource(videoId: String, maxHeight: Int): VideoSource? = null
}

/** Null on platforms that ship no extractor. */
expect fun createExtractorStreamResolver(): ExtractorStreamResolver?
