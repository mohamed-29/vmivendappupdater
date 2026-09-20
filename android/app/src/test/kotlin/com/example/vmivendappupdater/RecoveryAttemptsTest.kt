package com.example.vmivendappupdater

import org.junit.Assert.*
import org.junit.Test

class RecoveryAttemptsTest {
    @Test fun briefForegroundBetweenCrashesDoesNotHideCrashLoop() {
        val attempts = RecoveryAttempts()
        repeat(10) {
            val now = it * 20_000L
            assertTrue(attempts.mayLaunch(now))
            assertFalse(attempts.observeForeground(now + 1000, true))
            assertFalse(attempts.observeForeground(now + 5000, false))
        }
        assertFalse(attempts.mayLaunch(200_000))
        assertTrue(attempts.repairRequired)
    }

    @Test fun onlyContinuousMinuteOfHealthClearsFailures() {
        val attempts = RecoveryAttempts()
        assertTrue(attempts.mayLaunch(0))
        attempts.observeForeground(1000, true)
        assertFalse(attempts.observeForeground(60_000, true))
        assertEquals(1, attempts.count)
        attempts.observeForeground(60_500, false) // unknown/failed observation breaks continuity
        attempts.observeForeground(61_000, true)
        assertFalse(attempts.observeForeground(120_000, true))
        assertTrue(attempts.observeForeground(121_000, true))
        assertEquals(0, attempts.count)
    }

    @Test fun repeatedCrashesHoldRecoveryScreenBeforeRetrying() {
        val attempts = RecoveryAttempts()
        repeat(10) { assertTrue(attempts.mayLaunch(it * 10_000L)) }
        assertFalse(attempts.mayLaunch(100_000))
        assertTrue(attempts.repairRequired)
        assertFalse(attempts.mayLaunch(600_000))
        attempts.reset()
        assertTrue(attempts.mayLaunch(610_000))
    }

    @Test fun isolatedCrashesAndCompletedInstallationCanRecover() {
        val attempts = RecoveryAttempts()
        repeat(10) { assertTrue(attempts.mayLaunch(it * 61000L)) }
        attempts.reset()
        assertTrue(attempts.mayLaunch(0))
    }

    @Test fun launchGetsTenSecondsToStartAndWindowExpiresAtFiveMinutes() {
        val attempts = RecoveryAttempts()
        assertTrue(attempts.mayLaunch(0))
        assertFalse(attempts.mayLaunch(2000))
        assertFalse(attempts.repairRequired)
        repeat(9) { assertTrue(attempts.mayLaunch((it + 1) * 30_000L)) }
        assertTrue(attempts.mayLaunch(300_000L))
        assertFalse(attempts.repairRequired)
    }
}
