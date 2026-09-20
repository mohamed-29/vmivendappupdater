package com.example.vmivendappupdater

internal object RecoveryPolicy {
    fun updateDue(now: Long, last: Long, interval: Long): Boolean =
        last == Long.MIN_VALUE || now < last || now - last >= interval

    fun isUpToDate(
        serverHash: String,
        savedHash: String,
        installed: Boolean,
        forceReinstall: Boolean
    ): Boolean = !forceReinstall && installed && serverHash.equals(savedHash, ignoreCase = true)
}
