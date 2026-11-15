package com.drift.bleremoterokid;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_BLE = 51;
    private static final int REQ_MEDIA = 52;

    private TextView bleText;
    private TextView accessText;
    private TextView lastText;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestPermissionsIfNeeded();
        startPersistentServiceIfReady();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(20));
        root.setGravity(Gravity.TOP);
        root.setBackgroundColor(Color.BLACK);

        TextView title = label("ROKID BLE RECEIVER", 25, 0xFF8BF1D8);
        root.addView(title, full());

        bleText = label("BLE SERVICE: CHECKING", 17, Color.WHITE);
        accessText = label("ACCESS: CHECKING", 16, 0xFFA9B0C0);
        lastText = label("LAST: —", 15, 0xFFA9B0C0);
        root.addView(bleText, full());
        root.addView(accessText, full());
        root.addView(lastText, full());

        Button start = new Button(this);
        start.setText("START / KEEP BLE RUNNING");
        start.setOnClickListener(v -> {
            requestPermissionsIfNeeded();
            startPersistentServiceIfReady();
            refresh();
        });
        root.addView(start, full());

        Button access = new Button(this);
        access.setText("ENABLE ACCESSIBILITY");
        access.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Throwable ignored) {}
        });
        root.addView(access, full());

        TextView note = label(
                "BLE now runs as a foreground service. You can leave this screen; the watch should stay connected.",
                14, 0xFFA9B0C0);
        root.addView(note, full());

        setContentView(root);
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 &&
                (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                 checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, REQ_BLE);
            return;
        }

        if (Build.VERSION.SDK_INT >= 23 &&
                (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                 checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[] {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            }, REQ_MEDIA);
        }
    }

    private boolean blePermissionReady() {
        return Build.VERSION.SDK_INT < 31 ||
                (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                 checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED);
    }

    private void startPersistentServiceIfReady() {
        if (!blePermissionReady()) return;
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            BleGattService.bleState = "BLUETOOTH OFF";
            return;
        }
        Intent service = new Intent(this, BleGattService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE) {
            requestPermissionsIfNeeded();
            startPersistentServiceIfReady();
        } else if (requestCode == REQ_MEDIA) {
            startPersistentServiceIfReady();
        }
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        startPersistentServiceIfReady();
        refresh();
    }

    private void refresh() {
        boolean a11y = BleNavigationService.isReady() || isAccessibilityServiceEnabled();
        boolean cam = Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean mic = Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        bleText.setText("BLE: " + BleGattService.bleState + "   CLIENT: " + BleGattService.clientState);
        accessText.setText("A11Y " + (a11y ? "ON" : "OFF") +
                "   CAM " + (cam ? "ON" : "OFF") +
                "   MIC " + (mic ? "ON" : "OFF"));
        lastText.setText("LAST: " + BleGattService.lastAction);
    }

    private boolean isAccessibilityServiceEnabled() {
        try {
            String expected = new ComponentName(this, BleNavigationService.class).flattenToString();
            String enabled = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            while (splitter.hasNext()) {
                if (expected.equalsIgnoreCase(splitter.next())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private TextView label(String text, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setPadding(dp(4), dp(7), dp(4), dp(7));
        return v;
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
