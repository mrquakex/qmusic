package com.qmusic.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import com.qmusic.ui.theme.QMusicIcons

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
        "🎵" to PreviewKind.SONG,
        "🎧" to PreviewKind.SONG,
        "🍿" to PreviewKind.WATCH,
        "⏱" to PreviewKind.TIMER,
        "📎" to PreviewKind.ATTACH,
    )

    fun split(preview: String, english: Boolean): PreviewParts {
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
