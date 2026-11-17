package com.qmusic.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import com.qmusic.ui.theme.QMusicIcons
import androidx.compose.ui.text.withStyle

/** What a message preview is about, shown as a drawn icon in front of its words (never as an emoji). */
enum class PreviewKind { MISSED_CALL, CALL, VIDEO_CALL, VOICE, VIDEO, FILE, PHOTO, LOCATION, CONTACT, POLL, SONG, WATCH, TIMER, ATTACH }

/** A preview cut in two: its kind (if it has one) and its words. */
data class PreviewParts(val kind: PreviewKind?, val text: String)

/**
 * Message previews are kept with a small mark in front ("📷", "🎤 0:12", "📞 Missed call": older versions wrote them so,
 * and the stored ones stay). This turns the mark into a kind (drawn as an icon) and plain words, so no emoji shows in
 * the list, in quotes or in notifications.
 */
object ChatPreview {
    private val marks: List<Pair<String, PreviewKind>> = listOf(
        "📞" to PreviewKind.CALL,
        "🎥" to PreviewKind.VIDEO_CALL,
        "🎤" to PreviewKind.VOICE,
        "🎬" to PreviewKind.VIDEO,
        "📄" to PreviewKind.FILE,
        "📷" to PreviewKind.PHOTO,
        "📍" to PreviewKind.LOCATION,
        "👤" to PreviewKind.CONTACT,
        "📊" to PreviewKind.POLL,
        "📅" to PreviewKind.TIMER,
        "🎵" to PreviewKind.SONG,
        "🎧" to PreviewKind.SONG,
        "🍿" to PreviewKind.WATCH,
        "⏱" to PreviewKind.TIMER,
        "📎" to PreviewKind.ATTACH,
    )

    fun isE2eNotice(preview: String): Boolean {
        val t = preview.trimStart().removePrefix("🔒").trimStart()
        return t.startsWith("Mesajlar artık uçtan uca şifreli") || t.startsWith("Messages are now end-to-end encrypted")
    }

    fun split(preview: String, english: Boolean): PreviewParts {
        // The old "messages are now end-to-end encrypted" line: no longer shown in the list either.
        if (isE2eNotice(preview)) return PreviewParts(null, "")
        val t = preview.trimStart()
        // A group line "Ali: 📷": the writer stays in front.
        val (who, body) = t.indexOf(": ").takeIf { it in 1..40 }?.let { i ->
            val rest = t.substring(i + 2)
            if (marks.any { rest.startsWith(it.first) }) t.substring(0, i + 2) to rest else null
        } ?: ("" to t)
        val mark = marks.firstOrNull { body.startsWith(it.first) } ?: return PreviewParts(null, preview)
        var kind = mark.second
        val rest = body.removePrefix(mark.first).removePrefix("️").trim()
        if (kind == PreviewKind.CALL && (rest.startsWith("Cevapsız") || rest.startsWith("Missed"))) kind = PreviewKind.MISSED_CALL
        val word = when (kind) {
            PreviewKind.VOICE -> if (english) "Voice message" else "Sesli mesaj"
            PreviewKind.VIDEO -> "Video"
            PreviewKind.PHOTO -> if (english) "Photo" else "Fotoğraf"
            PreviewKind.LOCATION -> if (english) "Location" else "Konum"
            PreviewKind.ATTACH, PreviewKind.FILE -> if (english) "File" else "Dosya"
            else -> ""
        }
        // A bare mark ("📷"), a count ("📷 ×3") or a length ("🎤 0:12") gets its word in front.
        val words = when {
            rest.isEmpty() -> word
            word.isNotEmpty() && (rest.startsWith("×") || rest.first().isDigit()) -> "$word $rest"
            else -> rest
        }
        return PreviewParts(kind, who + words)
    }

    /** The words alone (notifications, quotes). */
    fun plain(preview: String, english: Boolean): String = split(preview, english).text

    fun icon(kind: PreviewKind): ImageVector = when (kind) {
        PreviewKind.MISSED_CALL, PreviewKind.CALL -> QMusicIcons.Phone
        PreviewKind.VIDEO_CALL, PreviewKind.PHOTO -> QMusicIcons.Camera
        PreviewKind.VOICE -> QMusicIcons.Mic
        PreviewKind.VIDEO, PreviewKind.WATCH -> QMusicIcons.VideoPlay
        PreviewKind.FILE -> QMusicIcons.File
        PreviewKind.LOCATION -> QMusicIcons.Location
        PreviewKind.CONTACT -> QMusicIcons.Contact
        PreviewKind.POLL -> QMusicIcons.Poll
        PreviewKind.SONG -> QMusicIcons.Headphones
        PreviewKind.TIMER -> QMusicIcons.Timer
        PreviewKind.ATTACH -> QMusicIcons.Attach
    }
}

/**
 * [text] with its emoji a little smaller than the system draws them (at the text's size they stood bigger than the
 * letters and pushed their lines apart, as on WhatsApp they do not).
 */
fun emojiFitted(text: String, scale: Float = 0.9f): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    var i = 0
    var runStart = -1
    fun isEmoji(cp: Int) = cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x2300..0x23FF
    fun joins(cp: Int) = cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 || cp in 0xE0020..0xE007F
    while (i < text.length) {
        val c = text[i]
        val cp = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) + 0x10000
        } else {
            c.code
        }
        val size = if (cp > 0xFFFF) 2 else 1
        val emojiPart = isEmoji(cp) || (runStart >= 0 && joins(cp))
        if (emojiPart && runStart < 0) runStart = i
        if (!emojiPart && runStart >= 0) {
            withStyle(androidx.compose.ui.text.SpanStyle(fontSize = androidx.compose.ui.unit.TextUnit(scale, androidx.compose.ui.unit.TextUnitType.Em))) { append(text, runStart, i) }
            runStart = -1
        }
        if (!emojiPart) append(text, i, i + size)
        i += size
    }
    if (runStart >= 0) {
        withStyle(androidx.compose.ui.text.SpanStyle(fontSize = androidx.compose.ui.unit.TextUnit(scale, androidx.compose.ui.unit.TextUnitType.Em))) { append(text, runStart, text.length) }
    }
}

/** Lines that keep an even height whether or not they hold an emoji (it is centred in the line, not let out of it). */
val EvenLines: androidx.compose.ui.text.style.LineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
)
