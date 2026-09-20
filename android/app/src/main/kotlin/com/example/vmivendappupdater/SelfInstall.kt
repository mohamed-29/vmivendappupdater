package com.example.vmivendappupdater

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Installation is owned by Android/root, not by the process being replaced. */
object SelfInstall {
    private var lastReconcile = 0L
    private fun prefs(context: Context) = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    fun status(context: Context, state: String, message: String) {
        prefs(context).edit().putString("self_status_state", state)
            .putString("self_status_message", message).putLong("self_status_time", System.currentTimeMillis()).commit()
        UpdaterLog.append(context, "Updater: $message")
    }

    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(32768)
            var count: Int
            while (input.read(bytes).also { count = it } != -1) digest.update(bytes, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun start(context: Context, apk: File, hash: String) {
        KioskSession.begin(context, "self_install", 3 * 60_000L)
        File(context.filesDir, "self-install-result.txt").delete()
        lastReconcile = 0L
        prefs(context).edit().putString(SelfUpdateWorker.PREF_PENDING_HASH, hash)
            .remove("self_install_session").putLong("self_install_started", System.currentTimeMillis()).commit()
        status(context, "installing", "Installing updater. Android will restart this process; awaiting confirmation (up to 3 minutes).")
        val policy = context.getSystemService(DevicePolicyManager::class.java)
        if (policy.isDeviceOwnerApp(context.packageName)) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val id = installer.createSession(params)
            prefs(context).edit().putInt("self_install_session", id).commit()
            try {
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        apk.inputStream().use { it.copyTo(output) }
                        session.fsync(output)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val callback = PendingIntent.getBroadcast(context, id,
                        Intent(context, SelfInstallReceiver::class.java).setAction("${context.packageName}.SELF_INSTALL_RESULT"), flags)
                    session.commit(callback.intentSender)
                }
            } catch (error: Exception) {
                runCatching { installer.abandonSession(id) }
                fail(context, "Could not submit updater install: ${error.message}")
                throw error
            }
        } else {
            // Root-only machines need an independent shell: pm replacing this
            // package terminates the worker before it can record its result.
            val result = File(context.filesDir, "self-install-result.txt")
            result.delete()
            val script = File(context.filesDir, "self-install.sh")
            script.writeText("#!/system/bin/sh\n" +
                "pm install -r -d ${RootShell.quote(apk.absolutePath)} > ${RootShell.quote(result.absolutePath)} 2>&1\n" +
                "chmod 644 ${RootShell.quote(result.absolutePath)}\n" +
                "am start-foreground-service -n ${context.packageName}/.UpdateForegroundService\n")
            val launched = RootShell.run("nohup sh ${RootShell.quote(script.absolutePath)} </dev/null >/dev/null 2>&1 &", 10)
            check(launched.succeeded) { "Could not start root installer: ${launched.output}" }
        }
    }

    @Synchronized
    fun reconcile(context: Context) {
        val p = prefs(context)
        val pending = p.getString(SelfUpdateWorker.PREF_PENDING_HASH, "").orEmpty()
        if (pending.isBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastReconcile != 0L && now - lastReconcile < 10_000L) return
        lastReconcile = now
        if (hash(File(context.applicationInfo.sourceDir)).equals(pending, true)) {
            p.edit().putString(SelfUpdateWorker.PREF_SAVED_HASH, pending)
                .remove(SelfUpdateWorker.PREF_PENDING_HASH).remove("self_install_session").commit()
            status(context, "updated", "Updater installed successfully. Running version ${BuildConfig.VERSION_NAME}.")
            KioskSession.end(context, "self_install")
            AppUpdateGate.finish(context)
            return
        }
        val result = File(context.filesDir, "self-install-result.txt")
        val output = runCatching { if (result.isFile) result.readText().take(2048) else "" }.getOrDefault("")
        if (output.contains("Failure [")) {
            fail(context, "Android rejected updater installation: $output")
        } else if (System.currentTimeMillis() - p.getLong("self_install_started", 0) >= 180_000L) {
            val id = p.getInt("self_install_session", -1)
            if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
            fail(context, "No confirmed replacement after 3 minutes; version ${BuildConfig.VERSION_NAME} is still running. ${output.take(500)}")
        }
    }

    fun fail(context: Context, message: String) {
        prefs(context).edit().remove(SelfUpdateWorker.PREF_PENDING_HASH).remove("self_install_session").commit()
        status(context, "error", message)
        KioskSession.end(context, "self_install")
        AppUpdateGate.finish(context)
    }
}

class SelfInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (prefs.getString(SelfUpdateWorker.PREF_PENDING_HASH, "").isNullOrBlank() ||
            sessionId < 0 || sessionId != prefs.getInt("self_install_session", -1)) return
        val pending = goAsync()
        Thread {
            try {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_SUCCESS -> SelfInstall.reconcile(context)
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                        if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                        SelfInstall.fail(context, "Android requires installation approval; unattended self-update was not permitted.")
                    }
                    else -> SelfInstall.fail(context, "Installation failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                }
                runCatching { UpdateForegroundService.start(context) }
            } finally { pending.finish() }
        }.start()
    }
}
