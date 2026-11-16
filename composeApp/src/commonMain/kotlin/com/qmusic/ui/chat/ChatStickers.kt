package com.qmusic.ui.chat

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The person's stickers, kept with their account (chatPrefs/{uid}/stickers): the last ones sent, the favourites
 * (a long press on a sticker), and the ones they made from their photos. The phone's part fills the hooks.
 */
object Stickers {
    val recent = MutableStateFlow<List<GifItem>>(emptyList())
    val favorites = MutableStateFlow<List<GifItem>>(emptyList())
    val mine = MutableStateFlow<List<GifItem>>(emptyList())

    /** Starts keeping the lists (once signed in). */
    var start: () -> Unit = {}

    /** A sticker was sent: first among the last ones. */
    var used: (GifItem) -> Unit = {}

    /** A sticker in or out of the favourites. */
    var toggleFavorite: (GifItem) -> Unit = {}

    /** A sticker made from a photo, kept among the person's own. */
    var addMine: (GifItem) -> Unit = {}

    /** Takes a sticker made by the person off their own. */
    var removeMine: (GifItem) -> Unit = {}

    fun isFavorite(item: GifItem) = favorites.value.any { it.url == item.url }
}

/** How making a sticker goes: picking a photo, cutting it out, sending it up. */
enum class StickerMaking { IDLE, CUTTING, UPLOADING }

/**
 * Making a sticker from a photo: the photo is picked, its subject cut out of the background on the phone (or, when it
 * cannot be, the photo in a circle), fitted into a square and sent to Qeyf's media server. [onMade] gets the sticker
 * (null: nothing made); [onStep] what it is doing.
 */
@Composable
expect fun rememberStickerMaker(onStep: (StickerMaking) -> Unit, onMade: (GifItem?) -> Unit): () -> Unit
