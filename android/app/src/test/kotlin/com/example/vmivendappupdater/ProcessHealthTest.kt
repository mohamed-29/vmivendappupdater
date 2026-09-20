package com.example.vmivendappupdater

import org.junit.Assert.*
import org.junit.Test

class ProcessHealthTest {
    @Test fun emptyOrUnsupportedFocusIsUnknownNotBackground() {
        for (dump in listOf("", "Permission denied", "mCurrentFocus=null", "mResumedActivity=null")) {
            assertNull(ProcessHealth.foreground(dump, "com.ivendapp"))
        }
        assertEquals(false, ProcessHealth.foreground(
            "mCurrentFocus=Window{abc u0 com.android.settings/.Settings}", "com.ivendapp"))
    }

    @Test fun detectsOnlyManagedProcessAnr() {
        val dump = "*APP* ProcessRecord{abc 123:com.ivendapp/u0a100}\n crashing=false notResponding=true\n"
        assertTrue(ProcessHealth.isNotResponding(dump, "com.ivendapp"))
        assertFalse(ProcessHealth.isNotResponding(dump.replace("com.ivendapp", "com.other"), "com.ivendapp"))
        assertFalse(ProcessHealth.isNotResponding(dump.replace("notResponding=true", "notResponding=false"), "com.ivendapp"))
        assertFalse(ProcessHealth.isNotResponding(dump.replace("com.ivendapp", "com.ivendapp.extra"), "com.ivendapp"))
    }

    @Test fun detectsManagedProcessWithoutMatchingSimilarPackage() {
        val dump = "*APP* ProcessRecord{abc 123:com.ivendapp/u0a100}"
        assertTrue(ProcessHealth.isRunning(dump, "com.ivendapp"))
        assertFalse(ProcessHealth.isRunning(dump.replace("com.ivendapp", "com.ivendapp.extra"), "com.ivendapp"))
    }

    @Test fun detectsForegroundAcrossAndroidDumpsysFormats() {
        assertTrue(ProcessHealth.isForeground(
            "mCurrentFocus=Window{abc u0 com.ivendapp/com.ivendapp.MainActivity}",
            "com.ivendapp"
        ))
        assertTrue(ProcessHealth.isForeground(
            "mCurrentFocus=null\n topResumedActivity=ActivityRecord{abc u0 com.ivendapp/.MainActivity}",
            "com.ivendapp"
        ))
        assertTrue(ProcessHealth.isForeground(
            "mResumedActivity: ActivityRecord{abc u0 com.ivendapp/.MainActivity t42}",
            "com.ivendapp"
        ))
    }

    @Test fun actualSystemDialogWinsOverStaleFocusedApp() {
        val dump = """
            mCurrentFocus=Window{abc u0 android/com.android.server.am.AppNotRespondingDialog}
            mFocusedApp=AppWindowToken{def token=ActivityRecord{ghi com.ivendapp/.MainActivity}}
        """.trimIndent()
        assertFalse(ProcessHealth.isForeground(dump, "com.ivendapp"))
        assertFalse(ProcessHealth.isForeground(
            "mCurrentFocus=Window{abc u0 com.ivendapp.extra/.MainActivity}",
            "com.ivendapp"
        ))
    }
}
