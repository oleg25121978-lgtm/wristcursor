package com.drift.bleremotewatch;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SERVICE_UUID = UUID.fromString("7f6f1000-5f8a-4c3a-9c6d-3f1d9b7a1000");
    private static final UUID COMMAND_UUID = UUID.fromString("7f6f1001-5f8a-4c3a-9c6d-3f1d9b7a1000");
    private static final int REQ_PERMS = 41;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandCharacteristic;

    private TextView hardwareText;
    private TextView scanText;
    private TextView linkText;
    private TextView lastText;
    private Button scanButton;
    private Button[] commandButtons;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            stopScan();
            BluetoothDevice device = result.getDevice();
            runOnUiThread(() -> {
                scanText.setText("SCAN: FOUND " + device.getAddress());
                linkText.setText("GATT: CONNECTING");
            });
            connect(device);
        }

        @Override public void onScanFailed(int errorCode) {
            runOnUiThread(() -> scanText.setText("SCAN: FAILED " + errorCode));
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> linkText.setText("GATT: CONNECTED, DISCOVERING"));
                try {
                    current.discoverServices();
                } catch (SecurityException e) {
                    showLinkError("DISCOVER PERMISSION");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                commandCharacteristic = null;
                runOnUiThread(() -> {
                    linkText.setText("GATT: DISCONNECTED (" + status + ")");
                    enableCommands(false);
                });
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt current, int status) {
            BluetoothGattService service = current.getService(SERVICE_UUID);
            commandCharacteristic = service == null ? null : service.getCharacteristic(COMMAND_UUID);
            final boolean ready = status == BluetoothGatt.GATT_SUCCESS && commandCharacteristic != null;
            runOnUiThread(() -> {
                linkText.setText(ready ? "GATT: READY" : "GATT: SERVICE NOT FOUND (" + status + ")");
                enableCommands(ready);
            });
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();

        boolean feature = getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        boolean enabled = adapter != null && adapter.isEnabled();
        hardwareText.setText("BLE HW: " + (feature ? "YES" : "NO") + "   BT: " + (enabled ? "ON" : "OFF"));

        requestBlePermissionsIfNeeded();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(20));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = label("BLE REMOTE", 22, 0xFF8BF1D8);
        title.setGravity(Gravity.CENTER);
        root.addView(title, full());

        hardwareText = label("BLE HW: CHECKING", 13, Color.WHITE);
        scanText = label("SCAN: IDLE", 13, 0xFFA9B0C0);
        linkText = label("GATT: IDLE", 13, 0xFFA9B0C0);
        lastText = label("LAST: —", 12, 0xFFA9B0C0);
        root.addView(hardwareText, full());
        root.addView(scanText, full());
        root.addView(linkText, full());
        root.addView(lastText, full());

        scanButton = button("SCAN / CONNECT");
        scanButton.setOnClickListener(v -> startScan());
        root.addView(scanButton, full());

        commandButtons = new Button[] {
                commandButton("PHOTO", "PHOTO"),
                commandButton("VIDEO", "VIDEO"),
                commandButton("RECORDER", "VOICE"),
                commandButton("KEY 1", "KEY1"),
                commandButton("KEY 2", "KEY2"),
                commandButton("KEY 3", "KEY3"),
                commandButton("KEY 4", "KEY4")
        };
        for (Button button : commandButtons) {
            button.setEnabled(false);
            root.addView(button, full());
        }

        setContentView(scroll);
    }

    private Button commandButton(String caption, String command) {
        Button button = button(caption);
        button.setOnClickListener(v -> send(command));
        return button;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinHeight(dp(44));
        return b;
    }

    private TextView label(String text, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setPadding(dp(4), dp(5), dp(4), dp(5));
        return v;
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3));
        return lp;
    }

    private void requestBlePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                }, REQ_PERMS);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, REQ_PERMS);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean ok = true;
            for (int result : grantResults) ok &= result == PackageManager.PERMISSION_GRANTED;
            scanText.setText(ok ? "PERMISSIONS: OK" : "PERMISSIONS: DENIED");
        }
    }

    private boolean permissionsReady() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {
        if (!permissionsReady()) {
            requestBlePermissionsIfNeeded();
            scanText.setText("SCAN: NEED PERMISSION");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            scanText.setText("SCAN: BLUETOOTH OFF / UNAVAILABLE");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            scanText.setText("SCAN API: UNAVAILABLE");
            return;
        }

        closeGatt();
        scanText.setText("SCAN: RUNNING");
        linkText.setText("GATT: WAITING");

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            handler.postDelayed(() -> {
                if (commandCharacteristic == null) {
                    stopScan();
                    scanText.setText("SCAN: NO ROKID BLE RECEIVER");
                }
            }, 15000);
        } catch (SecurityException e) {
            scanText.setText("SCAN: SECURITY ERROR");
        } catch (Throwable t) {
            scanText.setText("SCAN: ERROR " + t.getClass().getSimpleName());
        }
    }

    private void stopScan() {
        handler.removeCallbacksAndMessages(null);
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
        }
    }

    private void connect(BluetoothDevice device) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(this, false, gattCallback);
            }
        } catch (SecurityException e) {
            showLinkError("CONNECT PERMISSION");
        } catch (Throwable t) {
            showLinkError("CONNECT " + t.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("deprecation")
    private void send(String command) {
        BluetoothGatt current = gatt;
        BluetoothGattCharacteristic characteristic = commandCharacteristic;
        if (current == null || characteristic == null) {
            lastText.setText("LAST: NOT CONNECTED");
            return;
        }
        byte[] data = command.getBytes(StandardCharsets.UTF_8);
        try {
            boolean queued;
            if (Build.VERSION.SDK_INT >= 33) {
                int result = current.writeCharacteristic(
                        characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                queued = result == BluetoothGatt.GATT_SUCCESS;
            } else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                characteristic.setValue(data);
                queued = current.writeCharacteristic(characteristic);
            }
            lastText.setText("LAST: " + command + (queued ? " SENT" : " FAILED"));
        } catch (SecurityException e) {
            lastText.setText("LAST: PERMISSION ERROR");
        } catch (Throwable t) {
            lastText.setText("LAST: ERROR " + t.getClass().getSimpleName());
        }
    }

    private void enableCommands(boolean enabled) {
        for (Button b : commandButtons) b.setEnabled(enabled);
    }

    private void showLinkError(String text) {
        runOnUiThread(() -> {
            linkText.setText("GATT: " + text);
            enableCommands(false);
        });
    }

    private void closeGatt() {
        commandCharacteristic = null;
        if (gatt != null) {
            try { gatt.close(); } catch (Throwable ignored) {}
            gatt = null;
        }
        enableCommands(false);
    }

    @Override protected void onDestroy() {
        stopScan();
        closeGatt();
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
