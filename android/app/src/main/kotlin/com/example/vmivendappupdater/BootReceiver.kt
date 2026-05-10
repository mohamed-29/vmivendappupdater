package com.example.vmivendappupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Received ${intent.action} — scheduling updates and starting service")

                // Schedule the periodic update check (always works from BroadcastReceiver)
                scheduleUpdates(context)

                // Start the foreground service via root shell.
                // On Android 12+, normal startForegroundService() and startActivity()
                // are both blocked from a BroadcastReceiver (background context).
                // Since the device is rooted, we bypass this entirely using
                // `am start-foreground-service` via su.
                startServiceViaRoot()
            }
        }
    }

    private fun startServiceViaRoot() {
        Thread {
            try {
                val process = ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start()
                DataOutputStream(process.outputStream).use { os ->
                    os.writeBytes("am start-foreground-service -n com.example.vmivendappupdater/.UpdateForegroundService\n")
                    os.writeBytes("exit\n")
                    os.flush()
                }
                process.inputStream.use { it.readBytes() }
                val finished = process.waitFor(10, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                Log.i(TAG, "Root service start result: exit=${if (finished) process.exitValue() else "TIMEOUT"}")
            } catch (e: Exception) {
                Log.e(TAG, "Root service start failed: ${e.message}")
            }
        }.start()
    }
}

fun scheduleUpdates(context: Context) {
    val prefs = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    val intervalMinutes = prefs.getInt("check_interval_minutes", 360).toLong() // default 6h

    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val request = PeriodicWorkRequestBuilder<UpdateWorker>(intervalMinutes, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()

    // REPLACE so a new interval takes effect immediately
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "apk_update_check",
        ExistingPeriodicWorkPolicy.REPLACE,
        request
    )
}
