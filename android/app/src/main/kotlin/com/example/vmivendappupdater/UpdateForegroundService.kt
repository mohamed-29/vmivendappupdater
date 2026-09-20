package com.example.vmivendappupdater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Lightweight foreground service that keeps the updater process alive.
 * The actual update work is handled by WorkManager's periodic UpdateWorker.
 * The foreground notification improves process priority; Android may still kill
 * the service. START_STICKY requests a restart but is not an absolute guarantee.
 */
class UpdateForegroundService : Service() {

    private val guardianExecutor = Executors.newSingleThreadScheduledExecutor()
    private var guardianTask: ScheduledFuture<*>? = null

    companion object {
        private const val CHANNEL_ID = "updater_service_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, UpdateForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        ResourceMonitor.start(this)
        checkAfterUpdaterUpgrade(this)
        guardianTask = guardianExecutor.scheduleWithFixedDelay(
            {
                runCatching { SelfInstall.reconcile(this) }
                IvendKioskGuardian.enforce(this, "background health check")
            },
            0,
            2,
            TimeUnit.SECONDS
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Request a restart after process reclamation (not after force-stop).
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the updater task must not reveal the Android launcher.
        guardianExecutor.execute { IvendKioskGuardian.enforce(this, "updater task removed", force = true) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ResourceMonitor.stop()
        guardianTask?.cancel(true)
        guardianExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Updater Service",
                NotificationManager.IMPORTANCE_LOW   // minimal visual intrusion
            ).apply {
                description = "Keeps the app updater running in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("VM iVend Updater")
            .setContentText("Monitoring for app updates")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }
}
