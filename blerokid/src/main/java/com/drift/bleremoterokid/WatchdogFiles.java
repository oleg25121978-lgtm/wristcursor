package com.drift.bleremoterokid;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

final class WatchdogFiles {
    private WatchdogFiles() {}

    static File exportWatchdog(Context context) {
        return export(context, R.raw.rokid_ble_watchdog, "rokid_ble_watchdog.sh");
    }

    static File exportShortcutBridge(Context context) {
        return export(context, R.raw.rokid_shortcut_bridge, "rokid_shortcut_bridge.sh");
    }

    static void exportAll(Context context) {
        exportWatchdog(context);
        exportShortcutBridge(context);
        PrivilegedShortcutBridge.ensureReady(context);
    }

    private static File export(Context context, int raw, String name) {
        File root = context.getExternalFilesDir(null);
        if (root == null) return null;
        File out = new File(root, name);
        try (InputStream in = context.getResources().openRawResource(raw);
             FileOutputStream fos = new FileOutputStream(out, false)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) fos.write(buffer, 0, n);
            fos.flush();
            out.setReadable(true, false);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isBridgeAlive(Context context) {
        return PrivilegedShortcutBridge.isArmed(context);
    }

    static String adbArmCommand(Context context) {
        File watchdog = exportWatchdog(context);
        File bridge = exportShortcutBridge(context);
        if (watchdog == null || bridge == null) return "SCRIPTS UNAVAILABLE";
        return "cp '" + bridge.getAbsolutePath() + "' /data/local/tmp/rokid-ble-shortcut-bridge.sh && " +
                "cp '" + watchdog.getAbsolutePath() + "' /data/local/tmp/rokid-ble-a11y-watchdog.sh && " +
                "chmod 755 /data/local/tmp/rokid-ble-shortcut-bridge.sh /data/local/tmp/rokid-ble-a11y-watchdog.sh && " +
                "pm grant com.drift.bleremoterokid android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true; " +
                "sh /data/local/tmp/rokid-ble-shortcut-bridge.sh restart && " +
                "sh /data/local/tmp/rokid-ble-a11y-watchdog.sh restart";
    }
}
