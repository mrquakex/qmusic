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
internal fun isXiaomi(): Boolean = (Build.MANUFACTURER + " " + Build.BRAND).lowercase().let { "xiaomi" in it || "redmi" in it || "poco" in it }

/** The downloaded update, where [ApkUpdateInstaller] keeps it. */
internal fun downloadedUpdate(context: Context): File = File(File(context.cacheDir, "updates"), "qmusic-update.apk")

/** The system's own install screen for [file] ("Update"): on screen now, or a notification that opens it. */
internal fun openSystemInstaller(context: Context, file: File) {
    if (!file.exists()) return
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".updates", file)
    val open = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (com.qmusic.ui.chat.CallHooks.appVisible) {
        runCatching { context.startActivity(open) }.onSuccess { return }
    }
    InstallResultReceiver.showLater(context, open)
}

class ApkUpdateInstaller(private val context: Context) : UpdateInstaller {
    private val _progress = MutableStateFlow<Float?>(null)
    override val progress: StateFlow<Float?> = _progress.asStateFlow()

    override suspend fun install(info: UpdateInfo): Result<Unit> = runCatching {
        if (info.downloadUrl.isBlank() || !info.downloadUrl.endsWith(".apk", ignoreCase = true)) {
            error(getString(R.string.update_no_apk))
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            // Ask once; the person turns the switch on and taps the update button again.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            error(getString(R.string.update_allow_permission))
        }
        commit(download(info))
    }.also { _progress.value = null }

    /** Downloads [info]'s APK without installing it (the background update, see [AutoUpdate]). */
    internal suspend fun downloadOnly(info: UpdateInfo): File {
        if (info.downloadUrl.isBlank() || !info.downloadUrl.endsWith(".apk", ignoreCase = true)) error("no apk")
        return try {
            download(info)
        } finally {
            _progress.value = null
        }
    }

    /** Installs an APK already downloaded. */
    internal fun commitFile(file: File) = commit(file)

    /**
     * Hands the APK to Android through an install session. On Android 12+ the session asks for no confirmation
     * when QMusic itself installed the version being replaced, so from the second update on it installs by itself
     * (when the app is not on screen); otherwise the system confirmation sheet appears, via [InstallResultReceiver].
     */
    private fun commit(file: File) {
        // Xiaomi's system may block quiet installs (some fail without a word): the quiet way is tried first, and when no
        // answer comes in time, or it failed before on this phone, its own installer opens (the usual Update button).
        if (isXiaomi() && XiaomiInstall.needsSystemScreen(context)) {
            openSystemInstaller(context, file)
            return
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            // Android 14+: Nuvelo owns its updates from now on, so the next ones stay quiet (no other installer takes over).
            if (Build.VERSION.SDK_INT >= 34) {
                runCatching { setRequestUpdateOwnership(true) }
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
        if (isXiaomi()) XiaomiInstall.watch(context, file)
    }

    /**
     * The normal download address first; when GitHub's file servers are down (a 5xx, or no answer) the same file is
     * asked for through GitHub's API address, which is served from elsewhere. Each is tried twice.
     */
    private suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "qmusic-update.apk")
        _progress.value = 0f
        val sources = listOfNotNull(
            info.downloadUrl to null,
            info.fallbackUrl.takeIf { it.isNotBlank() }?.let { it to "application/octet-stream" },
        )
        var failure: Throwable? = null
        for ((url, accept) in sources) {
            repeat(2) {
                try {
                    fetch(url, accept, target)
                    return@withContext target
                } catch (e: java.io.IOException) {
                    failure = e
                } catch (e: IllegalStateException) {
                    failure = e
                }
            }
        }
        throw failure ?: IllegalStateException(getString(R.string.update_download_failed, 0))
    }

    private fun fetch(url: String, accept: String?, target: File) {
        target.delete()
        _progress.value = 0f
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "QMusic-App")
        accept?.let { connection.setRequestProperty("Accept", it) }
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

/**
 * Xiaomi and the quiet install: tried first; any answer from Android counts as a way through. No answer within a while
 * (the system swallowed it) or a refusal: the system's own install screen, and this phone uses it from then on.
 */
internal object XiaomiInstall {
    private const val PREFS = "nuvelo_xiaomi_install"
    private const val KEY_SYSTEM = "system_screen"
    private const val KEY_ANSWER = "answer_at"
    private const val WAIT_MS = 45_000L

    fun needsSystemScreen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SYSTEM, false)

    /** Android answered the session (installed, asks, or refused). */
    fun answered(context: Context, ok: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_ANSWER, System.currentTimeMillis())
            .apply { if (!ok) putBoolean(KEY_SYSTEM, true) }
            .apply()
    }

    fun watch(context: Context, file: File) {
        val app = context.applicationContext
        val started = System.currentTimeMillis()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_ANSWER, 0L) < started) {
                prefs.edit().putBoolean(KEY_SYSTEM, true).apply()
                openSystemInstaller(app, file)
            }
        }, WAIT_MS)
    }
}
