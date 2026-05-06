package com.example.vmivendappupdater

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val channelName = "com.example.vmivendappupdater/updater"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val prefs = getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "saveConfig" -> {
                        prefs.edit()
                            .putString("check_url", call.argument<String>("checkUrl") ?: "")
                            .putString("target_package", call.argument<String>("packageName") ?: "")
                            .putString("download_url", call.argument<String>("downloadUrl") ?: "")
                            .apply()
                        scheduleUpdates(this)
                        result.success(true)
                    }

                    "getConfig" -> {
                        result.success(
                            mapOf(
                                "checkUrl" to (prefs.getString("check_url", "") ?: ""),
                                "packageName" to (prefs.getString("target_package", "") ?: ""),
                                "downloadUrl" to (prefs.getString("download_url", "") ?: ""),
                                "savedHash" to (prefs.getString("saved_hash", "") ?: ""),
                            )
                        )
                    }

                    "checkNow" -> {
                        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                            .setConstraints(
                                Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build()
                            )
                            .build()
                        WorkManager.getInstance(this).enqueue(request)
                        result.success(true)
                    }

                    "clearHash" -> {
                        prefs.edit().remove("saved_hash").apply()
                        result.success(true)
                    }

                    else -> result.notImplemented()
                }
            }
    }
}
