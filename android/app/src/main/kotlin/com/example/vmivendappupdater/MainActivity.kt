package com.example.vmivendappupdater

import android.content.Context
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val channelName = "com.example.vmivendappupdater/updater"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val prefs = getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
        migrateLegacyWorkQueue(this)
        scheduleUpdates(this)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "saveConfig" -> {
                        val intervalMin = (call.argument<Int>("checkIntervalMinutes") ?: 360)
                        prefs.edit()
                            .putString("check_url", call.argument<String>("checkUrl") ?: "")
                            .putString("target_package", call.argument<String>("packageName") ?: "")
                            .putString("download_url", call.argument<String>("downloadUrl") ?: "")
                            .putInt("check_interval_minutes", intervalMin)
                            .apply()
                        scheduleUpdates(this)
                        UpdateForegroundService.start(this)
                        result.success(true)
                    }

                    "getConfig" -> {
                        result.success(
                            mapOf(
                                "checkUrl" to (prefs.getString("check_url", "") ?: ""),
                                "packageName" to (prefs.getString("target_package", "") ?: ""),
                                "downloadUrl" to (prefs.getString("download_url", "") ?: ""),
                                "savedHash" to (prefs.getString("saved_hash", "") ?: ""),
                                "checkIntervalMinutes" to prefs.getInt("check_interval_minutes", 360),
                            )
                        )
                    }

                    "checkNow" -> {
                        enqueueImmediateUpdate(this, ignoreCooldown = true)
                        result.success(true)
                    }

                    "clearHash" -> {
                        prefs.edit()
                            .remove("saved_hash")
                            .remove(UpdateWorker.PREF_RETRY_BLOCKED_UNTIL)
                            .apply()
                        result.success(true)
                    }

                    "getStatus" -> {
                        result.success(
                            mapOf(
                                "state" to (prefs.getString("status_state", "idle") ?: "idle"),
                                "message" to (prefs.getString("status_message", "Waiting for first check.") ?: ""),
                                "statusTime" to prefs.getLong("status_time", 0L).toString(),
                                "lastCheckTime" to prefs.getLong("last_check_time", 0L).toString(),
                                "downloadProgress" to prefs.getInt("download_progress", -1),
                                "lastInstalledVersion" to (prefs.getString("last_installed_version", "") ?: ""),
                                "log" to (prefs.getString("status_log", "[]") ?: "[]"),
                            )
                        )
                    }

                    else -> result.notImplemented()
                }
            }

        // Check for updates immediately on launch if configured
        val checkUrl = prefs.getString("check_url", "") ?: ""
        if (checkUrl.isNotEmpty()) {
            enqueueImmediateUpdate(this)
        }
    }
}
