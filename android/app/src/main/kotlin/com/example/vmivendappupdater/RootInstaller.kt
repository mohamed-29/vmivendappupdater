package com.example.vmivendappupdater

import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

object RootInstaller {

    fun install(apkPath: String): Boolean {
        val process = try {
            // redirectErrorStream prevents stderr buffer deadlock when su prints errors
            ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return false
        }

        return try {
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("pm install -r \"$apkPath\"\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            // Drain stdout/stderr so process doesn't block on full buffer
            process.inputStream.use { it.readBytes() }

            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { process.destroyForcibly() }
        }
    }
}
