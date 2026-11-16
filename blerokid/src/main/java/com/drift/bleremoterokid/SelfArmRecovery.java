package com.drift.bleremoterokid;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Recovery only. Initial SELF-ARM remains the proven R14/7c6 implementation.
 *
 * R08 principle:
 * - never run unless initial self-arm already set armed=true;
 * - make sure USB debugging/adbd survives reboot;
 * - keep the paired ADB key;
 * - restore helpers over 127.0.0.1:5555;
 * - if loopback is unavailable but Wireless Debugging already has a live TLS port,
 *   use that port as an opportunistic fallback;
 * - retry after boot with the R08 maintenance schedule.
 */
final class SelfArmRecovery {
    private static final String TAG = "RokidR16Recovery";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static final long[] BOOT_DELAYS = {4_000L, 30_000L, 90_000L, 240_000L};
    private static final long[] SERVICE_DELAYS = {8_000L, 30_000L, 90_000L, 240_000L};
    private static final long[] LAUNCH_DELAYS = {0L, 4_000L, 15_000L};

    private SelfArmRecovery() {}

    static boolean isConfigured(Context context) {
        return context.getSharedPreferences(SelfArmBootstrapper.PREFS, Context.MODE_PRIVATE)
                .getBoolean(SelfArmBootstrapper.KEY_ARMED, false);
    }

    static void onLaunch(Context context) {
        rearmAsync(context, "launch", LAUNCH_DELAYS);
    }

    static void onBoot(Context context) {
        rearmAsync(context, "boot", BOOT_DELAYS);
    }

    static void onAccessibilityConnected(Context context) {
        rearmAsync(context, "accessibility", SERVICE_DELAYS);
    }

    private static void rearmAsync(Context context, String reason, long[] delays) {
        Context app = context.getApplicationContext();

        // Critical isolation: recovery cannot participate in first-time self-arm.
        if (!isConfigured(app)) {
            Log.d(TAG, "skip " + reason + ": not armed");
            return;
        }
        if (PrivilegedShortcutBridge.isCurrent(app)) {
            Log.d(TAG, "skip " + reason + ": helpers current");
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            Log.d(TAG, "skip " + reason + ": recovery already running");
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                String lastError = "";
                for (int i = 0; i < delays.length; i++) {
                    long delay = delays[i];
                    if (delay > 0L) Thread.sleep(delay);

                    if (!isConfigured(app)) return;
                    if (PrivilegedShortcutBridge.isCurrent(app)) {
                        SelfArmState.status = "SELF-ARM ACTIVE";
                        return;
                    }

                    try {
                        String out = attempt(app);
                        Log.i(TAG, reason + " attempt " + (i + 1) + " complete: " + firstLine(out));
                        SelfArmState.status = "SELF-ARM ACTIVE";
                        return;
                    } catch (Throwable t) {
                        lastError = shortMessage(t);
                        Log.w(TAG, reason + " attempt " + (i + 1) + " failed: " + lastError, t);
                    }
                }

                if (!PrivilegedShortcutBridge.isCurrent(app)) {
                    SelfArmState.status = "RECOVERY PENDING: " + firstLine(lastError);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                RUNNING.set(false);
            }
        }, "rokid-r16-recovery");
        thread.setDaemon(true);
        thread.start();
    }

    private static String attempt(Context app) throws Exception {
        repairAccessibility(app);

        boolean adbWasOff = Settings.Global.getInt(
                app.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1;

        if (adbWasOff) {
            boolean enabled;
            try {
                enabled = Settings.Global.putInt(
                        app.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            } catch (SecurityException e) {
                throw new IllegalStateException("USB debugging off; WRITE_SECURE_SETTINGS unavailable", e);
            }
            if (!enabled) {
                throw new IllegalStateException("USB debugging could not be enabled");
            }

            // Same reason as R08: enabling USB debugging can restart adbd.
            Thread.sleep(4_000L);
        }

        try {
            Settings.Global.putLong(
                    app.getContentResolver(), "adb_allowed_connection_time", 0L);
        } catch (Throwable ignored) {
        }

        SelfArmBootstrapper bootstrapper = new SelfArmBootstrapper(app);

        // Primary path used by R08 when Nexus/another manager is not owning ADB.
        try {
            return "loopback " + bootstrapper.rearmLoopback();
        } catch (Throwable loopbackFailure) {
            // Opportunistic R08-style Wireless Debugging fallback.
            int wirelessPort = SelfArmWirelessAdbController.INSTANCE.readWirelessPort();
            if (wirelessPort > 0) {
                try {
                    return "wireless:" + wirelessPort + " " + bootstrapper.rearmPort(wirelessPort);
                } catch (Throwable wirelessFailure) {
                    wirelessFailure.addSuppressed(loopbackFailure);
                    throw wirelessFailure;
                }
            }
            throw loopbackFailure;
        }
    }

    private static void repairAccessibility(Context context) {
        try {
            String target = new ComponentName(
                    context, BleNavigationService.class).flattenToString();
            String current = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null || "null".equals(current)) current = "";

            if (!containsService(current, target)) {
                String next = TextUtils.isEmpty(current) ? target : current + ":" + target;
                Settings.Secure.putString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        next);
            }
            Settings.Secure.putInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1);
        } catch (Throwable ignored) {
            // Shell watchdog will repair it once restored.
        }
    }

    private static boolean containsService(String services, String target) {
        if (TextUtils.isEmpty(services)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(services);
        while (splitter.hasNext()) {
            if (target.equalsIgnoreCase(splitter.next())) return true;
        }
        return false;
    }

    private static String shortMessage(Throwable t) {
        String m = t == null ? "" : t.getMessage();
        if (m == null || m.trim().isEmpty()) {
            return t == null ? "unknown" : t.getClass().getSimpleName();
        }
        return m.trim();
    }

    private static String firstLine(String value) {
        if (value == null) return "";
        int i = value.indexOf('\n');
        return (i >= 0 ? value.substring(0, i) : value).trim();
    }
}
