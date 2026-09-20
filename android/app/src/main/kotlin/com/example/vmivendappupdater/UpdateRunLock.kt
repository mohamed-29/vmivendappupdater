package com.example.vmivendappupdater

import kotlinx.coroutines.sync.Mutex

/** Prevent the iVend and updater installers from running at the same time. */
object UpdateRunLock {
    private val running = Mutex()
    val isLocked: Boolean get() = running.isLocked
    // Suspend in FIFO order instead of turning contention into scheduler backoff.
    suspend fun acquire() { running.lock() }
    fun release() { running.unlock() }
}
