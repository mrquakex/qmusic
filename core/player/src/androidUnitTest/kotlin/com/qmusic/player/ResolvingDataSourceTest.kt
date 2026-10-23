package com.qmusic.player

import androidx.media3.common.C
import com.qmusic.player.source.ResolvingDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvingDataSourceTest {

    @Test
    fun testChunkBoundaryClampingAtStart() {
        val position = 0L
        val requestedLength = C.LENGTH_UNSET.toLong()

        val length = ResolvingDataSource.calculateChunkLength(position, requestedLength)
        assertEquals(ResolvingDataSource.CHUNK_SIZE_BYTES, length)
    }

    @Test
    fun testChunkBoundaryClampingMidChunk() {
        // Position at 4 MiB
        val position = 4L * 1024L * 1024L
        val requestedLength = C.LENGTH_UNSET.toLong()

        val length = ResolvingDataSource.calculateChunkLength(position, requestedLength)

        // Remaining until 5 MiB boundary is 1 MiB
        val expectedLength = 1L * 1024L * 1024L
        assertEquals(expectedLength, length)
    }

    @Test
    fun testChunkBoundaryClampingAcrossChunks() {
        // Position at 7 MiB (Chunk 2, between 5 MiB and 10 MiB)
        val position = 7L * 1024L * 1024L
        val requestedLength = C.LENGTH_UNSET.toLong()

        val length = ResolvingDataSource.calculateChunkLength(position, requestedLength)

        // Remaining until 10 MiB boundary is 3 MiB
        val expectedLength = 3L * 1024L * 1024L
        assertEquals(expectedLength, length)
    }

    @Test
    fun testChunkBoundarySmallerThanRemaining() {
        // Position at 1 MiB, requested 512 KiB (smaller than remaining 4 MiB)
        val position = 1L * 1024L * 1024L
        val requestedLength = 512L * 1024L

        val length = ResolvingDataSource.calculateChunkLength(position, requestedLength)
        assertEquals(requestedLength, length)
    }

    @Test
    fun testUrlExpiration() {
        val nowSec = 1700000000L

        // Expired URL
        val expiredUrl = "https://rr1---sn.googlevideo.com/videoplayback?expire=${nowSec - 100}&id=123"
        assertTrue(ResolvingDataSource.isUrlExpired(expiredUrl, nowSec))

        // Valid URL (future)
        val validUrl = "https://rr1---sn.googlevideo.com/videoplayback?expire=${nowSec + 3600}&id=123"
        assertFalse(ResolvingDataSource.isUrlExpired(validUrl, nowSec))

        // URL without expire param
        val normalUrl = "https://example.com/audio.m4a"
        assertFalse(ResolvingDataSource.isUrlExpired(normalUrl, nowSec))
    }
}
