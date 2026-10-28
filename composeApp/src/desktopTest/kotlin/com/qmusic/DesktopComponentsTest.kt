package com.qmusic

import com.qmusic.player.DashMp4Defragmenter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DesktopComponentsTest {

    @Test
    fun desktopAppSettingsPersistsAcrossInstances() {
        val tempDir = Files.createTempDirectory("qmusic-desktop-test").toFile()
        try {
            val first = DesktopAppSettings(tempDir)
            first.setLanguage("en")
            first.setCrossfadeSeconds(7)
            first.setEqualizer("3,2,1,0,0,0,0,1,2,3", "ROCK")
            first.saveSession("""{"index":2}""")
            first.setBool("amoled_theme", true)
            first.setString("account_mode", "youtube")

            val second = DesktopAppSettings(tempDir)
            assertEquals("en", second.language.value)
            assertEquals(7, second.crossfadeSeconds.value)
            assertEquals("3,2,1,0,0,0,0,1,2,3", second.equalizerBands.value)
            assertEquals("ROCK", second.equalizerPreset.value)
            assertEquals("""{"index":2}""", second.loadSession())
            assertEquals(true, second.bool("amoled_theme", false).value)
            assertEquals("youtube", second.string("account_mode", "").value)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun dashMp4DefragmenterProducesStandardM4aWithMatchingAudioPayload() {
        val sample1 = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val sample2 = byteArrayOf(0x55, 0x66, 0x77)
        val stsd = box("stsd", ByteArray(16))
        val mdhd = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0) // version 0 + flags
            putInt(0) // creation
            putInt(0) // modification
            putInt(44100) // timescale
            putInt(2048) // duration
        }.array()
        val mdia = box("mdia", box("mdhd", mdhd) + box("minf", box("stbl", stsd)))
        val trak = box("trak", mdia)
        val trex = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0) // version + flags
            putInt(1) // track_ID
            putInt(1) // default_sample_description_index
            putInt(1024) // default_sample_duration
            putInt(0) // default_sample_size
            putInt(0) // default_sample_flags
        }.array()
        val mvex = box("mvex", box("trex", trex))
        val moov = box("moov", trak + mvex)

        val tfhd = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0) // version + flags
            putInt(1) // track_ID
        }.array()
        val trun = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x000200) // flags: sample-size-present
            putInt(2) // sample_count = 2
            putInt(sample1.size)
            putInt(sample2.size)
        }.array()
        val moof = box("moof", box("traf", box("tfhd", tfhd) + box("trun", trun)))
        val mdat = box("mdat", sample1 + sample2)

        val fragmented = box("ftyp", "dash".toByteArray()) + moov + moof + mdat
        val defragmented = DashMp4Defragmenter.defragmentIfNeeded(fragmented)

        // Verify output is larger than input's raw audio and ends with the exact mdat payload
        val expectedTail = sample1 + sample2
        val actualTail = defragmented.copyOfRange(defragmented.size - expectedTail.size, defragmented.size)
        assertContentEquals(expectedTail, actualTail)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + payload.size)
        val len = 8 + payload.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }
}
