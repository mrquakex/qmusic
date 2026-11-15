package com.qmusic.ui.chat

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private fun issuesOf(context: Context): List<ReadinessIssue> = buildList {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) add(ReadinessIssue.NOTIFICATIONS)
    // Android 14+: without it a call only peeks in at the top of the screen and does not fill a locked one.
    if (Build.VERSION.SDK_INT >= 34 && !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
        add(ReadinessIssue.FULL_SCREEN)
    }
    // Battery limits (Samsung's above all) hold back pushes to a sleeping phone: calls arrive late or not at all.
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!power.isIgnoringBatteryOptimizations(context.packageName)) add(ReadinessIssue.BATTERY)
}

@Suppress("DEPRECATION")
@Composable
actual fun rememberReadiness(): Readiness {
    val context = LocalContext.current
    var issues by remember { mutableStateOf(issuesOf(context)) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        // Coming back from the settings page: checked again.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) issues = issuesOf(context) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return Readiness(issues) { issue ->
        val pkg = context.packageName
        val intents = when (issue) {
            ReadinessIssue.NOTIFICATIONS -> listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
            ReadinessIssue.FULL_SCREEN -> listOf(
                Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:$pkg")),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
            )
            ReadinessIssue.BATTERY -> listOf(
                @Suppress("BatteryLife") Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
        // The first page this phone has (makers move them around).
        intents.firstOrNull { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
    }
}
