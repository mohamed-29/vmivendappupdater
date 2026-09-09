package com.example.vmivendappupdater

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UpdaterLog {
    /** The worker and guardian share this lock to avoid losing each other's entries. */
    @Synchronized
    fun append(context: Context, message: String) {
        val prefs = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
        val current = runCatching { JSONArray(prefs.getString("status_log", "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray()
        for (i in (current.length() - 19).coerceAtLeast(0) until current.length()) next.put(current.get(i))
        next.put(JSONObject()
            .put("t", SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
            .put("m", message.take(2048)))
        prefs.edit().putString("status_log", next.toString()).apply()
    }
}
