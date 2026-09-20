package com.example.vmivendappupdater

import org.junit.Assert.*
import org.junit.Test

class KioskSessionTest {
    @Test fun sixMinuteInstallationWindowDoesNotUseOperatorLimit() {
        val end = 1000L + 6 * 60_000L
        assertTrue(KioskSession.windowActive(end, 1000, 4, 4, 10 * 60_000L))
        assertFalse(KioskSession.windowActive(end, 1000, 4, 4))
        assertFalse(KioskSession.windowActive(end, end, 4, 4, 10 * 60_000L))
    }
    @Test fun operatorWindowExpiresAtFiveMinutes() {
        val start = 1000L
        val end = start + KioskSession.OPERATOR_DURATION_MS
        assertTrue(KioskSession.windowActive(end, start, 4, 4))
        assertTrue(KioskSession.windowActive(end, end - 1, 4, 4))
        assertFalse(KioskSession.windowActive(end, end, 4, 4))
    }

    @Test fun rebootOrInvalidDurationDoesNotKeepMaintenanceOpen() {
        assertFalse(KioskSession.windowActive(301000, 1000, 4, 5))
        assertFalse(KioskSession.windowActive(301001, 1000, 4, 4))
        assertFalse(KioskSession.windowActive(0, 1000, 4, 4))
    }
}

