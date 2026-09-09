package com.example.vmivendappupdater

import android.content.Context
import android.provider.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class VmmcLogClient(
    private val endpoint: String,
    private val token: String,
    private val responseCode: (Request) -> Int = { request ->
        defaultClient.newCall(request).execute().use { response -> response.code }
    }
) {
    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    enum class Result { UPLOADED, ALREADY_EXISTS, RETRY }

    fun upload(filename: String, content: String): Result {
        if (token.isBlank()) return Result.RETRY
        val body = JSONObject()
            .put("filename", filename)
            .put("content", content)
            .put("encoding", "utf-8")
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            // Use the API's dedicated token header. This avoids vendor/OEM
            // proxies that consume or rewrite the generic Authorization header.
            .header("X-Log-Token", token)
            .post(body)
            .build()
        return try {
            when (responseCode(request)) {
                201 -> Result.UPLOADED
                409 -> Result.ALREADY_EXISTS
                else -> Result.RETRY
            }
        } catch (_: Exception) {
            Result.RETRY
        }
    }
}

object DiagnosticLogUploader {
    private const val PREFS = "updater_prefs"
    private const val KEY_CRASH_LOG = "ivend_crash_log"
    private const val KEY_NEXT_ATTEMPT = "log_upload_next_attempt"
    private const val RETRY_DELAY_MS = 5 * 60 * 1000L

    @Synchronized
    fun uploadPending(context: Context) {
        if (BuildConfig.VMMC_LOG_TOKEN.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now < prefs.getLong(KEY_NEXT_ATTEMPT, 0L)) return
        val queued = runCatching { JSONArray(prefs.getString(KEY_CRASH_LOG, "[]")) }
            .getOrDefault(JSONArray())
        if (queued.length() == 0) return

        val entry = queued.optJSONObject(0) ?: run {
            removeFirst(prefs, queued)
            return
        }
        val timestamp = entry.optLong("timestamp", now)
        val details = entry.optString("details", "")
        val category = entry.optString("category", "critical_diagnostic")
        val machineId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty().ifBlank { "unknown" }
        val fingerprint = sha256("$timestamp\n$details").take(16)
        val filename = "vmmc-$machineId-$timestamp-$fingerprint.json"
        val content = JSONObject()
            .put("level", "error")
            .put("category", category)
            .put("machine_id", machineId)
            .put("target_version", ManagedTarget.targetName(context))
            .put("package_name", ManagedTarget.packageName(context))
            .put("timestamp_ms", timestamp)
            .put("message", details)
            .toString()

        when (VmmcLogClient(BuildConfig.VMMC_LOG_UPLOAD_URL, BuildConfig.VMMC_LOG_TOKEN)
            .upload(filename, content)) {
            VmmcLogClient.Result.UPLOADED,
            VmmcLogClient.Result.ALREADY_EXISTS -> {
                removeFirst(prefs, queued)
                prefs.edit().remove(KEY_NEXT_ATTEMPT).apply()
                UpdaterLog.append(context, "Uploaded queued crash diagnostics to VMMC.")
            }
            VmmcLogClient.Result.RETRY -> {
                prefs.edit().putLong(KEY_NEXT_ATTEMPT, now + RETRY_DELAY_MS).apply()
                UpdaterLog.append(context, "VMMC diagnostic upload failed; retry scheduled.")
            }
        }
    }

    private fun removeFirst(
        prefs: android.content.SharedPreferences,
        queued: JSONArray
    ) {
        val remaining = JSONArray()
        for (index in 1 until queued.length()) remaining.put(queued.get(index))
        prefs.edit().putString(KEY_CRASH_LOG, remaining.toString()).apply()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
