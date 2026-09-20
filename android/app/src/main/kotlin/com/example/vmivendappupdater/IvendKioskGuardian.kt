package com.example.vmivendappupdater

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a recovery HOME available independently of the vending application.
 * Device Owner supplies lock-task containment; root-only operation is polling.
 */
object IvendKioskGuardian {
    private const val PREFS = "updater_prefs"
    private const val KEY_LAST_HOME_ENFORCEMENT = "kiosk_last_home_enforcement"
    private const val KEY_HOME_MISMATCH_ACTIVE = "kiosk_home_mismatch_active"
    private const val KEY_LAST_CRASH_CAPTURE = "kiosk_last_crash_capture"
    private const val KEY_LAST_CRASH_FINGERPRINT = "kiosk_last_crash_fingerprint"
    private const val HOME_ENFORCEMENT_INTERVAL_MS = 5 * 60 * 1000L
    private const val CRASH_CAPTURE_INTERVAL_MS = 60 * 1000L
    private const val RECOVERY_UPDATE_INTERVAL_MS = 60 * 1000L
    private const val STARTUP_GRACE_MS = 30 * 1000L
    private const val KEY_LAST_RECOVERY_UPDATE = "kiosk_last_recovery_update"
    private val attempts = RecoveryAttempts()
    private val timing = GuardianTiming()
    private val healthBackoff = HealthCheckBackoff()
    @Volatile var healthMessage = "Waiting for health check."
        private set
    private var lastProcessCheck = 0L
    private var notResponding = false
    private var processRunning = false
    private var startupObservedAt = Long.MIN_VALUE
    private val diagnostics = Executors.newSingleThreadExecutor()
    private val diagnosticsRunning = AtomicBoolean(false)

    fun beginMaintenance(context: Context, durationMs: Long = 2 * 60 * 1000L) {
        KioskSession.begin(context, "install", durationMs)
    }

    @Synchronized
    fun endMaintenance(context: Context, reason: String, repaired: Boolean = true) {
        KioskSession.end(context, "install")
        if (repaired) {
            attempts.reset()
            prefs(context).edit().remove("recovery_pending").apply()
        }
        enforce(context, "maintenance complete: $reason", force = true)
    }

