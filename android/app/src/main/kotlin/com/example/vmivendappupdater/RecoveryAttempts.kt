package com.example.vmivendappupdater

/** Ten launches in a rolling five-minute window, then hold until repair completes. */
internal class RecoveryAttempts {
    private val launches = ArrayDeque<Long>()
    private var healthySince: Long? = null

    fun observeForeground(now: Long, foreground: Boolean): Boolean {
        if (!foreground) { healthySince = null; return false }
        val since = healthySince
        if (since == null) healthySince = now
        else if (now - since >= 60_000L) { reset(); return true }
        return false
    }
    var repairRequired = false
        private set
    val count: Int get() = launches.size

    fun mayLaunch(now: Long): Boolean {
        if (repairRequired) return false
        while (launches.isNotEmpty() && now - launches.first() >= 300_000L) launches.removeFirst()
        if (launches.size >= 10) {
            repairRequired = true
            return false
        }
        if (launches.isNotEmpty() && now - launches.last() < 10_000L) return false
        launches.addLast(now)
        return true
    }

    fun reset() { launches.clear(); repairRequired = false; healthySince = null }
}
