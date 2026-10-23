package com.qmusic.player

/** The index of the playing song ([current]) after the song at [from] moves to [to]. */
fun movedIndex(current: Int, from: Int, to: Int): Int = when {
    current == from -> to
    from < current && to >= current -> current - 1
    from > current && to <= current -> current + 1
    else -> current
}
