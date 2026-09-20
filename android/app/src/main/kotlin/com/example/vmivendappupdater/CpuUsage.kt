package com.example.vmivendappupdater

internal data class CpuTicks(val total: Long, val idle: Long, val wait: Long)
internal data class ProcessTicks(val pid: Long, val started: Long, val ticks: Long)
internal data class CpuPercent(val busy: Double, val wait: Double)

internal object CpuUsage {
    fun parseCpu(text: String): CpuTicks? {
        val fields = text.lineSequence().firstOrNull { it.startsWith("cpu ") }
            ?.trim()?.split(Regex("\\s+")) ?: return null
        val ticks = fields.drop(1).take(8).map { it.toLongOrNull() ?: return null }
        if (ticks.size < 4 || ticks.any { it < 0 }) return null
        // guest/guest_nice are already included in user/nice: do not double count.
        return CpuTicks(ticks.sum(), ticks[3], ticks.getOrElse(4) { 0 })
    }

    fun parseProcess(line: String): ProcessTicks? {
        val end = line.lastIndexOf(')')
        if (end < 0) return null
        val pid = line.substringBefore(' ').toLongOrNull() ?: return null
        val fields = line.substring(end + 1).trim().split(Regex("\\s+"))
        if (fields.size < 20) return null
        val user = fields[11].toLongOrNull() ?: return null
        val system = fields[12].toLongOrNull() ?: return null
        val start = fields[19].toLongOrNull() ?: return null
        return ProcessTicks(pid, start, user + system)
    }

    fun between(before: CpuTicks?, after: CpuTicks?): CpuPercent? {
        if (before == null || after == null) return null
        val total = after.total - before.total
        val idle = after.idle - before.idle
        val wait = after.wait - before.wait
        if (total <= 0 || idle < 0 || wait < 0 || idle + wait > total) return null
        return CpuPercent(100.0 * (total - idle - wait) / total, 100.0 * wait / total)
    }

    fun processPercent(before: ProcessTicks?, after: ProcessTicks?, total: Long): Double? {
        if (before == null || after == null || total <= 0 || before.pid != after.pid ||
            before.started != after.started || after.ticks < before.ticks) return null
        return (100.0 * (after.ticks - before.ticks) / total).coerceIn(0.0, 100.0)
    }
}
