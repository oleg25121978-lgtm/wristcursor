package com.drift.bleremoterokid;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.flyfishxu.kadb.Kadb;
import com.flyfishxu.kadb.cert.KadbCert;
import com.flyfishxu.kadb.cert.KadbCertPolicy;
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * R08-style maintenance self-arm.
 *
 * After the first successful local Wireless Debugging pairing, the app keeps its ADB
 * private key in app-private storage. On boot / accessibility reconnect / app launch it
 * restores the shell bridge and watchdog over 127.0.0.1:5555. Wi-Fi is not required.
 *
 * Adapted from Anezium/R08-Access-Bridge (Apache-2.0).
 */
final class SelfArmController {
    private static final String TAG = "RokidSelfArmR08";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SHELL_TIMEOUT_MS = 15000;

    static final String SETTING_ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time";

    // Exact R08 maintenance timings.
    static final long[] BOOT_RETRY_DELAYS_MS = {4_000L, 30_000L, 90_000L, 240_000L};
    static final long[] SERVICE_CONNECTED_RETRY_DELAYS_MS = {8_000L, 30_000L, 90_000L, 240_000L};
    private static final long[] LAUNCH_DELAYS_MS = {0L};
    private static final long ADBD_START_GRACE_MS = 4_000L;
    private static final long HELPER_LIVENESS_SETTLE_MS = 16_000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile boolean certConfigured;

    enum UsbDebugging { ON, ENABLED_NOW, OFF_NO_PERMISSION }

    static final class KeyMissingException extends Exception {
        KeyMissingException(String message) { super(message); }
    }

    static final class Outcome {
        final boolean success;
        final boolean terminal;
        final String state;
        final String detail;

        private Outcome(boolean success, boolean terminal, String state, String detail) {
            this.success = success;
            this.terminal = terminal;
            this.state = state;
            this.detail = detail;
        }

        static Outcome success(String detail) {
            return new Outcome(true, true, "auto_rearm_complete", detail);
        }

        static Outcome terminal(String state, String detail) {
            return new Outcome(false, true, state, detail);
        }

        static Outcome retry(String state, String detail) {
            return new Outcome(false, false, state, detail);
        }
    }

    private SelfArmController() {}

    static void armOnLaunch(Context context) {
        armAsync(context, "launch", LAUNCH_DELAYS_MS, false);
    }

    static void armOnBoot(Context context) {
        armAsync(context, "boot", BOOT_RETRY_DELAYS_MS, false);
    }

    static void armOnServiceConnected(Context context) {
        armAsync(context, "service_connected", SERVICE_CONNECTED_RETRY_DELAYS_MS, true);
    }

    static boolean isConfigured(Context context) {
        return isBridgeArmed(context.getApplicationContext());
    }

