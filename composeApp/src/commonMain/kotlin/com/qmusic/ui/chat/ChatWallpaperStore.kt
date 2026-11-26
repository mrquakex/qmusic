package com.qmusic.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.BorderHover
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import com.qmusic.ui.theme.lightTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Resolved wallpaper to paint behind a conversation's message list. */
internal sealed interface ResolvedWallpaper {
    data object None : ResolvedWallpaper
    data class Solid(val index: Int) : ResolvedWallpaper
    data class Photo(val path: String) : ResolvedWallpaper
}

/**
 * Local-only custom chat wallpaper store (global custom photo under key "*", and per-chat overrides under chatId).
 * Encodings:
 *   - "photo:<absoluteFilePath>" -> gallery photo stored locally under app filesDir
 *   - "color:<index>"            -> preset solid color index in ChatWallpapers
 *   - "none"                     -> explicitly clear wallpaper for this chat even when global is set
 */
object ChatWallpaperStore {
    const val GLOBAL_KEY = "*"
    private const val PREFIX_PHOTO = "photo:"
    private const val PREFIX_COLOR = "color:"
    private const val VALUE_NONE = "none"

    private val _entries = MutableStateFlow<Map<String, String>>(emptyMap())
    val entries: StateFlow<Map<String, String>> = _entries.asStateFlow()

    /** Platform hook wired on Android startup to persist key->value entries (null removes). */
    var onSave: ((Map<String, String>) -> Unit)? = null

    /** Platform hook wired on Android startup to write picked JPEG bytes to app storage and return its path. */
    var savePhotoFile: ((scopeKey: String, jpeg: ByteArray) -> String?)? = null

    /** Platform hook wired on Android startup to delete an old local wallpaper file. */
    var deletePhotoFile: ((path: String) -> Unit)? = null

    fun hydrate(initial: Map<String, String>) {
        _entries.value = initial.filterValues { it.isNotBlank() }
    }

    fun globalPhotoPath(map: Map<String, String> = _entries.value): String? =
        map[GLOBAL_KEY]?.takeIf { it.startsWith(PREFIX_PHOTO) }?.removePrefix(PREFIX_PHOTO)?.takeIf { it.isNotBlank() }

    fun setGlobalPhoto(jpeg: ByteArray): Boolean {
        val oldPath = globalPhotoPath()
        val path = savePhotoFile?.invoke("global", jpeg) ?: return false
        if (oldPath != null && oldPath != path) deletePhotoFile?.invoke(oldPath)
        update(GLOBAL_KEY, PREFIX_PHOTO + path)
        return true
    }

    fun clearGlobalPhoto() {
        val oldPath = globalPhotoPath()
        if (oldPath != null) deletePhotoFile?.invoke(oldPath)
        update(GLOBAL_KEY, null)
    }

    fun setChatPhoto(chatId: String, jpeg: ByteArray): Boolean {
        val oldPath = chatPhotoPath(chatId)
        val safeKey = "chat_" + chatId.filter { it.isLetterOrDigit() || it == '_' }.take(48)
        val path = savePhotoFile?.invoke(safeKey, jpeg) ?: return false
        if (oldPath != null && oldPath != path) deletePhotoFile?.invoke(oldPath)
        update(chatId, PREFIX_PHOTO + path)
        return true
    }

    fun setChatColor(chatId: String, index: Int) {
        val oldPath = chatPhotoPath(chatId)
        if (oldPath != null) deletePhotoFile?.invoke(oldPath)
        update(chatId, if (index <= 0) VALUE_NONE else PREFIX_COLOR + index)
    }

    fun resetChatOverride(chatId: String) {
        val oldPath = chatPhotoPath(chatId)
        if (oldPath != null) deletePhotoFile?.invoke(oldPath)
        update(chatId, null)
    }

    fun chatPhotoPath(chatId: String, map: Map<String, String> = _entries.value): String? =
        map[chatId]?.takeIf { it.startsWith(PREFIX_PHOTO) }?.removePrefix(PREFIX_PHOTO)?.takeIf { it.isNotBlank() }

    /** Resolves effective wallpaper for [chatId], falling back to global custom photo, then global preset color. */
    internal fun resolve(chatId: String?, globalColorIndex: Int, map: Map<String, String> = _entries.value): ResolvedWallpaper {
        val chatRaw = chatId?.let { map[it] }
        if (chatRaw != null) {
            return parseEntry(chatRaw)
        }
        val globalPhoto = globalPhotoPath(map)
        if (globalPhoto != null) return ResolvedWallpaper.Photo(globalPhoto)
        return if (globalColorIndex > 0) ResolvedWallpaper.Solid(globalColorIndex) else ResolvedWallpaper.None
    }

