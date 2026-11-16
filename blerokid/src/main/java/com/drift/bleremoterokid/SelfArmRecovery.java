package com.drift.bleremoterokid;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

final class SelfArmRecovery {
    private static final String TAG = "RokidSelfArmRecovery";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private SelfArmRecovery() {}

    static boolean isConfigured(Context context) {
        return context.getSharedPreferences(SelfArmBootstrapper.PREFS, Context.MODE_PRIVATE)
                .getBoolean(SelfArmBootstrapper.KEY_ARMED, false);
    }

    static void onLaunch(Context context) {
        rearmAsync(context, new long[]{0L, 3000L, 10000L});
    }

    static void onBoot(Context context) {
        rearmAsync(context, new long[]{3000L, 4000L, 8000L, 15000L, 30000L, 60000L});
    }

    static void onAccessibilityConnected(Context context) {
        rearmAsync(context, new long[]{1000L, 8000L, 30000L});
    }

    private static void rearmAsync(Context context, long[] delays) {
        Context app = context.getApplicationContext();
        if (!isConfigured(app)) return;
        if (PrivilegedShortcutBridge.isCurrent(app)) return;
        if (!RUNNING.compareAndSet(false, true)) return;

        Thread thread = new Thread(() -> {
            try {
                Throwable last = null;
                boolean adbPrepared = false;
                for (long delay : delays) {
                    if (delay > 0) Thread.sleep(delay);
                    if (!isConfigured(app)) return;
                    if (PrivilegedShortcutBridge.isCurrent(app)) return;

                    if (!adbPrepared) {
                        boolean wasEnabled = false;
                        try {
                            wasEnabled = Settings.Global.getInt(
                                    app.getContentResolver(),
                                    Settings.Global.ADB_ENABLED,
                                    0) == 1;
                            Settings.Global.putLong(
                                    app.getContentResolver(),
                                    "adb_allowed_connection_time",
                                    0L);
                            if (!wasEnabled) {
                                Settings.Global.putInt(
                                        app.getContentResolver(),
                                        Settings.Global.ADB_ENABLED,
                                        1);
                                Log.i(TAG, "USB debugging enabled for boot recovery");
                                Thread.sleep(4000L);
                            }
                            adbPrepared = true;
                        } catch (Throwable t) {
                            Log.w(TAG, "cannot prepare ADB for boot recovery", t);
                        }
                    }

                    try {
                        String out = new SelfArmBootstrapper(app).rearmLoopback();
                        Log.i(TAG, "re-arm complete: " + out);
                        SelfArmState.status = "SELF-ARM ACTIVE";
                        return;
                    } catch (Throwable t) {
                        last = t;
                        Log.w(TAG, "re-arm attempt failed", t);
                    }
                }
                if (last != null) {
                    SelfArmState.status = "SELF-ARM RECOVERY PENDING";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                RUNNING.set(false);
            }
        }, "rokid-self-arm-recovery");
        thread.setDaemon(true);
        thread.start();
    }
}
