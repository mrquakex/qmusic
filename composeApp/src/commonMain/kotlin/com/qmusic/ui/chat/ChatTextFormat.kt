package com.qmusic.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/** Inline token parsed from a chat message: plain text, URL, styled run, or tap-to-reveal spoiler. */
internal sealed interface ChatTextToken {
    data class Plain(val text: String) : ChatTextToken
    data class Url(val url: String) : ChatTextToken
    data class Bold(val text: String) : ChatTextToken
    data class Italic(val text: String) : ChatTextToken
    data class Strike(val text: String) : ChatTextToken
    data class Code(val text: String) : ChatTextToken
    data class Spoiler(val text: String) : ChatTextToken
}

internal object ChatTextFormat {
    private val urlRegex = Regex("""https?://[^\s<>"]+[^\s<>".,;:!?)\]]""")

    /** True when the message contains any inline markdown, spoiler, or clickable link. */
    fun hasFormatting(text: String): Boolean {
        if (text.isEmpty()) return false
        return parse(text).any { it !is ChatTextToken.Plain }
    }

    /**
     * Parses WhatsApp/Telegram-style inline formatting (*bold*, _italic_, ~strike~, `code`, ||spoiler||)
     * alongside clickable HTTP(S) links without corrupting punctuation inside URLs or inline code.
     */
    fun parse(text: String): List<ChatTextToken> {
        if (text.isEmpty()) return emptyList()
        val urls = urlRegex.findAll(text).toList()
        val out = ArrayList<ChatTextToken>()
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out.add(ChatTextToken.Plain(plain.toString()))
                plain.setLength(0)
            }
        }

        var i = 0
        var urlIdx = 0
        val n = text.length
        while (i < n) {
            while (urlIdx < urls.size && urls[urlIdx].range.last < i) urlIdx++
            if (urlIdx < urls.size && urls[urlIdx].range.first == i) {
                flushPlain()
                val match = urls[urlIdx]
                out.add(ChatTextToken.Url(match.value))
                i = match.range.last + 1
                urlIdx++
                continue
            }
            val nextUrlStart = if (urlIdx < urls.size) urls[urlIdx].range.first else n

            // ||spoiler||
            if (i + 3 < nextUrlStart && text[i] == '|' && text[i + 1] == '|') {
                val close = text.indexOf("||", i + 2)
                if (close in (i + 3) until nextUrlStart) {
                    val inner = text.substring(i + 2, close)
                    if (inner.isNotBlank()) {
                        flushPlain()
                        out.add(ChatTextToken.Spoiler(inner))
                        i = close + 2
                        continue
                    }
                }
            }

            // `inline code` (single line)
            if (text[i] == '`') {
                val close = text.indexOf('`', i + 1)
                if (close in (i + 2) until nextUrlStart) {
                    val inner = text.substring(i + 1, close)
                    if ('\n' !in inner && inner.isNotBlank()) {
                        flushPlain()
                        out.add(ChatTextToken.Code(inner))
                        i = close + 1
                        continue
                    }
                }
            }

            // *bold*, _italic_, ~strikethrough~ with word-boundary guards so foo_bar_baz or 2*3*4 stay literal.
            val ch = text[i]
            if ((ch == '*' || ch == '_' || ch == '~') && isOpenBoundary(text, i)) {
                val close = findDelimitedClose(text, ch, i + 1, nextUrlStart)
                if (close > i + 1) {
                    val inner = text.substring(i + 1, close)
                    flushPlain()
                    out.add(
                        when (ch) {
                            '*' -> ChatTextToken.Bold(inner)
                            '_' -> ChatTextToken.Italic(inner)
                            else -> ChatTextToken.Strike(inner)
                        },
                    )
                    i = close + 1
                    continue
                }
            }

            plain.append(ch)
            i++
        }
        flushPlain()
        return out
    }

    private fun isOpenBoundary(text: String, pos: Int): Boolean {
        if (pos + 1 >= text.length || text[pos + 1].isWhitespace()) return false
        if (pos == 0) return true
        val prev = text[pos - 1]
        return prev.isWhitespace() || prev in "([{\"'«<"
    }

    private fun isCloseBoundary(text: String, pos: Int): Boolean {
        if (pos <= 0 || text[pos - 1].isWhitespace()) return false
        if (pos + 1 >= text.length) return true
        val next = text[pos + 1]
        return next.isWhitespace() || next in ".,;:!?)]}\"'»>"
    }

    private fun findDelimitedClose(text: String, marker: Char, start: Int, limit: Int): Int {
        var idx = start
        while (idx < limit) {
            val c = text[idx]
            if (c == '\n') return -1
            if (c == marker && idx > start && isCloseBoundary(text, idx)) {
                return idx
            }
            idx++
        }
        return -1
    }
}

private val FormattedEvenLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** Renders chat text with *bold*, _italic_, ~strike~, `code`, tappable ||spoiler|| masks, and clickable links. */
@Composable
internal fun FormattedMessageText(
    text: String,
    color: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = remember(text) { ChatTextFormat.parse(text) }
    var revealed by remember(text) { mutableStateOf(emptySet<Int>()) }
    val annotated = buildAnnotatedString {
        var spoilerIdx = 0
        for (token in tokens) {
            when (token) {
                is ChatTextToken.Plain -> append(token.text)
                is ChatTextToken.Url -> withLink(
                    LinkAnnotation.Url(
                        token.url,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append(token.url) }
                is ChatTextToken.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.text) }
                is ChatTextToken.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.text) }
                is ChatTextToken.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.text) }
                is ChatTextToken.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        background = color.copy(alpha = 0.12f),
                    ),
                ) { append(" ${token.text} ") }
                is ChatTextToken.Spoiler -> {
                    val idx = spoilerIdx++
                    val shown = idx in revealed
                    val style = if (shown) {
                        SpanStyle(color = color, background = color.copy(alpha = 0.14f))
                    } else {
                        SpanStyle(color = Color.Transparent, background = color.copy(alpha = 0.85f))
                    }
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "spoiler:$idx",
                            styles = TextLinkStyles(style),
                            linkInteractionListener = {
                                revealed = if (idx in revealed) revealed - idx else revealed + idx
                            },
                        ),
                    ) { append(token.text) }
                }
            }
        }
    }
    Text(
        text = annotated,
        color = color,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        style = TextStyle(lineHeightStyle = FormattedEvenLines),
        modifier = modifier,
    )
}
