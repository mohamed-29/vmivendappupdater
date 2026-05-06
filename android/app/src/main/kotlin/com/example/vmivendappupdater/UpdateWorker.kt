package com.example.vmivendappupdater

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        // Singleton — avoids recreating connection pool every 15 min
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    private val prefs by lazy {
        applicationContext.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val checkUrl = prefs.getString("check_url", "") ?: ""
        val packageName = prefs.getString("target_package", "") ?: ""
        val savedHash = prefs.getString("saved_hash", "") ?: ""

        if (checkUrl.isEmpty() || packageName.isEmpty()) return@withContext Result.success()

        val (serverHash, downloadUrl) = try {
            fetchHashInfo(checkUrl)
        } catch (e: Exception) {
            return@withContext Result.retry()
        }

        if (serverHash.isEmpty() || serverHash == savedHash) return@withContext Result.success()

        val apkFile = File(applicationContext.filesDir, "update.apk")

        val downloaded = try {
            downloadApk(downloadUrl.ifEmpty { checkUrl }, apkFile)
        } catch (e: Exception) {
            apkFile.delete()
            return@withContext Result.retry()
        }

        if (!downloaded) {
            apkFile.delete()
            return@withContext Result.retry()
        }

        val fileHash = sha256(apkFile)
        if (fileHash != serverHash) {
            apkFile.delete()
            return@withContext Result.retry()
        }

        val installed = RootInstaller.install(apkFile.absolutePath)
        apkFile.delete()

        if (!installed) return@withContext Result.retry()

        // commit() is blocking but critical — must persist before worker finishes
        prefs.edit().putString("saved_hash", serverHash).commit()

        // Launch outside error-handling scope — failure here doesn't undo the install
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
            dest.outputStream().use { out ->
                body.byteStream().use { stream -> stream.copyTo(out) }
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
}
