package com.example.vmivendappupdater

import android.content.Context
import android.net.Uri
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

object AppUpdateGate {
    @Suppress("DEPRECATION")
    fun supportsApproval(context: Context): Boolean {
        val info = try {
            context.packageManager.getPackageInfo("com.ivendapp",
                android.content.pm.PackageManager.GET_PROVIDERS or
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            return false
        }
        // A disabled modern provider must not be mistaken for a legacy app.
        return info.providers.orEmpty().any {
            it.authority?.split(';')?.contains("com.ivendapp.updater") == true
        }
    }

    fun waitingMessage(context: Context): String = if (!supportsApproval(context))
        "Installed iVend has no update-approval support. Waiting on Home cannot approve this update; a legacy upgrade is required."
    else "Update downloaded. iVend has not approved installation: it may be busy, initializing, disconnected, or not responding."
    private val endpoint = Uri.parse("content://com.ivendapp.updater")
    // A wedged Binder call must not also wedge the updater/guardian. One bounded
    // worker avoids accumulating threads if the app never answers.
    private val calls = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>())
    private fun <T> bounded(action: () -> T): T? {
        val task = runCatching { calls.submit<T> { action() } }.getOrNull() ?: return null
        return try { task.get(7, TimeUnit.SECONDS) }
        catch (_: Exception) { task.cancel(true); null }
    }

    fun foreground(context: Context): Boolean? = runCatching {
        context.contentResolver.call(endpoint, "foreground", null, null)?.let {
            if (it.containsKey("foreground")) it.getBoolean("foreground") else null
        }
    }.getOrNull()

    fun prepare(context: Context): Boolean {
        // Old iVend releases cannot reply. Compatibility installation is
        // authorized for these releases only; a modern timeout is still a veto.
        if (!supportsApproval(context)) {
            UpdaterLog.append(context, "Legacy iVend has no approval bridge. Proceeding with automatic compatibility installation; transaction state is unavailable.")
            return true
        }
        return bounded {
            context.contentResolver.call(endpoint, "prepare", null, null)?.getBoolean("ready") == true
        } == true
    }

    fun finish(context: Context) {
        if (!supportsApproval(context)) return
        bounded { context.contentResolver.call(endpoint, "finish", null, null) }
    }
}
