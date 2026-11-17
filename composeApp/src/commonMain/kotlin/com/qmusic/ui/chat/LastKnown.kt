package com.qmusic.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow

/** What a chat's live values last were, for this run of the app. */
private val lastKnown = HashMap<String, Any?>()

/**
 * [flow] collected starting from what it last gave under [key] (opening a chat again draws its ticks, "online" line,
 * reactions and pins as they were, instead of from nothing and then filling in); [empty] only the very first time.
 */
@Composable
internal fun <T> rememberLastKnown(key: String, empty: T, flow: () -> Flow<T>): State<T> {
    val source = remember(key) { flow() }
    @Suppress("UNCHECKED_CAST")
    val start = remember(key) { if (lastKnown.containsKey(key)) lastKnown[key] as T else empty }
    val state = source.collectAsState(initial = start)
    lastKnown[key] = state.value
    return state
}
