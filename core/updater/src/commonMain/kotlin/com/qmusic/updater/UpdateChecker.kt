package com.qmusic.updater

import com.qmusic.updater.model.UpdateInfo

interface UpdateChecker {
    suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo?>

    /** What changed in [version] (its release notes), for the "what's new" sheet after an update. */
    suspend fun releaseNotes(version: String): Result<String> = Result.failure(UnsupportedOperationException())
}
