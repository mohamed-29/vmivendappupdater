package com.example.vmivendappupdater

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ShellResult(val code: Int, val output: String, val timedOut: Boolean = false) {
    val succeeded: Boolean get() = code == 0 && !timedOut
}

/** Small, bounded wrapper around the rooted shell available on vending machines. */
object RootShell {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    internal fun processArguments(command: String): List<String> =
        listOf("su", "-c", "sh", "-c", command)

    fun run(command: String, timeoutSeconds: Long = 15): ShellResult {
        var child: Process? = null
        return try {
            // This machine's su executes argv directly instead of interpreting
            // the value after -c as a shell command. Explicitly invoke sh so
            // pipelines, spaces and normal Android shell commands work.
            val process = ProcessBuilder(processArguments(command))
                .redirectErrorStream(true)
                .start()
            child = process
            process.outputStream.close()
            val bytes = ByteArrayOutputStream()
            val reader = thread(isDaemon = true, name = "root-output") {
                runCatching {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            synchronized(bytes) {
                                val keep = minOf(count, 256 * 1024 - bytes.size())
                                if (keep > 0) bytes.write(buffer, 0, keep)
                            }
                        }
                    }
                }
            }
            // Timed Process.waitFor is not available on every supported Android
            // release. Poll exitValue with a monotonic deadline instead.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            var finished = false
            while (System.nanoTime() < deadline) {
                try {
                    process.exitValue()
                    finished = true
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(25)
                }
            }
            if (!finished) process.destroy()
            reader.join(500)
            val output = synchronized(bytes) { bytes.toString("UTF-8").trim() }
            ShellResult(if (finished) process.exitValue() else -1, output, !finished)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            ShellResult(-1, "Interrupted", true)
        } catch (error: Exception) {
            ShellResult(-1, error.message ?: error.javaClass.simpleName)
        } finally {
            child?.destroy()
        }
    }

    fun exec(command: String, timeoutSeconds: Long = 15): String {
        val result = run(command, timeoutSeconds)
        return if (result.timedOut) "TIMEOUT" else "${result.output} (exit=${result.code})"
    }
}
