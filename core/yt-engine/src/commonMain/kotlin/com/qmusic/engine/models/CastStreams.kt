package com.qmusic.engine.models

/**
 * One stream of a video for a TV (Chromecast): where it is, what it is, and where its header ([initStart]..[initEnd])
 * and its index of pieces ([indexStart]..[indexEnd]) are, which a DASH player needs to play it piece by piece.
 */
data class CastTrack(
    val url: String,
    val mimeType: String,
    val codecs: String,
    val bitrate: Int,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val initStart: Int,
    val initEnd: Int,
    val indexStart: Int,
    val indexEnd: Int,
)

/** What a TV plays for a video: separate picture and sound (joined by a DASH description), and its length. */
data class CastStreams(val video: CastTrack, val audio: CastTrack, val durationSec: Long, val title: String = "")
