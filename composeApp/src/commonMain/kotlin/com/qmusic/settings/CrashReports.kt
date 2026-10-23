package com.qmusic.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The last crash of the app, kept on the device so it can be shared from Settings. */
interface CrashReports {
    /** The text of the last crash, or null when there has not been one. */
    val lastReport: StateFlow<String?>

    fun clear()
}

class NoCrashReports : CrashReports {
    override val lastReport: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    override fun clear() = Unit
}
