package com.drift.rokidreceiver

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (12 * resources.displayMetrics.density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        val openButton = Button(this).apply {
            text = "OPEN ACCESSIBILITY"
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { openAccessibility() }
        }
        column.addView(
            openButton,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        column.addView(TextView(this).apply {
            text = "Drift → Rokid Receiver"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad / 2)
        })

        column.addView(TextView(this).apply {
            text = "1. Enable Drift Rokid Receiver in Accessibility.\n" +
                "2. Put Rokid and Huawei Watch on the same Wi‑Fi / phone hotspot.\n" +
                "3. On the watch: Drift → Wi‑Fi → Rokid Glasses."
            textSize = 15f
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        setContentView(scroll)

        openButton.requestFocus()

        // Rokid has no normal touch UI in many screens, so open Accessibility automatically.
        if (savedInstanceState == null) {
            openButton.postDelayed({ openAccessibility() }, 700)
        }
    }

    private fun openAccessibility() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
