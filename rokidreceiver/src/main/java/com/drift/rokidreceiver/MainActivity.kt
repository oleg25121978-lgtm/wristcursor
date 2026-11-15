package com.drift.rokidreceiver

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var actionButton: TextView

    private val bg = Color.rgb(0, 0, 0)
    private val surface = Color.rgb(26, 30, 39)
    private val surfaceHigh = Color.rgb(36, 41, 52)
    private val mint = Color.rgb(139, 241, 216)
    private val mintDark = Color.rgb(0, 56, 45)
    private val textColor = Color.rgb(239, 241, 246)
    private val muted = Color.rgb(169, 176, 192)
    private val warning = Color.rgb(255, 158, 158)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()

        if (savedInstanceState == null && !isAccessibilityEnabled()) {
            actionButton.postDelayed({ openAccessibility() }, 900)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) refreshState()
    }

    private fun buildUi() {
        val pad = dp(18)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(bg)
        }

        val brand = TextView(this).apply {
            text = "DRIFT"
            setTextColor(mint)
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
        }
        column.addView(brand, matchWrap())

        val subtitle = TextView(this).apply {
            text = "ROKID RECEIVER"
            setTextColor(textColor)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(14))
        }
        column.addView(subtitle, matchWrap())

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(surface, dp(22))
        }

        val icon = TextView(this).apply {
            text = "◉"
            setTextColor(mint)
            textSize = 42f
            gravity = Gravity.CENTER
        }
        hero.addView(icon, matchWrap())

        statusText = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }
        hero.addView(statusText, matchWrap())

        actionButton = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                if (!isAccessibilityEnabled()) openAccessibility()
            }
        }
        hero.addView(
            actionButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        column.addView(
            hero,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        )

        column.addView(infoCard("1", "SAME WI-FI", "Connect Watch 4 Pro and Rokid to the same Wi‑Fi or phone hotspot."))
        column.addView(infoCard("2", "WATCH", "Open Drift → Wi‑Fi → scan."))
        column.addView(infoCard("3", "CONNECT", "Choose “Rokid Glasses”. Cursor control starts locally — internet is not required."))

        val foot = TextView(this).apply {
            text = "UDP 47800  •  LOCAL ONLY"
            setTextColor(Color.rgb(111, 119, 136))
            textSize = 11f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        }
        column.addView(foot, matchWrap())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(column)
        }
        setContentView(scroll)
        actionButton.requestFocus()
        refreshState()
    }

    private fun infoCard(number: String, title: String, body: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(surfaceHigh, dp(18))
        }

        val badge = TextView(this).apply {
            text = number
            setTextColor(mintDark)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(mint, dp(18))
        }
        row.addView(
            badge,
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
        )

        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply {
            text = title
            setTextColor(textColor)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
        })
        copy.addView(TextView(this).apply {
            text = body
            setTextColor(muted)
            textSize = 13f
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setPadding(0, 0, 0, dp(8))
        }
    }

    private fun refreshState() {
        val enabled = isAccessibilityEnabled()
        if (enabled) {
            statusText.text = "READY"
            statusText.setTextColor(mint)
            actionButton.text = "ACCESSIBILITY  ON"
            actionButton.setTextColor(mintDark)
            actionButton.background = rounded(mint, dp(18))
        } else {
            statusText.text = "ACCESSIBILITY REQUIRED"
            statusText.setTextColor(warning)
            actionButton.text = "ENABLE ACCESSIBILITY"
            actionButton.setTextColor(textColor)
            actionButton.background = rounded(Color.rgb(122, 31, 31), dp(18))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, DriftRokidService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openAccessibility() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun matchWrap() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
