package com.imagestory.jarvis

import android.app.Application
import com.imagestory.jarvis.service.Notifier

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
