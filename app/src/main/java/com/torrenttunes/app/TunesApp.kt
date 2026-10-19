package com.torrenttunes.app

import android.app.Application
import com.torrenttunes.app.util.CrashLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TunesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to filesDir/crash.log so on-device
        // failures can be diagnosed without adb attached.
        CrashLogger.install(this)
    }
}
