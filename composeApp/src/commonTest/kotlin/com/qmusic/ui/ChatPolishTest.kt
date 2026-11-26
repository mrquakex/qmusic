package com.qmusic.ui

import com.qmusic.ui.chat.ChatEmojiCategories
import com.qmusic.ui.chat.ChatPoll
import com.qmusic.ui.chat.ChatTextFormat
import com.qmusic.ui.chat.ChatTextToken
import com.qmusic.ui.chat.ChatWallpaperStore
import com.qmusic.ui.chat.PinnedMessage
import com.qmusic.ui.chat.ResolvedWallpaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatPolishTest {

    @Test
    fun richTextParsesAllInlineStylesAndUrls() {
        val tokens = ChatTextFormat.parse("Selam *kalın* _italik_ ~çizili~ `kod_parçası` ||gizli son|| https://example.com/a_b*c")
        assertTrue(ChatTextToken.Bold("kalın") in tokens)
        assertTrue(ChatTextToken.Italic("italik") in tokens)
        assertTrue(ChatTextToken.Strike("çizili") in tokens)
        assertTrue(ChatTextToken.Code("kod_parçası") in tokens)
        assertTrue(ChatTextToken.Spoiler("gizli son") in tokens)
        assertTrue(ChatTextToken.Url("https://example.com/a_b*c") in tokens)
    }

    @Test
    fun richTextRespectsWordBoundariesAndUnclosedDelimiters() {
        val literal = "degisken_adi_1 ve 2*3*4 ve a~b ve *kapanmayan ve ||gizli"
        val tokens = ChatTextFormat.parse(literal)
        assertEquals(listOf(ChatTextToken.Plain(literal)), tokens)
        assertFalse(ChatTextFormat.hasFormatting(literal))
    }

    @Test
    fun richTextAllowsPunctuationBoundariesAroundFormattedWords() {
        val tokens = ChatTextFormat.parse("(*önemli*), _dikkat_! ~eski~.")
        assertTrue(ChatTextToken.Bold("önemli") in tokens)
        assertTrue(ChatTextToken.Italic("dikkat") in tokens)
        assertTrue(ChatTextToken.Strike("eski") in tokens)
    }

    @Test
    fun wallpaperResolutionFollowsPerChatThenGlobalPhotoThenGlobalColor() {
        val empty = emptyMap<String, String>()
        assertEquals(ResolvedWallpaper.None, ChatWallpaperStore.resolve("c1", 0, empty))
        assertEquals(ResolvedWallpaper.Solid(3), ChatWallpaperStore.resolve("c1", 3, empty))

        val withGlobalPhoto = mapOf(ChatWallpaperStore.GLOBAL_KEY to "photo:/tmp/bg.jpg")
        assertEquals(ResolvedWallpaper.Photo("/tmp/bg.jpg"), ChatWallpaperStore.resolve("c1", 3, withGlobalPhoto))

        val withChatOverrides = mapOf(
            ChatWallpaperStore.GLOBAL_KEY to "photo:/tmp/bg.jpg",
            "c1" to "color:2",
            "c2" to "photo:/tmp/c2.jpg",
            "c3" to "none",
        )
        assertEquals(ResolvedWallpaper.Solid(2), ChatWallpaperStore.resolve("c1", 3, withChatOverrides))
        assertEquals(ResolvedWallpaper.Photo("/tmp/c2.jpg"), ChatWallpaperStore.resolve("c2", 3, withChatOverrides))
        assertEquals(ResolvedWallpaper.None, ChatWallpaperStore.resolve("c3", 3, withChatOverrides))
        assertEquals(ResolvedWallpaper.Photo("/tmp/bg.jpg"), ChatWallpaperStore.resolve("c4", 3, withChatOverrides))
    }

    @Test
    fun pollAndPinnedMessageDefaultsRemainBackwardCompatible() {
        val p = ChatPoll("Soru?", listOf("A", "B"), multiple = false)
        assertFalse(p.anonymous)
        assertFalse(p.closed)

        val anonClosed = ChatPoll("Gizli?", listOf("Evet", "Hayır"), multiple = false, anonymous = true, closed = true)
        assertTrue(anonClosed.anonymous)
        assertTrue(anonClosed.closed)

        val permanentPin = PinnedMessage("m1", "Merhaba", "u1", 1000L)
        assertEquals(0L, permanentPin.expireAt)

        val timedPin = PinnedMessage("m2", "24 saat", "u1", 1000L, expireAt = 1000L + 86_400_000L)
        assertTrue(timedPin.expireAt > timedPin.at)
    }

    @Test
    fun reactionEmojiPickerCategoryPoolHasValidShortEmojis() {
        assertTrue(ChatEmojiCategories.isNotEmpty())
        val valid = ChatEmojiCategories.flatMap { it.second }.filter { it.isNotBlank() && it.length <= 8 }
        assertTrue(valid.size >= 100)
        assertTrue(valid.all { it.length <= 8 })
    }
}
