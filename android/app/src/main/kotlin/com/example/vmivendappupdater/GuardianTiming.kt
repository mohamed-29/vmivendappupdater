package com.example.vmivendappupdater

/** Monotonic, in-memory gates: reboot never inherits a future wall-clock deadline. */
internal class GuardianTiming {
    private val last = mutableMapOf<String, Long>()
    fun due(key: String, now: Long, interval: Long): Boolean {
        val previous = last[key]
        if (previous != null && now >= previous && now - previous < interval) return false
        last[key] = now
        return true
    }
}
