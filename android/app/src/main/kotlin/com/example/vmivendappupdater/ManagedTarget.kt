package com.example.vmivendappupdater

import android.content.Context

object ManagedTarget {
    const val DEFAULT_PACKAGE = "com.ivendapp"

    /** The Android application ID remains fixed for safe APK validation/recovery. */
    fun packageName(@Suppress("UNUSED_PARAMETER") context: Context): String = DEFAULT_PACKAGE

    /** Editable server-side version/channel identifier used by the check URL. */
    fun targetName(context: Context): String {
        val prefs = context.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
        return prefs.getString("target_name", null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs.getString("target_package", DEFAULT_PACKAGE)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PACKAGE
    }
}
