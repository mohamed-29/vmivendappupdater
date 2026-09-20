package com.example.vmivendappupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        // WorkManager and these preferences use credential-protected storage.
        // Do not access them from LOCKED_BOOT_COMPLETED.
        migrateLegacyWorkQueue(context)
        scheduleUpdates(context)
        runCatching { UpdateForegroundService.start(context) }.onFailure {
            val pending = goAsync()
            Thread {
                try {
                    Log.i("BootReceiver", RootShell.exec(
                        "am start-foreground-service -n com.example.vmivendappupdater/.UpdateForegroundService", 5))
                } finally { pending.finish() }
            }.start()
        }
    }
}

private const val IMMEDIATE_UPDATE_WORK = "apk_update_check_immediate"
private const val IMMEDIATE_RECOVERY_WORK = "apk_recovery_immediate"
private const val PERIODIC_UPDATE_WORK = "apk_update_check"
private const val UPDATE_WORK_TAG = "apk_update_work"
private const val IMMEDIATE_SELF_UPDATE_WORK = "updater_self_update_immediate"
private const val PERIODIC_SELF_UPDATE_WORK = "updater_self_update"
private const val SELF_UPDATE_WORK_TAG = "updater_self_update_work"
private const val WORK_QUEUE_SCHEMA = 1

fun migrateLegacyWorkQueue(context: Context) {
    ManagedTarget.initialize(context)
    val prefs = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    if (prefs.getInt("work_queue_schema", 0) >= WORK_QUEUE_SCHEMA) return

    // Older versions enqueued anonymous one-time workers on every broadcast.
    // They cannot be addressed by name or tag, so clear them once on upgrade.
    WorkManager.getInstance(context).cancelAllWork()
    prefs.edit().putInt("work_queue_schema", WORK_QUEUE_SCHEMA).commit()
}

fun enqueueImmediateUpdate(
    context: Context,
    ignoreCooldown: Boolean = false,
    forceReinstall: Boolean = false,
    upgradeVersion: Int? = null
) {
    val prefs = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    val blockedUntil = prefs.getLong(UpdateWorker.PREF_RETRY_BLOCKED_UNTIL, 0L)
    if (!ignoreCooldown && System.currentTimeMillis() < blockedUntil) return

    val constraints = Constraints.Builder()
        .setRequiredNetworkType(if (forceReinstall) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED)
        .build()

    val request = OneTimeWorkRequestBuilder<UpdateWorker>()
        .setConstraints(constraints)
        .setInputData(workDataOf(
            UpdateWorker.KEY_IGNORE_COOLDOWN to ignoreCooldown,
            UpdateWorker.KEY_FORCE_REINSTALL to forceReinstall
        ))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .addTag(UPDATE_WORK_TAG)
        .build()

    // Repeated broadcasts, UI taps and app launches share one update cycle.
    WorkManager.getInstance(context).enqueueUniqueWork(
        if (forceReinstall) IMMEDIATE_RECOVERY_WORK else
            upgradeVersion?.let { "${IMMEDIATE_UPDATE_WORK}_upgrade_$it" } ?: IMMEDIATE_UPDATE_WORK,
        ExistingWorkPolicy.KEEP,
        request
    )
}

/** Fresh work bypasses inherited scheduler backoff without cancelling an installer. */
@Synchronized
fun checkAfterUpdaterUpgrade(context: Context) {
    val prefs = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    if (prefs.getInt("last_upgrade_check_version", 0) >= BuildConfig.VERSION_CODE) return
    ManagedTarget.initialize(context)
    if (prefs.getString("check_url", "").isNullOrBlank()) return
    enqueueImmediateUpdate(context, ignoreCooldown = true, upgradeVersion = BuildConfig.VERSION_CODE)
    prefs.edit().putInt("last_upgrade_check_version", BuildConfig.VERSION_CODE).commit()
    UpdaterLog.append(context, "Updater ${BuildConfig.VERSION_NAME}: fresh iVend update check queued; previous retry delays do not apply.")
}

fun enqueueImmediateSelfUpdate(context: Context) {
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val request = OneTimeWorkRequestBuilder<SelfUpdateWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .addTag(SELF_UPDATE_WORK_TAG)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        IMMEDIATE_SELF_UPDATE_WORK,
        ExistingWorkPolicy.KEEP,
        request
    )
}

fun scheduleUpdates(context: Context) {
    val prefs = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    val intervalMinutes = prefs.getInt("check_interval_minutes", 360).coerceIn(15, 1440).toLong() // default 6h

    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val request = PeriodicWorkRequestBuilder<UpdateWorker>(intervalMinutes, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .addTag(UPDATE_WORK_TAG)
        .build()

    // Change interval/constraints without restarting a pending or active run.
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PERIODIC_UPDATE_WORK,
        ExistingPeriodicWorkPolicy.UPDATE,
        request
    )

    val selfRequest = PeriodicWorkRequestBuilder<SelfUpdateWorker>(intervalMinutes, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .setInitialDelay(5, TimeUnit.MINUTES)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .addTag(SELF_UPDATE_WORK_TAG)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PERIODIC_SELF_UPDATE_WORK,
        ExistingPeriodicWorkPolicy.UPDATE,
        selfRequest
    )
}
