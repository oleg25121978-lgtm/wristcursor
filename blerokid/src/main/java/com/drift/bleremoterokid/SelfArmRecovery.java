package com.drift.bleremoterokid;

import android.content.Context;
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
        rearmAsync(context, new long[]{4000L, 15000L, 45000L, 120000L});
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
                for (long delay : delays) {
                    if (delay > 0) Thread.sleep(delay);
                    if (!isConfigured(app)) return;
                    if (PrivilegedShortcutBridge.isCurrent(app)) return;
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