    /** Called on boot and repeatedly by the updater foreground service. */
    @Synchronized
    fun enforce(context: Context, reason: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val preferences = prefs(context)
        val targetPackage = ManagedTarget.packageName(context)
        val homeComponent = KioskPolicy.homeComponent(context)

        try {
            KioskPolicy.ensureOwner(context)
            // Configure the fallback even if iVend is missing. Reapplying this
            // small local policy also removes Settings promptly at expiry.
            KioskPolicy.configure(context, targetPackage, homeComponent)
            KioskPolicy.reportLock(context)
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            val homeMismatch = home?.activityInfo?.let { ComponentName(it.packageName, it.name) } != homeComponent
            if (force || timing.due("home", SystemClock.elapsedRealtime(),
                    if (homeMismatch) 30_000L else HOME_ENFORCEMENT_INTERVAL_MS)) {
                configureDeviceForIvend(context, targetPackage, homeComponent)
                preferences.edit().putLong(KEY_LAST_HOME_ENFORCEMENT, now).apply()
            }
            // No event, including package changes, cancels an approved window.
            if (KioskSession.active(context, "operator")) {
                attempts.observeForeground(SystemClock.elapsedRealtime(), false)
                // Settings remains available for the full authorized window,
                // but pressing HOME must not strand the operator on our cover.
                if (UpdateProgressActivity.visible) {
                    val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
                    if (intent != null) {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                return
            }
            if (KioskSession.active(context, "self_install")) {
                attempts.observeForeground(SystemClock.elapsedRealtime(), false)
                showRecovery(context)
                return
            }
            if (KioskSession.active(context, "install")) {
                attempts.observeForeground(SystemClock.elapsedRealtime(), false)
                showRecovery(context)
                return
            }
            val recoveryPending = preferences.getBoolean("recovery_pending", false)
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
                showRecovery(context)
                requestRecoveryUpdate(context, preferences, "iVend is missing")
                return
            }
            val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPackage)
            val launchComponent = launchIntent?.component
            if (launchIntent == null || launchComponent == null) {
                appendEvent(context, "No launcher activity found for $targetPackage")
                showRecovery(context)
                requestRecoveryUpdate(context, preferences, "iVend has no launcher")
                return
            }
            val resolvedHome = home?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: "none"
            if (homeMismatch && !preferences.getBoolean(KEY_HOME_MISMATCH_ACTIVE, false)) {
                preferences.edit().putBoolean(KEY_HOME_MISMATCH_ACTIVE, true).apply()
                if (CriticalEventQueue.enqueue(
                        context,
                        "home_app_changed",
                        "Android HOME changed to $resolvedHome. Recovery HOME restored."
                    )) {
                    appendEvent(context, "HOME mismatch recorded locally: $resolvedHome")
                }
            } else if (!homeMismatch && preferences.getBoolean(KEY_HOME_MISMATCH_ACTIVE, false)) {
                preferences.edit().remove(KEY_HOME_MISMATCH_ACTIVE).apply()
                appendEvent(context, "iVend confirmed as Android HOME app again.")
            }
            // A synchronous ContentProvider call can hang forever inside an ANR.
            // Inspect actual window focus through a bounded root command instead.
            val elapsed = SystemClock.elapsedRealtime()
            if (!healthBackoff.ready(elapsed)) return
            var processCheckFresh = false
            var processCheckFailed = false
            if (elapsed - lastProcessCheck >= 10_000L) {
                lastProcessCheck = elapsed
                val processes = RootShell.run("dumpsys activity processes", 5)
                if (processes.succeeded) {
                    processCheckFresh = true
                    processRunning = ProcessHealth.isRunning(processes.output, targetPackage)
                    notResponding = ProcessHealth.isNotResponding(processes.output, targetPackage)
                } else processCheckFailed = true
            }
            val focus = ivendForeground(targetPackage)
            val observationTime = SystemClock.elapsedRealtime()
            val delay = healthBackoff.observed(observationTime, focus != null && !processCheckFailed)
            if (focus == null || processCheckFailed) {
                attempts.observeForeground(observationTime, false)
                healthMessage = "Health check inconclusive; retry in ${delay / 1000}s. No restart requested."
                return
            }
            val foreground = !notResponding && focus
            val needsLock = KioskPolicy.needsLock(context)
            val stable = attempts.observeForeground(observationTime, foreground && !needsLock)
            healthMessage = if (foreground) "iVend in foreground; failed launches ${attempts.count}/10." else
                "iVend not healthy/foreground; failed launches ${attempts.count}/10."
            if (foreground) {
                startupObservedAt = Long.MIN_VALUE
                if (stable && recoveryPending) preferences.edit().remove("recovery_pending").apply()
                UpdateProgressActivity.dismissIfOpen()
            } else if (recoveryPending) {
                showRecovery(context)
                requestRecoveryUpdate(context, preferences, "Waiting for iVend repair")
                return
            }
            val waitingForStartup = !foreground && processRunning && !notResponding &&
                startupGraceActive(context, elapsed)
            if ((!foreground && !waitingForStartup) || needsLock) {
                if (!attempts.mayLaunch(SystemClock.elapsedRealtime())) {
                    if (attempts.repairRequired) {
                        preferences.edit().putBoolean("recovery_pending", true)
                            .remove(UpdateWorker.PREF_RETRY_BLOCKED_UNTIL).commit()
                        RootShell.run("am force-stop $targetPackage", 5)
                        showRecovery(context)
                        requestRecoveryUpdate(context, preferences, "Ten failed launches within five minutes; repairing cached APK")
                    }
                    return
                }
                appendEvent(context, "Opening iVend: attempt ${attempts.count}/10 in five minutes")
                // Close a crash/ANR dialog before relaunching the broken process.
                // Never kill based on an old or failed process observation.
                if (processCheckFresh && notResponding) RootShell.run("am force-stop $targetPackage", 5)
                notResponding = false
                val intent = Intent(Intent.ACTION_MAIN).setComponent(launchComponent)
                val managedLaunch = runCatching { KioskPolicy.launch(context, intent) }.getOrDefault(false)
                val result = if (managedLaunch) "managed lock task" else RootShell.exec(
                    "am start -n ${launchComponent.flattenToShortString()} -a android.intent.action.MAIN " +
                        "-c android.intent.category.HOME -f 0x14000000"
                )
                startupObservedAt = elapsed
                processRunning = true
                appendEvent(context, "Kiosk recovery ($reason): $result")
            }
            if (!foreground && diagnosticsRunning.compareAndSet(false, true)) diagnostics.execute {
                try {
                    captureCrashLogIfNeeded(context, now, targetPackage)
                } finally { diagnosticsRunning.set(false) }
            }
        } catch (error: Exception) {
            appendEvent(context, "Kiosk guardian error: ${error.message ?: error.javaClass.simpleName}")
            runCatching { showRecovery(context) }
        }
    }

