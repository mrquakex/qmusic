package com.qmusic.ui.chat.games

/**
 * Locale-independent Turkish text helpers.
 * Never rely on JVM/JS String.uppercase()/lowercase() because default US locale turns 'i' -> 'I' and 'I' -> 'i'.
 */
object TurkishText {
    const val ALPHABET: String = "ABCÇDEFGĞHIİJKLMNOÖPRSŞTUÜVYZ"
    const val PLAYABLE_NAME_CITY_LETTERS: String = "ABCÇDEFGHIKLMNOÖPRSŞTUÜVYZ" // 26 letters (excludes Ğ and J)

    private val UPPER_SET: Set<Char> = ALPHABET.toSet()

    fun trUpperChar(c: Char): Char = when (c) {
        'i' -> 'İ'
        'ı' -> 'I'
        'ş' -> 'Ş'
        'ğ' -> 'Ğ'
        'ü' -> 'Ü'
        'ö' -> 'Ö'
        'ç' -> 'Ç'
        in 'a'..'z' -> (c.code - 32).toChar()
        else -> c
    }

    fun trLowerChar(c: Char): Char = when (c) {
        'İ' -> 'i'
        'I' -> 'ı'
        'Ş' -> 'ş'
        'Ğ' -> 'ğ'
        'Ü' -> 'ü'
        'Ö' -> 'ö'
        'Ç' -> 'ç'
        in 'A'..'Z' -> (c.code + 32).toChar()
        else -> c
    }

    fun trUpper(text: String): String = buildString(text.length) {
        for (c in text) {
            if (c == '\u0307') continue // combining dot above after I -> skip if already converted
            append(trUpperChar(c))
        }
    }

    fun trLower(text: String): String = buildString(text.length) {
        for (c in text) {
            if (c == '\u0307') continue
            append(trLowerChar(c))
        }
    }

    /** Trims and converts to canonical Turkish uppercase (letters only when [lettersOnly] is true). */
    fun trNormalize(text: String, lettersOnly: Boolean = true): String = buildString(text.length) {
        for (c in text.trim()) {
            if (c == '\u0307') continue
            val u = trUpperChar(c)
            if (!lettersOnly || u in UPPER_SET) append(u)
        }
    }

    fun isTurkishLetter(c: Char): Boolean = trUpperChar(c) in UPPER_SET

    fun isTurkishWord(text: String, minLen: Int = 2, maxLen: Int = 24): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in minLen..maxLen) return false
        return trimmed.all { isTurkishLetter(it) }
    }
}