    /**
     * R08 post-bootstrap sequence. Crucially, adb_enabled is changed only AFTER the
     * Wireless-Debugging shell has installed the helpers, because toggling it can restart adbd.
     */
    static String afterLocalBootstrap(Context context) {
        Context app = context.getApplicationContext();
        UsbDebugging usb = ensureUsbDebuggingEnabled(app);
        keepAdbKeysForever(app);

        if (usb == UsbDebugging.ON) {
            return "usb debugging on";
        }
        if (usb == UsbDebugging.OFF_NO_PERMISSION) {
            LocalSelfArmStatus.reportSimple(app, "usb_debugging_off");
            return "usb debugging OFF";
        }

        try {
            Thread.sleep(HELPER_LIVENESS_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "usb debugging enabled";
        }

        if (PrivilegedShortcutBridge.isCurrent(app)) {
            return "usb debugging enabled; helpers alive";
        }

        Log.i(TAG, "helpers died after enabling USB debugging; re-arming over loopback");
        try {
            configureCert(app);
            return "usb debugging enabled; re-armed: " + armOverLoopback(app);
        } catch (Exception e) {
            Log.w(TAG, "post-bootstrap loopback re-arm failed", e);
            LocalSelfArmStatus.reportSimple(app, "bootstrap_rearm_failed");
            return "usb debugging enabled; loopback re-arm failed: " + firstLine(shortMessage(e));
        }
    }

    static String rearmNow(Context context) throws Exception {
        Context app = context.getApplicationContext();
        UsbDebugging usb = ensureUsbDebuggingEnabled(app);
        if (usb == UsbDebugging.OFF_NO_PERMISSION) {
            throw new IOException("USB debugging off and WRITE_SECURE_SETTINGS unavailable");
        }
        if (usb == UsbDebugging.ENABLED_NOW) {
            Thread.sleep(ADBD_START_GRACE_MS);
        }
        keepAdbKeysForever(app);
        configureCert(app);
        return armOverLoopback(app);
    }

    private static void armAsync(Context context, String reason, long[] delays, boolean onlyWhenHelpersDown) {
        Context app = context.getApplicationContext();
        if (!isBridgeArmed(app)) {
            Log.d(TAG, "skip " + reason + ": self-arm not configured");
            return;
        }
        if (onlyWhenHelpersDown && PrivilegedShortcutBridge.isCurrent(app)) {
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            Log.d(TAG, "skip " + reason + ": recovery already running");
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                Outcome last = null;
                for (int attempt = 0; attempt < delays.length; attempt++) {
                    long delay = delays[attempt];
                    if (delay > 0) Thread.sleep(delay);

                    if (!isBridgeArmed(app)) return;
                    last = armOnce(app);
                    Log.i(TAG, "self-arm " + reason + " attempt=" + (attempt + 1) + "/" + delays.length
                            + " state=" + last.state + " " + last.detail);

                    if (last.success) {
                        LocalSelfArmStatus.reportSimple(app, last.state);
                        return;
                    }
                    if (last.terminal) {
                        LocalSelfArmStatus.reportSimple(app, last.state);
                        return;
                    }
                }

                if (last != null && !PrivilegedShortcutBridge.isArmed(app)) {
                    LocalSelfArmStatus.reportSimple(app, last.state);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Log.w(TAG, "self-arm " + reason + " failed", t);
                SelfArmState.status = "RECOVERY FAILED: " + firstLine(shortMessage(t));
            } finally {
                RUNNING.set(false);
            }
        }, "rokid-r08-self-arm");
        worker.setDaemon(true);
        worker.start();
    }

    static Outcome armOnce(Context context) throws InterruptedException {
        String secureStatus = repairAccessibilityFromApp(context);

        if (PrivilegedShortcutBridge.isCurrent(context)) {
            return Outcome.success(secureStatus + "helpers alive");
        }

        UsbDebugging usb = ensureUsbDebuggingEnabled(context);
        if (usb == UsbDebugging.OFF_NO_PERMISSION) {
            return Outcome.terminal("usb_debugging_off",
                    "USB debugging off and WRITE_SECURE_SETTINGS unavailable");
        }
        if (usb == UsbDebugging.ENABLED_NOW) {
            Thread.sleep(ADBD_START_GRACE_MS);
        }

        keepAdbKeysForever(context);

        try {
            configureCert(context);
        } catch (KeyMissingException e) {
            return Outcome.terminal("adb_key_missing", e.getMessage());
        }

        try {
            return Outcome.success(secureStatus + armOverLoopback(context));
        } catch (Exception e) {
            return Outcome.retry("adb_loopback_unreachable", firstLine(shortMessage(e)));
        }
    }

    static String armOverLoopback(Context context) throws Exception {
        return armOverAdb(context, BridgeProtocol.DEFAULT_ADB_PORT);
    }

    private static String armOverAdb(Context context, int port) throws Exception {
        String bridgeScript = Base64.encodeToString(
                readRawResource(context, R.raw.rokid_shortcut_bridge), Base64.NO_WRAP);
        String watchdogScript = Base64.encodeToString(
                readRawResource(context, R.raw.rokid_ble_watchdog), Base64.NO_WRAP);

        PrivilegedShortcutBridge.ensureReady(context);

        Kadb kadb = null;
        try {
            kadb = new Kadb("127.0.0.1", port, CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS);
            String probe = kadb.shell("echo rokid-r08-self-arm").getOutput().trim();
            if (!probe.endsWith("rokid-r08-self-arm")) {
                throw new IOException("adb probe mismatch: " + firstLine(probe));
            }

            installScript(kadb, bridgeScript, BridgeProtocol.REMOTE_SCRIPT);
            installScript(kadb, watchdogScript, BridgeProtocol.REMOTE_WATCHDOG_SCRIPT);

            String bridgeStatus = firstLine(kadb.shell(
                    "sh " + BridgeProtocol.REMOTE_SCRIPT + " start").getOutput().trim());
            String watchdogStatus = firstLine(kadb.shell(
                    "sh " + BridgeProtocol.REMOTE_WATCHDOG_SCRIPT + " start").getOutput().trim());

            if (!bridgeStatus.contains("pid=")) {
                throw new IOException("shortcut bridge did not start: " + bridgeStatus);
            }
            if (!watchdogStatus.contains("pid=")) {
                throw new IOException("watchdog did not start: " + watchdogStatus);
            }

            return "bridge " + bridgeStatus + "; watchdog " + watchdogStatus;
        } finally {
            if (kadb != null) {
                try { kadb.close(); } catch (RuntimeException ignored) {}
            }
        }
    }

