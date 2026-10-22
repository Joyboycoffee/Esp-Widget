package dev.gourav.espwidget.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.gourav.espwidget.AppGraph
import dev.gourav.espwidget.MainActivity
import dev.gourav.espwidget.R

class SyncService : Service() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget)
            .setContentTitle("ESP Widget sync")
            .setContentText("Keeping your little display up to date")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                41,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (error: SecurityException) {
            AppGraph.repository.log("Background sync waits for Bluetooth permission")
            stopSelf()
            return START_NOT_STICKY
        }
        AppGraph.engine.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL,
                "Widget sync",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Background Bluetooth sync with your ESP Widget"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL = "esp_widget_sync"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SyncService::class.java),
                )
            }
        }
    }
}
