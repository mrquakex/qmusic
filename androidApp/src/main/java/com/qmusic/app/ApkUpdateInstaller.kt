package com.qmusic.app

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.net.Uri
import android.provider.Settings
import com.qmusic.updater.UpdateInstaller
import com.qmusic.updater.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the APK into the app's cache and opens Android's installer for it. Android never installs an app
 * silently: the person confirms once on the system sheet, and must have allowed installs from QMusic.
 */
class ApkUpdateInstaller(private val context: Context) : UpdateInstaller {
    private val _progress = MutableStateFlow<Float?>(null)
    override val progress: StateFlow<Float?> = _progress.asStateFlow()

    override suspend fun install(info: UpdateInfo): Result<Unit> = runCatching {
        if (info.downloadUrl.isBlank() || !info.downloadUrl.endsWith(".apk", ignoreCase = true)) {
            error(getString(R.string.update_no_apk))
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Ask once; the person turns the switch on and taps the update button again.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            error(getString(R.string.update_allow_permission))
        }
        commit(download(info.downloadUrl))
    }.also { _progress.value = null }

    /**
     * Hands the APK to Android through an install session. On Android 12+ the session asks for no confirmation
     * when QMusic itself installed the version being replaced, so from the second update on it installs by itself
     * (when the app is not on screen); otherwise the system confirmation sheet appears, via [InstallResultReceiver].
     */
    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("qmusic.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val result = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    private suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "qmusic-update.apk")
        _progress.value = 0f
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode !in 200..299) error(getString(R.string.update_download_failed, connection.responseCode))
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) _progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        target
    }

    /**
     * Resolves strings from res/values/strings.xml and res/values-tr/strings.xml matching the in-app
     * language setting kept in SharedPreferences.
     */
    private fun getString(resId: Int, vararg formatArgs: Any): String {
        val prefs = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "tr") ?: "tr"
        val locale = if (lang == "en") java.util.Locale.ENGLISH else java.util.Locale("tr")
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        val localizedContext = context.createConfigurationContext(config)
        return localizedContext.getString(resId, *formatArgs)
    }
}
