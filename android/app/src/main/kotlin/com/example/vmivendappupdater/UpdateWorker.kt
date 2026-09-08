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
import java.util.concurrent.atomic.AtomicBoolean

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
        private val workerRunning = AtomicBoolean(false)
        private const val MAX_ATTEMPTS = 3
        const val KEY_IGNORE_COOLDOWN = "ignore_retry_cooldown"
        const val PREF_RETRY_BLOCKED_UNTIL = "retry_blocked_until"

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

    override suspend fun doWork(): Result {
        if (!workerRunning.compareAndSet(false, true)) {
            return Result.success()
        }
        return try {
            withContext(Dispatchers.IO) { runUpdateCycle() }
        } finally {
            workerRunning.set(false)
        }
    }

    private fun runUpdateCycle(): Result {
        val blockedUntil = prefs.getLong(PREF_RETRY_BLOCKED_UNTIL, 0L)
        val ignoreCooldown = inputData.getBoolean(KEY_IGNORE_COOLDOWN, false)
        if (!ignoreCooldown && System.currentTimeMillis() < blockedUntil) {
            return Result.success()
        }

        val checkUrl = prefs.getString("check_url", "") ?: ""
        val packageName = prefs.getString("target_package", "") ?: ""
        val savedHash = prefs.getString("saved_hash", "") ?: ""

        if (checkUrl.isEmpty() || packageName.isEmpty()) {
            setStatus(STATE_ERROR, "Not configured — open app and set Check URL and Package Name.")
            return Result.success()
        }

        setStatus(STATE_CHECKING, "Checking for updates…")
        log("Contacting server: $checkUrl")

        val (serverHash, downloadUrl) = try {
            fetchHashInfo(checkUrl)
        } catch (e: Exception) {
            setStatus(STATE_ERROR, "Server unreachable: ${e.message}")
            log("Error: ${e.message}")
            return retryOrStop("Server unreachable: ${e.message}", "Server check failed: ${e.message}")
        }

        if (serverHash.isEmpty()) {
            setStatus(STATE_ERROR, "Server returned empty hash.")
            log("Error: empty hash in server response")
            return retryOrStop("Server returned empty hash.", "Server check failed: empty hash")
        }

        log("Server hash: ${serverHash.take(16)}…")

        if (serverHash == savedHash) {
            setStatus(STATE_UP_TO_DATE, "App is up to date.")
            log("Hash matches — no update needed.")
            prefs.edit()
                .putLong("last_check_time", System.currentTimeMillis())
                .remove(PREF_RETRY_BLOCKED_UNTIL)
                .apply()
            launchApp(packageName, false)
            return Result.success()
        }

        log("New hash detected — starting download.")
        setStatus(STATE_DOWNLOADING, "Downloading update…", progress = 0)

        val apkFile = File(applicationContext.filesDir, "update.apk")
        val partialFile = File(applicationContext.filesDir, "update.apk.part")
        apkFile.delete()
        partialFile.delete()
        val downloaded = try {
            downloadApk(downloadUrl.ifEmpty { checkUrl }, partialFile)
        } catch (e: Exception) {
            partialFile.delete()
            setStatus(STATE_ERROR, "Download failed: ${e.message}")
            log("Download error: ${e.message}")
            return retryOrStop("Download failed: ${e.message}", "Download error: ${e.message}")
        }

        if (!downloaded) {
            partialFile.delete()
            setStatus(STATE_ERROR, "Download failed — bad server response.")
            log("Download failed: non-2xx or empty body")
            return retryOrStop("Download failed: bad server response.", "Download failed: non-2xx or empty body")
        }

        setStatus(STATE_VERIFYING, "Verifying file integrity…")
        log("Verifying SHA-256…")
        val fileHash = sha256(partialFile)

        if (fileHash != serverHash) {
            partialFile.delete()
            setStatus(STATE_ERROR, "Hash mismatch — corrupted download. Will retry.")
            log("Hash mismatch! Expected ${serverHash.take(16)}… got ${fileHash.take(16)}…")
            return retryOrStop("Hash mismatch: corrupted download.", "Hash mismatch! Expected ${serverHash.take(16)} got ${fileHash.take(16)}")
        }

        if (!partialFile.renameTo(apkFile)) {
            try {
                partialFile.copyTo(apkFile, overwrite = true)
                partialFile.delete()
            } catch (e: Exception) {
                apkFile.delete()
                partialFile.delete()
                return retryOrStop(
                    "Could not finalize downloaded APK: ${e.message}",
                    "APK finalize failed: ${e.message}"
                )
            }
        }

        log("Hash verified ✓")
        setStatus(STATE_INSTALLING, "Installing update via root…")
        log("Running: pm install -r ${apkFile.absolutePath}")

        // Show the "Installing Update" screen BEFORE the install so users see our
        // progress screen instead of the Android default launcher when IvendApp gets killed
        log("Showing update progress screen…")
        rootExec("am start -n com.example.vmivendappupdater/.UpdateProgressActivity --activity-new-task --activity-clear-top")
        Thread.sleep(1000) // give the activity time to appear

        val installed = RootInstaller.install(apkFile.absolutePath)
        apkFile.delete()

        if (!installed) {
            setStatus(STATE_ERROR, "Installation failed — check root permissions.")
            log("pm install returned non-zero exit code.")
            return retryOrStop("Installation failed: check root permissions.", "pm install returned non-zero exit code")
        }

        prefs.edit()
            .putString("saved_hash", serverHash)
            .putLong("last_check_time", System.currentTimeMillis())
            .remove(PREF_RETRY_BLOCKED_UNTIL)
            .commit()

        // Read version from server response if available (stored during fetchHashInfo)
        val installedVersion = prefs.getString("pending_version", "") ?: ""
        if (installedVersion.isNotEmpty()) {
            prefs.edit().putString("last_installed_version", installedVersion).apply()
        }

        setStatus(STATE_UPDATED, "Update installed!${if (installedVersion.isNotEmpty()) " v$installedVersion" else ""} Relaunching app…")
        log("Install successful! Relaunching $packageName")

        launchApp(packageName, true)
        return Result.success()
    }

    private fun retryOrStop(message: String, logMessage: String): Result {
        val completedAttempt = runAttemptCount + 1
        return if (completedAttempt < MAX_ATTEMPTS) {
            val nextAttempt = completedAttempt + 1
            val delayMinutes = 1L shl runAttemptCount.coerceAtMost(10)
            setStatus(
                STATE_ERROR,
                "$message Retry $nextAttempt/$MAX_ATTEMPTS in about ${delayMinutes}m."
            )
            log("$logMessage; retry $nextAttempt/$MAX_ATTEMPTS scheduled")
            Result.retry()
        } else {
            val intervalMinutes = prefs.getInt("check_interval_minutes", 360)
                .coerceIn(15, 1440)
            val blockedUntil = System.currentTimeMillis() +
                TimeUnit.MINUTES.toMillis(intervalMinutes.toLong())
            prefs.edit().putLong(PREF_RETRY_BLOCKED_UNTIL, blockedUntil).commit()
            setStatus(
                STATE_ERROR,
                "$message Automatic retries stopped after $MAX_ATTEMPTS attempts; next scheduled cycle in ${intervalMinutes}m."
            )
            log("$logMessage; automatic retries stopped after $MAX_ATTEMPTS attempts")
            Result.failure()
        }
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

    private fun launchApp(packageName: String, isAfterInstall: Boolean = true) {
        if (isAfterInstall) {
            // Give the system time to register the updated package after pm install
            log("Waiting 10s for package registration…")
            Thread.sleep(10000)
        }

        // On Android 12+, startActivity() from a background WorkManager worker is blocked.
        // Since the device is rooted, we use `su` shell commands to launch the app.
        // Multiple strategies with fallbacks:

        // Strategy 1: `monkey` — the most reliable way to force-launch any app via root
        log("Launch attempt 1: monkey -p $packageName")
        val r1 = rootExec("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        log("monkey result: $r1")

        if ("error" in r1.lowercase() || "exception" in r1.lowercase() || r1.isBlank()) {
            // Strategy 2: `am start` targeting the specific package activity
            log("Launch attempt 2: am start -n $packageName/.MainActivity")
            val r2 = rootExec("am start -n $packageName/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --activity-clear-top --activity-new-task")
            log("am start result: $r2")
        }

        // Strategy 3: always also press HOME — since IvendApp is the home launcher,
        // this ensures it comes to the foreground
        Thread.sleep(2000)
        log("Launch attempt 3: HOME key press")
        val r3 = rootExec("input keyevent 3")
        log("HOME key result: $r3")
    }

    private fun rootExec(command: String): String {
        return try {
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            java.io.DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("$command\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            val output = process.inputStream.use { String(it.readBytes()).trim() }
            val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                "TIMEOUT"
            } else {
                val exit = process.exitValue()
                if (output.isNotEmpty()) "$output (exit=$exit)" else "exit=$exit"
            }
        } catch (e: Exception) {
            "EXCEPTION: ${e.message}"
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
