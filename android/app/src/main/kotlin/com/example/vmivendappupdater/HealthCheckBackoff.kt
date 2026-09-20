package com.example.vmivendappupdater

/** Failed observations never authorize a restart; give a loaded system time to recover. */
internal class HealthCheckBackoff {
    private var failures = 0
    private var nextCheck = 0L
    fun ready(now: Long) = now >= nextCheck
    fun observed(now: Long, known: Boolean): Long {
        failures = if (known) 0 else (failures + 1).coerceAtMost(4)
        val delay = if (known) 0L else (2_000L shl failures).coerceAtMost(30_000L)
        nextCheck = now + delay
        return delay
    }
}
