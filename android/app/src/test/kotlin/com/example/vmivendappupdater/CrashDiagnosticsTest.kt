package com.example.vmivendappupdater

import org.junit.Assert.*
import org.junit.Test

class CrashDiagnosticsTest {
    @Test fun configuredPackageCrashIsCaptured() {
        val raw = "E/AndroidRuntime( 456): Process: ivend.cloud, PID: 456\n" +
            "E/AndroidRuntime( 456): java.lang.IllegalStateException: broken"
        assertTrue(CrashDiagnostics.nativeCrash(raw, "ivend.cloud").contains("broken"))
    }

    @Test fun isolatesIvendProcessFromInterleavedCrashes() {
        val raw = "E/AndroidRuntime( 123): FATAL EXCEPTION: main\n" +
            "E/AndroidRuntime( 555): Process: other.app, PID: 555\n" +
            "E/AndroidRuntime( 123): Process: com.ivendapp, PID: 123\n" +
            "E/AndroidRuntime( 555): private unrelated exception\n" +
            "E/AndroidRuntime( 123): java.lang.IllegalStateException: test"
        val captured = CrashDiagnostics.nativeCrash(raw)
        assertTrue(captured.contains("IllegalStateException"))
        assertFalse(captured.contains("555"))
        assertFalse(captured.contains("private unrelated"))
    }
    @Test fun ignoresLookalikePackage() {
        assertEquals("", CrashDiagnostics.nativeCrash("E/AndroidRuntime( 123): Process: com.ivendapp.fake, PID: 123"))
    }
    @Test fun ignoresNormalActivityMessages() {
        assertEquals("", CrashDiagnostics.nativeCrash("I/ActivityManager( 123): Starting com.ivendapp"))
    }
}
