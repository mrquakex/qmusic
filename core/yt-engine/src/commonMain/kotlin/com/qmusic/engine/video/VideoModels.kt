package com.qmusic.engine.video

/** One video as the feeds list it: what a card shows, and what is needed to open it. */
data class VideoItem(
    val id: String,
    val title: String,
    val channelName: String? = null,
    val channelId: String? = null,
    val channelAvatar: String? = null,
    /** "86 Mn görüntüleme", worded as YouTube words it. */
    val views: String? = null,
    /** "1 yıl önce". */
    val published: String? = null,
    /** "3:54"; null for a live stream. */
    val duration: String? = null,
    val thumbnail: String? = null,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
)

data class VideoChannel(
    val id: String,
    val name: String,
    val handle: String? = null,
    val subscribers: String? = null,
    val avatar: String? = null,
    val description: String? = null,
)

data class VideoPlaylist(
    val id: String,
    val title: String,
    val channelName: String? = null,
    val videoCount: String? = null,
    val thumbnail: String? = null,
)

/** What a feed page is made of, in the order YouTube lists it. */
sealed interface VideoEntry {
    data class Video(val item: VideoItem) : VideoEntry
    data class Channel(val item: VideoChannel) : VideoEntry
    data class Playlist(val item: VideoPlaylist) : VideoEntry

    /** Shorts that come one after the other, shown together as a row. */
    data class Shorts(val items: List<VideoItem>) : VideoEntry
}

/** A filter at the top of a feed ("Tümü", "Müzik", ...); [token] is what asks YouTube for the filtered feed. */
data class VideoChip(val text: String, val token: String, val selected: Boolean = false)

/** One page of a feed; [continuation] asks for the next one. */
data class VideoFeed(
    val entries: List<VideoEntry> = emptyList(),
    val continuation: String? = null,
    val chips: List<VideoChip> = emptyList(),
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/** The watch page: the video's own details, the channel, and what comes after it. */
data class VideoDetails(
    val id: String,
    val title: String,
    val views: String? = null,
    val date: String? = null,
    val description: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelAvatar: String? = null,
    val subscribers: String? = null,
    val subscribed: Boolean? = null,
    val likes: String? = null,
    val related: VideoFeed = VideoFeed(),
    /** Asks for the comments; null when they are off. */
    val commentsToken: String? = null,
    val isLive: Boolean = false,
)

/** A channel page: its header and the first page of its videos. */
data class VideoChannelPage(
    val id: String,
    val name: String,
    val handle: String? = null,
    val subscribers: String? = null,
    val videoCount: String? = null,
    val avatar: String? = null,
    val banner: String? = null,
    val description: String? = null,
    val subscribed: Boolean? = null,
    val tabs: List<VideoChannelTab> = emptyList(),
    val videos: VideoFeed = VideoFeed(),
)

/** A tab of a channel page; [params] is what opens it. */
data class VideoChannelTab(val title: String, val params: String)

/** One comment. */
data class VideoComment(
    val id: String,
    val author: String,
    val avatar: String?,
    val text: String,
    val published: String?,
    val likes: String?,
    val replies: String? = null,
    val isPinned: Boolean = false,
    /** Asks for the replies; null when there are none. */
    val repliesToken: String? = null,
)

data class VideoComments(
    val total: String? = null,
    val comments: List<VideoComment> = emptyList(),
    val continuation: String? = null,
    /** What posting a comment on this video needs (null when comments are off or nobody is signed in). */
    val createToken: String? = null,
)

/** One subtitle track a video offers. */
data class VideoCaption(
    val language: String,
    val label: String,
    val url: String,
    val auto: Boolean,
    val mimeType: String,
)

/**
 * A way to play a video: [audioUrl] is set when picture and sound are separate streams to play together; [isLive] marks
 * a live stream's adaptive playlist (HLS), whose quality the player picks itself ([height] is 0 then).
 */
data class VideoQuality(
    val height: Int,
    val fps: Int,
    val videoUrl: String,
    val audioUrl: String?,
    val isLive: Boolean = false,
) {
    val label: String get() = if (isLive) "Auto" else if (fps > 30) "${height}p$fps" else "${height}p"
}
