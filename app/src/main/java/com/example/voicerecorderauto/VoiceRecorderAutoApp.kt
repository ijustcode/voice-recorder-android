package com.example.voicerecorderauto

import android.app.Application

class VoiceRecorderAutoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: VoiceRecorderAutoApp
            private set
    }
}
