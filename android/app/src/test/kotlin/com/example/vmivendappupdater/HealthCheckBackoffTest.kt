package com.example.vmivendappupdater

import org.junit.Assert.*
import org.junit.Test

class HealthCheckBackoffTest {
    @Test fun unknownChecksBackOffAndSuccessfulObservationRestoresNormalChecks() {
        val backoff = HealthCheckBackoff()
        var now = 0L
        for (delay in listOf(4000L, 8000L, 16000L, 30000L, 30000L)) {
            assertTrue(backoff.ready(now))
            assertEquals(delay, backoff.observed(now, false))
            assertFalse(backoff.ready(now + delay - 1))
            now += delay
        }
        assertEquals(0L, backoff.observed(now, true))
        assertTrue(backoff.ready(now))
        assertEquals(4000L, backoff.observed(now, false))
    }
}
