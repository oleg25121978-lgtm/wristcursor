package com.drift.bleremoterokid

import android.content.Context
import android.util.Base64
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Initial one-time self-pair using the same ordering as R08 Access Bridge.
 * Adapted from Anezium/R08-Access-Bridge (Apache-2.0).
 */
internal class SelfArmLocalAdbBootstrapper(context: Context) {
    private val appContext = context.applicationContext

    data class BootstrapResult(
        val pairHost: String,
        val pairPort: Int,
        val connectHost: String,
        val connectPort: Int,
        val output: String,
        val postBootstrap: String,
    )

    fun bootstrap(pairPort: Int, pairingCode: String, connectPort: Int): BootstrapResult {
        val code = pairingCode.trim()
        if (!code.matches(Regex("\\d{6}"))) {
            throw IOException("Wireless Debugging pairing code is invalid")
        }
        if (pairPort !in 1024..65535) {
            throw IOException("Wireless Debugging pairing port is missing")
        }
        if (connectPort !in 1024..65535) {
            throw IOException("Wireless Debugging connect port is missing")
        }

        configureCert()
        pairWirelessDebugging(pairPort, code)

        if (!PrivilegedShortcutBridge.ensureReady(appContext)) {
            throw IOException("cannot prepare bridge request directory")
        }

        val kadb = Kadb(LOCALHOST, connectPort, CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS)
        try {
            val probe = kadb.shell("echo rokid-r08-bootstrap")
            if (probe.exitCode != 0 || probe.output.trim() != "rokid-r08-bootstrap") {
                throw IOException(
                    "connect probe failed on 127.0.0.1:$connectPort: ${probe.allOutput.trim()}"
                )
            }

            val bootstrap = kadb.shell(buildBootstrapCommand())
            if (bootstrap.exitCode != 0) {
                throw IOException(
                    "bootstrap shell failed with exit ${bootstrap.exitCode}: ${bootstrap.allOutput.trim()}"
                )
            }

            val marker = bootstrap.output.lineSequence()
                .firstOrNull { it.contains(INSTALL_SENTINEL) }
                .orEmpty()
            if (!marker.contains("grant=1") || !marker.contains("persist=5555")) {
                throw IOException("bootstrap verification failed: $marker")
            }

            markBridgeArmed()

            // R08 rule: adb_enabled is changed only AFTER the Wireless-Debugging shell
            // returns, because changing it can restart adbd and kill helper children.
            val post = SelfArmController.afterLocalBootstrap(appContext)
            Log.i(TAG, "post-bootstrap: $post")

            PrivilegedShortcutBridge.requestWifiEnabled(appContext, false)

            return BootstrapResult(
                pairHost = LOCALHOST,
                pairPort = pairPort,
                connectHost = LOCALHOST,
                connectPort = connectPort,
                output = bootstrap.output.trim(),
                postBootstrap = post,
            )
        } catch (e: RuntimeException) {
            throw IOException(
                "connect to 127.0.0.1:$connectPort failed: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        } finally {
            runCatching { kadb.close() }
        }
    }

    private fun pairWirelessDebugging(port: Int, code: String) {
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
            name = "rokid-r08-self-pair"
            isDaemon = true
        }

        thread.start()
        if (!done.await(PAIRING_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            thread.interrupt()
            throw IOException("Wireless Debugging self-pairing timed out")
        }

        val cause = failure.get()
        if (cause is Error) throw cause
        if (cause != null) {
            throw IOException(
                "Wireless Debugging self-pairing failed: ${cause.message ?: cause.javaClass.simpleName}",
                cause,
            )
        }
    }

    private fun configureCert() {
        synchronized(CERT_LOCK) {
            if (configured) return
            val key = SelfArmController.internalKeyFile(appContext)
            val dir = key.parentFile
            if (dir != null && !dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("cannot create KADB key directory")
            }

            KadbCert.configure(
                OkioFilePrivateKeyStore(okioPath(key.absolutePath), FileSystem.SYSTEM),
                KadbCertPolicy(),
                emptyList(),
            )
            configured = true
        }
    }

    internal fun buildBootstrapCommand(): String {
        val bridge = encodedRaw(R.raw.rokid_shortcut_bridge)
        val watchdog = encodedRaw(R.raw.rokid_ble_watchdog)

        return buildString {
            appendLine("PKG='com.drift.bleremoterokid'")
            appendLine("BRIDGE='${BridgeProtocol.REMOTE_SCRIPT}'")
            appendLine("WATCHDOG='${BridgeProtocol.REMOTE_WATCHDOG_SCRIPT}'")
            appendLine("printf '%s' '$bridge' | base64 -d > \"\$BRIDGE\"")
            appendLine("printf '%s' '$watchdog' | base64 -d > \"\$WATCHDOG\"")
            appendLine("chmod 755 \"\$BRIDGE\" \"\$WATCHDOG\"")
            appendLine("pm grant \$PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true")
            appendLine("settings put global adb_wifi_enabled 1 >/dev/null 2>&1 || true")
            appendLine("settings put global ${SelfArmController.SETTING_ADB_ALLOWED_CONNECTION_TIME} 0 >/dev/null 2>&1 || true")
            // Do not set adb_enabled here. SelfArmController.afterLocalBootstrap does it.
            appendLine("setprop persist.adb.tcp.port ${BridgeProtocol.DEFAULT_ADB_PORT} >/dev/null 2>&1 || true")
            appendLine("BRIDGE_START=\$(sh \"\$BRIDGE\" restart 2>&1 || true)")
            appendLine("BRIDGE_STATUS=\$(sh \"\$BRIDGE\" status 2>&1 || true)")
            appendLine("WATCHDOG_START=\$(sh \"\$WATCHDOG\" restart 2>&1 || true)")
            appendLine("WATCHDOG_STATUS=\$(sh \"\$WATCHDOG\" status 2>&1 || true)")
            appendLine("GRANTED=\$(dumpsys package \$PKG 2>/dev/null | grep -A3 android.permission.WRITE_SECURE_SETTINGS | grep -c 'granted=true')")
            appendLine("PERSIST_PORT=\$(getprop persist.adb.tcp.port)")
            appendLine("SERVICE_PORT=\$(getprop service.adb.tcp.port)")
            appendLine("echo '$INSTALL_SENTINEL grant='\"\$GRANTED\"' persist='\"\$PERSIST_PORT\"' service='\"\$SERVICE_PORT\"")
            appendLine("[ \"\$GRANTED\" != \"0\" ] || exit 21")
            appendLine("[ \"\$PERSIST_PORT\" = \"${BridgeProtocol.DEFAULT_ADB_PORT}\" ] || exit 22")
            appendLine("case \"\$BRIDGE_STATUS\" in *\"pid=\"*) ;; *) echo \"bridge failed: \$BRIDGE_START \$BRIDGE_STATUS\"; exit 23 ;; esac")
            appendLine("case \"\$WATCHDOG_STATUS\" in *\"pid=\"*) ;; *) echo \"watchdog failed: \$WATCHDOG_START \$WATCHDOG_STATUS\"; exit 24 ;; esac")
        }
    }

    private fun markBridgeArmed() {
        appContext.getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BridgeProtocol.PREF_BRIDGE_ARMED, true)
            .apply()
    }

    private fun encodedRaw(id: Int): String =
        appContext.resources.openRawResource(id).use {
            Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
        }

    private fun okioPath(value: String): Path =
        Path::class.java.getMethod("get", String::class.java).invoke(null, value) as Path

    companion object {
        private const val TAG = "RokidR08Bootstrap"
        private const val INSTALL_SENTINEL = "ROKID_R08_SELF_ARM_RESULT"
        private const val LOCALHOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SHELL_TIMEOUT_MS = 15_000
        private const val PAIRING_TIMEOUT_MS = 12_000L
        private val CERT_LOCK = Any()
        @Volatile private var configured = false
    }
}
