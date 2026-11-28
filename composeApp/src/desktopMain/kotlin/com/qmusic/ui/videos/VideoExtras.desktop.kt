package com.qmusic.ui.videos

import androidx.compose.runtime.Composable

@Composable
actual fun rememberVideoDownloads(): VideoDownloads? = null

actual suspend fun fetchSponsorSegments(videoId: String, categories: Set<String>): List<SkipSegment> = emptyList()

actual suspend fun fetchVideoDislikes(videoId: String): VideoDislikeStats? = null
