package com.example.vmivendappupdater

import android.app.ActivityOptions
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings

class KioskAdminReceiver : DeviceAdminReceiver()

/** Enroll rooted machines automatically; verify ownership before applying policy. */
object KioskPolicy {
    private val enrollment = OwnerEnrollment()
    private var appliedKey: String? = null
    private var appliedAt = 0L

    fun ensureOwner(context: Context): Boolean {
        val policy = context.getSystemService(DevicePolicyManager::class.java)
        return enrollment.ensure(android.os.SystemClock.elapsedRealtime(),
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN),
            { policy.isDeviceOwnerApp(context.packageName) },
            { RootShell.exec("dpm set-device-owner --user current " +
                RootShell.quote(ComponentName(context, KioskAdminReceiver::class.java).flattenToShortString()), 15) },
            { report(context, it) })
    }

    fun report(context: Context, message: String) {
        val prefs = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
        if (prefs.getString("kiosk_status", "") == message) return
        prefs.edit().putString("kiosk_status", message).apply()
        UpdaterLog.append(context, message)
    }

    fun reportLock(context: Context) {
        if (!context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)) return
        val locked = context.getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
        report(context, if (KioskSession.active(context, "operator")) "Operator maintenance active (up to five minutes)."
            else if (locked) "Kiosk locked — Device Owner verified."
            else "Device Owner verified; waiting for kiosk lock to activate.")
    }
    fun homeComponent(context: Context) = ComponentName(context, UpdateProgressActivity::class.java)

    fun needsLock(context: Context): Boolean = Build.VERSION.SDK_INT >= 28 &&
        context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName) &&
        context.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_LOCKED

    @Synchronized
    fun configure(context: Context, targetPackage: String, launchComponent: ComponentName, force: Boolean = false): Boolean {
        val policy = context.getSystemService(DevicePolicyManager::class.java)
        if (!policy.isDeviceOwnerApp(context.packageName)) return false
        val admin = ComponentName(context, KioskAdminReceiver::class.java)
        val packages = mutableListOf(context.packageName, targetPackage)
        if (KioskSession.active(context, "operator")) {
            context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
                ?.activityInfo?.packageName?.let { packages.add(it) }
        }
        val key = packages.joinToString(",") + launchComponent.flattenToString()
        val now = android.os.SystemClock.elapsedRealtime()
        // Maintenance changes apply immediately. Periodic refresh recovers external policy changes.
        if (!force && appliedKey == key && now - appliedAt < 5 * 60_000L) return true
        policy.setLockTaskPackages(admin, packages.toTypedArray())
        if (Build.VERSION.SDK_INT >= 28) {
            policy.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            policy.addUserRestriction(admin, UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS)
        }
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        policy.addPersistentPreferredActivity(admin, home,
            launchComponent)
        appliedKey = key
        appliedAt = now
        return true
    }

    fun launch(context: Context, intent: Intent): Boolean {
        val policy = context.getSystemService(DevicePolicyManager::class.java)
        if (Build.VERSION.SDK_INT < 28 || !policy.isDeviceOwnerApp(context.packageName)) return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
        return true
    }
}

