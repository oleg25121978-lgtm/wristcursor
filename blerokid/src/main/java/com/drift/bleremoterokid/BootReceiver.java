package com.drift.bleremoterokid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            restoreAccessibility(context);
            Intent service = new Intent(context, BleGattService.class)
                    .putExtra("auto_start", true);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (Throwable ignored) {
        }
    }

    private void restoreAccessibility(Context context) {
        try {
            String target = new ComponentName(context, BleNavigationService.class).flattenToString();
            String current = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean found = false;
            if (current != null) {
                TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
                splitter.setString(current);
                while (splitter.hasNext()) {
                    if (target.equalsIgnoreCase(splitter.next())) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                String next = current == null || current.isEmpty() ? target : current + ":" + target;
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
            // WRITE_SECURE_SETTINGS may not have been granted yet; service still starts.
        }
    }
}
