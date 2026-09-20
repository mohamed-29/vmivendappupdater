package com.example.vmivendappupdater

/** Verify Android's state, never infer enrollment from command text alone. */
internal class OwnerEnrollment {
    companion object { private const val RETRY_INTERVAL_MS = 6 * 60 * 60 * 1000L }
    private var lastAttempt: Long? = null

    fun ensure(now: Long, supported: Boolean = true, isOwner: () -> Boolean, enroll: () -> String,
               report: (String) -> Unit): Boolean {
        if (isOwner()) return true
        if (!supported) {
            report("Kiosk NOT locked: this Android firmware does not support Device Owner.")
            return false
        }
        if (lastAttempt?.let { now - it in 0 until RETRY_INTERVAL_MS } == true) return false
        lastAttempt = now
        report("Setting up Device Owner using root…")
        val output = runCatching(enroll).getOrElse { it.message ?: it.javaClass.simpleName }
        if (isOwner()) {
            report("Device Owner verified. Applying kiosk lock…")
            return true
        }
        report("Kiosk NOT locked: Device Owner setup rejected. ${output.take(700)}")
        return false
    }
}
