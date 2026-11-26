package com.qmusic.ui.chat.games

/** Evaluation rank for a letter in WordDuel and Hangman. */
enum class KeyMark(val priority: Int) {
    UNUSED(0),
    ABSENT(1),
    PRESENT(2),
    EXACT(3);

    fun merge(other: KeyMark): KeyMark = if (other.priority > this.priority) other else this
}
