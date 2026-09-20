package com.example.vmivendappupdater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {
    @Test fun recoveryRequestIsThrottledAndRebootClockIsAccepted() {
        assertTrue(RecoveryPolicy.updateDue(1_000, Long.MIN_VALUE, 60_000))
        assertFalse(RecoveryPolicy.updateDue(59_999, 1_000, 60_000))
        assertTrue(RecoveryPolicy.updateDue(61_000, 1_000, 60_000))
        assertTrue(RecoveryPolicy.updateDue(100, 61_000, 60_000))
    }

    @Test fun forcedRecoveryDoesNotTrustThePreviouslySavedHash() {
        assertTrue(RecoveryPolicy.isUpToDate("ABC", "abc", installed = true, forceReinstall = false))
        assertFalse(RecoveryPolicy.isUpToDate("ABC", "abc", installed = true, forceReinstall = true))
        assertFalse(RecoveryPolicy.isUpToDate("ABC", "abc", installed = false, forceReinstall = false))
    }
}
