package com.example.vmivendappupdater

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One bounded sample every 15 seconds, off both the UI and kiosk guardian threads.
 * No history files, network upload, top, dumpsys, or directory scans. */
internal object ResourceMonitor {
    private var executor: ScheduledExecutorService? = null
    private var previousCpu: CpuTicks? = null
    private var previousUpdater: ProcessTicks? = null
    private var previousIvend: ProcessTicks? = null
    private var peak = 0.0
    @Volatile private var snapshot = "CPU: waiting for samples\nRAM: waiting\nInternal storage: waiting"
    @Volatile private var sampledAt = 0L

    @Synchronized fun start(context: Context) {
        if (executor != null) return
        val app = context.applicationContext
        executor = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({
                runCatching { sample(app) }.onFailure {
                    snapshot = "Resource sample unavailable; retrying."
                    sampledAt = SystemClock.elapsedRealtime()
                }
            }, 0, 15, TimeUnit.SECONDS)
        }
    }

    @Synchronized fun stop() {
        executor?.shutdownNow()
        executor = null
        previousCpu = null
        previousUpdater = null
        previousIvend = null
    }

    fun display(): String {
        val age = if (sampledAt == 0L) "Sampling every 15s" else
            "Sample age: ${((SystemClock.elapsedRealtime() - sampledAt) / 1000).coerceAtLeast(0)}s · every 15s"
        return "$snapshot\n$age"
    }

    private fun sample(context: Context) {
        // /proc access varies by firmware; root supplies only these tiny counters.
        val target = RootShell.quote(ManagedTarget.packageName(context))
        val root = RootShell.run(
            "head -n 1 /proc/stat; echo UPDATER; cat /proc/${Process.myPid()}/stat; " +
                "echo IVEND; for p in \$(pidof $target); do cat /proc/\$p/stat; break; done", 3)
        val output = if (root.succeeded) root.output else ""
        val cpu = CpuUsage.parseCpu(output) ?: runCatching {
            File("/proc/stat").bufferedReader().use { CpuUsage.parseCpu(it.readLine().orEmpty()) }
        }.getOrNull()
        fun process(section: String) = output.substringAfter("$section\n", "")
            .lineSequence().takeWhile { it != "IVEND" }
            .mapNotNull { CpuUsage.parseProcess(it) }.firstOrNull()
        val updater = process("UPDATER") ?: runCatching {
            CpuUsage.parseProcess(File("/proc/${Process.myPid()}/stat").readText())
        }.getOrNull()
        val ivend = process("IVEND")
        val usage = CpuUsage.between(previousCpu, cpu)
        val totalDelta = if (usage != null) cpu!!.total - previousCpu!!.total else 0L
        val updaterPercent = CpuUsage.processPercent(previousUpdater, updater, totalDelta)
        val ivendPercent = CpuUsage.processPercent(previousIvend, ivend, totalDelta)
        previousCpu = cpu
        previousUpdater = updater
        previousIvend = ivend
        if (usage != null) peak = maxOf(peak, usage.busy)
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val disk = StatFs(context.filesDir.absolutePath)
        val ramUsed = (memory.totalMem - memory.availMem).coerceAtLeast(0)
        val storageUsed = (disk.totalBytes - disk.availableBytes).coerceAtLeast(0)
        snapshot = "CPU (whole device): ${percent(usage?.busy)} · peak ${percent(if (usage != null || peak > 0) peak else null)}\n" +
            "iVend main: ${percent(ivendPercent)} · Updater: ${percent(updaterPercent)}\n" +
            "CPU I/O wait: ${percent(usage?.wait)}\n" +
            "RAM: ${bytes(ramUsed)} / ${bytes(memory.totalMem)} (${ratio(ramUsed, memory.totalMem)})\n" +
            "Internal storage: ${bytes(storageUsed)} / ${bytes(disk.totalBytes)} (${ratio(storageUsed, disk.totalBytes)})\n" +
            "Free: RAM ${bytes(memory.availMem)} · storage ${bytes(disk.availableBytes)}\n" +
            "CPU uses total device capacity; — means unavailable or warming up. Peak since updater started."
        sampledAt = SystemClock.elapsedRealtime()
    }

    private fun percent(value: Double?) = value?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"
    private fun ratio(used: Long, total: Long) = percent(if (total > 0) 100.0 * used / total else null)
    private fun bytes(value: Long) = if (value >= 1024L * 1024 * 1024)
        String.format(Locale.US, "%.1f GiB", value / (1024.0 * 1024 * 1024)) else
        String.format(Locale.US, "%.0f MiB", value / (1024.0 * 1024))
}
