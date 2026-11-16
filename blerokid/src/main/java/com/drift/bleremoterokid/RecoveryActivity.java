package com.drift.bleremoterokid;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

public class RecoveryActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent service = new Intent(this, BleGattService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (Throwable ignored) {}
        finish();
    }
}
