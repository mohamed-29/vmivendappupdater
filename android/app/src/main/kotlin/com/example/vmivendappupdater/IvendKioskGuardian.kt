package com.example.vmivendappupdater

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Root-backed kiosk recovery for the read-only iVend application.
 *
 * Device Owner enrollment enables real lock-task mode on Android 9+ without
 * editing iVend. Root-only operation is recovery polling, not an OS lock.
 */
object IvendKioskGuardian {
    private const val PREFS = "updater_prefs"
    private const val KEY_MAINTENANCE_UNTIL = "kiosk_maintenance_until"
    private const val KEY_LAST_HOME_ENFORCEMENT = "kiosk_last_home_enforcement"
    private const val KEY_HOME_MISMATCH_ACTIVE = "kiosk_home_mismatch_active"
    private const val KEY_LAST_CRASH_CAPTURE = "kiosk_last_crash_capture"
    private const val KEY_LAST_CRASH_FINGERPRINT = "kiosk_last_crash_fingerprint"
    private const val HOME_ENFORCEMENT_INTERVAL_MS = 30 * 1000L
    private const val CRASH_CAPTURE_INTERVAL_MS = 60 * 1000L

    fun beginMaintenance(context: Context, durationMs: Long = 2 * 60 * 1000L) {
        prefs(context).edit()
            .putLong(KEY_MAINTENANCE_UNTIL, System.currentTimeMillis() + durationMs)
            .apply()
    }

    fun endMaintenance(context: Context, reason: String) {
        prefs(context).edit().remove(KEY_MAINTENANCE_UNTIL).apply()
        enforce(context, "maintenance complete: $reason", force = true)
    }

