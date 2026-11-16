package com.drift.bleremoterokid

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.FileSystem
import okio.Path
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R08 maintenance self-arm, adapted to Rokid BLE Receiver.
 * Adapted from Anezium/R08-Access-Bridge (Apache-2.0).
 */
object R08SelfArmController {
    private const val TAG = "RokidR08SelfArm"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val SHELL_TIMEOUT_MS = 15_000
    private const val ADBD_START_GRACE_MS = 4_000L
    private const val HELPER_LIVENESS_SETTLE_MS = 16_000L

    private val BOOT_RETRY_DELAYS_MS = longArrayOf(4_000L, 30_000L, 90_000L, 240_000L)
    private val SERVICE_CONNECTED_RETRY_DELAYS_MS = longArrayOf(8_000L, 30_000L, 90_000L, 240_000L)
    private val LAUNCH_DELAYS_MS = longArrayOf(0L)

    private val running = AtomicBoolean(false)
    @Volatile private var certConfigured = false

    private enum class UsbDebugging { ON, ENABLED_NOW, OFF_NO_PERMISSION }

    private data class Outcome(
        val success: Boolean,
        val terminal: Boolean,
        val state: String,
        val detail: String,
    ) {
        companion object {
            fun success(detail: String) = Outcome(true, true, "auto_rearm_complete", detail)
            fun terminal(state: String, detail: String) = Outcome(false, true, state, detail)
            fun retry(state: String, detail: String) = Outcome(false, false, state, detail)
        }
    }

    @JvmStatic
    fun armOnLaunch(context: Context) =
        armAsync(context, "launch", LAUNCH_DELAYS_MS, false)

    @JvmStatic
    fun armOnBoot(context: Context) =
        armAsync(context, "boot", BOOT_RETRY_DELAYS_MS, false)

    @JvmStatic
    fun armOnServiceConnected(context: Context) =
        armAsync(context, "service_connected", SERVICE_CONNECTED_RETRY_DELAYS_MS, true)

    @JvmStatic
    fun isConfigured(context: Context): Boolean =
        isBridgeArmed(context.applicationContext)

