package com.qmusic.updater

import com.qmusic.updater.model.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Downloads a new version and hands it to the system installer. */
interface UpdateInstaller {
    /** 0..1 while the file is downloading, null when idle. */
    val progress: StateFlow<Float?>

    /**
     * Downloads [info] and opens the installer. A failure carries a message that is fit to show to the user,
     * including when the system still has to be asked for permission to install apps from here.
     */
    suspend fun install(info: UpdateInfo): Result<Unit>
}

/** Where nothing can be installed (desktop, tests). */
class NoOpUpdateInstaller : UpdateInstaller {
    override val progress: StateFlow<Float?> = MutableStateFlow<Float?>(null).asStateFlow()
    override suspend fun install(info: UpdateInfo): Result<Unit> =
        Result.failure(UnsupportedOperationException("Updating is not supported on this platform"))
}
