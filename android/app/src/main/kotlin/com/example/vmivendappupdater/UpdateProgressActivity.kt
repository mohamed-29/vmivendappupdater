package com.example.vmivendappupdater

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.graphics.Color
import android.os.Bundle
import android.app.admin.DevicePolicyManager
import android.view.View
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ScrollView
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.lang.ref.WeakReference

/**
 * Full-screen activity that displays "Installing update…" while pm install runs.
 * This prevents the Android default launcher from showing when the target app
 * (IvendApp) is killed during installation.
 *
 * Stays visible until the guardian restores iVend, including failed reinstalls.
 */
class UpdateProgressActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var heading: TextView
    private lateinit var details: TextView
    private val refresh = object : Runnable {
        override fun run() {
            heading.text = when {
                KioskSession.active(this@UpdateProgressActivity, "operator") -> "Operator maintenance"
                KioskSession.active(this@UpdateProgressActivity, "self_install") -> "Updating updater"
                KioskSession.active(this@UpdateProgressActivity, "install") -> "Updating iVend"
                else -> "Out of service"
            }
            val prefs = getSharedPreferences("updater_prefs", MODE_PRIVATE)
            val logs = runCatching { JSONArray(prefs.getString("status_log", "[]")) }.getOrDefault(JSONArray())
            val recent = (maxOf(0, logs.length() - 8) until logs.length()).mapNotNull { i ->
                val entry = logs.optJSONObject(i) ?: return@mapNotNull null
                "${entry.optString("t")}  ${entry.optString("m")}"
            }
            val age = ((System.currentTimeMillis() - prefs.getLong("status_time", System.currentTimeMillis())) / 1000).coerceAtLeast(0)
            details.text = "Updater ${BuildConfig.VERSION_NAME}\n" +
                (if (KioskSession.active(this@UpdateProgressActivity, "operator"))
                    "Authorized Settings window is active. Returning to iVend; automatic repair resumes when maintenance ends.\n\n" else "") +
                prefs.getString("kiosk_status", "Checking kiosk setup…") + "\n\n" +
                "Device usage\n" + ResourceMonitor.display() + "\n\n" +
                IvendKioskGuardian.healthMessage + "\n\n" +
                "iVend (${ManagedTarget.targetName(this@UpdateProgressActivity)})\n" +
                prefs.getString("status_message", "Checking kiosk and preparing recovery…") +
                "\nLast update: ${age}s ago\n\nUpdater\n" +
                prefs.getString("self_status_message", "Waiting for self-update check.") +
                "\n\nRecent activity\n" + recent.joinToString("\n")
            handler.postDelayed(this, 1000)
        }
    }
    companion object {
        @Volatile var visible = false
            private set
        @Volatile private var current = WeakReference<UpdateProgressActivity>(null)

        fun dismissIfOpen() {
            current.get()?.let { activity ->
                activity.runOnUiThread { if (!activity.isFinishing) activity.finish() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        val policy = getSystemService(DevicePolicyManager::class.java)
        if (policy.isLockTaskPermitted(packageName)) runCatching { startLockTask() }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        runCatching { UpdateForegroundService.start(this) }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        // Covers both legacy back buttons and predictive back gestures.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Keep the recovery screen visible. */ }
        })

        // Keep screen on during update
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Build UI programmatically — no XML layout needed
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(60, 60, 60, 60)
        }

        heading = TextView(this).apply {
            text = if (KioskSession.active(this@UpdateProgressActivity, "install")) "Updating iVend" else "Out of service"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Please wait. Progress and recovery details appear below."
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }

        val progress = ProgressBar(this).apply {
            isIndeterminate = true
        }

        val spacer = { height: Int ->
            android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, height
                )
            }
        }

        layout.addView(heading)
        layout.addView(spacer(40))
        layout.addView(progress)
        layout.addView(spacer(40))
        layout.addView(subtitle)
        layout.addView(spacer(24))
        details = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#262640"))
        }
        val box = ScrollView(this).apply { addView(details) }
        layout.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(layout)

        // Remain visible if reinstall fails. The guardian brings iVend forward
        // once it is installed again; finishing on a timer exposes Android Home.
    }

    override fun onDestroy() {
        if (current.get() === this) current.clear()
        super.onDestroy()
    }

}