    private static void installScript(Kadb kadb, String encodedScript, String remotePath) {
        kadb.shell("printf '%s' '" + encodedScript + "' | base64 -d > " + remotePath);
        kadb.shell("chmod 755 " + remotePath);
    }

    static UsbDebugging ensureUsbDebuggingEnabled(Context context) {
        ContentResolver resolver = context.getContentResolver();
        try {
            if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1) {
                return UsbDebugging.ON;
            }
        } catch (Throwable ignored) {}

        try {
            if (Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)) {
                Log.i(TAG, "USB debugging enabled so adbd survives reboot");
                return UsbDebugging.ENABLED_NOW;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS unavailable", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot enable USB debugging", e);
        }
        return UsbDebugging.OFF_NO_PERMISSION;
    }

    static void keepAdbKeysForever(Context context) {
        ContentResolver resolver = context.getContentResolver();
        try {
            long current = Settings.Global.getLong(
                    resolver, SETTING_ADB_ALLOWED_CONNECTION_TIME, -1L);
            if (current != 0L) {
                Settings.Global.putLong(
                        resolver, SETTING_ADB_ALLOWED_CONNECTION_TIME, 0L);
            }
        } catch (SecurityException e) {
            Log.d(TAG, "cannot disable ADB key expiry", e);
        }
    }

    private static String repairAccessibilityFromApp(Context context) {
        try {
            String service = new ComponentName(context, BleNavigationService.class).flattenToString();
            String current = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null || "null".equals(current)) current = "";

            if (!containsService(current, service)) {
                String updated = TextUtils.isEmpty(current) ? service : current + ":" + service;
                Settings.Secure.putString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        updated);
                Settings.Secure.putInt(
                        context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1);
                return "accessibility repaired; ";
            }

            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            if (!"1".equals(enabled)) {
                Settings.Secure.putInt(
                        context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1);
                return "accessibility enabled; ";
            }
        } catch (SecurityException e) {
            Log.d(TAG, "app-side accessibility repair lacks permission", e);
        } catch (RuntimeException e) {
            Log.d(TAG, "app-side accessibility repair failed", e);
        }
        return "";
    }

    private static boolean containsService(String services, String service) {
        if (TextUtils.isEmpty(services)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(services);
        while (splitter.hasNext()) {
            if (service.equalsIgnoreCase(splitter.next())) return true;
        }
        return false;
    }

    private static synchronized void configureCert(Context context) throws KeyMissingException {
        if (certConfigured) return;

        File key = internalKeyFile(context);
        if (!key.isFile()) {
            throw new KeyMissingException("self-arm adb key missing");
        }

        KadbCert.INSTANCE.configure(
                new OkioFilePrivateKeyStore(
                        okio.Path.Companion.get(key.getAbsolutePath()),
                        okio.FileSystem.SYSTEM),
                new KadbCertPolicy(),
                Collections.emptyList());
        certConfigured = true;
    }

    static File internalKeyFile(Context context) {
        return new File(
                new File(context.getFilesDir(), "kadb"),
                BridgeProtocol.SELF_ARM_ADB_KEY_FILE);
    }

    private static boolean isBridgeArmed(Context context) {
        return context.getSharedPreferences(
                BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
                .getBoolean(BridgeProtocol.PREF_BRIDGE_ARMED, false);
    }

    private static byte[] readRawResource(Context context, int id) throws Exception {
        try (InputStream input = context.getResources().openRawResource(id);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m.trim();
    }

    private static String firstLine(String value) {
        if (value == null) return "";
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline).trim() : value.trim();
    }
}
