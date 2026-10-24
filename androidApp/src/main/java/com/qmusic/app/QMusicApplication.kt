package com.qmusic.app

import android.app.Application

class QMusicApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.crashReports.install(this)
        UpdatePush.subscribe(this)
        HeadsetResume.start(this, container.settings)
    }
}
