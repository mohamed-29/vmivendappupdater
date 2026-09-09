package com.example.vmivendappupdater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalDiagnosticsTest {
    @Test fun crucialBusinessAndMachineFailuresAreSelected() {
        val raw = listOf(
            entry("SalesManager", "Dispensing sale timed out", "order-1"),
            entry("Action", "Fawry refund failed", "FCRN=123456789012"),
            entry("Init", "Initialization workflow failed", "corrupt state")
        ).joinToString("\n")
        val result = CriticalDiagnostics.appLog(raw)
        assertTrue(result.contains("Dispensing sale timed out"))
        assertTrue(result.contains("Fawry refund failed"))
        assertTrue(result.contains("Initialization workflow failed"))
        assertFalse(result.contains("123456789012"))
    }

    @Test fun routineEventsAreExcludedAndRepeatedFailuresEscalate() {
        val raw = listOf(
            entry("ApiService", "Heartbeat sent successfully"),
            entry("WebSocket", "Local VMC bridge error"),
            entry("WebSocket", "Local VMC bridge error"),
            entry("WebSocket", "Local VMC bridge error")
        ).joinToString("\n")
        val result = CriticalDiagnostics.appLog(raw)
        assertFalse(result.contains("Heartbeat sent successfully"))
        assertTrue(result.contains("Repeated VMC connection failures"))
    }

    @Test fun secretsAndPaymentIdentifiersAreRedacted() {
        val result = CriticalDiagnostics.redact(
            "Authorization: Bearer abc123 token=xyz card_number=4111111111111111 FCRN=999999999999"
        )
        assertFalse(result.contains("abc123"))
        assertFalse(result.contains("xyz"))
        assertFalse(result.contains("4111111111111111"))
        assertFalse(result.contains("999999999999"))
    }

    @Test fun anrAndNativeSignalAreCapturedForTargetOnly() {
        val anr = CrashDiagnostics.anr("E/ActivityManager: ANR in com.ivendapp", "com.ivendapp")
        val native = CrashDiagnostics.nativeCrash(
            "F/libc( 321): Fatal signal 11 in tid 321 (com.ivendapp)",
            "com.ivendapp"
        )
        assertTrue(anr.contains("ANR in com.ivendapp"))
        assertTrue(native.contains("Fatal signal 11"))
        assertTrue(CrashDiagnostics.anr("ANR in other.app", "com.ivendapp").isEmpty())
    }

    private fun entry(tag: String, message: String, data: String = ""): String =
        "{\"timestamp\":\"2026-09-09T12:00:00Z\",\"tag\":\"$tag\",\"message\":\"$message\",\"data\":\"$data\"},"
}
