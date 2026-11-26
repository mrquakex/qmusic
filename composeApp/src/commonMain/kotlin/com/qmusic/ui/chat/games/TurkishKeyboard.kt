package com.qmusic.ui.chat.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.motion.pressScale
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary

/** Evaluation rank for a letter in WordDuel, Hangman, and TurkishKeyboard. */
enum class KeyMark(val priority: Int) {
    UNUSED(0),
    ABSENT(1),
    PRESENT(2),
    EXACT(3);

    fun merge(other: KeyMark): KeyMark = if (other.priority > this.priority) other else this
}

private val ROW_1 = listOf('E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P', 'Ğ', 'Ü')
private val ROW_2 = listOf('A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L', 'Ş', 'İ')
private val ROW_3 = listOf('Z', 'C', 'V', 'B', 'N', 'M', 'Ö', 'Ç')

/**
 * Monochrome 29-letter Turkish Q keyboard for WordDuel, Hangman, and similar word games.
 * Adapts automatically across Dark, Light, and Pure Black themes via QMusicTheme tokens.
 */
@Composable
internal fun TurkishKeyboard(
    marks: Map<Char, KeyMark>,
    enabled: Boolean = true,
    showEnter: Boolean = true,
    enterEnabled: Boolean = true,
    enterAccent: androidx.compose.ui.graphics.Color = GameColors.Emerald,
    onLetter: (Char) -> Unit,
    onBackspace: () -> Unit = {},
    onEnter: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyRow(ROW_1, marks, enabled, onLetter)
        KeyRow(ROW_2, marks, enabled, onLetter)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showEnter) {
                val activeEnter = enabled && enterEnabled
                Box(
                    modifier = Modifier
                        .weight(1.45f)
                        .height(44.dp)
                        .pressScale(0.95f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (activeEnter) enterAccent else DarkSurfaceVariant)
                        .border(1.dp, if (activeEnter) enterAccent else BorderSubtle, RoundedCornerShape(8.dp))
                        .clickable(enabled = activeEnter, onClick = onEnter),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        QMusicIcons.Check,
                        contentDescription = null,
                        tint = if (activeEnter) GameColors.onAccent(enterAccent) else TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            ROW_3.forEach { ch ->
                LetterKey(ch, marks[ch] ?: KeyMark.UNUSED, enabled, Modifier.weight(1f)) { onLetter(ch) }
            }
            if (showEnter) {
                Box(
                    modifier = Modifier
                        .weight(1.45f)
                        .height(44.dp)
                        .pressScale(0.95f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled, onClick = onBackspace),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        QMusicIcons.Back,
                        contentDescription = null,
                        tint = if (enabled) TextPrimary else TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyRow(
    letters: List<Char>,
    marks: Map<Char, KeyMark>,
    enabled: Boolean,
    onLetter: (Char) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        letters.forEach { ch ->
            LetterKey(ch, marks[ch] ?: KeyMark.UNUSED, enabled, Modifier.weight(1f)) { onLetter(ch) }
        }
    }
}

@Composable
private fun LetterKey(
    ch: Char,
    mark: KeyMark,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val exact = GameColors.Emerald
    val present = GameColors.Amber
    val bg = when (mark) {
        KeyMark.EXACT -> exact
        KeyMark.PRESENT -> present
        KeyMark.ABSENT -> DarkSurface
        KeyMark.UNUSED -> DarkSurfaceVariant
    }
    val fg = when (mark) {
        KeyMark.EXACT -> GameColors.onAccent(exact)
        KeyMark.PRESENT -> GameColors.onAccent(present)
        KeyMark.ABSENT -> TextMuted
        KeyMark.UNUSED -> TextPrimary
    }
    val borderWidth = if (mark == KeyMark.EXACT || mark == KeyMark.PRESENT) 1.dp else 1.dp
    val borderColor = when (mark) {
        KeyMark.EXACT -> exact
        KeyMark.PRESENT -> present
        else -> BorderSubtle
    }
    Box(
        modifier = modifier
            .height(44.dp)
            .alpha(if (mark == KeyMark.ABSENT) 0.36f else 1f)
            .pressScale(0.94f)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ch.toString(),
            color = fg,
            fontSize = 14.sp,
            fontWeight = if (mark == KeyMark.EXACT || mark == KeyMark.PRESENT) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}
