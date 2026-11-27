package com.qmusic.ui.videos

import com.qmusic.engine.video.VideoEntry
import com.qmusic.engine.video.VideoItem

/**
 * Pure responsive breakpoints and feed grid chunking for tablet / wide-screen Video layouts.
 * Phones (< 600 dp) keep their exact single-column layout and bottom bar untouched.
 */
internal const val TABLET_MIN_WIDTH_DP = 600f
internal const val TABLET_EXPANDED_WIDTH_DP = 900f
internal const val TWO_PANE_WATCH_MIN_WIDTH_DP = 720f
internal const val TWO_PANE_WATCH_MIN_HEIGHT_DP = 480f

internal fun isTabletVideoWidth(widthDp: Float): Boolean = widthDp >= TABLET_MIN_WIDTH_DP

internal fun isExpandedTabletWidth(widthDp: Float): Boolean = widthDp >= TABLET_EXPANDED_WIDTH_DP

internal fun isTwoPaneWatch(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= TWO_PANE_WATCH_MIN_WIDTH_DP && heightDp >= TWO_PANE_WATCH_MIN_HEIGHT_DP

/**
 * Number of card columns for a video feed at [widthDp]:
 * - Full feeds (Home, Subscriptions, Search, Channel): 1 on phones, 2 on portrait tablets, 3 on landscape tablets, 4 on desktop/XL.
 * - Compact lists (History, Liked, Watch Later, Playlist): 1 on phones/portrait, 2 on landscape tablets.
 */
internal fun feedColumnCount(widthDp: Float, compact: Boolean): Int = when {
    compact -> if (widthDp >= 900f) 2 else 1
    widthDp >= 1320f -> 4
    widthDp >= 920f -> 3
    widthDp >= TABLET_MIN_WIDTH_DP -> 2
    else -> 1
}

/** One visual row inside a multi-column tablet feed while keeping full-width shelves (Shorts, Channels) intact. */
internal sealed interface FeedGridRow {
    val stableKey: String

    data class VideoChunk(
        val items: List<IndexedVideo>,
        override val stableKey: String,
    ) : FeedGridRow

    data class FullWidth(
        val entry: VideoEntry,
        val index: Int,
        override val stableKey: String,
    ) : FeedGridRow
}

internal data class IndexedVideo(
    val index: Int,
    val item: VideoItem,
)

/**
 * Groups consecutive [VideoEntry.Video] entries into [columns]-wide grid rows while preserving
 * full-width rows for Shorts shelves, channels, and playlists.
 */
internal fun groupFeedForGrid(entries: List<VideoEntry>, columns: Int): List<FeedGridRow> {
    if (entries.isEmpty()) return emptyList()
    val cols = columns.coerceAtLeast(1)
    if (cols == 1) {
        return entries.mapIndexed { idx, entry ->
            val key = (entry.ids().firstOrNull() ?: "e") + "#" + idx
            when (entry) {
                is VideoEntry.Video -> FeedGridRow.VideoChunk(listOf(IndexedVideo(idx, entry.item)), key)
                else -> FeedGridRow.FullWidth(entry, idx, key)
            }
        }
    }
    val rows = ArrayList<FeedGridRow>(entries.size / cols + 2)
    val buffer = ArrayList<IndexedVideo>(cols)

    fun flushBuffer() {
        if (buffer.isEmpty()) return
        val copy = buffer.toList()
        val key = copy.joinToString("|") { "${it.item.id}#${it.index}" }
        rows += FeedGridRow.VideoChunk(copy, "row:$key")
        buffer.clear()
    }

    entries.forEachIndexed { idx, entry ->
        if (entry is VideoEntry.Video) {
            buffer += IndexedVideo(idx, entry.item)
            if (buffer.size == cols) flushBuffer()
        } else {
            flushBuffer()
            val key = (entry.ids().firstOrNull() ?: "e") + "#" + idx
            rows += FeedGridRow.FullWidth(entry, idx, key)
        }
    }
    flushBuffer()
    return rows
}