    private fun startupGraceActive(context: Context, elapsed: Long): Boolean {
        // Only an actual launch grants startup time. Leaving a healthy app for
        // Settings must not create a fresh thirty-second escape window.
        return startupObservedAt != Long.MIN_VALUE && elapsed - startupObservedAt < STARTUP_GRACE_MS
    }

    private fun requestRecoveryUpdate(
        context: Context,
        preferences: android.content.SharedPreferences,
        reason: String
    ) {
        val now = SystemClock.elapsedRealtime()
        val last = preferences.getLong(KEY_LAST_RECOVERY_UPDATE, Long.MIN_VALUE)
        if (!RecoveryPolicy.updateDue(now, last, RECOVERY_UPDATE_INTERVAL_MS)) return
        preferences.edit().putLong(KEY_LAST_RECOVERY_UPDATE, now).apply()
        if (!preferences.getBoolean("recovery_pending", false)) {
            preferences.edit().remove(UpdateWorker.PREF_RETRY_BLOCKED_UNTIL).apply()
        }
        if (System.currentTimeMillis() < preferences.getLong(UpdateWorker.PREF_RETRY_BLOCKED_UNTIL, 0L)) return
        preferences.edit().putBoolean("recovery_pending", true).apply()
        enqueueImmediateUpdate(context, forceReinstall = true)
        appendEvent(context, "$reason; immediate APK recovery requested.")
    }

    fun showRecovery(context: Context): Boolean {
        if (UpdateProgressActivity.visible) return true
        val intent = Intent(context, UpdateProgressActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { KioskPolicy.launch(context, intent) }.getOrDefault(false)) return true
        val result = RootShell.run("am start -W -n com.example.vmivendappupdater/.UpdateProgressActivity -f 0x14000000", 5)
        if (result.succeeded && !result.output.contains("Error:")) return true
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** The installer must see the cover resumed before it can stop iVend. */
    fun awaitRecovery(context: Context): Boolean {
        if (!showRecovery(context)) return false
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (!UpdateProgressActivity.visible && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        return UpdateProgressActivity.visible
    }

    private fun configureDeviceForIvend(context: Context, targetPackage: String, launchComponent: ComponentName) {
        NavigationPolicy.apply(context, targetPackage)
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
            commands += "cmd role add-role-holder --user 0 android.app.role.HOME ${context.packageName}"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            commands += "appops set $targetPackage MANAGE_EXTERNAL_STORAGE allow"
        }
        val output = RootShell.run(commands.joinToString("\n") { command ->
            "$command || echo ${RootShell.quote("Setup failed: $command")}" }, 10)
        if (!output.succeeded || output.output.contains("Setup failed:")) {
            appendEvent(context, "HOME/permission repair: ${output.output.take(1200)} (timeout=${output.timedOut})")
        }
    }

    private fun ivendForeground(targetPackage: String): Boolean? {
        val state = RootShell.run("dumpsys window windows", 5)
        if (!state.succeeded) return null
        ProcessHealth.foreground(state.output, targetPackage)?.let { return it }
        // Old firmware may omit window focus. Only then request the activity dump.
        val activities = RootShell.run("dumpsys activity activities", 5)
        return if (activities.succeeded) ProcessHealth.foreground(activities.output, targetPackage) else null
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
            appendEvent(context, "Recorded iVend diagnostics locally.")
        }
    }

    private fun appendEvent(context: Context, message: String) {
        Log.i("IvendKioskGuardian", message)
        UpdaterLog.append(context, message)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
