package com.example.vmivendappupdater

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            retryOrStop("Update interrupted: ${error.javaClass.simpleName}", "Update cycle failed: ${error.javaClass.simpleName}")
        } finally {
            workerRunning.set(false)
        }
    }

    private fun runUpdateCycle(): Result {
        // Maintain iVend as the visible kiosk app even if an update check fails.
        IvendKioskGuardian.enforce(applicationContext, "update check")
        val blockedUntil = prefs.getLong(PREF_RETRY_BLOCKED_UNTIL, 0L)
        val ignoreCooldown = inputData.getBoolean(KEY_IGNORE_COOLDOWN, false)
        if (!ignoreCooldown && System.currentTimeMillis() < blockedUntil) {
            return Result.success()
        }

        val checkUrl = prefs.getString("check_url", "") ?: ""
        val packageName = ManagedTarget.packageName(applicationContext)
        val savedHash = prefs.getString("saved_hash", "") ?: ""

        if (checkUrl.isEmpty()) {
            setStatus(STATE_ERROR, "Not configured — open app and set Check URL.")
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

        if (!Regex("[a-fA-F0-9]{64}").matches(serverHash)) {
            return retryOrStop("Server returned an invalid SHA-256.", "Server check failed: invalid checksum")
        }

        log("Server hash: ${serverHash.take(16)}…")

        if (serverHash.equals(savedHash, ignoreCase = true) && isTargetInstalled(packageName)) {
            setStatus(STATE_UP_TO_DATE, "App is up to date.")
            log("Hash matches — no update needed.")
            prefs.edit()
                .putLong("last_check_time", System.currentTimeMillis())
                .remove(PREF_RETRY_BLOCKED_UNTIL)
                .apply()
            return Result.success()
        }

        log("New hash detected — starting download.")
        setStatus(STATE_DOWNLOADING, "Downloading update…", progress = 0)

        val apkFile = File(applicationContext.filesDir, "update.apk")
        val partialFile = File(applicationContext.filesDir, "update.apk.part")
        val cachedVerified = apkFile.isFile && sha256(apkFile).equals(serverHash, ignoreCase = true)
        if (!cachedVerified) {
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

            if (!fileHash.equals(serverHash, ignoreCase = true)) {
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
        }

        log("Hash verified ✓")
        @Suppress("DEPRECATION")
        val archiveFlags = if (android.os.Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = applicationContext.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            archiveFlags
        )
        @Suppress("DEPRECATION")
        val hasModernSignature = android.os.Build.VERSION.SDK_INT >= 28 &&
            !archive?.signingInfo?.apkContentsSigners.isNullOrEmpty()
        @Suppress("DEPRECATION")
        val hasLegacySignature = !archive?.signatures.isNullOrEmpty()
        val archiveMinSdk = archive?.applicationInfo?.minSdkVersion
        val validationErrors = mutableListOf<String>()
        if (archive == null) validationErrors += "Android could not parse the archive"
        if (archive?.packageName != packageName) {
            validationErrors += "package=${archive?.packageName ?: "unknown"}, expected=$packageName"
        }
        if (!hasModernSignature && !hasLegacySignature) validationErrors += "no signer metadata"
        if (archiveMinSdk == null) {
            validationErrors += "minimum SDK metadata missing"
        } else if (archiveMinSdk > android.os.Build.VERSION.SDK_INT) {
            validationErrors += "minimum API $archiveMinSdk exceeds device API ${android.os.Build.VERSION.SDK_INT}"
        }
        if (validationErrors.isNotEmpty()) {
            apkFile.delete()
            val reason = validationErrors.joinToString("; ")
            return retryOrStop(
                "Invalid or incompatible iVend APK: $reason.",
                "APK validation failed: $reason; installed app retained."
            )
        }
        setStatus(STATE_INSTALLING, "Installing update via root…")
        log("Running: pm install -r ${apkFile.absolutePath}")
        // Do not let the 10-second guardian heartbeat replace the controlled
        // update screen while package-manager stops iVend.
        IvendKioskGuardian.beginMaintenance(applicationContext, 6 * 60 * 1000L)

        // Show the "Installing Update" screen BEFORE the install so users see our
        // progress screen instead of the Android default launcher when IvendApp gets killed
        val outcome = try {
            log("Showing update progress screen…")
            val cover = RootShell.run("am start -W -n com.example.vmivendappupdater/.UpdateProgressActivity -f 0x14000000")
            if (!cover.succeeded || cover.output.contains("Error:")) {
                return retryOrStop("Could not open recovery screen.", "Install deferred: recovery screen unavailable.")
            }
            RootInstaller.install(apkFile.absolutePath, packageName, ::log)
        } finally {
            IvendKioskGuardian.endMaintenance(applicationContext, "install attempt")
        }

        val targetStillInstalled = isTargetInstalled(packageName)
        if (outcome !in listOf(InstallRecovery.Outcome.UPDATED, InstallRecovery.Outcome.REINSTALLED) || !targetStillInstalled) {
            if (!targetStillInstalled) {
                CriticalEventQueue.enqueue(
                    applicationContext,
                    "update_left_app_missing",
                    "Update recovery ended with $outcome and $packageName is not installed."
                )
                DiagnosticLogUploader.uploadPending(applicationContext)
            }
            // Retain the validated replacement for diagnosis after reinstall failure.
            prefs.edit().remove("saved_hash").apply()
            return retryOrStop("Installation recovery: $outcome", "Installation recovery failed: $outcome")
        }
        apkFile.delete()

        prefs.edit()
            .putString("saved_hash", serverHash)
            .putLong("last_check_time", System.currentTimeMillis())
            .remove(PREF_RETRY_BLOCKED_UNTIL)
            .commit()

        // Display the actual verified APK version rather than stale server data.
        val installedVersion = archive?.versionName ?: ""
        if (installedVersion.isNotEmpty()) {
            prefs.edit().putString("last_installed_version", installedVersion).apply()
        }

        setStatus(STATE_UPDATED, "Update installed!${if (installedVersion.isNotEmpty()) " v$installedVersion" else ""} Relaunching app…")
        log("Install successful! Relaunching $packageName")

        IvendKioskGuardian.enforce(applicationContext, "update successful", force = true)
        return Result.success()
    }

    @Suppress("DEPRECATION")
    private fun isTargetInstalled(packageName: String): Boolean = try {
        applicationContext.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

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


    private fun setStatus(state: String, message: String, progress: Int = -1) {
        prefs.edit()
            .putString("status_state", state)
            .putString("status_message", message)
            .putLong("status_time", System.currentTimeMillis())
            .also { if (progress >= 0) it.putInt("download_progress", progress) else it.remove("download_progress") }
            .apply()
    }

    private fun log(message: String) = UpdaterLog.append(applicationContext, message)
}
