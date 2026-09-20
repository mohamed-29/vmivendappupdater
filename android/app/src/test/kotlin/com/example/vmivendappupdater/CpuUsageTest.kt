package com.example.vmivendappupdater

import org.junit.Assert.*
import org.junit.Test

class CpuUsageTest {
    @Test fun separatesCpuWorkFromIoWaitAndDoesNotCountGuestTwice() {
        val before = CpuUsage.parseCpu("cpu 100 0 100 700 100 0 0 0 90 0")!!
        val after = CpuUsage.parseCpu("cpu 130 0 120 730 120 0 0 0 100 0")!!
        assertEquals(1000L, before.total)
        val usage = CpuUsage.between(before, after)!!
        assertEquals(50.0, usage.busy, 0.01)
        assertEquals(20.0, usage.wait, 0.01)
    }

    @Test fun unavailableResetAndFirstSampleAreNotZeroUsage() {
        assertNull(CpuUsage.parseCpu("Permission denied"))
        assertNull(CpuUsage.between(null, CpuTicks(100, 50, 0)))
        assertNull(CpuUsage.between(CpuTicks(100, 50, 0), CpuTicks(90, 40, 0)))
        assertNull(CpuUsage.between(CpuTicks(100, 50, 0), CpuTicks(100, 50, 0)))
    }

    @Test fun processNamesWithSpacesAndPidReuseAreHandled() {
        val fields = MutableList(20) { "0" }
        fields[0] = "S"; fields[11] = "20"; fields[12] = "10"; fields[19] = "500"
        val process = CpuUsage.parseProcess("42 (app (worker)) ${fields.joinToString(" ")}")!!
        assertEquals(ProcessTicks(42, 500, 30), process)
        assertEquals(10.0, CpuUsage.processPercent(process, process.copy(ticks = 40), 100)!!, 0.01)
        assertNull(CpuUsage.processPercent(process, process.copy(started = 600), 100))
        assertNull(CpuUsage.processPercent(null, process, 100))
    }
}
