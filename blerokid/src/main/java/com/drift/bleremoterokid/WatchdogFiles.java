package com.drift.bleremoterokid;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

final class WatchdogFiles {
    private WatchdogFiles() {}

    static File exportScript(Context context) {
        File root = context.getExternalFilesDir(null);
        if (root == null) return null;
        File out = new File(root, "rokid_ble_watchdog.sh");
        try (InputStream in = context.getResources().openRawResource(R.raw.rokid_ble_watchdog);
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

    static boolean isAlive(Context context) {
        File root = context.getExternalFilesDir(null);
        if (root == null) return false;
        File heartbeat = new File(root, "watchdog.heartbeat");
        if (!heartbeat.isFile()) return false;
        long age = System.currentTimeMillis() - heartbeat.lastModified();
        return age >= 0L && age <= 5000L;
    }

    static String adbArmCommand(Context context) {
        File script = exportScript(context);
        if (script == null) return "WATCHDOG SCRIPT UNAVAILABLE";
        return "cp '" + script.getAbsolutePath() + "' /data/local/tmp/rokid_ble_watchdog.sh && " +
                "chmod 755 /data/local/tmp/rokid_ble_watchdog.sh && " +
                "sh /data/local/tmp/rokid_ble_watchdog.sh restart";
    }
}
