package com.qmusic.updater.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long = 0L,
    val changelog: String = "",
    val downloadUrl: String = "",
    val isMandatory: Boolean = false,
)
