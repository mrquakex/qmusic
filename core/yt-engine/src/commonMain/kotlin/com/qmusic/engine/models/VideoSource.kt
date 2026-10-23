package com.qmusic.engine.models

/**
 * A playable music video. YouTube serves the picture and the sound separately above 360p, so [audioUrl] is
 * set when the two have to be played together; null means [videoUrl] already carries the sound.
 */
data class VideoSource(
    val videoUrl: String,
    val audioUrl: String?,
    val height: Int,
)
