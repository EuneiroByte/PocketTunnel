package com.kotunnel.client.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kotunnel.client.MainActivity
import com.kotunnel.client.R
import com.kotunnel.client.TunnelApp
import com.kotunnel.client.tunnel.LogLine
import com.kotunnel.client.tunnel.TunnelEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TunnelService : Service() {

    companion object {
        const val ACTION_START = "com.kotunnel.client.START"
        const val ACTION_STOP = "com.kotunnel.client.STOP"
        const val NOTIFICATION_ID = 1001

        // Observed by the UI.
        val logs = MutableSharedFlow<LogLine>(extraBufferCapacity=256)
        val running = MutableStateFlow(false)
        val status = MutableStateFlow<TunnelEngine.Status>(TunnelEngine.Status.Idle)
    }

    @Inject lateinit var engine: TunnelEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var statusSyncJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        startForeground(NOTIFICATION_ID, buildNotification())
        // Idempotent: don't launch duplicate poll loop / sync loop on START_STICKY restart.
        if (job?.isActive == true) return
        running.value = true
        job = scope.launch {
            try {
                engine.start { line ->
                    logs.tryEmit(line)
                    status.value = engine.status
                }
            } catch (t: Throwable) {
                Log.e("TunnelService", "engine crashed", t)
            } finally {
                running.value = false
                status.value = engine.status
            }
        }
        // Sync engine.status into UI-observed StateFlow every 1s.
        statusSyncJob?.cancel()
        statusSyncJob = scope.launch {
            while (true) {
                delay(1000)
                status.value = engine.status
                running.value = engine.running
            }
        }
    }

    private fun stopTunnel() {
        engine.stop()
        job?.cancel()
        statusSyncJob?.cancel()
        job = null
        statusSyncJob = null
        running.value = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart ourselves if the user swipes the app away.
        val restartIntent = Intent(this, TunnelService::class.java).apply { action = ACTION_START }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        } catch (e: Exception) {
            Log.e("TunnelService", "onTaskRemoved restart failed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, TunnelApp.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
