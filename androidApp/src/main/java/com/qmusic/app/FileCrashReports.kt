package com.qmusic.app

import android.content.Context
import android.os.Build
import com.qmusic.settings.CrashReports
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Writes the stack trace of an uncaught crash to a file before the process dies, and reads it back on the next
 * launch. Nothing leaves the device unless the person chooses to share it from Settings.
 */
class FileCrashReports(context: Context) : CrashReports {
    private val file = File(context.filesDir, "last_crash.txt")
    private val _report = MutableStateFlow(read())
    override val lastReport: StateFlow<String?> = _report.asStateFlow()

    override fun clear() {
        file.delete()
        _report.value = null
    }

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(
                    buildString {
                        appendLine("QMusic $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                        appendLine("Thread: ${thread.name}")
                        appendLine(error.stackTraceToString().take(MAX_CHARS))
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun read(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()

    private companion object {
        const val MAX_CHARS = 12_000
    }
}
