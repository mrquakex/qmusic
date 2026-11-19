package com.qmusic.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/** Hears back from the install session: when Android needs the person to confirm, this puts the sheet on screen. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (isXiaomi()) {
            XiaomiInstall.answered(
                context,
                ok = status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_PENDING_USER_ACTION || status == PackageInstaller.STATUS_FAILURE_ABORTED,
            )
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (com.qmusic.ui.chat.CallHooks.appVisible) {
                    context.startActivity(confirm)
                } else {
                    // Out of sight Android does not let the sheet open by itself: a notification brings it.
                    confirmLater(context, confirm)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> Unit

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The person said no: asked again with the next release (or the update button).
                AutoUpdate.forget(context)
            }

            else -> {
                Log.w("QMUpdate", "Install ended with status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                // The quiet way failed (some makers' systems refuse it): the system's own install screen instead.
                val file = downloadedUpdate(context)
                if (file.exists()) openSystemInstaller(context, file) else AutoUpdate.forget(context)
            }
        }
    }

    companion object {
        /** The "update is ready, tap to install" notification, opening [confirm]. */
        fun showLater(context: Context, confirm: Intent) = InstallResultReceiver().confirmLater(context, confirm)
    }

    internal fun confirmLater(context: Context, confirm: Intent) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                android.app.NotificationChannel("qmusic_updates", context.getString(R.string.update_channel_name), android.app.NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val english = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") == "en"
        val tap = android.app.PendingIntent.getActivity(
            context, 7, confirm,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            manager.notify(
                1003,
                androidx.core.app.NotificationCompat.Builder(context, "qmusic_updates")
                    .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                    .setContentTitle(if (english) "Nuvelo update is ready" else "Nuvelo güncellemesi hazır")
                    .setContentText(
                        when {
                            isXiaomi() -> if (english) "Tap to install" else "Kurmak için dokun"
                            english -> "Tap to install (next updates will install by themselves)"
                            else -> "Kurmak için dokun (sonraki güncellemeler kendiliğinden kurulacak)"
                        },
                    )
                    .setAutoCancel(true)
                    .setContentIntent(tap)
                    .build(),
            )
        }
    }
}
