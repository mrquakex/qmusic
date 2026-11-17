package com.qmusic.ui

import com.qmusic.engine.models.SongItem
import com.qmusic.ui.viewmodel.speedDialOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/** The speed dial's order: listened to most first, recent plays weighing more. */
class SpeedDialTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private fun song(id: String) = SongItem(id = id, title = id)
    private fun daysAgo(vararg d: Int) = d.map { now - it * day }

    @Test
    fun oftenBeatsOnceRecently() {
        val order = speedDialOrder(
            listOf(
                song("once-yesterday") to daysAgo(1),
                song("twenty-this-month") to (1..20).map { now - it * day },
            ),
            now,
        )
        assertEquals(listOf("twenty-this-month", "once-yesterday"), order.map { it.id })
    }

    @Test
    fun lotsLatelyBeatsLotsLongAgo() {
        val order = speedDialOrder(
            listOf(
                song("old-favourite") to List(6) { now - 28 * day },
                song("new-favourite") to List(5) { now - 2 * day },
            ),
            now,
        )
        assertEquals(listOf("new-favourite", "old-favourite"), order.map { it.id })
    }

    @Test
    fun samePlaysSameDayKeepBoth() {
        val order = speedDialOrder(listOf(song("a") to daysAgo(3, 4), song("b") to daysAgo(3, 4, 5)), now)
        assertEquals(listOf("b", "a"), order.map { it.id })
        assertEquals(emptyList(), speedDialOrder(emptyList(), now))
    }
}
