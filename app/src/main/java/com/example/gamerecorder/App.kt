package com.example.gamerecorder

import android.app.Application
import android.content.Context

class App : Application() {
    companion object {
        lateinit var context: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }
}
