package com.example.vmivendappupdater

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()

        const val STATE_IDLE = "idle"
        const val STATE_CHECKING = "checking"
        const val STATE_DOWNLOADING = "downloading"
        const val STATE_VERIFYING = "verifying"
        const val STATE_INSTALLING = "installing"
        const val STATE_UP_TO_DATE = "up_to_date"
        const val STATE_UPDATED = "updated"
        const val STATE_ERROR = "error"
    }

    private val prefs by lazy {
        applicationContext.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val checkUrl = prefs.getString("check_url", "") ?: ""
        val packageName = prefs.getString("target_package", "") ?: ""
        val savedHash = prefs.getString("saved_hash", "") ?: ""

        if (checkUrl.isEmpty() || packageName.isEmpty()) {
            setStatus(STATE_ERROR, "Not configured — open app and set Check URL and Package Name.")
            return@withContext Result.success()
        }

        setStatus(STATE_CHECKING, "Checking for updates…")
        log("Contacting server: $checkUrl")

        val (serverHash, downloadUrl) = try {
            fetchHashInfo(checkUrl)
        } catch (e: Exception) {
            setStatus(STATE_ERROR, "Server unreachable: ${e.message}")
            log("Error: ${e.message}")
            return@withContext Result.retry()
        }

        if (serverHash.isEmpty()) {
            setStatus(STATE_ERROR, "Server returned empty hash.")
            log("Error: empty hash in server response")
            return@withContext Result.retry()
        }

        log("Server hash: ${serverHash.take(16)}…")

        if (serverHash == savedHash) {
            setStatus(STATE_UP_TO_DATE, "App is up to date.")
            log("Hash matches — no update needed.")
            prefs.edit().putLong("last_check_time", System.currentTimeMillis()).apply()
            return@withContext Result.success()
        }

        log("New hash detected — starting download.")
        setStatus(STATE_DOWNLOADING, "Downloading update…", progress = 0)

        val apkFile = File(applicationContext.filesDir, "update.apk")
        val downloaded = try {
            downloadApk(downloadUrl.ifEmpty { checkUrl }, apkFile)
        } catch (e: Exception) {
            apkFile.delete()
            setStatus(STATE_ERROR, "Download failed: ${e.message}")
            log("Download error: ${e.message}")
            return@withContext Result.retry()
        }

        if (!downloaded) {
            apkFile.delete()
            setStatus(STATE_ERROR, "Download failed — bad server response.")
            log("Download failed: non-2xx or empty body")
            return@withContext Result.retry()
        }

        setStatus(STATE_VERIFYING, "Verifying file integrity…")
        log("Verifying SHA-256…")
        val fileHash = sha256(apkFile)

        if (fileHash != serverHash) {
            apkFile.delete()
            setStatus(STATE_ERROR, "Hash mismatch — corrupted download. Will retry.")
            log("Hash mismatch! Expected ${serverHash.take(16)}… got ${fileHash.take(16)}…")
            return@withContext Result.retry()
        }

        log("Hash verified ✓")
        setStatus(STATE_INSTALLING, "Installing update via root…")
        log("Running: pm install -r ${apkFile.absolutePath}")

        val installed = RootInstaller.install(apkFile.absolutePath)
        apkFile.delete()

        if (!installed) {
            setStatus(STATE_ERROR, "Installation failed — check root permissions.")
            log("pm install returned non-zero exit code.")
            return@withContext Result.retry()
        }

        prefs.edit()
            .putString("saved_hash", serverHash)
            .putLong("last_check_time", System.currentTimeMillis())
            .commit()

        // Read version from server response if available (stored during fetchHashInfo)
        val installedVersion = prefs.getString("pending_version", "") ?: ""
        if (installedVersion.isNotEmpty()) {
            prefs.edit().putString("last_installed_version", installedVersion).apply()
        }

        setStatus(STATE_UPDATED, "Update installed!${if (installedVersion.isNotEmpty()) " v$installedVersion" else ""} Relaunching app…")
        log("Install successful! Relaunching $packageName")

        launchApp(packageName)
        Result.success()
    }

    private fun fetchHashInfo(url: String): Pair<String, String> {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body?.string() ?: error("Empty body")
            return parseResponse(body)
        }
    }

    private fun parseResponse(body: String): Pair<String, String> {
        return runCatching {
            val json = JSONObject(body)
            val hash = json.optString("hash", json.optString("checksum", ""))
            val url = json.optString("url", json.optString("download_url", ""))
            val version = json.optString("version", "")
            if (version.isNotEmpty()) {
                prefs.edit().putString("pending_version", version).apply()
            }
            Pair(hash, url)
        }.getOrElse {
            val downloadUrl = prefs.getString("download_url", "") ?: ""
            Pair(body.trim(), downloadUrl)
        }
    }

    private fun downloadApk(url: String, dest: File): Boolean {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            dest.outputStream().use { out ->
                body.byteStream().use { stream ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            val mb = downloadedBytes / 1024 / 1024
                            setStatus(STATE_DOWNLOADING, "Downloading… ${mb}MB", progress = progress)
                        }
                    }
                }
            }
        }
        return true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun launchApp(packageName: String) {
        runCatching {
            val intent = applicationContext.packageManager
                .getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            applicationContext.startActivity(intent)
        }
    }

    private fun setStatus(state: String, message: String, progress: Int = -1) {
        prefs.edit()
            .putString("status_state", state)
            .putString("status_message", message)
            .putLong("status_time", System.currentTimeMillis())
            .also { if (progress >= 0) it.putInt("download_progress", progress) else it.remove("download_progress") }
            .apply()
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = JSONObject().put("t", time).put("m", message).toString()

        val existing = prefs.getString("status_log", "[]") ?: "[]"
        val arr = try { JSONArray(existing) } catch (_: Exception) { JSONArray() }

        // Keep last 20 entries
        val newArr = JSONArray()
        val start = if (arr.length() >= 19) 1 else 0
        for (i in start until arr.length()) newArr.put(arr.get(i))
        newArr.put(JSONObject(entry))

        prefs.edit().putString("status_log", newArr.toString()).apply()
    }
}
