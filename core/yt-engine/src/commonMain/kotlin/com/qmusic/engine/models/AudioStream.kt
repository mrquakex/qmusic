package com.qmusic.engine.models

import kotlinx.serialization.Serializable

@Serializable
data class AudioStream(
    val itag: Int,
    val url: String,
    val bitrate: Int,
    val mimeType: String,
    val contentLength: Long? = null,
    val isDrm: Boolean = false,
)
