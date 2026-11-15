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
        HeadsetResume.start(this, container.settings)
    }

    /** Calls: the ringing notification when the app is not on screen, and the service that keeps a call going. */
    private fun hookCalls() {
        val app = this
        com.qmusic.ui.chat.CallHooks.onIncoming = { _, peer, video -> CallNotifications.showIncoming(app, peer, video) }
        com.qmusic.ui.chat.CallHooks.onIncomingGone = { CallNotifications.cancelIncoming(app) }
        com.qmusic.ui.chat.CallHooks.onMissed = { peer, video -> CallNotifications.showMissed(app, peer, video) }
        com.qmusic.ui.chat.ChatPresence.onOpened = { chatId -> ChatPush.clear(app, chatId) }
        com.qmusic.ui.chat.CallHooks.onActive = { active, peer, video ->
            val service = android.content.Intent(app, CallKeepAliveService::class.java)
            if (active) {
                service.putExtra(CallKeepAliveService.EXTRA_VIDEO, video).putExtra(CallKeepAliveService.EXTRA_NAME, peer?.name)
                runCatching { androidx.core.content.ContextCompat.startForegroundService(app, service) }
            } else {
                app.stopService(service)
            }
        }
    }
}
