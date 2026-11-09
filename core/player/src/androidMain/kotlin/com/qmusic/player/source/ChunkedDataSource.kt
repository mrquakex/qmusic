package com.qmusic.player.source

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * YouTube's file servers hold a request that has no end (`Range: bytes=N-`, which is how a player asks for a file)
 * to about real-time speed: measured, 4 Mbit/s for a 1080p picture and a quarter of a megabit for the sound, so the
 * sound alone kept the video waiting. The same file asked for in bounded pieces comes at the line's full speed
 * (60-80 Mbit/s measured). Music has the same problem: a song started from its beginning is asked for with no
 * range at all, and came at about 260 kbit/s for a 148 kbit/s stream. This source asks for [chunkBytes] at a time and hands the player one unbroken stream.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long,
) : DataSource {
    private var spec: DataSpec? = null
    private var chunked = false
    private var position = 0L
    /** Where the asked-for part ends (exclusive), when known: the asked length, or the file's size. */
    private var end: Long? = null
    private var chunkEnd = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunked = shouldChunk(dataSpec.uri)
        if (!chunked) {
            opened = true
            return upstream.open(dataSpec)
        }
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else null
        openChunk()
        if (end == null) totalLength()?.let { end = it }
        return end?.let { it - dataSpec.position } ?: C.LENGTH_UNSET.toLong()
    }

    private fun openChunk() {
        val stop = minOf(position + chunkBytes, end ?: Long.MAX_VALUE)
        chunkEnd = stop
        val piece = spec!!.buildUpon().setPosition(position).setLength(stop - position).build()
        upstream.open(piece)
        opened = true
    }

    /** The file's whole size, from `Content-Range: bytes a-b/TOTAL`. */
    private fun totalLength(): Long? = upstream.responseHeaders.entries
        .firstOrNull { it.key.equals("content-range", ignoreCase = true) }
        ?.value?.firstOrNull()?.substringAfterLast('/')?.trim()?.toLongOrNull()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            val stop = end
            if (stop != null && position >= stop) return C.RESULT_END_OF_INPUT
            val n = upstream.read(buffer, offset, length)
            if (n != C.RESULT_END_OF_INPUT) {
                position += n
                return n
            }
            // This piece is done. A piece that came short is the end of the file; else the next piece is asked for.
            if (position < chunkEnd) return C.RESULT_END_OF_INPUT
            upstream.close()
            opened = false
            if (stop != null && position >= stop) return C.RESULT_END_OF_INPUT
            openChunk()
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        if (opened) {
            opened = false
            upstream.close()
        }
    }

    class Factory(private val upstream: DataSource.Factory, private val chunkBytes: Long) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource(), chunkBytes)
    }

    companion object {
        /** One piece: asking costs ~0.1 s, and 2 MB is several seconds of video or about two minutes of a song. */
        const val DEFAULT_CHUNK_BYTES: Long = 2L * 1024 * 1024

        /** A file on YouTube's servers given by query (picture or sound); live pieces are small and not split. */
        fun shouldChunk(uri: Uri): Boolean =
            uri.host?.endsWith("googlevideo.com") == true && uri.getQueryParameter("itag") != null
    }
}
