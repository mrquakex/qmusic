package com.qmusic.ui.chat

import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** An image loader that plays animated GIFs and WebPs (the shared one shows only their first frame). */
@Composable
expect fun rememberAnimatedImageLoader(): ImageLoader

/** What opens under the message box: emoji, GIFs or stickers, one tab each. */
@Composable
internal fun MediaKeyboard(
    controller: ChatController,
    s: ChatStrings,
    onEmoji: (String) -> Unit,
    onGif: (GifItem, sticker: Boolean) -> Unit,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    ExpandablePanel(collapsed = 360.dp, onDismiss = onDismiss, modifier = modifier) {
        // Emoji, GIF and stickers as one segmented control (as on WhatsApp).
        Row(
            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp).clip(RoundedCornerShape(50))
                .border(1.dp, TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(50)),
        ) {
            listOf(s.emojiTab, s.gifTab, s.stickerTab).forEachIndexed { i, label ->
                val on = tab == i
                Box(
                    Modifier.width(76.dp).height(38.dp).background(if (on) Ink.copy(alpha = 0.12f) else Color.Transparent).clickable { tab = i },
                    contentAlignment = Alignment.Center,
                ) {
                    val tint = if (on) TextPrimary else TextSecondary
                    when (i) {
                        0 -> Icon(QMusicIcons.Mood, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
                        1 -> Text("GIF", color = tint, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        else -> StickerGlyph(tint, 21.dp)
                    }
                }
            }
        }
        when (tab) {
            0 -> EmojiPanel(onEmoji = onEmoji, modifier = Modifier.weight(1f))
            else -> GifGrid(controller, s, sticker = tab == 2, onPick = { onGif(it, tab == 2) })
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun GifGrid(controller: ChatController, s: ChatStrings, sticker: Boolean, onPick: (GifItem) -> Unit) {
    var query by remember(sticker) { mutableStateOf("") }
    var items by remember(sticker) { mutableStateOf<List<GifItem>?>(null) }
    var problem by remember(sticker) { mutableStateOf<String?>(null) }
    val loader = rememberAnimatedImageLoader()
    val grid = rememberLazyGridState()
    val english = ChatMusic.english
    // The person's own stickers (made, favourite, last sent), kept with their account.
    LaunchedEffect(sticker) { if (sticker) Stickers.start() }
    val recent by Stickers.recent.collectAsState()
    val favorites by Stickers.favorites.collectAsState()
    val mine by Stickers.mine.collectAsState()
    var making by remember { mutableStateOf(StickerMaking.IDLE) }
    val makeSticker = rememberStickerMaker(onStep = { making = it }) { made ->
        if (made != null) {
            Stickers.addMine(made)
            controller.reportInfo(if (english) "Your sticker is ready: tap it to send" else "Çıkartman hazır: göndermek için dokun")
        } else {
            controller.reportError(if (english) "The sticker could not be made" else "Çıkartma yapılamadı")
        }
    }
    val pick: (GifItem) -> Unit = { item ->
        if (sticker) Stickers.used(item)
        onPick(item)
    }
    // Typing waits a moment before it asks, so each letter is not a search.
    LaunchedEffect(sticker) {
        snapshotFlow { query.trim() }.debounce(350).distinctUntilChanged().collect { q ->
            items = null
            problem = null
            controller.searchGifs(sticker, q)
                .onSuccess { items = it }
                .onFailure { problem = if (it.message == "gif-key") s.gifsUnavailable else s.gifsFailed }
            grid.scrollToItem(0)
        }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)).background(DarkSurfaceVariant).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(QMusicIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(if (sticker) s.searchStickers else s.searchGifs, color = TextMuted, fontSize = 15.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(60) },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val list = items
        when {
            problem != null -> Text(problem.orEmpty(), color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
            list == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(if (sticker) 4 else 3),
                state = grid,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (sticker && query.isBlank()) {
                    // Making one from a photo, first (as on WhatsApp).
                    item(key = "make") {
                        Column(
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .clickable(enabled = making == StickerMaking.IDLE) { makeSticker() }.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (making != StickerMaking.IDLE) {
                                CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                            } else {
                                Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(QeyfGreen), contentAlignment = Alignment.Center) {
                                    Icon(QMusicIcons.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                when (making) {
                                    StickerMaking.CUTTING -> if (english) "Cutting out" else "Kesiliyor"
                                    StickerMaking.UPLOADING -> if (english) "Saving" else "Kaydediliyor"
                                    StickerMaking.IDLE -> if (english) "Make a sticker" else "Çıkartma oluştur"
                                },
                                color = QeyfGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                lineHeight = 13.sp,
                            )
                        }
                    }
                    items(mine, key = { "m" + it.url }) { item -> StickerCell(item, loader, true, pick) }
                    if (favorites.isNotEmpty()) {
                        item(key = "favHead", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { GridHeading(if (english) "Favourites" else "Favoriler") }
                        items(favorites, key = { "f" + it.url }) { item -> StickerCell(item, loader, true, pick) }
                    }
                    if (recent.isNotEmpty()) {
                        item(key = "recentHead", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { GridHeading(if (english) "Recent" else "Son kullanılanlar") }
                        items(recent, key = { "r" + it.url }) { item -> StickerCell(item, loader, true, pick) }
                    }
                    item(key = "popHead", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { GridHeading(if (english) "Popular" else "Popüler") }
                }
                items(list, key = { "s" + it.id }) { item -> StickerCell(item, loader, sticker, pick) }
            }
        }
        // KLIPY asks apps to name it where its results show.
        Text(s.poweredByKlipy, color = TextMuted, fontSize = 10.sp, modifier = Modifier.align(Alignment.End).padding(end = 12.dp, bottom = 4.dp))
    }
}

/** A GIF or a sticker in a message: it moves; a sticker stands without a bubble. */
@Composable
internal fun GifView(gif: ChatGif) {
    val loader = rememberAnimatedImageLoader()
    val ratio = if (gif.width > 0 && gif.height > 0) (gif.width.toFloat() / gif.height).coerceIn(0.5f, 2f) else 1f
    AsyncImage(
        model = gif.url,
        imageLoader = loader,
        contentDescription = null,
        contentScale = if (gif.sticker) ContentScale.Fit else ContentScale.Crop,
        modifier = if (gif.sticker) {
            Modifier.size(150.dp)
        } else {
            Modifier.padding(4.dp).widthIn(max = 240.dp).fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(14.dp))
        },
    )
}

private val QeyfGreen = Color(0xFF1DAA61)

@Composable
private fun GridHeading(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
}

/** A GIF or a sticker of the panel: a tap sends it; a sticker held goes in or out of the favourites (a star on it). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StickerCell(item: GifItem, loader: ImageLoader, sticker: Boolean, onPick: (GifItem) -> Unit) {
    val favorites by Stickers.favorites.collectAsState()
    val favorite = sticker && favorites.any { it.url == item.url }
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
            .background(if (sticker) Color.Transparent else DarkSurfaceVariant)
            .combinedClickable(
                onClick = { onPick(item) },
                onLongClick = if (sticker) ({ Stickers.toggleFavorite(item) }) else null,
            ),
    ) {
        AsyncImage(
            model = item.preview,
            imageLoader = loader,
            contentDescription = null,
            contentScale = if (sticker) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (favorite) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(2.dp).size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(QeyfGreen),
                contentAlignment = Alignment.Center,
            ) { Icon(QMusicIcons.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp)) }
        }
    }
}
