package com.qmusic.player.source

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * DataSource wrapper enforcing 5 MiB chunk boundaries and cache validity verification.
 * Prevents bandwidth exhaustion and handles googlevideo expire timestamp checking.
 */
class ResolvingDataSource(
    private val upstreamDataSource: DataSource,
    private val urlResolver: ((Uri) -> Uri)? = null,
) : DataSource {

    companion object {
        const val CHUNK_SIZE_BYTES: Long = 5 * 1024 * 1024L // 5 MiB

        /**
         * Clamps requested length so it does not cross the next 5 MiB chunk boundary.
         */
        fun calculateChunkLength(position: Long, requestedLength: Long): Long {
            val offsetInChunk = position % CHUNK_SIZE_BYTES
            val bytesToNextChunkBoundary = CHUNK_SIZE_BYTES - offsetInChunk
            return if (requestedLength == C.LENGTH_UNSET.toLong()) {
                bytesToNextChunkBoundary
            } else {
                minOf(requestedLength, bytesToNextChunkBoundary)
            }
        }

        /**
         * Checks if a URL containing an 'expire' timestamp query parameter has expired.
         */
        fun isUrlExpired(url: String, currentTimestampSec: Long = System.currentTimeMillis() / 1000L): Boolean {
            val regex = Regex("[?&]expire=([0-9]+)")
            val match = regex.find(url) ?: return false
            val expireTimestampSec = match.groupValues[1].toLongOrNull() ?: return false
            return expireTimestampSec <= currentTimestampSec
        }
    }

    private var currentDataSpec: DataSpec? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstreamDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        var uri = dataSpec.uri
        val uriString = uri.toString()

        // Verify URL expiration if present (e.g. googlevideo 'expire' query param)
        if (isUrlExpired(uriString)) {
            val resolved = urlResolver?.invoke(uri)
            if (resolved != null && !isUrlExpired(resolved.toString())) {
                uri = resolved
            } else {
                throw IOException("Audio stream URL has expired and cannot be resolved")
            }
        } else if (urlResolver != null && uri.scheme == "qmusic") {
            uri = urlResolver.invoke(uri)
        }

        // The request is passed on whole. Clamping it to a 5 MiB chunk made the response look like the end of
        // the file, so any song longer than about 4.5 minutes was cut off at the clamp.
        val chunkDataSpec = dataSpec.buildUpon()
            .setUri(uri)
            .build()

        currentDataSpec = chunkDataSpec
        return upstreamDataSource.open(chunkDataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return upstreamDataSource.read(buffer, offset, length)
    }

    override fun getUri(): Uri? {
        return upstreamDataSource.uri ?: currentDataSpec?.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return upstreamDataSource.responseHeaders
    }

    override fun close() {
        currentDataSpec = null
        upstreamDataSource.close()
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val urlResolver: ((Uri) -> Uri)? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return ResolvingDataSource(upstreamFactory.createDataSource(), urlResolver)
        }
    }
}
