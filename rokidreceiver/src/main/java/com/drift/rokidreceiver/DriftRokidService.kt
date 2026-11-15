package com.drift.rokidreceiver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DriftRokidService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private var overlay: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var dragStartX: Float? = null
    private var dragStartY: Float? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        centerCursor()
        showCursor()
        startReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopReceiver()
        hideCursor()
        super.onDestroy()
    }

    private fun startReceiver() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(InetSocketAddress(PORT))
                }
                socket = s
                val buffer = ByteArray(8192)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        s.receive(packet)
                        val msg = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                        when {
                            msg.startsWith("DRIFT?") -> reply(s, packet, "DRIFT! Rokid Glasses")
                            msg.startsWith("HELLO") -> {
                                reply(s, packet, "OK")
                                main.post { showCursor() }
                            }
                            else -> handleCommand(msg)
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            } catch (_: Throwable) {
            } finally {
                socket?.close()
                socket = null
                running.set(false)
            }
        }, "Drift-Rokid-UDP").also { it.start() }
    }

    private fun stopReceiver() {
        running.set(false)
        socket?.close()
        socket = null
        worker = null
    }

    private fun reply(s: DatagramSocket, packet: DatagramPacket, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        runCatching { s.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port)) }
    }

    private fun handleCommand(msg: String) {
        when {
            msg.startsWith("m ") -> {
                val p = msg.split(' ')
                if (p.size >= 3) {
                    val dx = p[1].toFloatOrNull() ?: 0f
                    val dy = p[2].toFloatOrNull() ?: 0f
                    main.post { moveCursor(dx, dy) }
                }
            }
            msg == "click" -> main.post { tap(cursorX, cursorY, 45) }
            msg == "double" -> main.post {
                tap(cursorX, cursorY, 45)
                main.postDelayed({ tap(cursorX, cursorY, 45) }, 130)
            }
            msg == "right" -> main.post { tap(cursorX, cursorY, 650) }
            msg == "down" -> main.post {
                dragStartX = cursorX
                dragStartY = cursorY
            }
            msg == "up" -> main.post {
                val sx = dragStartX
                val sy = dragStartY
                if (sx != null && sy != null) drag(sx, sy, cursorX, cursorY)
                dragStartX = null
                dragStartY = null
            }
            msg.startsWith("s ") -> {
                val steps = msg.substringAfter(' ').toIntOrNull() ?: 0
                if (steps != 0) main.post { scroll(steps) }
            }
            msg == "center" -> main.post { centerCursor() }
            msg == "key next" || msg == "key right" -> main.post { swipeHorizontal(left = true) }
            msg == "key prev" || msg == "key left" -> main.post { swipeHorizontal(left = false) }
            msg == "key up" -> main.post { swipeVertical(up = true) }
            msg == "key down" -> main.post { swipeVertical(up = false) }
            msg == "key enter" -> main.post { tap(cursorX, cursorY, 45) }
            msg == "key back" || msg == "key esc" -> main.post { performGlobalAction(GLOBAL_ACTION_BACK) }
            msg == "key home" -> main.post { performGlobalAction(GLOBAL_ACTION_HOME) }
            msg.startsWith("type ") -> main.post { typeText(msg.substringAfter("type ")) }
            msg == "BYE" -> Unit
        }
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun centerCursor() {
        cursorX = screenWidth() / 2f
        cursorY = screenHeight() / 2f
        updateOverlay()
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val scale = 1.35f
        cursorX = (cursorX + dx * scale).coerceIn(8f, max(8f, screenWidth() - 8f))
        cursorY = (cursorY + dy * scale).coerceIn(8f, max(8f, screenHeight() - 8f))
        updateOverlay()
    }

    private fun showCursor() {
        if (overlay != null) {
            updateOverlay()
            return
        }
        val size = dp(14)
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 255, 255, 255))
                setStroke(dp(2), Color.argb(240, 0, 210, 255))
            }
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        overlay = view
        overlayParams = lp
        runCatching { windowManager?.addView(view, lp) }
        updateOverlay()
    }

    private fun hideCursor() {
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
        overlayParams = null
    }

    private fun updateOverlay() {
        val v = overlay ?: return
        val lp = overlayParams ?: return
        lp.x = (cursorX - lp.width / 2f).toInt()
        lp.y = (cursorY - lp.height / 2f).toInt()
        runCatching { windowManager?.updateViewLayout(v, lp) }
    }

    private fun tap(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun drag(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val duration = max(180L, min(800L, (abs(x2 - x1) + abs(y2 - y1)).toLong() * 2L))
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun scroll(steps: Int) {
        val distance = min(420f, max(120f, abs(steps) * 55f))
        val dy = if (steps > 0) -distance else distance
        gestureLine(cursorX, cursorY, cursorX, (cursorY + dy).coerceIn(20f, screenHeight() - 20f), 180)
    }

    private fun swipeHorizontal(left: Boolean) {
        val w = screenWidth().toFloat()
        val h = screenHeight().toFloat()
        val y = h * 0.55f
        if (left) gestureLine(w * 0.78f, y, w * 0.22f, y, 180)
        else gestureLine(w * 0.22f, y, w * 0.78f, y, 180)
    }

    private fun swipeVertical(up: Boolean) {
        val w = screenWidth().toFloat()
        val h = screenHeight().toFloat()
        val x = w * 0.5f
        if (up) gestureLine(x, h * 0.78f, x, h * 0.22f, 180)
        else gestureLine(x, h * 0.22f, x, h * 0.78f, 180)
    }

    private fun gestureLine(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build(),
            null,
            null
        )
    }

    private fun typeText(encoded: String) {
        val text = runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val PORT = 47800
    }
}
