package com.example.vmivendappupdater

import android.content.Context

object ManagedTarget {
    const val DEFAULT_PACKAGE = "com.ivendapp"
    const val DEFAULT_CHANNEL = "ivend.cloud"
    const val CHECK_PREFIX = "https://machine.ivend.cloud/api/v1/updates/check/"

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("channel_default_v2", false)) return
        val old = prefs.getString("target_name", null)
            ?: prefs.getString("target_package", DEFAULT_CHANNEL).orEmpty()
        val channel = old.takeIf { it.isNotBlank() && it != DEFAULT_PACKAGE } ?: DEFAULT_CHANNEL
        val url = prefs.getString("check_url", "").orEmpty()
        prefs.edit().putString("target_name", channel)
            .putString("check_url", if (url.isBlank() || url.startsWith(CHECK_PREFIX)) checkUrl(channel) else url)
            .putBoolean("channel_default_v2", true).commit()
    }

    fun checkUrl(channel: String): String = CHECK_PREFIX + android.net.Uri.encode(channel) + "/"

    /** The Android application ID remains fixed for safe APK validation/recovery. */
    fun packageName(@Suppress("UNUSED_PARAMETER") context: Context): String = DEFAULT_PACKAGE

    /** Editable server-side version/channel identifier used by the check URL. */
    fun targetName(context: Context): String {
        val prefs = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
        return prefs.getString("target_name", null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_CHANNEL
    }
}