    private fun parseEntry(raw: String): ResolvedWallpaper = when {
        raw == VALUE_NONE -> ResolvedWallpaper.None
        raw.startsWith(PREFIX_PHOTO) -> {
            val p = raw.removePrefix(PREFIX_PHOTO)
            if (p.isNotBlank()) ResolvedWallpaper.Photo(p) else ResolvedWallpaper.None
        }
        raw.startsWith(PREFIX_COLOR) -> {
            val idx = raw.removePrefix(PREFIX_COLOR).toIntOrNull() ?: 0
            if (idx > 0) ResolvedWallpaper.Solid(idx) else ResolvedWallpaper.None
        }
        else -> ResolvedWallpaper.None
    }

    private fun update(key: String, value: String?) {
        val next = _entries.value.toMutableMap()
        if (value == null) next.remove(key) else next[key] = value
        _entries.value = next
        onSave?.invoke(next)
    }
}

/** Paints either a solid color or a gallery photo with a subtle readability scrim behind messages. */
@Composable
internal fun ChatWallpaperBackdrop(
    chatId: String?,
    globalColorIndex: Int,
    modifier: Modifier = Modifier,
) {
    val overrides by ChatWallpaperStore.entries.collectAsState()
    when (val resolved = ChatWallpaperStore.resolve(chatId, globalColorIndex, overrides)) {
        ResolvedWallpaper.None -> Unit
        is ResolvedWallpaper.Solid -> {
            val color = ChatWallpapers.getOrNull(resolved.index)
            if (color != null) Box(modifier.fillMaxSize().background(color))
        }
        is ResolvedWallpaper.Photo -> {
            Box(modifier.fillMaxSize()) {
                AsyncImage(
                    model = resolved.path,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(BgRoot.copy(alpha = if (lightTheme) 0.44f else 0.52f)),
                )
            }
        }
    }
}

/** Per-chat wallpaper selector dialog launched from Conversation top-bar menu. */
@Composable
internal fun ChatWallpaperDialog(
    chatId: String,
    globalColorIndex: Int,
    s: ChatStrings,
    onDismiss: () -> Unit,
) {
    val overrides by ChatWallpaperStore.entries.collectAsState()
    val hasOverride = overrides.containsKey(chatId)
    val customPhoto = ChatWallpaperStore.chatPhotoPath(chatId, overrides)
    val resolved = ChatWallpaperStore.resolve(chatId, globalColorIndex, overrides)
    val selectedColorIdx = when (resolved) {
        ResolvedWallpaper.None -> 0
        is ResolvedWallpaper.Solid -> resolved.index
        is ResolvedWallpaper.Photo -> -1
    }
    val pickPhoto = rememberImagePicker { photos ->
        photos.firstOrNull()?.let { ChatWallpaperStore.setChatPhoto(chatId, it.jpeg) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(s.chatWallpaper, color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.chatWallpaperNote, color = TextSecondary, fontSize = 13.sp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (ChatWallpaperStore.savePhotoFile != null) {
                        Box(
                            Modifier
                                .size(width = 56.dp, height = 84.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkSurface)
                                .border(2.dp, if (customPhoto != null) Ink else BorderHover, RoundedCornerShape(12.dp))
                                .clickable { pickPhoto() },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (customPhoto != null) {
                                AsyncImage(model = customPhoto, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(QMusicIcons.Samples, contentDescription = null, tint = Ink, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.height(4.dp))
                                    Text(s.galleryShort, color = TextSecondary, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    ChatWallpapers.forEachIndexed { i, colour ->
                        Box(
                            Modifier
                                .size(width = 56.dp, height = 84.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colour ?: BgRoot)
                                .border(2.dp, if (i == selectedColorIdx) Ink else BorderSubtle, RoundedCornerShape(12.dp))
                                .clickable { ChatWallpaperStore.setChatColor(chatId, i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (colour == null) Text(s.wallpaperNone, color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
                if (hasOverride) {
                    Text(
                        s.resetToDefaultWallpaper,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                ChatWallpaperStore.resetChatOverride(chatId)
                                onDismiss()
                            }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.closeLabel, color = Ink) } },
    )
}

/** Compact monochrome emoji picker for reacting with any emoji (filtered to RTDB rule length <= 8). */
@Composable
internal fun ReactionEmojiPickerDialog(
    currentEmoji: String?,
    s: ChatStrings,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var category by remember { mutableStateOf(0) }
    val categories = remember {
        ChatEmojiCategories.map { (icon, list) -> icon to list.filter { it.isNotBlank() && it.length <= 8 } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(s.moreReactions, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    categories.forEachIndexed { i, (icon, _) ->
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (i == category) DarkSurface else Color.Transparent)
                                .border(1.dp, if (i == category) BorderHover else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable { category = i },
                            contentAlignment = Alignment.Center,
                        ) { Text(icon, fontSize = 20.sp) }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(40.dp),
                    modifier = Modifier.fillMaxWidth().height(250.dp),
                    contentPadding = PaddingValues(2.dp),
                ) {
                    items(categories[category].second) { emoji ->
                        val chosen = currentEmoji == emoji
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (chosen) Ink.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable {
                                    onPick(emoji)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, fontSize = 24.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.cancel, color = TextSecondary) } },
    )
}
