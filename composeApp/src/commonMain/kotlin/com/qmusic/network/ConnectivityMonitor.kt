package com.qmusic.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the device can reach the internet right now. */
interface ConnectivityMonitor {
    val isOnline: StateFlow<Boolean>

    /** On Wi-Fi or ethernet (not counted against a data plan). */
    val isUnmetered: StateFlow<Boolean>
}

/** Where connectivity is not tracked (desktop, tests): always online. */
class AlwaysOnline : ConnectivityMonitor {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    override val isUnmetered: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
