package com.qmusic.player

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a YouTube DASH fragmented MP4 (`ftyp dash` with `moof`/`mdat` fragments)
 * into a standard non-fragmented M4A (`ftyp M4A ` + `moov` with `stts`/`stsc`/`stsz`/`stco` + single `mdat`)
 * so native desktop players (JavaFX MediaPlayer / Windows Media Foundation) can play and seek it seamlessly.
 */
object DashMp4Defragmenter {

    fun defragmentIfNeeded(input: ByteArray): ByteArray {
        return runCatching { defragment(input) }.getOrNull() ?: input
    }

    private fun defragment(input: ByteArray): ByteArray? {
        val topBoxes = parseBoxes(input, 0, input.size)
        val moovBox = topBoxes.firstOrNull { it.type == "moov" } ?: return null
        val moofCount = topBoxes.count { it.type == "moof" }
        if (moofCount == 0) return null // Already a non-fragmented MP4

        var timescale = 44100
        var defaultSampleDuration = 1024
        var defaultSampleSize = 0
        var stsdBytes: ByteArray? = null

        val moovChildren = parseBoxes(input, moovBox.payloadOffset, moovBox.endOffset)
        for (child in moovChildren) {
            when (child.type) {
                "mvex" -> {
                    val mvexChildren = parseBoxes(input, child.payloadOffset, child.endOffset)
                    for (mc in mvexChildren) {
                        if (mc.type == "trex" && mc.payloadLength >= 24) {
                            val buf = ByteBuffer.wrap(input, mc.payloadOffset, mc.payloadLength).order(ByteOrder.BIG_ENDIAN)
                            buf.int // version + flags
                            buf.int // track_ID
                            buf.int // default_sample_description_index
                            val dDur = buf.int
                            val dSize = buf.int
                            if (dDur > 0) defaultSampleDuration = dDur
                            if (dSize > 0) defaultSampleSize = dSize
                        }
                    }
                }
                "trak" -> {
                    val trakChildren = parseBoxes(input, child.payloadOffset, child.endOffset)
                    val mdia = trakChildren.firstOrNull { it.type == "mdia" } ?: continue
                    val mdiaChildren = parseBoxes(input, mdia.payloadOffset, mdia.endOffset)
                    for (mdc in mdiaChildren) {
                        if (mdc.type == "mdhd" && mdc.payloadLength >= 16) {
                            val buf = ByteBuffer.wrap(input, mdc.payloadOffset, mdc.payloadLength).order(ByteOrder.BIG_ENDIAN)
                            val version = (buf.int ushr 24) and 0xFF
                            if (version == 1 && mdc.payloadLength >= 24) {
                                buf.long // creation
                                buf.long // modification
                                val ts = buf.int
                                if (ts > 0) timescale = ts
                            } else {
                                buf.int // creation
                                buf.int // modification
                                val ts = buf.int
                                if (ts > 0) timescale = ts
                            }
                        } else if (mdc.type == "minf") {
                            val minfChildren = parseBoxes(input, mdc.payloadOffset, mdc.endOffset)
                            val stbl = minfChildren.firstOrNull { it.type == "stbl" } ?: continue
                            val stblChildren = parseBoxes(input, stbl.payloadOffset, stbl.endOffset)
                            val stsd = stblChildren.firstOrNull { it.type == "stsd" } ?: continue
                            stsdBytes = input.copyOfRange(stsd.offset, stsd.endOffset)
                        }
                    }
                }
            }
        }

        val stsd = stsdBytes ?: return null
        val sampleSizes = IntList()
        val sampleDurations = IntList()
        val rawAudio = ByteArrayOutputStream(input.size)

        for (box in topBoxes) {
            when (box.type) {
                "moof" -> {
                    val moofChildren = parseBoxes(input, box.payloadOffset, box.endOffset)
                    for (traf in moofChildren) {
                        if (traf.type != "traf") continue
                        var trafDefaultDuration = defaultSampleDuration
                        var trafDefaultSize = defaultSampleSize
                        val trafChildren = parseBoxes(input, traf.payloadOffset, traf.endOffset)
                        for (tc in trafChildren) {
                            if (tc.type == "tfhd" && tc.payloadLength >= 8) {
                                val buf = ByteBuffer.wrap(input, tc.payloadOffset, tc.payloadLength).order(ByteOrder.BIG_ENDIAN)
                                val flags = buf.int and 0x00FFFFFF
                                buf.int // track_ID
                                if ((flags and 0x000001) != 0 && buf.remaining() >= 8) buf.long // base_data_offset
                                if ((flags and 0x000002) != 0 && buf.remaining() >= 4) buf.int // sample_description_index
                                if ((flags and 0x000008) != 0 && buf.remaining() >= 4) {
                                    val d = buf.int
                                    if (d > 0) trafDefaultDuration = d
                                }
                                if ((flags and 0x000010) != 0 && buf.remaining() >= 4) {
                                    val s = buf.int
                                    if (s > 0) trafDefaultSize = s
                                }
                            }
                        }
                        for (tc in trafChildren) {
                            if (tc.type == "trun" && tc.payloadLength >= 8) {
                                val buf = ByteBuffer.wrap(input, tc.payloadOffset, tc.payloadLength).order(ByteOrder.BIG_ENDIAN)
                                val flags = buf.int and 0x00FFFFFF
                                val count = buf.int
                                if ((flags and 0x000001) != 0 && buf.remaining() >= 4) buf.int // data_offset
                                if ((flags and 0x000004) != 0 && buf.remaining() >= 4) buf.int // first_sample_flags
                                val hasDuration = (flags and 0x000100) != 0
                                val hasSize = (flags and 0x000200) != 0
                                val hasFlags = (flags and 0x000400) != 0
                                val hasCto = (flags and 0x000800) != 0
                                for (i in 0 until count) {
                                    val dur = if (hasDuration && buf.remaining() >= 4) buf.int else trafDefaultDuration
                                    val sz = if (hasSize && buf.remaining() >= 4) buf.int else trafDefaultSize
                                    if (hasFlags && buf.remaining() >= 4) buf.int
                                    if (hasCto && buf.remaining() >= 4) buf.int
                                    if (sz > 0) {
                                        sampleSizes.add(sz)
                                        sampleDurations.add(if (dur > 0) dur else defaultSampleDuration)
                                    }
                                }
                            }
                        }
                    }
                }
                "mdat" -> {
                    rawAudio.write(input, box.payloadOffset, box.payloadLength)
                }
            }
        }

        val sampleCount = sampleSizes.size
        val mdatPayload = rawAudio.toByteArray()
        if (sampleCount == 0 || mdatPayload.isEmpty()) return null

        var totalDuration = 0L
        for (i in 0 until sampleDurations.size) {
            totalDuration += sampleDurations[i].toLong()
        }

        val ftyp = box("ftyp") {
            writeAscii("M4A ")
            writeInt(0)
            writeAscii("M4A ")
            writeAscii("mp42")
            writeAscii("isom")
            writeInt(0)
        }

        // Build moov with placeholder stco offset (0), then patch the exact mdat payload offset
        fun buildMoov(chunkOffset: Int): ByteArray {
            val mvhd = fullBox("mvhd", 0, 0) {
                writeInt(0) // creation_time
                writeInt(0) // modification_time
                writeInt(timescale)
                writeInt(totalDuration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                writeInt(0x00010000) // rate 1.0
                writeShort(0x0100) // volume 1.0
                writeBytes(ByteArray(10)) // reserved
                writeMatrix()
                writeBytes(ByteArray(24)) // pre_defined
                writeInt(2) // next_track_ID
            }
            val tkhd = fullBox("tkhd", 0, 0x000007) {
                writeInt(0) // creation_time
                writeInt(0) // modification_time
                writeInt(1) // track_ID
                writeInt(0) // reserved
                writeInt(totalDuration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                writeBytes(ByteArray(8)) // reserved
                writeShort(0) // layer
                writeShort(0) // alternate_group
                writeShort(0x0100) // volume 1.0
                writeShort(0) // reserved
                writeMatrix()
                writeInt(0) // width
                writeInt(0) // height
            }
            val mdhd = fullBox("mdhd", 0, 0) {
                writeInt(0)
                writeInt(0)
                writeInt(timescale)
                writeInt(totalDuration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                writeShort(0x55C4) // "und"
                writeShort(0)
            }
            val hdlr = fullBox("hdlr", 0, 0) {
                writeInt(0)
                writeAscii("soun")
                writeBytes(ByteArray(12))
                writeAscii("SoundHandler\u0000")
            }
            val smhd = fullBox("smhd", 0, 0) {
                writeShort(0)
                writeShort(0)
            }
            val dinf = box("dinf") {
                writeBytes(
                    fullBox("dref", 0, 0) {
                        writeInt(1)
                        writeBytes(fullBox("url ", 0, 1) {})
                    },
                )
            }
            val stts = fullBox("stts", 0, 0) {
                // Run-length encode sample durations
                val runs = ArrayList<IntArray>()
                for (i in 0 until sampleDurations.size) {
                    val d = sampleDurations[i]
                    val last = runs.lastOrNull()
                    if (last != null && last[1] == d) {
                        last[0]++
                    } else {
                        runs.add(intArrayOf(1, d))
                    }
                }
                writeInt(runs.size)
                for (r in runs) {
                    writeInt(r[0])
                    writeInt(r[1])
                }
            }
            val stsc = fullBox("stsc", 0, 0) {
                writeInt(1)
                writeInt(1) // first_chunk
                writeInt(sampleCount) // samples_per_chunk
                writeInt(1) // sample_description_index
            }
            val stsz = fullBox("stsz", 0, 0) {
                writeInt(0) // variable sample size
                writeInt(sampleCount)
                for (i in 0 until sampleCount) {
                    writeInt(sampleSizes[i])
                }
            }
            val stco = fullBox("stco", 0, 0) {
                writeInt(1)
                writeInt(chunkOffset)
            }
            val stbl = box("stbl") {
                writeBytes(stsd)
                writeBytes(stts)
                writeBytes(stsc)
                writeBytes(stsz)
                writeBytes(stco)
            }
            val minf = box("minf") {
                writeBytes(smhd)
                writeBytes(dinf)
                writeBytes(stbl)
            }
            val mdia = box("mdia") {
                writeBytes(mdhd)
                writeBytes(hdlr)
                writeBytes(minf)
            }
            val trak = box("trak") {
                writeBytes(tkhd)
                writeBytes(mdia)
            }
            return box("moov") {
                writeBytes(mvhd)
                writeBytes(trak)
            }
        }

        val dummyMoov = buildMoov(0)
        val mdatDataOffset = ftyp.size + dummyMoov.size + 8
        val finalMoov = buildMoov(mdatDataOffset)

        val out = ByteBuffer.allocate(ftyp.size + finalMoov.size + 8 + mdatPayload.size).order(ByteOrder.BIG_ENDIAN)
        out.put(ftyp)
        out.put(finalMoov)
        out.putInt(8 + mdatPayload.size)
        out.put("mdat".toByteArray(Charsets.US_ASCII))
        out.put(mdatPayload)
        return out.array()
    }

    private data class Mp4Box(
        val type: String,
        val offset: Int,
        val payloadOffset: Int,
        val endOffset: Int,
    ) {
        val payloadLength: Int get() = endOffset - payloadOffset
    }

    private fun parseBoxes(data: ByteArray, start: Int, end: Int): List<Mp4Box> {
        val list = ArrayList<Mp4Box>()
        var pos = start
        while (pos + 8 <= end) {
            val buf = ByteBuffer.wrap(data, pos, end - pos).order(ByteOrder.BIG_ENDIAN)
            val size32 = buf.int.toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            buf.get(typeBytes)
            val type = String(typeBytes, Charsets.ISO_8859_1)
            val headerSize: Int
            val boxSize: Int
            if (size32 == 1L) {
                if (pos + 16 > end) break
                headerSize = 16
                boxSize = buf.long.toInt()
            } else if (size32 == 0L) {
                headerSize = 8
                boxSize = end - pos
            } else {
                headerSize = 8
                boxSize = size32.toInt()
            }
            if (boxSize < headerSize || pos + boxSize > end) break
            list.add(Mp4Box(type, pos, pos + headerSize, pos + boxSize))
            pos += boxSize
        }
        return list
    }

    private class BoxWriter {
        private val out = ByteArrayOutputStream()
        fun writeInt(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun writeShort(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun writeAscii(s: String) {
            out.write(s.toByteArray(Charsets.ISO_8859_1))
        }
        fun writeBytes(b: ByteArray) {
            out.write(b)
        }
        fun writeMatrix() {
            writeInt(0x00010000); writeInt(0); writeInt(0)
            writeInt(0); writeInt(0x00010000); writeInt(0)
            writeInt(0); writeInt(0); writeInt(0x40000000)
        }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun box(type: String, block: BoxWriter.() -> Unit): ByteArray {
        val payload = BoxWriter().apply(block).toByteArray()
        return BoxWriter().apply {
            writeInt(8 + payload.size)
            writeAscii(type)
            writeBytes(payload)
        }.toByteArray()
    }

    private fun fullBox(type: String, version: Int, flags: Int, block: BoxWriter.() -> Unit): ByteArray {
        return box(type) {
            writeInt(((version and 0xFF) shl 24) or (flags and 0x00FFFFFF))
            block()
        }
    }

    private class IntList(initialCapacity: Int = 1024) {
        private var data = IntArray(initialCapacity)
        var size: Int = 0
            private set

        fun add(element: Int) {
            if (size == data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[size++] = element
        }

        operator fun get(index: Int): Int = data[index]
    }
}
