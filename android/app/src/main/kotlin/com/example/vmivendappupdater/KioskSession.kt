package com.example.vmivendappupdater

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Two short local windows: operator Settings and the installer cover. */
object KioskSession {
    const val OPERATOR_DURATION_MS = 5 * 60 * 1000L
    private fun maximumDuration(kind: String) = if (kind == "operator") OPERATOR_DURATION_MS else 10 * 60 * 1000L
    private fun prefs(context: Context) = context.getSharedPreferences("kiosk_session", Context.MODE_PRIVATE)
    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun active(context: Context, kind: String): Boolean {
        val p = prefs(context)
        return windowActive(p.getLong("${kind}_until", 0), SystemClock.elapsedRealtime(),
            p.getInt("${kind}_boot", -2), boot(context), maximumDuration(kind))
    }

    internal fun windowActive(until: Long, now: Long, savedBoot: Int, currentBoot: Int,
                              maximum: Long = OPERATOR_DURATION_MS) =
        savedBoot == currentBoot && now < until && until - now <= maximum

    @Synchronized
    fun begin(context: Context, kind: String, durationMs: Long = OPERATOR_DURATION_MS) {
        require(kind in listOf("operator", "install", "self_install"))
        check(listOf("operator", "install", "self_install").filter { it != kind }.none { active(context, it) }) { "Another maintenance operation is active" }
        require(durationMs in 1..maximumDuration(kind))
        prefs(context).edit().putLong("${kind}_until", SystemClock.elapsedRealtime() + durationMs)
            .putInt("${kind}_boot", boot(context)).commit()
    }

    fun end(context: Context, kind: String) {
        prefs(context).edit().remove("${kind}_until").remove("${kind}_boot").commit()
    }
}

