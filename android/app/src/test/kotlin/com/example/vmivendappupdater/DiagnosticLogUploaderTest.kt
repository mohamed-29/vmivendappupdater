package com.example.vmivendappupdater

import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticLogUploaderTest {
    @Test fun uploadUsesDedicatedTokenHeaderAndNamedUtf8File() {
        lateinit var captured: Request
        val result = VmmcLogClient("https://example.test/v1/logs", "secret") { request ->
            captured = request
            201
        }.upload("machine.log", "diagnostic")
        assertEquals(VmmcLogClient.Result.UPLOADED, result)
        assertEquals("secret", captured.header("X-Log-Token"))
        assertNull(captured.header("Authorization"))
        val buffer = Buffer()
        captured.body!!.writeTo(buffer)
        val rawBody = buffer.readUtf8()
        val json = JSONObject(rawBody)
        assertEquals("machine.log", json.getString("filename"))
        assertEquals("diagnostic", json.getString("content"))
        assertEquals("utf-8", json.getString("encoding"))
        assertFalse(rawBody.contains("secret"))
    }

    @Test fun duplicateIsTreatedAsAlreadyUploaded() {
        val result = VmmcLogClient("https://example.test/v1/logs", "secret") { 409 }
            .upload("machine.log", "diagnostic")
        assertEquals(VmmcLogClient.Result.ALREADY_EXISTS, result)
    }

    @Test fun serverFailureRemainsRetryable() {
        val result = VmmcLogClient("https://example.test/v1/logs", "secret") { 500 }
            .upload("machine.log", "diagnostic")
        assertEquals(VmmcLogClient.Result.RETRY, result)
    }
}
