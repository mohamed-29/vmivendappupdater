package com.example.vmivendappupdater

import android.app.ActivityOptions
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager

class KioskAdminReceiver : DeviceAdminReceiver()

/** Optional managed-device enforcement. Enrollment is a deployment step. */
object KioskPolicy {
    fun needsLock(context: Context): Boolean = Build.VERSION.SDK_INT >= 28 &&
        context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName) &&
        context.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_LOCKED

    fun configure(context: Context, targetPackage: String, launchComponent: ComponentName): Boolean {
        val policy = context.getSystemService(DevicePolicyManager::class.java)
        if (!policy.isDeviceOwnerApp(context.packageName)) return false
        val admin = ComponentName(context, KioskAdminReceiver::class.java)
        policy.setLockTaskPackages(admin, arrayOf(context.packageName, targetPackage))
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
