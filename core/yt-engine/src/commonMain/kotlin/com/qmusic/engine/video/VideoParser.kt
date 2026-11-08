package com.qmusic.engine.video

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Reads YouTube's `browse`, `search` and `next` answers for the video side of the app.
 *
 * The answers are undocumented, change shape from one account and one month to the next, and differ between the home
 * feed, a channel and a search. So nothing here follows one fixed path: a feed is read by looking through the whole
 * answer, in order, for the things a feed is made of (a video card in any of its forms, a Short, a channel, a playlist).
 * A missing field means "leave it out", never a crash.
 */
internal object VideoParser {

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    /** YouTube writes "20 Mn" with a non-breaking space in some answers and a plain one in others: always a plain one here. */
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.replace(' ', ' ')?.replace(' ', ' ')

    private fun JsonElement?.path(vararg keys: String): JsonElement? {
        var current: JsonElement? = this
        for (key in keys) {
            current = when (current) {
                is JsonObject -> current[key]
                is JsonArray -> key.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            }
        }
        return current
    }

    /** Text as YouTube writes it: `simpleText`, `runs`, or a view model's `content`. */
    private fun JsonElement?.text(): String? {
        val element = this ?: return null
        element.str()?.let { return it }
        element.path("simpleText").str()?.let { return it }
        element.path("content").str()?.let { return it }
        element.path("runs").arr()?.let { runs ->
            return runs.joinToString("") { it.path("text").str().orEmpty() }.takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** Pictures come smallest first; the last is the sharpest. Addresses may start with "//". */
    private fun JsonElement?.lastImage(): String? {
        val list = this.arr() ?: return null
        val url = list.mapNotNull { it.path("url").str() }.lastOrNull() ?: return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    /** The first value of [key] found anywhere under this element, in order. */
    private fun JsonElement?.firstString(key: String): String? {
        when (val element = this) {
            is JsonObject -> {
                element[key].str()?.let { return it }
                for ((_, value) in element) value.firstString(key)?.let { return it }
            }

            is JsonArray -> for (value in element) value.firstString(key)?.let { return it }
            else -> Unit
        }
        return null
    }

    /** Every object stored under [key] anywhere under this element, in order. */
    private fun JsonElement?.all(key: String, out: MutableList<JsonObject> = mutableListOf()): MutableList<JsonObject> {
        when (val element = this) {
            is JsonObject -> for ((k, v) in element) {
                if (k == key) v.obj()?.let(out::add)
                v.all(key, out)
            }

            is JsonArray -> for (v in element) v.all(key, out)
            else -> Unit
        }
        return out
    }

    /** What the answer holds that is not the list itself. */
    private val SKIPPED = setOf(
        "responseContext", "frameworkUpdates", "topbar", "header", "trackingParams", "engagementPanels", "playerOverlays",
        "overlay", "currentVideoEndpoint", "menu", "serviceTrackingParams", "onResponseReceivedEndpoints_unused",
    )

    // ------------------------------------------------------------------ feeds

    /** The whole page: its entries, the token for the next page and the filter chips. */
    fun feed(root: JsonElement?): VideoFeed = VideoFeed(entries(root), continuation(root), chips(root))

    fun entries(root: JsonElement?): List<VideoEntry> {
        val found = ArrayList<VideoEntry>()
        collect(root, found)
        // Shorts that come one after the other are one row.
        val grouped = ArrayList<VideoEntry>()
        for (entry in found) {
            val last = grouped.lastOrNull()
            if (entry is VideoEntry.Video && entry.item.isShort) {
                if (last is VideoEntry.Shorts) grouped[grouped.lastIndex] = VideoEntry.Shorts(last.items + entry.item)
                else grouped += VideoEntry.Shorts(listOf(entry.item))
            } else {
                grouped += entry
            }
        }
        return grouped.distinctBy { entryKey(it) }
    }

    private fun entryKey(entry: VideoEntry): String = when (entry) {
        is VideoEntry.Video -> "v:${entry.item.id}"
        is VideoEntry.Channel -> "c:${entry.item.id}"
        is VideoEntry.Playlist -> "p:${entry.item.id}"
        is VideoEntry.Shorts -> "s:${entry.items.joinToString(",") { it.id }}"
    }

    private fun collect(node: JsonElement?, out: MutableList<VideoEntry>) {
        when (node) {
            is JsonObject -> for ((key, value) in node) {
                if (key in SKIPPED) continue
                when (key) {
                    "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer", "videoWithContextRenderer" ->
                        videoRenderer(value.obj())?.let { out += VideoEntry.Video(it) }

                    "lockupViewModel" -> lockup(value.obj())?.let { out += it }
                    "shortsLockupViewModel" -> shortsLockup(value.obj())?.let { out += VideoEntry.Video(it) }
                    "reelItemRenderer" -> reelItem(value.obj())?.let { out += VideoEntry.Video(it) }
                    "channelRenderer", "gridChannelRenderer" -> channelRenderer(value.obj())?.let { out += VideoEntry.Channel(it) }
                    "playlistRenderer", "gridPlaylistRenderer" -> playlistRenderer(value.obj())?.let { out += VideoEntry.Playlist(it) }
                    else -> collect(value, out)
                }
            }

            is JsonArray -> for (child in node) collect(child, out)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ cards

    private fun videoRenderer(r: JsonObject?): VideoItem? {
        r ?: return null
        val id = r["videoId"].str() ?: return null
        val byline = r["longBylineText"] ?: r["ownerText"] ?: r["shortBylineText"]
        val live = r["badges"].arr().orEmpty().any {
            it.path("metadataBadgeRenderer", "style").str()?.contains("LIVE") == true
        } || r["thumbnailOverlays"].arr().orEmpty().any {
            it.path("thumbnailOverlayTimeStatusRenderer", "style").str() == "LIVE"
        }
        return VideoItem(
            id = id,
            title = r["title"].text() ?: return null,
            channelName = byline.path("runs", "0", "text").str() ?: byline.text(),
            channelId = byline.firstString("browseId") ?: r["channelId"].str(),
            channelAvatar = r.path("channelThumbnailSupportedRenderers", "channelThumbnailWithLinkRenderer", "thumbnail", "thumbnails").lastImage()
                ?: r.path("channelThumbnail", "channelThumbnailWithLinkRenderer", "thumbnail", "thumbnails").lastImage(),
            views = r["viewCountText"].text() ?: r["shortViewCountText"].text(),
            published = r["publishedTimeText"].text(),
            duration = r["lengthText"].text()?.takeIf { !live },
            thumbnail = r.path("thumbnail", "thumbnails").lastImage(),
            isLive = live,
        )
    }

    private fun lockup(l: JsonObject?): VideoEntry? {
        l ?: return null
        val id = l["contentId"].str() ?: return null
        val metadata = l.path("metadata", "lockupMetadataViewModel")
        val title = metadata.path("title").text() ?: return null
        when (l["contentType"].str()) {
            "LOCKUP_CONTENT_TYPE_VIDEO" -> Unit
            "LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_PODCAST", "LOCKUP_CONTENT_TYPE_ALBUM" -> {
                val rows = metadataRows(metadata)
                return VideoEntry.Playlist(
                    VideoPlaylist(
                        id = id,
                        title = title,
                        channelName = rows.firstOrNull()?.firstOrNull(),
                        videoCount = l.path("contentImage").all("thumbnailBadgeViewModel").firstOrNull()?.get("text").str(),
                        thumbnail = l.path("contentImage").firstSources(),
                    ),
                )
            }

            else -> return null
        }
        val badge = l.path("contentImage").all("thumbnailBadgeViewModel").firstOrNull()
        val liveBadge = l.path("contentImage").all("thumbnailBadgeViewModel").any {
            it["badgeStyle"].str()?.contains("LIVE") == true || it.path("icon", "sources", "0", "clientResource", "imageName").str() == "LIVE"
        }
        val rows = metadataRows(metadata)
        // With a channel line the rows are [channel][views, time]; inside a channel page there is only [views, time].
        val (channel, views, published) = when {
            rows.size >= 2 -> Triple(rows[0].firstOrNull(), rows[1].getOrNull(0), rows[1].getOrNull(1))
            rows.size == 1 -> Triple(null, rows[0].getOrNull(0), rows[0].getOrNull(1))
            else -> Triple(null, null, null)
        }
        // One channel's avatar, or a stack of them for a video made together (the first is shown).
        val image = metadata.path("image")
        return VideoEntry.Video(
            VideoItem(
                id = id,
                title = title,
                channelName = channel,
                channelId = image.firstString("browseId"),
                channelAvatar = image.all("image").firstNotNullOfOrNull { it["sources"].lastImage() },
                views = views,
                published = published,
                duration = badge?.get("text").str()?.takeIf { !liveBadge },
                thumbnail = l.path("contentImage").firstSources(),
                isLive = liveBadge,
            ),
        )
    }

    /** The lines under a card's title, each as its parts ("channel" / "86 Mn views", "1 year ago"). */
    private fun metadataRows(metadata: JsonElement?): List<List<String>> =
        metadata.path("metadata", "contentMetadataViewModel", "metadataRows").arr().orEmpty().mapNotNull { row ->
            row.path("metadataParts").arr().orEmpty().mapNotNull { it.path("text").text()?.trim() }.takeIf { it.isNotEmpty() }
        }

    /** The sharpest picture under a card's image (thumbnail view models, or a playlist's stacked covers). */
    private fun JsonElement?.firstSources(): String? =
        this.all("image").firstNotNullOfOrNull { it["sources"].lastImage() }

    private fun shortsLockup(s: JsonObject?): VideoItem? {
        s ?: return null
        val id = s.path("onTap", "innertubeCommand", "reelWatchEndpoint", "videoId").str()
            ?: s["entityId"].str()?.removePrefix("shorts-shelf-item-")?.takeIf { it.length == 11 }
            ?: s.firstString("videoId")
            ?: return null
        return VideoItem(
            id = id,
            title = s.path("overlayMetadata", "primaryText").text() ?: s["accessibilityText"].str().orEmpty(),
            views = s.path("overlayMetadata", "secondaryText").text(),
            thumbnail = s.all("image").firstNotNullOfOrNull { it["sources"].lastImage() }
                ?: s.path("onTap", "innertubeCommand", "reelWatchEndpoint", "thumbnail", "thumbnails").lastImage(),
            isShort = true,
        )
    }

    private fun reelItem(r: JsonObject?): VideoItem? {
        r ?: return null
        return VideoItem(
            id = r["videoId"].str() ?: return null,
            title = r["headline"].text().orEmpty(),
            views = r["viewCountText"].text(),
            thumbnail = r.path("thumbnail", "thumbnails").lastImage(),
            isShort = true,
        )
    }

    private fun channelRenderer(c: JsonObject?): VideoChannel? {
        c ?: return null
        val id = c["channelId"].str() ?: return null
        // YouTube puts the @handle in the subscriber field, and the subscribers in the video-count one, in some answers.
        val a = c["subscriberCountText"].text()
        val b = c["videoCountText"].text()
        val handle = listOfNotNull(a, b).firstOrNull { it.startsWith("@") }
        return VideoChannel(
            id = id,
            name = c["title"].text() ?: return null,
            handle = handle,
            subscribers = listOfNotNull(a, b).firstOrNull { !it.startsWith("@") },
            avatar = c.path("thumbnail", "thumbnails").lastImage(),
            description = c["descriptionSnippet"].text(),
        )
    }

    private fun playlistRenderer(p: JsonObject?): VideoPlaylist? {
        p ?: return null
        return VideoPlaylist(
            id = p["playlistId"].str() ?: return null,
            title = p["title"].text() ?: return null,
            channelName = (p["longBylineText"] ?: p["shortBylineText"]).path("runs", "0", "text").str(),
            videoCount = p["videoCount"].str() ?: p["videoCountText"].text(),
            thumbnail = p.path("thumbnails", "0", "thumbnails").lastImage() ?: p.path("thumbnail", "thumbnails").lastImage(),
        )
    }

    // ------------------------------------------------------------------ continuation and chips

    /**
     * The token for the page after this one: the last "load more" entry that sits in a list directly (those inside a
     * comment's replies, or a panel, belong to something else).
     */
    fun continuation(root: JsonElement?): String? {
        var token: String? = null
        fun visit(node: JsonElement?) {
            when (node) {
                is JsonObject -> for ((key, value) in node) {
                    if (key in SKIPPED) continue
                    if (key == "continuationItemRenderer") {
                        value.path("continuationEndpoint", "continuationCommand", "token").str()?.let { token = it }
                    } else if (key == "commentRepliesRenderer") {
                        continue
                    } else {
                        visit(value)
                    }
                }

                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return token
    }

    fun chips(root: JsonElement?): List<VideoChip> {
        val bar = root.all("feedFilterChipBarRenderer").firstOrNull() ?: return emptyList()
        val chips = bar.all("chipCloudChipRenderer").mapNotNull { chip ->
            VideoChip(
                text = chip["text"].text() ?: return@mapNotNull null,
                token = chip.path("navigationEndpoint", "continuationCommand", "token").str() ?: return@mapNotNull null,
                selected = chip["isSelected"].let { (it as? JsonPrimitive)?.booleanOrNull } == true,
            )
        }
        if (chips.isNotEmpty()) return chips
        return bar.all("chipViewModel").mapNotNull { chip ->
            VideoChip(
                text = chip["text"].str() ?: return@mapNotNull null,
                token = chip.firstString("token") ?: return@mapNotNull null,
                selected = chip["selectionState"].str()?.contains("SELECTED") == true,
            )
        }
    }

    // ------------------------------------------------------------------ the watch page

    fun details(root: JsonObject, videoId: String): VideoDetails? {
        val results = root.path("contents", "twoColumnWatchNextResults", "results", "results", "contents").arr().orEmpty()
        val primary = results.firstNotNullOfOrNull { it.path("videoPrimaryInfoRenderer").obj() }
        val secondary = results.firstNotNullOfOrNull { it.path("videoSecondaryInfoRenderer").obj() }
        val title = primary?.get("title").text() ?: return null
        val owner = secondary.path("owner", "videoOwnerRenderer")
        val subscribeButton = secondary.path("subscribeButton", "subscribeButtonRenderer")
        val viewCount = primary.path("viewCount", "videoViewCountRenderer")
        val likeButton = primary.path("videoActions", "menuRenderer", "topLevelButtons").arr().orEmpty()
            .firstNotNullOfOrNull { it.path("segmentedLikeDislikeButtonViewModel", "likeButtonViewModel").obj() }
        val commentsSection = results.firstNotNullOfOrNull { section ->
            section.path("itemSectionRenderer").obj()?.takeIf { it["sectionIdentifier"].str() == "comment-item-section" }
        }
        val secondaryResults = root.path("contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults")
        return VideoDetails(
            id = videoId,
            title = title,
            views = viewCount.path("viewCount").text() ?: viewCount.path("shortViewCount").text(),
            date = primary.path("dateText").text(),
            description = secondary.path("attributedDescription").text()
                ?: secondary.path("description").text(),
            channelId = owner.path("navigationEndpoint", "browseEndpoint", "browseId").str() ?: subscribeButton.path("channelId").str(),
            channelName = owner.path("title").text(),
            channelAvatar = owner.path("thumbnail", "thumbnails").lastImage(),
            subscribers = owner.path("subscriberCountText").text(),
            subscribed = subscribeButton.path("subscribed").let { (it as? JsonPrimitive)?.booleanOrNull },
            likes = likeButton.firstString("title"),
            related = feed(secondaryResults),
            commentsToken = commentsSection?.let(::continuation),
            isLive = viewCount.path("isLive").let { (it as? JsonPrimitive)?.booleanOrNull } == true,
        )
    }

    // ------------------------------------------------------------------ comments

    /** The comments of a page of the comment feed: each thread's text lives in the answer's entity updates. */
    fun comments(root: JsonObject): VideoComments {
        val payloads = HashMap<String, JsonObject>()
        root.path("frameworkUpdates", "entityBatchUpdate", "mutations").arr().orEmpty().forEach { mutation ->
            mutation.path("payload", "commentEntityPayload").obj()?.let { payload ->
                payload["key"].str()?.let { payloads[it] = payload }
            }
        }
        val comments = root.all("commentThreadRenderer").mapNotNull { thread ->
            val view = thread.path("commentViewModel", "commentViewModel").obj() ?: return@mapNotNull null
            val payload = view["commentKey"].str()?.let(payloads::get) ?: return@mapNotNull null
            val properties = payload["properties"].obj()
            VideoComment(
                id = properties?.get("commentId").str() ?: view["commentId"].str() ?: return@mapNotNull null,
                author = payload.path("author", "displayName").str().orEmpty(),
                avatar = payload.path("author", "avatarThumbnailUrl").str(),
                text = properties?.get("content").text().orEmpty(),
                published = properties?.get("publishedTime").str(),
                likes = payload.path("toolbar", "likeCountNotliked").str()?.takeIf { it.isNotBlank() && it != "0" },
                replies = payload.path("toolbar", "replyCount").str()?.takeIf { it.isNotBlank() && it != "0" },
                isPinned = thread["commentViewModel"].firstString("pinnedText") != null,
            )
        }
        val total = root.all("commentsHeaderRenderer").firstOrNull()?.get("countText").text()
        return VideoComments(total = total, comments = comments, continuation = continuation(root))
    }

    // ------------------------------------------------------------------ channels

    fun channel(root: JsonObject, requestedId: String): VideoChannelPage? {
        val meta = root.path("metadata", "channelMetadataRenderer").obj()
        val header = root.path("header", "pageHeaderRenderer", "content", "pageHeaderViewModel").obj()
        val name = header.path("title", "dynamicTextViewModel", "text").text()
            ?: root.path("header", "pageHeaderRenderer", "pageTitle").str()
            ?: root.path("header", "c4TabbedHeaderRenderer", "title").str()
            ?: meta?.get("title").str()
            ?: return null
        val rows = metadataRows(header)
        // The rows are [@handle][subscribers, videos]; older headers carry the subscribers on their own.
        val handle = rows.flatten().firstOrNull { it.startsWith("@") }
        val others = rows.flatten().filter { !it.startsWith("@") }
        val subscribeButton = header.path("actions", "flexibleActionsViewModel", "actionsRows", "0", "actions", "0", "buttonViewModel")
        val tabs = root.path("contents", "twoColumnBrowseResultsRenderer", "tabs").arr().orEmpty().mapNotNull { tab ->
            val renderer = tab.path("tabRenderer").obj() ?: return@mapNotNull null
            val params = renderer.path("endpoint", "browseEndpoint", "params").str() ?: return@mapNotNull null
            VideoChannelTab(renderer["title"].str().orEmpty(), params)
        }
        return VideoChannelPage(
            id = meta?.get("externalId").str() ?: requestedId,
            name = name,
            handle = handle,
            subscribers = others.firstOrNull(),
            videoCount = others.getOrNull(1),
            avatar = header.path("image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image", "sources").lastImage()
                ?: meta.path("avatar", "thumbnails").lastImage(),
            banner = header.path("banner", "imageBannerViewModel", "image", "sources").lastImage(),
            description = meta?.get("description").str()?.takeIf { it.isNotBlank() }
                ?: header.path("description", "descriptionPreviewViewModel", "description").text(),
            // "Subscribed" shows as a different button title; the label is language-dependent, so it is read as a flag only when YouTube gives one.
            subscribed = subscribeButton.path("isSubscribed").let { (it as? JsonPrimitive)?.booleanOrNull },
            tabs = tabs,
            videos = feed(root.path("contents")),
        )
    }
}
