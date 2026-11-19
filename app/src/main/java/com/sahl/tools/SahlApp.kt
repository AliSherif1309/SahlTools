package com.sahl.tools

import android.app.Application
import android.os.Build

/** Saves the details of any crash so the app can show them next time (to copy and send for fixing). */
class SahlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val report = "Sahl Tools v${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                android.util.Log.getStackTraceString(e).take(6000)
            Tools.prefs(this).edit().putString("crash", report).commit()
            previous?.uncaughtException(t, e)
        }
    }
}
