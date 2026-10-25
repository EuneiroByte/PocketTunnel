package com.kotunnel.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class TunnelApp : Application() {
    companion object {
        const val CHANNEL_ID = "kotunnel_service"
    }

    override fun onCreate() {
        super.onCreate()
        MyApp.app = this
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_desc) }
            nm.createNotificationChannel(ch)
        }
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val dir = File(filesDir, "crashes").apply { mkdirs() }
                File(dir, "crash-$ts.txt").writeText(
                    "Thread: ${thread.name}\n\n$sw"
                )
            } catch (_: Throwable) {}
            prev?.uncaughtException(thread, throwable)
        }
    }
}
