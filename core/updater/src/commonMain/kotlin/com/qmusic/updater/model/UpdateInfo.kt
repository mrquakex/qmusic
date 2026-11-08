package com.qmusic.updater.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long = 0L,
    val changelog: String = "",
    val downloadUrl: String = "",
    /** GitHub's API address of the same file: used when the normal download address is down (a 5xx). */
    val fallbackUrl: String = "",
    val isMandatory: Boolean = false,
)
