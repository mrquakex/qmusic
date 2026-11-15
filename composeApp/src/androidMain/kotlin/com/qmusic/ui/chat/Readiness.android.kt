package com.qmusic.ui.chat

import android.app.NotificationManager
import android.content.ComponentName
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

private const val KEY_AUTOSTART_DONE = "readiness_autostart_done"

/**
 * The makers whose phones stop a closed app from waking for pushes unless "autostart" is allowed for it, and where
 * that switch is (each tried in turn; makers move it between versions).
 */
private val AUTOSTART: Map<String, List<ComponentName>> = mapOf(
    "xiaomi" to listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    ),
    "redmi" to listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    ),
    "poco" to listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    ),
    "oppo" to listOf(
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    ),
    "realme" to listOf(
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
    ),
    "oneplus" to listOf(
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
    ),
    "vivo" to listOf(
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
    ),
    "huawei" to listOf(
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    ),
    "honor" to listOf(
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    ),
    "asus" to listOf(
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
    ),
)

private fun isXiaomi(): Boolean = (Build.MANUFACTURER + " " + Build.BRAND).lowercase().let { "xiaomi" in it || "redmi" in it || "poco" in it }

/**
 * One of MIUI's own permissions (10008 autostart, 10020 show on lock screen, 10021 open windows from the background):
 * true when allowed, false when denied, null when it cannot be read (not MIUI, or the system hides it).
 */
private fun miuiAllowed(context: Context, op: Int): Boolean? = runCatching {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    val check = android.app.AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
    (check.invoke(ops, op, android.os.Process.myUid(), context.packageName) as Int) == android.app.AppOpsManager.MODE_ALLOWED
}.getOrNull()

private fun autostartPages(): List<ComponentName> {
    val maker = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    return AUTOSTART.entries.firstOrNull { (name, _) -> name in maker }?.value.orEmpty()
}

private fun issuesOf(context: Context): List<ReadinessIssue> = buildList {
    val notifications = NotificationManagerCompat.from(context)
    if (!notifications.areNotificationsEnabled()) add(ReadinessIssue.NOTIFICATIONS)
    // The app's notifications on, but the messages or the ringing turned off one by one in the phone's settings.
    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val off = listOf(QeyfSounds.messageChannelId(context), QeyfSounds.ringChannelId(context))
            .any { id -> manager.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE }
        if (off) add(ReadinessIssue.CHANNELS)
    }
    // Android 14+: without it a call only peeks in at the top of the screen and does not fill a locked one.
    if (Build.VERSION.SDK_INT >= 34 && !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
        add(ReadinessIssue.FULL_SCREEN)
    }
    // Battery limits (Samsung's above all) hold back pushes to a sleeping phone: calls arrive late or not at all.
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!power.isIgnoringBatteryOptimizations(context.packageName)) add(ReadinessIssue.BATTERY)
    // Xiaomi's switches are read for real; the other makers' cannot be, so those are asked for until opened once.
    val prefs = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE)
    if (isXiaomi()) {
        val auto = miuiAllowed(context, 10008)
        if (auto == false || (auto == null && !prefs.getBoolean(KEY_AUTOSTART_DONE, false))) add(ReadinessIssue.AUTOSTART)
        // The ringing call fills a locked screen only with these two.
        if (miuiAllowed(context, 10020) == false || miuiAllowed(context, 10021) == false) add(ReadinessIssue.LOCK_SCREEN)
    } else if (autostartPages().isNotEmpty() && !prefs.getBoolean(KEY_AUTOSTART_DONE, false)) {
        add(ReadinessIssue.AUTOSTART)
    }
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
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        val intents = when (issue) {
            ReadinessIssue.NOTIFICATIONS -> listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
            ReadinessIssue.CHANNELS -> {
                val manager = context.getSystemService(NotificationManager::class.java)
                // The channel that is off, opened straight away; the app's notification page otherwise.
                val off = listOf(QeyfSounds.messageChannelId(context), QeyfSounds.ringChannelId(context))
                    .firstOrNull { id -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE }
                listOfNotNull(
                    off?.let {
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg).putExtra(Settings.EXTRA_CHANNEL_ID, it)
                    },
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
                )
            }
            ReadinessIssue.FULL_SCREEN -> listOf(
                Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:$pkg")),
                details,
            )
            ReadinessIssue.BATTERY -> listOf(
                @Suppress("BatteryLife") Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            ReadinessIssue.LOCK_SCREEN -> listOf(
                // MIUI's own page of the app's permissions (lock screen, pop-ups); the app's settings otherwise.
                Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", pkg),
                Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity").putExtra("extra_pkgname", pkg),
                details,
            )
            ReadinessIssue.AUTOSTART -> {
                context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTOSTART_DONE, true).apply()
                autostartPages().map { Intent().setComponent(it) } + details
            }
        }
        // The first page this phone has (makers move them around).
        intents.firstOrNull { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
        issues = issuesOf(context)
    }
}
