package com.example.vmivendappupdater

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UpdateRunLockTest {
    @Test fun ivendWaiterRunsBeforeLaterSelfChecks() = runBlocking {
        withTimeout(5000) {
            val order = mutableListOf<String>()
            UpdateRunLock.acquire()
            val ivend = launch(start = CoroutineStart.UNDISPATCHED) {
                UpdateRunLock.acquire()
                try { order.add("ivend") } finally { UpdateRunLock.release() }
            }
            val self = launch(start = CoroutineStart.UNDISPATCHED) {
                UpdateRunLock.acquire()
                try { order.add("self") } finally { UpdateRunLock.release() }
            }
            assertTrue(order.isEmpty())
            UpdateRunLock.release()
            joinAll(ivend, self)
            assertEquals(listOf("ivend", "self"), order)
            assertFalse(UpdateRunLock.isLocked)
        }
    }

    @Test fun cancelledWaiterDoesNotReleaseOwnersLockOrBlockNextWorker() = runBlocking {
        withTimeout(5000) {
            UpdateRunLock.acquire()
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                UpdateRunLock.acquire()
                try { fail("Cancelled waiter ran") } finally { UpdateRunLock.release() }
            }
            waiter.cancelAndJoin()
            assertTrue(UpdateRunLock.isLocked)
            UpdateRunLock.release()
            UpdateRunLock.acquire()
            UpdateRunLock.release()
            assertFalse(UpdateRunLock.isLocked)
        }
    }
}
