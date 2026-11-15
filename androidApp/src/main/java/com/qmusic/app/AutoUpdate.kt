package com.qmusic.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Updates QMusic by itself, the way a store does: a new release is downloaded in the background, and installed without
 * a question (Android 12+, once QMusic installed the version being replaced) at a moment it is not in use: not on
 * screen, nothing playing, no call. The person simply finds the new version the next time they open it.
 *
 * Where Android still needs a confirmation (an older Android, the first update after a manual install, installs from
 * QMusic not allowed), it falls back to the notification and the update button, as before.
 */
object AutoUpdate {
    private const val JOB_ID = 7301
    private const val PREFS = "qmusic_auto_update"
    private const val KEY_FILE = "file"
    private const val KEY_VERSION = "version"
    private const val RETRY_MS = 20 * 60 * 1000L

    /** Whether this phone can take an update without a question at all. */
    fun canBeSilent(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.packageManager.canRequestPackageInstalls()

    /** Looks for a new release (and gets it) in the background, now or after [delayMs], when there is a network. */
    fun schedule(context: Context, delayMs: Long = 0) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutoUpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .apply { if (delayMs > 0) setMinimumLatency(delayMs) }
            .build()
        runCatching { scheduler.schedule(job) }
    }

    /**
     * The app left the screen: an update that is waiting goes in a couple of minutes later, if the app is still out of
     * sight and nothing plays then (a quick look at another app does not restart QMusic under the person).
     */
    fun onAppHidden(context: Context) {
        if (ready(context) != null) schedule(context, IDLE_MS)
    }

    private const val IDLE_MS = 2 * 60 * 1000L

    /** The downloaded update, when it is still newer than what runs. */
    internal fun ready(context: Context): File? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = prefs.getString(KEY_FILE, null)?.let(::File)?.takeIf { it.exists() } ?: return null
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        return file.takeIf { UpdatePush.isNewer(version, installedVersion(context)) }
    }

    internal fun remember(context: Context, file: File, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FILE, file.absolutePath).putString(KEY_VERSION, version).apply()
        com.qmusic.ui.UpdateReady.version.value = version
    }

    internal fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        com.qmusic.ui.UpdateReady.version.value = null
    }

    /** At start: an update downloaded before shows its "ready" banner; the banner's button installs it. */
    fun hookBanner(context: Context) {
        val app = context.applicationContext
        com.qmusic.ui.UpdateReady.install = { installNow(app) }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        com.qmusic.ui.UpdateReady.version.value = if (ready(app) != null) prefs.getString(KEY_VERSION, null) else null
    }

    /** Not on screen, no sound playing (music or video, ours or another app's), no call. */
    private fun quiet(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return !com.qmusic.ui.chat.CallHooks.appVisible &&
            com.qmusic.ui.chat.CallHooks.state?.value == null &&
            !audio.isMusicActive
    }

    /** Installs the waiting update when the moment is right; otherwise tries again a while later. */
    internal fun tryInstall(context: Context) {
        val file = ready(context) ?: return
        if (!quiet(context)) {
            schedule(context, RETRY_MS)
            return
        }
        val installer = (context.applicationContext as QMusicApplication).container.updateInstaller as? ApkUpdateInstaller ?: return
        runCatching { installer.commitFile(file) }
    }

    /** The person asked for it (tapped the notification): the downloaded update goes in now. False when none is downloaded yet. */
    internal fun installNow(context: Context): Boolean {
        val file = ready(context) ?: return false
        val installer = (context.applicationContext as QMusicApplication).container.updateInstaller as? ApkUpdateInstaller ?: return false
        return runCatching { installer.commitFile(file) }.isSuccess
    }

    internal fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}

/** The background job: finds the new release, downloads it once, and hands it over when the app is not in use. */
class AutoUpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val context = applicationContext
        // Without silent installs (older Android, or installs from the app not allowed) the update is still
        // downloaded: the banner, or the system's own install screen on Xiaomi, takes it from there.
        if (!AutoUpdate.canBeSilent(context) && !isXiaomi() && !context.packageManager.canRequestPackageInstalls()) return false
        work = scope.launch {
            try {
                if (AutoUpdate.ready(context) == null) {
                    val container = (context as QMusicApplication).container
                    val info = container.updateChecker.checkForUpdate(AutoUpdate.installedVersion(context)).getOrNull()
                    val installer = container.updateInstaller as? ApkUpdateInstaller
                    if (info != null && installer != null && UpdatePush.isNewer(info.versionName, AutoUpdate.installedVersion(context))) {
                        val file = installer.downloadOnly(info)
                        AutoUpdate.remember(context, file, info.versionName)
                        // The notes go with it: the new version shows them at once, even without a connection.
                        if (info.changelog.isNotBlank()) {
                            context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).edit()
                                .putString("release_notes_" + info.versionName.removePrefix("v"), info.changelog).apply()
                        }
                    }
                }
                AutoUpdate.tryInstall(context)
                jobFinished(params, false)
            } catch (e: Exception) {
                jobFinished(params, true)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }
}

/** Runs in the new version right after an update: says so once, and drops the downloaded file. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AutoUpdate.forget(context)
        // The "new version is out" notification is done.
        context.getSystemService(NotificationManager::class.java)?.cancel(UpdatePush.NOTIFICATION_ID_PUBLIC)
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        val version = AutoUpdate.installedVersion(context)
        val english = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") == "en"
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("qmusic_updates", context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_WHATS_NEW, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            manager.notify(
                1004,
                NotificationCompat.Builder(context, "qmusic_updates")
                    .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                    .setContentTitle(if (english) "Qeyf updated to $version" else "Qeyf $version sürümüne güncellendi")
                    .setContentText(if (english) "Open it to see what's new" else "Yenilikleri görmek için aç")
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }
}

/** On the "Qeyf was updated" notification: open the app on what changed. */
internal const val EXTRA_WHATS_NEW = "show_whats_new"
