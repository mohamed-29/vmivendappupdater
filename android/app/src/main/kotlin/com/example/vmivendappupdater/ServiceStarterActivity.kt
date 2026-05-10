package com.example.vmivendappupdater

import android.app.Activity
import android.os.Bundle

/**
 * Transparent trampoline activity that starts the foreground service and immediately finishes.
 * Needed on Android 12+ where starting a foreground service from a BroadcastReceiver
 * (background context) is blocked.
 */
class ServiceStarterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateForegroundService.start(this)
        finish()
    }
}
