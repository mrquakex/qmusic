package com.qmusic.engine.models

import kotlinx.serialization.Serializable

@Serializable
data class SongInfo(
    val videoId: String,
    val title: String? = null,
    val author: String? = null,
    val authorId: String? = null,
    val authorThumbnail: String? = null,
    val description: String? = null,
    val uploadDate: String? = null,
    val subscribers: String? = null,
    val viewCount: Long? = null,
    val likeCount: Long? = null,
)
