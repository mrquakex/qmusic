package com.qmusic.engine.models

object ITAG {
    /** Opus ~50 kbps */
    const val AUDIO_OPUS_LOWEST: Int = 249

    /** Opus ~70 kbps */
    const val AUDIO_OPUS_LOW: Int = 250

    /** Opus ~160 kbps */
    const val AUDIO_OPUS_MEDIUM: Int = 251

    /** Opus ~256 kbps (Premium) */
    const val AUDIO_OPUS_HIGH: Int = 774

    /** AAC ~48 kbps */
    const val AUDIO_AAC_LOWEST: Int = 139

    /** AAC ~128 kbps */
    const val AUDIO_AAC_MEDIUM: Int = 140

    /** AAC ~256 kbps (Premium) */
    const val AUDIO_AAC_HIGH: Int = 141

    /** H.264 360p video */
    const val VIDEO_360P: Int = 134

    /** H.264 720p video */
    const val VIDEO_720P: Int = 136

    /** H.264 1080p video */
    const val VIDEO_1080P: Int = 137

    /** Muxed 360p video + audio */
    const val MUXED_360P: Int = 18

    val AUDIO: Set<Int> = setOf(
        AUDIO_OPUS_LOWEST,
        AUDIO_OPUS_LOW,
        AUDIO_OPUS_MEDIUM,
        AUDIO_OPUS_HIGH,
        AUDIO_AAC_LOWEST,
        AUDIO_AAC_MEDIUM,
        AUDIO_AAC_HIGH,
    )

    val VIDEO: Set<Int> = setOf(VIDEO_1080P, VIDEO_720P, VIDEO_360P)

    fun isAudio(itag: Int): Boolean = itag in AUDIO
}
