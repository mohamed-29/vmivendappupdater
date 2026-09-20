package com.example.vmivendappupdater

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class SelfUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val RELEASE_CHANNEL = "appupdater.ivend.cloud"
        const val CHECK_URL = "https://machine.ivend.cloud/api/v1/updates/check/$RELEASE_CHANNEL/"
        const val PREF_SAVED_HASH = "self_saved_hash"
        const val PREF_PENDING_HASH = "self_pending_hash"
        private const val MAX_ATTEMPTS = 3
        private val client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.MINUTES)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    private val prefs by lazy {
        applicationContext.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    }

    override suspend fun doWork(): Result {
        if (UpdateRunLock.isLocked) {
            SelfInstall.status(applicationContext, "idle", "Waiting for iVend update/repair to finish.")
        }
        UpdateRunLock.acquire()
        return try {
            withContext(Dispatchers.IO) { updateSelf() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            retry("Updater self-update failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            UpdateRunLock.release()
        }
    }

    private fun updateSelf(): Result {
        SelfInstall.reconcile(applicationContext)
        if (prefs.getString(PREF_PENDING_HASH, "").orEmpty().isNotBlank()) return Result.retry()
        SelfInstall.status(applicationContext, "checking", "Checking $RELEASE_CHANNEL for an updater release…")
        val release = fetchRelease() ?: run {
            SelfInstall.status(applicationContext, "up_to_date", "No active updater release is published (HTTP 404).")
            return Result.success()
        }
        if (!Regex("[a-fA-F0-9]{64}").matches(release.hash)) {
            return retry("Updater channel returned an invalid SHA-256.")
        }
        if (release.hash.equals(SelfInstall.hash(File(applicationContext.applicationInfo.sourceDir)), ignoreCase = true)) {
            SelfInstall.status(applicationContext, "up_to_date", "Updater ${BuildConfig.VERSION_NAME} is up to date.")
            return Result.success()
        }
        if (!isInstalled(ManagedTarget.packageName(applicationContext))) {
            return retry("Updater update deferred because iVend is not installed.")
        }
        if (KioskSession.active(applicationContext, "operator")) {
            SelfInstall.status(applicationContext, "idle", "Waiting for the five-minute operator window to end.")
            return Result.retry()
        }

        val apk = File(applicationContext.filesDir, "updater-update.apk")
        val partial = File(applicationContext.filesDir, "updater-update.apk.part")
        if (!apk.isFile || !sha256(apk).equals(release.hash, ignoreCase = true)) {
            apk.delete()
            partial.delete()
            download(release.url, partial)
            if (!sha256(partial).equals(release.hash, ignoreCase = true)) {
                partial.delete()
                return retry("Downloaded updater APK failed its checksum.")
            }
            if (!partial.renameTo(apk)) {
                partial.copyTo(apk, overwrite = true)
                partial.delete()
            }
        }

        SelfInstall.status(applicationContext, "verifying", "Checking APK checksum, package and signing certificate…")
        val validationError = validateIdentity(apk)
        if (validationError != null) {
            apk.delete()
            return retry("Updater APK rejected: $validationError")
        }
        SelfInstall.status(applicationContext, "idle", "Waiting for iVend to approve installation while idle…")
        if (prefs.getBoolean("recovery_pending", false)) return Result.retry()
        if (!AppUpdateGate.prepare(applicationContext)) {
            SelfInstall.status(applicationContext, "idle", AppUpdateGate.waitingMessage(applicationContext))
            return Result.retry()
        }

        // Android/root owns installation after replacement kills this process.
        // Reconcile the installed APK hash before reporting success.
        return try {
            KioskSession.begin(applicationContext, "self_install", 3 * 60_000L)
            check(IvendKioskGuardian.awaitRecovery(applicationContext)) { "Updater status screen could not open" }
            SelfInstall.start(applicationContext, apk, release.hash)
            Result.success() // Submission only; receiver/service confirms installed APK.
        } catch (error: Exception) {
            SelfInstall.fail(applicationContext, "Updater install could not start: ${error.message}")
            Result.retry()
        }
    }

    private data class Release(val hash: String, val url: String, val version: String)

    private fun fetchRelease(): Release? {
        client.newCall(Request.Builder().url(CHECK_URL).get().build()).execute().use { response ->
            if (response.code == 404) return null
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val json = JSONObject(response.body?.string() ?: error("Empty response"))
            check(json.optString("package_name") == RELEASE_CHANNEL) { "wrong release channel" }
            val url = json.optString("url")
            check(url.isNotBlank()) { "missing APK URL" }
            return Release(json.optString("hash"), url, json.optString("version"))
        }
    }

    private fun download(url: String, destination: File) {
        SelfInstall.status(applicationContext, "downloading", "Downloading updater APK… (network timeout: 5 minutes)")
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            check(response.isSuccessful) { "download HTTP ${response.code}" }
            val body = response.body ?: error("Empty APK response")
            var downloaded = 0L
            var lastMessage = 0L
            destination.outputStream().use { output -> body.byteStream().use { input ->
                val buffer = ByteArray(32768)
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    output.write(buffer, 0, count)
                    downloaded += count
                    if (System.currentTimeMillis() - lastMessage >= 1000) {
                        val total = body.contentLength()
                        val percent = if (total > 0) " (${downloaded * 100 / total}%)" else ""
                        SelfInstall.status(applicationContext, "downloading", "Downloaded ${downloaded / 1024} KB$percent")
                        lastMessage = System.currentTimeMillis()
                    }
                }
            } }
            check(destination.length() > 0L) { "empty APK" }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateIdentity(apk: File): String? {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else PackageManager.GET_SIGNATURES
        val archive = applicationContext.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return "Android could not parse it"
        if (archive.packageName != applicationContext.packageName) {
            return "package ${archive.packageName} does not match ${applicationContext.packageName}"
        }
        val minSdk = archive.applicationInfo?.minSdkVersion ?: return "minimum Android version is missing"
        if (minSdk > Build.VERSION.SDK_INT) return "requires Android API $minSdk"
        val installed = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, flags)
        val archiveSigners = signerDigests(archive)
        val installedSigners = signerDigests(installed)
        if (archiveSigners.isEmpty() || archiveSigners != installedSigners) {
            return "signer does not match the installed updater"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else info.signatures ?: emptyArray()
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun isInstalled(packageName: String): Boolean = try {
        applicationContext.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun retry(message: String): Result {
        SelfInstall.status(applicationContext, "error", message + if (runAttemptCount + 1 < MAX_ATTEMPTS) " Will retry automatically." else " Retrying at the next scheduled check.")
        return if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }
}