    @JvmStatic
    fun afterLocalBootstrap(context: Context): String {
        val app = context.applicationContext
        val usb = ensureUsbDebuggingEnabled(app)
        keepAdbKeysForever(app)

        when (usb) {
            UsbDebugging.ON -> return "usb debugging on"
            UsbDebugging.OFF_NO_PERMISSION -> {
                LocalSelfArmStatus.reportSimple(app, "usb_debugging_off")
                return "usb debugging OFF"
            }
            UsbDebugging.ENABLED_NOW -> Unit
        }

        try {
            Thread.sleep(HELPER_LIVENESS_SETTLE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return "usb debugging enabled"
        }

        if (PrivilegedShortcutBridge.isCurrent(app)) {
            return "usb debugging enabled; helpers alive"
        }

        Log.i(TAG, "helpers died after enabling USB debugging; re-arming over loopback")
        return try {
            configureCert(app)
            "usb debugging enabled; re-armed: " + armOverLoopback(app)
        } catch (e: Exception) {
            Log.w(TAG, "post-bootstrap loopback re-arm failed", e)
            LocalSelfArmStatus.reportSimple(app, "bootstrap_rearm_failed")
            "usb debugging enabled; loopback re-arm failed: " + firstLine(shortMessage(e))
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun rearmNow(context: Context): String {
        val app = context.applicationContext
        val usb = ensureUsbDebuggingEnabled(app)
        if (usb == UsbDebugging.OFF_NO_PERMISSION) {
            throw IOException("USB debugging off and WRITE_SECURE_SETTINGS unavailable")
        }
        if (usb == UsbDebugging.ENABLED_NOW) {
            Thread.sleep(ADBD_START_GRACE_MS)
        }
        keepAdbKeysForever(app)
        configureCert(app)
        return armOverLoopback(app)
    }

    private fun armAsync(context: Context, reason: String, delays: LongArray, onlyWhenHelpersDown: Boolean) {
        val app = context.applicationContext
        if (!isBridgeArmed(app)) {
            Log.d(TAG, "skip $reason: self-arm not configured")
            return
        }
        if (onlyWhenHelpersDown && PrivilegedShortcutBridge.isCurrent(app)) return
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "skip $reason: recovery already running")
            return
        }

        Thread({
            try {
                var last: Outcome? = null
                for ((index, delay) in delays.withIndex()) {
                    if (delay > 0L) Thread.sleep(delay)
                    if (!isBridgeArmed(app)) return@Thread

                    last = armOnce(app)
                    Log.i(
                        TAG,
                        "self-arm $reason attempt=${index + 1}/${delays.size} " +
                            "state=${last.state} ${last.detail}",
                    )

                    if (last.success || last.terminal) {
                        LocalSelfArmStatus.reportSimple(app, last.state)
                        return@Thread
                    }
                }

                if (last != null && !PrivilegedShortcutBridge.isArmed(app)) {
                    LocalSelfArmStatus.reportSimple(app, last.state)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                Log.w(TAG, "self-arm $reason failed", t)
                SelfArmState.status = "RECOVERY FAILED: " + firstLine(shortMessage(t))
            } finally {
                running.set(false)
            }
        }, "rokid-r08-self-arm").apply {
            isDaemon = true
            start()
        }
    }

    private fun armOnce(context: Context): Outcome {
        val secureStatus = repairAccessibilityFromApp(context)

        if (PrivilegedShortcutBridge.isCurrent(context)) {
            return Outcome.success(secureStatus + "helpers alive")
        }

        val usb = ensureUsbDebuggingEnabled(context)
        if (usb == UsbDebugging.OFF_NO_PERMISSION) {
            return Outcome.terminal(
                "usb_debugging_off",
                "USB debugging off and WRITE_SECURE_SETTINGS unavailable",
            )
        }
        if (usb == UsbDebugging.ENABLED_NOW) {
            Thread.sleep(ADBD_START_GRACE_MS)
        }

        keepAdbKeysForever(context)

        try {
            configureCert(context)
        } catch (e: IOException) {
            return Outcome.terminal("adb_key_missing", e.message.orEmpty())
        }

        return try {
            Outcome.success(secureStatus + armOverLoopback(context))
        } catch (e: Exception) {
            Outcome.retry("adb_loopback_unreachable", firstLine(shortMessage(e)))
        }
    }

    private fun armOverLoopback(context: Context): String =
        armOverAdb(context, BridgeProtocol.DEFAULT_ADB_PORT)

    private fun armOverAdb(context: Context, port: Int): String {
        val bridgeScript = Base64.encodeToString(
            readRawResource(context, R.raw.rokid_shortcut_bridge),
            Base64.NO_WRAP,
        )
        val watchdogScript = Base64.encodeToString(
            readRawResource(context, R.raw.rokid_ble_watchdog),
            Base64.NO_WRAP,
        )

        PrivilegedShortcutBridge.ensureReady(context)

        val kadb = Kadb("127.0.0.1", port, CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS)
        try {
            val probe = kadb.shell("echo rokid-r08-self-arm").output.trim()
            if (!probe.endsWith("rokid-r08-self-arm")) {
                throw IOException("adb probe mismatch: " + firstLine(probe))
            }

            installScript(kadb, bridgeScript, BridgeProtocol.REMOTE_SCRIPT)
            installScript(kadb, watchdogScript, BridgeProtocol.REMOTE_WATCHDOG_SCRIPT)

            val bridgeStatus = firstLine(
                kadb.shell("sh ${BridgeProtocol.REMOTE_SCRIPT} start").output.trim()
            )
            val watchdogStatus = firstLine(
                kadb.shell("sh ${BridgeProtocol.REMOTE_WATCHDOG_SCRIPT} start").output.trim()
            )

            if (!bridgeStatus.contains("pid=")) {
                throw IOException("shortcut bridge did not start: $bridgeStatus")
            }
            if (!watchdogStatus.contains("pid=")) {
                throw IOException("watchdog did not start: $watchdogStatus")
            }

            return "bridge $bridgeStatus; watchdog $watchdogStatus"
        } finally {
            runCatching { kadb.close() }
        }
    }

    private fun installScript(kadb: Kadb, encoded: String, remotePath: String) {
        kadb.shell("printf '%s' '$encoded' | base64 -d > $remotePath")
        kadb.shell("chmod 755 $remotePath")
    }

    private fun ensureUsbDebuggingEnabled(context: Context): UsbDebugging {
        val resolver = context.contentResolver
        runCatching {
            if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1) {
                return UsbDebugging.ON
            }
        }

        return try {
            if (Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)) {
                Log.i(TAG, "USB debugging enabled so adbd survives reboot")
                UsbDebugging.ENABLED_NOW
            } else {
                UsbDebugging.OFF_NO_PERMISSION
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS unavailable", e)
            UsbDebugging.OFF_NO_PERMISSION
        }
    }

    private fun keepAdbKeysForever(context: Context) {
        val resolver = context.contentResolver
        try {
            val current = Settings.Global.getLong(
                resolver,
                SelfArmController.SETTING_ADB_ALLOWED_CONNECTION_TIME,
                -1L,
            )
            if (current != 0L) {
                Settings.Global.putLong(
                    resolver,
                    SelfArmController.SETTING_ADB_ALLOWED_CONNECTION_TIME,
                    0L,
                )
            }
        } catch (e: SecurityException) {
            Log.d(TAG, "cannot disable ADB key expiry", e)
        }
    }

    private fun repairAccessibilityFromApp(context: Context): String {
        return try {
            val service = ComponentName(context, BleNavigationService::class.java).flattenToString()
            var current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (current == null || current == "null") current = ""

            if (!containsService(current, service)) {
                val updated = if (TextUtils.isEmpty(current)) service else "$current:$service"
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated,
                )
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1,
                )
                "accessibility repaired; "
            } else {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                )
                if (enabled != "1") {
                    Settings.Secure.putInt(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1,
                    )
                    "accessibility enabled; "
                } else {
                    ""
                }
            }
        } catch (e: SecurityException) {
            Log.d(TAG, "app-side accessibility repair lacks permission", e)
            ""
        } catch (e: RuntimeException) {
            Log.d(TAG, "app-side accessibility repair failed", e)
            ""
        }
    }

    private fun containsService(services: String, target: String): Boolean {
        if (TextUtils.isEmpty(services)) return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(services)
        while (splitter.hasNext()) {
            if (target.equals(splitter.next(), ignoreCase = true)) return true
        }
        return false
    }

    @Synchronized
    private fun configureCert(context: Context) {
        if (certConfigured) return

        val key = internalKeyFile(context)
        if (!key.isFile) throw IOException("self-arm adb key missing")

        KadbCert.configure(
            OkioFilePrivateKeyStore(okioPath(key.absolutePath), FileSystem.SYSTEM),
            KadbCertPolicy(),
            emptyList(),
        )
        certConfigured = true
    }

    @JvmStatic
    fun internalKeyFile(context: Context): File =
        File(File(context.filesDir, "kadb"), BridgeProtocol.SELF_ARM_ADB_KEY_FILE)

    private fun isBridgeArmed(context: Context): Boolean =
        context.getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
            .getBoolean(BridgeProtocol.PREF_BRIDGE_ARMED, false)

    private fun readRawResource(context: Context, id: Int): ByteArray =
        context.resources.openRawResource(id).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }

    private fun okioPath(value: String): Path =
        Path::class.java.getMethod("get", String::class.java).invoke(null, value) as Path

    private fun shortMessage(t: Throwable): String =
        t.message?.trim().takeUnless { it.isNullOrEmpty() } ?: t.javaClass.simpleName

    private fun firstLine(value: String): String =
        value.substringBefore('\n').trim()
}
