package com.example.vmivendappupdater

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.graphics.Color
import android.os.Bundle
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
 * Stays visible until the guardian restores iVend, including failed reinstalls.
 */
class UpdateProgressActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val title = TextView(this).apply {
            text = "Restoring iVend"
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

        // Remain visible if reinstall fails. The guardian brings iVend forward
        // once it is installed again; finishing on a timer exposes Android Home.
    }

}
