package org.olcbox.app

import android.app.Application
import android.content.Context
import org.olcbox.app.telemetry.CrashRecorder

class App : Application() {
    companion object {
        lateinit var appContext: Context
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        // Every process (UI and VPN service) saves its crash for the next start to report.
        CrashRecorder.install(filesDir, CurrentAppInfo.value.version)
    }
}
