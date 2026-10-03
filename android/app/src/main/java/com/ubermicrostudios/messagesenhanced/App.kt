package com.ubermicrostudios.messagesenhanced

import android.app.Application

/** Records uncaught crashes in [LaunchLog] so the setup screen can show them. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { LaunchLog.record(this, "Crash on thread ${thread.name}", e) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }
}
