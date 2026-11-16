package com.drift.bleremoterokid

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class SelfArmBootstrapper(context: Context) {
    private val appContext = context.applicationContext

    fun bootstrap(pairPort: Int, pairingCode: String, connectPort: Int): String {
        require(pairPort in 1024..65535) { "pair port missing" }
        require(connectPort in 1024..65535) { "connect port missing" }
        require(pairingCode.matches(Regex("\\d{6}"))) { "pairing code invalid" }

        configureCert()
        pair(pairPort, pairingCode)
        val result = installViaPort(connectPort)

        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ARMED, true).apply()

        try {
            Settings.Global.putLong(appContext.contentResolver, "adb_allowed_connection_time", 0L)
            Settings.Global.putInt(appContext.contentResolver, Settings.Global.ADB_ENABLED, 1)
        } catch (_: Throwable) {
        }

        Thread.sleep(4500L)
        try {
            installViaPort(LOOPBACK_PORT)
        } catch (t: Throwable) {
            Log.w(TAG, "loopback restart after bootstrap failed", t)
        }

        PrivilegedShortcutBridge.requestWifiEnabled(appContext, false)
        return result
    }

    fun rearmLoopback(): String {
        configureCert()
        return installViaPort(LOOPBACK_PORT)
    }

    private fun pair(port: Int, code: String) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                runBlocking {
                    Kadb.pair(LOCALHOST, port, code, "Rokid BLE Receiver")
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }.apply {
            name = "rokid-self-arm-pair"
            isDaemon = true
            start()
        }
        if (!done.await(15, TimeUnit.SECONDS)) {
            thread.interrupt()
            throw IOException("pairing timeout")
        }
        failure.get()?.let { throw IOException("pairing failed: " + shortMessage(it), it) }
    }

    private fun installViaPort(port: Int): String {
        PrivilegedShortcutBridge.ensureReady(appContext)
        val bridge = encodedRaw(R.raw.rokid_shortcut_bridge)
        val watchdog = encodedRaw(R.raw.rokid_ble_watchdog)
        val kadb = Kadb(LOCALHOST, port, 5000, 15000)
        try {
            val probe = kadb.shell("echo rokid-self-arm")
            if (probe.exitCode != 0 || !probe.output.trim().endsWith("rokid-self-arm")) {
                throw IOException("ADB probe failed: " + probe.allOutput.trim())
            }
            val cmd = buildString {
                appendLine("PKG='com.drift.bleremoterokid'")
                appendLine("BRIDGE='/data/local/tmp/rokid-ble-shortcut-bridge.sh'")
                appendLine("WATCHDOG='/data/local/tmp/rokid-ble-a11y-watchdog.sh'")
                appendLine("printf '%s' '$bridge' | base64 -d > \"\$BRIDGE\"")
                appendLine("printf '%s' '$watchdog' | base64 -d > \"\$WATCHDOG\"")
                appendLine("chmod 755 \"\$BRIDGE\" \"\$WATCHDOG\"")
                appendLine("pm grant \$PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true")
                appendLine("settings put global adb_allowed_connection_time 0 >/dev/null 2>&1 || true")
                appendLine("setprop persist.adb.tcp.port 5555 >/dev/null 2>&1 || true")
                appendLine("sh \"\$BRIDGE\" restart")
                appendLine("sh \"\$WATCHDOG\" restart")
                appendLine("echo SELF_ARM_OK")
            }
            val out = kadb.shell(cmd)
            if (out.exitCode != 0 || !out.output.contains("SELF_ARM_OK")) {
                throw IOException("bootstrap failed: " + out.allOutput.trim())
            }
            return out.output.trim()
        } finally {
            runCatching { kadb.close() }
        }
    }

    private fun configureCert() {
        synchronized(CERT_LOCK) {
            if (configured) return
            val file = File(File(appContext.filesDir, "kadb"), "adbkey.pem")
            file.parentFile?.let {
                if (!it.isDirectory && !it.mkdirs() && !it.isDirectory) {
                    throw IOException("cannot create KADB key directory")
                }
            }
            KadbCert.configure(
                OkioFilePrivateKeyStore(okioPath(file.absolutePath), FileSystem.SYSTEM),
                KadbCertPolicy(),
                emptyList(),
            )
            configured = true
        }
    }

    private fun encodedRaw(id: Int): String =
        appContext.resources.openRawResource(id).use {
            Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
        }

    private fun okioPath(value: String): Path =
        Path::class.java.getMethod("get", String::class.java).invoke(null, value) as Path

    private fun shortMessage(t: Throwable): String =
        t.message?.trim().takeUnless { it.isNullOrEmpty() } ?: t.javaClass.simpleName

    companion object {
        const val PREFS = "self_arm"
        const val KEY_ARMED = "armed"
        const val LOOPBACK_PORT = 5555
        private const val LOCALHOST = "127.0.0.1"
        private const val TAG = "RokidSelfArm"
        private val CERT_LOCK = Any()
        @Volatile private var configured = false
    }
}