    /** Called on boot and repeatedly by the updater foreground service. */
    @Synchronized
    fun enforce(context: Context, reason: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val preferences = prefs(context)
        val targetPackage = ManagedTarget.packageName(context)
        val maintenanceActive = !force && now < preferences.getLong(KEY_MAINTENANCE_UNTIL, 0L)

        try {
            @Suppress("DEPRECATION")
            val installed = try {
                context.packageManager.getPackageInfo(targetPackage, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
            if (!installed) {
                CriticalEventQueue.enqueue(
                    context,
                    "managed_app_missing",
                    "Managed application $targetPackage is not installed."
                )
                RootShell.exec("am start -n com.example.vmivendappupdater/.UpdateProgressActivity -f 0x14000000")
                return
            }
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPackage)
            val launchComponent = launchIntent?.component
            if (launchIntent == null || launchComponent == null) {
                appendEvent(context, "No launcher activity found for $targetPackage")
                return
            }
            val resolvedHome = home?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: "none"
            val homeMismatch = home?.activityInfo?.packageName != targetPackage
            if (homeMismatch && !preferences.getBoolean(KEY_HOME_MISMATCH_ACTIVE, false)) {
                preferences.edit().putBoolean(KEY_HOME_MISMATCH_ACTIVE, true).apply()
                if (CriticalEventQueue.enqueue(
                        context,
                        "home_app_changed",
                        "Android HOME changed from $targetPackage to $resolvedHome. iVend HOME recovery started."
                    )) {
                    appendEvent(context, "Crucial HOME mismatch queued for upload: $resolvedHome")
                }
                DiagnosticLogUploader.uploadPending(context)
            } else if (!homeMismatch && preferences.getBoolean(KEY_HOME_MISMATCH_ACTIVE, false)) {
                preferences.edit().remove(KEY_HOME_MISMATCH_ACTIVE).apply()
                appendEvent(context, "iVend confirmed as Android HOME app again.")
            }
            if (force || homeMismatch ||
                now - preferences.getLong(KEY_LAST_HOME_ENFORCEMENT, 0L) >= HOME_ENFORCEMENT_INTERVAL_MS) {
                configureDeviceForIvend(context, targetPackage, launchComponent)
                preferences.edit().putLong(KEY_LAST_HOME_ENFORCEMENT, now).apply()
            }

            // Keep the Android HOME assignment enforced during an APK replacement.
            // Maintenance only suppresses competing foreground launches while the
            // package manager is replacing iVend.
            if (maintenanceActive) {
                DiagnosticLogUploader.uploadPending(context)
                return
            }
            if (!isIvendForeground(targetPackage) || KioskPolicy.needsLock(context)) {
                val intent = Intent(Intent.ACTION_MAIN).setComponent(launchComponent)
                val managedLaunch = runCatching { KioskPolicy.launch(context, intent) }.getOrDefault(false)
                val result = if (managedLaunch) "managed lock task" else RootShell.exec(
                    "am start -n ${launchComponent.flattenToShortString()} -a android.intent.action.MAIN " +
                        "-c android.intent.category.HOME -f 0x14000000"
                )
                appendEvent(context, "Kiosk recovery ($reason): $result")
            }
            captureCrashLogIfNeeded(context, now, targetPackage)
            DiagnosticLogUploader.uploadPending(context)
        } catch (error: Exception) {
            appendEvent(context, "Kiosk guardian error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun configureDeviceForIvend(context: Context, targetPackage: String, launchComponent: ComponentName) {
        runCatching { KioskPolicy.configure(context, targetPackage, launchComponent) }.onFailure {
            appendEvent(context, "Device policy: ${it.javaClass.simpleName}")
        }
        // These commands require root.  Failures are recorded rather than
        // preventing recovery; Android versions expose slightly different APIs.
        val commands = mutableListOf(
            "cmd package set-home-activity --user 0 ${launchComponent.flattenToShortString()}",
            "settings put global show_first_crash_dialog 0",
            "settings put global show_restart_in_crash_dialog 0",
            "settings put secure show_first_crash_dialog 0",
            // Grant every permission iVend declares that can be granted by pm.
            "pm grant $targetPackage android.permission.READ_EXTERNAL_STORAGE",
            "pm grant $targetPackage android.permission.WRITE_EXTERNAL_STORAGE"
        )
        if (Build.VERSION.SDK_INT >= 29) {
            commands += "cmd role add-role-holder --user 0 android.app.role.HOME $targetPackage"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            commands += "appops set $targetPackage MANAGE_EXTERNAL_STORAGE allow"
        }
        commands.forEach { command ->
            val output = RootShell.run(command)
            if (!output.succeeded) appendEvent(context, "Root setup failed: $command (code=${output.code})")
        }
    }

    private fun isIvendForeground(targetPackage: String): Boolean {
        val windowState = RootShell.exec("dumpsys window windows")
        return windowState.lineSequence().any { line ->
            // mFocusedApp can remain iVend while Android's crash dialog owns
            // mCurrentFocus, so intentionally inspect only the actual focus.
            line.contains("mCurrentFocus") &&
                line.contains("$targetPackage/")
        }
    }

    private fun captureCrashLogIfNeeded(context: Context, now: Long, targetPackage: String) {
        val preferences = prefs(context)
        if (now - preferences.getLong(KEY_LAST_CRASH_CAPTURE, 0L) < CRASH_CAPTURE_INTERVAL_MS) return
        preferences.edit().putLong(KEY_LAST_CRASH_CAPTURE, now).apply()

        // Read only a recent bounded tail. Never clear the shared log buffer.
        val raw = RootShell.run("logcat -b crash -d -t 500 -v brief", timeoutSeconds = 10)
        val native = if (raw.succeeded) CrashDiagnostics.nativeCrash(raw.output, targetPackage) else ""
        val system = RootShell.run("logcat -b system -d -t 500 -v brief", timeoutSeconds = 10)
        val anr = if (system.succeeded) CrashDiagnostics.anr(system.output, targetPackage) else ""
        // iVend's ErrorBoundary catches React errors without terminating its
        // process. Read only its own bounded log tail, including Crash entries.
        val appLog = RootShell.run("tail -c 65536 /sdcard/Android/data/$targetPackage/files/app_logs.json", 5)
        val appCritical = if (appLog.succeeded) CriticalDiagnostics.appLog(appLog.output) else ""
        val relevant = listOf(native, anr, appCritical).filter { it.isNotBlank() }.joinToString("\n")
        if (relevant.isBlank()) return

        val fingerprint = relevant.hashCode().toString()
        if (fingerprint == preferences.getString(KEY_LAST_CRASH_FINGERPRINT, "")) return
        preferences.edit().putString(KEY_LAST_CRASH_FINGERPRINT, fingerprint).apply()
        if (CriticalEventQueue.enqueue(context, "critical_diagnostic", relevant)) {
            appendEvent(context, "Captured crucial iVend diagnostics for upload.")
        }
    }

    private fun appendEvent(context: Context, message: String) {
        Log.i("IvendKioskGuardian", message)
        UpdaterLog.append(context, message)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
