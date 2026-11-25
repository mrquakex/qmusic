package com.qmusic.app

import android.app.Application

class QMusicApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        hookCalls()
        container = AppContainer(this)
        container.crashReports.install(this)
        UpdatePush.subscribe(this)
        // A release the push did not bring (the phone was off) is picked up in the background too.
        AutoUpdate.schedule(this, delayMs = 60_000)
        AutoUpdate.hookBanner(this)
        HeadsetResume.start(this, container.settings)
        com.qmusic.ui.chat.ArrivalWatch.init(this)
    }

    /** Calls: the ringing notification when the app is not on screen, and the service that keeps a call going. */
    private fun hookCalls() {
        val app = this
        com.qmusic.ui.chat.CallHooks.onIncoming = { id, peer, video -> CallNotifications.showIncoming(app, peer, video, id) }
        // Off the main thread: the caller's photo is fetched for the notification (on it, only the initial could show).
        com.qmusic.ui.chat.CallHooks.onBusy = { peer, video -> Thread { CallNotifications.showMissed(app, peer, video, busy = true) }.start() }
        com.qmusic.ui.chat.CallHooks.onIncomingGone = { CallNotifications.cancelIncoming(app) }
        com.qmusic.ui.chat.CallHooks.onMissed = { peer, video -> Thread { CallNotifications.showMissed(app, peer, video) }.start() }
        com.qmusic.ui.chat.ChatPresence.onOpened = { chatId ->
            ChatPush.clear(app, chatId)
            com.qmusic.ui.chat.CallHooks.trace?.invoke("chat opened")
        }
        // The locked conversations' ids, kept for notifications that come with the app closed.
        val lockPrefs = getSharedPreferences("qeyf_locked_chats", MODE_PRIVATE)
        com.qmusic.ui.chat.LockedChats.restore(lockPrefs.getStringSet("ids", emptySet()).orEmpty().toSet())
        com.qmusic.ui.chat.LockedChats.onChanged = { ids -> lockPrefs.edit().putStringSet("ids", ids).apply() }
        // The same for the secret space's conversations, and whether its calls show who calls.
        val secretPrefs = getSharedPreferences("nuvelo_secret_chats", MODE_PRIVATE)
        com.qmusic.ui.chat.SecretChats.restore(secretPrefs.getStringSet("ids", emptySet()).orEmpty().toSet())
        com.qmusic.ui.chat.SecretChats.showCallerName = secretPrefs.getBoolean("showCaller", true)
        com.qmusic.ui.chat.SecretChats.onChanged = { ids -> secretPrefs.edit().putStringSet("ids", ids).apply() }
        // Messages to go later (held Send): kept and sent by the phone at their time.
        ScheduledSend.init(app)
        // Unsent text of each conversation (drafts), on this phone only.
        val draftPrefs = getSharedPreferences("nuvelo_drafts", MODE_PRIVATE)
        com.qmusic.ui.chat.ChatDrafts.restore(
            runCatching {
                val o = org.json.JSONObject(draftPrefs.getString("all", "{}") ?: "{}")
                o.keys().asSequence().associateWith { o.getString(it) }
            }.getOrDefault(emptyMap()),
        )
        com.qmusic.ui.chat.ChatDrafts.onChanged = { all -> draftPrefs.edit().putString("all", org.json.JSONObject(all).toString()).apply() }
        com.qmusic.ui.chat.CallHooks.onGroupJoined = { groupId -> GroupCallNotifications.inCall(app, groupId) }
        // How the message notifications behave (Settings → Notifications), kept on the phone.
        val notifPrefs = getSharedPreferences("qmusic_settings", MODE_PRIVATE)
        com.qmusic.ui.chat.NotificationPrefs.hideOnLock.value = notifPrefs.getBoolean("notif_hide_lock", false)
        com.qmusic.ui.chat.NotificationPrefs.inAppBanner.value = notifPrefs.getBoolean("notif_banner", true)
        com.qmusic.ui.chat.NotificationPrefs.onChanged = {
            notifPrefs.edit()
                .putBoolean("notif_hide_lock", com.qmusic.ui.chat.NotificationPrefs.hideOnLock.value)
                .putBoolean("notif_banner", com.qmusic.ui.chat.NotificationPrefs.inAppBanner.value)
                .apply()
        }
        com.qmusic.ui.chat.ChatPresence.onReadElsewhere = { chatId -> ChatPush.clear(app, chatId) }
        // Unlocked: the messages kept short on the lock screen show in full.
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                app,
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context, intent: android.content.Intent) = ChatPush.unlocked(context)
                },
                android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        com.qmusic.ui.chat.CallHooks.onScreenShare = { sharing ->
            // The call's service says "screen" to Android too while the screen is shown (Android 14 needs it first).
            val service = android.content.Intent(app, CallKeepAliveService::class.java)
                .putExtra(CallKeepAliveService.EXTRA_VIDEO, CallKeepAliveService.lastVideo)
                .putExtra(CallKeepAliveService.EXTRA_NAME, CallKeepAliveService.lastName)
                .putExtra(CallKeepAliveService.EXTRA_UID, CallKeepAliveService.lastUid)
                .putExtra(CallKeepAliveService.EXTRA_PHOTO, CallKeepAliveService.lastPhoto)
                .putExtra(CallKeepAliveService.EXTRA_SCREEN, sharing)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(app, service) }
        }
        com.qmusic.ui.chat.CallHooks.onActive = { active, peer, video ->
            val service = android.content.Intent(app, CallKeepAliveService::class.java)
            if (active) {
                CallKeepAliveService.lastVideo = video
                CallKeepAliveService.lastName = peer?.name
                CallKeepAliveService.lastUid = peer?.uid
                CallKeepAliveService.lastPhoto = peer?.photo
                service.putExtra(CallKeepAliveService.EXTRA_VIDEO, video)
                    .putExtra(CallKeepAliveService.EXTRA_NAME, peer?.name)
                    .putExtra(CallKeepAliveService.EXTRA_UID, peer?.uid)
                    .putExtra(CallKeepAliveService.EXTRA_PHOTO, peer?.photo)
                runCatching { androidx.core.content.ContextCompat.startForegroundService(app, service) }
            } else {
                app.stopService(service)
                // Whatever said "on a call" goes with it: the ongoing notification, and a ringing one left over.
                runCatching { app.getSystemService(android.app.NotificationManager::class.java).cancel(CallNotifications.ONGOING_ID) }
                CallNotifications.cancelIncoming(app)
            }
        }
    }
}
