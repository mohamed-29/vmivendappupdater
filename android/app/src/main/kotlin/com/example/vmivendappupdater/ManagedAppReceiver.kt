package com.example.vmivendappupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Immediately restores the managed HOME app after package-manager events. */
class ManagedAppReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val targetPackage = ManagedTarget.packageName(context)
        if (intent.data?.schemeSpecificPart != targetPackage) return
        val pending = goAsync()
        Thread {
            try {
                runCatching { UpdateForegroundService.start(context) }
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED && !replacing) {
                    CriticalEventQueue.enqueue(
                        context,
                        "managed_app_missing",
                        "Managed HOME application $targetPackage was removed. Immediate recovery started."
                    )
                    enqueueImmediateUpdate(context, ignoreCooldown = true)
                }
                if (intent.action != Intent.ACTION_PACKAGE_REMOVED || !replacing) {
                    IvendKioskGuardian.enforce(
                        context,
                        "managed package event ${intent.action?.substringAfterLast('.')}"
                    )
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
