package com.qmusic.updater

import com.qmusic.updater.model.UpdateInfo

interface UpdateChecker {
    suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo?>
}
