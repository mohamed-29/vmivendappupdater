package com.example.vmivendappupdater

import org.junit.Assert.*
import org.junit.Test

class OwnerEnrollmentTest {
    @Test fun existingOwnerDoesNotRunShell() {
        assertTrue(OwnerEnrollment().ensure(0, isOwner = { true },
            enroll = { error("Must not enroll twice") }, report = {}))
    }

    @Test fun successfulCommandIsNotEnoughWithoutVerifiedOwnership() {
        val state = OwnerEnrollment()
        var calls = 0
        val messages = mutableListOf<String>()
        val enroll = { calls++; "Success" }
        assertFalse(state.ensure(0, isOwner = { false }, enroll = enroll, report = messages::add))
        assertTrue(messages.last().contains("NOT locked"))
        assertFalse(state.ensure(2_000, isOwner = { false }, enroll = enroll, report = messages::add))
        assertEquals(1, calls)
        assertFalse(state.ensure(60_000, isOwner = { false }, enroll = enroll, report = messages::add))
        assertEquals(1, calls)
        assertFalse(state.ensure(6 * 60 * 60 * 1000L, isOwner = { false }, enroll = enroll, report = messages::add))
        assertEquals(2, calls)
    }

    @Test fun verifiesStateAfterEnrollmentAndHandlesRejection() {
        var owner = false
        assertTrue(OwnerEnrollment().ensure(0, isOwner = { owner },
            enroll = { owner = true; "Success" }, report = {}))
        var message = ""
        assertFalse(OwnerEnrollment().ensure(0, isOwner = { false },
            enroll = { error("Existing owner") }, report = { message = it }))
        assertTrue(message.contains("Existing owner"))
    }

    @Test fun unsupportedFirmwareNeverRunsEnrollmentCommand() {
        var calls = 0
        var message = ""
        assertFalse(OwnerEnrollment().ensure(0, false, { false }, { calls++; "" }, { message = it }))
        assertEquals(0, calls)
        assertTrue(message.contains("firmware does not support"))
    }
}
