package com.example.vmivendappupdater

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object CriticalDiagnostics {
    private val directCritical = Regex(
        "render error|critical error|initialization workflow failed|giving up|" +
            "vmc sync complete with [1-9][0-9]* failed|invalid bootstrap cache|" +
            "response validation failed|dispensing sale timed out|" +
            "dispensing screen timed out|failed to persist dispensing sale|" +
            "cannot mark missing dispensing sale|dispensing expiry failed|" +
            "purchase command failed|selection status failed|vend failed after .*payment|" +
            "refund failed|refund exception|recovery required|state_load_failed|" +
            "corrupt|uncertain|ignored duplicate final sale|cancellation was not confirmed|" +
            "sale\\(s\\) still pending after retry",
        RegexOption.IGNORE_CASE
    )
    private val vmcFailure = Regex(
        "connection timed out|local vmc bridge error|command_timeout|transport_ack_timeout|" +
            "vmc server.*failed|websocket.*closed",
        RegexOption.IGNORE_CASE
    )
    private val backendFailure = Regex(
        "heartbeat failed|heartbeat error|fetch (products|config) error|" +
            "sale report failed|report sale error|promotion api .* (failed|error)|" +
            "command .* response (failed|error)",
        RegexOption.IGNORE_CASE
    )

    fun appLog(raw: String): String {
        val entries = raw.lineSequence().mapNotNull(::normalizeEntry).toList()
        val selected = entries.filter { directCritical.containsMatchIn(it) }.takeLast(10).toMutableList()
        addRepeated(selected, entries.filter { vmcFailure.containsMatchIn(it) }, "Repeated VMC connection failures")
        addRepeated(selected, entries.filter { backendFailure.containsMatchIn(it) }, "Repeated backend failures")
        return redact(selected.distinct().takeLast(12).joinToString("\n"))
    }

    fun redact(raw: String): String = raw
        .replace(Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s\\\"]+"), "${'$'}1[REDACTED]")
        .replace(Regex("(?i)(x-log-token\\s*[:=]\\s*)[^\\s\\\"]+"), "${'$'}1[REDACTED]")
        .replace(Regex("(?i)(token|card_number|payment_ref|fcrn|qr)[\\\"']?\\s*[:=]\\s*[\\\"']?[^,\\s\\\"']+"), "${'$'}1=[REDACTED]")

    private fun normalizeEntry(line: String): String? {
        val trimmed = line.trim().removeSuffix(",")
        if (trimmed.isEmpty()) return null
        return runCatching {
            val json = JSONObject(trimmed)
            "${json.optString("timestamp")} [${json.optString("tag")}] " +
                "${json.optString("message")} ${json.optString("data")}".trim()
        }.getOrElse { trimmed }
    }

    private fun addRepeated(target: MutableList<String>, matches: List<String>, heading: String) {
        if (matches.size >= 3) {
            target += "$heading (${matches.size} recent occurrences): ${matches.takeLast(3).joinToString(" | ")}"
        }
    }
}

object CriticalEventQueue {
    private const val PREFS = "updater_prefs"
    private const val KEY_CRASH_LOG = "ivend_crash_log"
    private const val MAX_ENTRIES = 10

    @Synchronized
    fun enqueue(context: Context, category: String, details: String): Boolean {
        val sanitized = CriticalDiagnostics.redact(details).take(64 * 1024)
        if (sanitized.isBlank()) return false
        val fingerprint = sha256("$category\n$sanitized")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = runCatching { JSONArray(prefs.getString(KEY_CRASH_LOG, "[]")) }
            .getOrDefault(JSONArray())
        for (index in 0 until current.length()) {
            if (current.optJSONObject(index)?.optString("fingerprint") == fingerprint) return false
        }
        val next = JSONArray()
        val first = (current.length() - (MAX_ENTRIES - 1)).coerceAtLeast(0)
        for (index in first until current.length()) next.put(current.get(index))
        next.put(
            JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("category", category)
                .put("fingerprint", fingerprint)
                .put("details", sanitized)
        )
        prefs.edit().putString(KEY_CRASH_LOG, next.toString()).apply()
        return true
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
