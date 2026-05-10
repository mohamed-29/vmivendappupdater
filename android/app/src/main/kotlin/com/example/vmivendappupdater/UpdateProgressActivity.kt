package com.example.vmivendappupdater

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Full-screen activity that displays "Installing update…" while pm install runs.
 * This prevents the Android default launcher from showing when the target app
 * (IvendApp) is killed during installation.
 *
 * Auto-finishes after 60 seconds as a safety net.
 */
class UpdateProgressActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during update
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Build UI programmatically — no XML layout needed
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(60, 60, 60, 60)
        }

        val title = TextView(this).apply {
            text = "Installing Update"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Please wait, the app will restart automatically…"
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

        layout.addView(title)
        layout.addView(spacer(40))
        layout.addView(progress)
        layout.addView(spacer(40))
        layout.addView(subtitle)

        setContentView(layout)

        // Safety net: auto-finish after 60 seconds in case something goes wrong
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 60_000)
    }

    override fun onBackPressed() {
        // Block back button during update
    }
}
