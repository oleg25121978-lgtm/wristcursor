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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    private final List<Button> allCommandButtons = new ArrayList<>();
    private LinearLayout mediaPage;
    private LinearLayout navPage;
    private LinearLayout systemPage;
    private LinearLayout appsPage;
    private int seenDevices = 0;
    private boolean scanning = false;
    private final Runnable autoReconnect = () -> {
        if (commandCharacteristic == null && !scanning && permissionsReady()) {
            startScan();
        }
    };
    private int touchTapCount = 0;
    private final Runnable touchTapCommit = () -> {
        int count = touchTapCount;
        touchTapCount = 0;
        if (count <= 1) send("TOUCH1");
        else if (count == 2) send("TOUCH2");
        else send("TOUCH3");
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            seenDevices++;
            BluetoothDevice device = result.getDevice();
            boolean matches = false;
            if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                for (ParcelUuid uuid : result.getScanRecord().getServiceUuids()) {
                    if (SERVICE_UUID.equals(uuid.getUuid())) {
                        matches = true;
                        break;
                    }
                }
            }

            final boolean foundRokid = matches;
            final String address;
            final String name;
            try {
                address = device.getAddress();
                name = device.getName();
            } catch (SecurityException e) {
                runOnUiThread(() -> scanText.setText("SCAN: DEVICE FOUND, NO PERMISSION"));
                return;
            }

            runOnUiThread(() -> {
                scanText.setText("SCAN: " + seenDevices + " devices; last " +
                        (name == null ? "unnamed" : name) + " " + address +
                        (foundRokid ? "  [ROKID SERVICE]" : ""));
            });

            if (foundRokid) {
                stopScan();
                runOnUiThread(() -> linkText.setText("GATT: CONNECTING"));
                connect(device);
            }
        }

        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            runOnUiThread(() -> {
                scanText.setText("SCAN: FAILED " + errorCode + " • RETRY");
                handler.removeCallbacks(autoReconnect);
                handler.postDelayed(autoReconnect, 1200);
            });
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
                    linkText.setText("GATT: DISCONNECTED (" + status + ") • AUTO");
                    enableCommands(false);
                    handler.removeCallbacks(autoReconnect);
                    handler.postDelayed(autoReconnect, 700);
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
        if (permissionsReady()) handler.postDelayed(autoReconnect, 150);
    }

    @Override protected void onResume() {
        super.onResume();
        if (adapter != null && permissionsReady() && commandCharacteristic == null) {
            handler.removeCallbacks(autoReconnect);
            handler.postDelayed(autoReconnect, 150);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(16));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = label("ROKID BLE", 19, 0xFF8BF1D8);
        title.setGravity(Gravity.CENTER);
        root.addView(title, full());

        hardwareText = label("BLE HW: CHECKING", 10, Color.WHITE);
        scanText = label("SCAN: IDLE", 10, 0xFFA9B0C0);
        linkText = label("GATT: IDLE", 10, 0xFFA9B0C0);
        lastText = label("LAST: —", 10, 0xFFA9B0C0);
        root.addView(hardwareText, full());
        root.addView(scanText, full());
        root.addView(linkText, full());
        root.addView(lastText, full());

        scanButton = button("SCAN / CONNECT");
        scanButton.setOnClickListener(v -> startScan());
        root.addView(scanButton, full());

        LinearLayout tabs1 = new LinearLayout(this);
        tabs1.setOrientation(LinearLayout.HORIZONTAL);
        Button mediaTab = button("MEDIA");
        Button navTab = button("NAV");
        mediaTab.setOnClickListener(v -> showPage(0));
        navTab.setOnClickListener(v -> showPage(1));
        tabs1.addView(mediaTab, half());
        tabs1.addView(navTab, half());
        root.addView(tabs1, full());

        LinearLayout tabs2 = new LinearLayout(this);
        tabs2.setOrientation(LinearLayout.HORIZONTAL);
        Button systemTab = button("SYSTEM");
        Button appsTab = button("APPS");
        systemTab.setOnClickListener(v -> showPage(2));
        appsTab.setOnClickListener(v -> showPage(3));
        tabs2.addView(systemTab, half());
        tabs2.addView(appsTab, half());
        root.addView(tabs2, full());

        // Always-visible navigation controls.
        LinearLayout quickNav = new LinearLayout(this);
        quickNav.setOrientation(LinearLayout.HORIZONTAL);
        quickNav.addView(commandButton("BACK", "BACK"), quarter());
        quickNav.addView(commandButton("←", "LEFT"), quarter());
        quickNav.addView(commandButton("OK", "OK"), quarter());
        quickNav.addView(commandButton("→", "RIGHT"), quarter());
        root.addView(quickNav, full());

        mediaPage = page();
        navPage = page();
        systemPage = page();
        appsPage = page();
        root.addView(mediaPage, full());
        root.addView(navPage, full());
        root.addView(systemPage, full());
        root.addView(appsPage, full());

        mediaPage.addView(commandButton("PHOTO", "PHOTO"), full());
        mediaPage.addView(commandButton("VIDEO START / STOP", "VIDEO"), full());
        mediaPage.addView(commandButton("RECORDER", "VOICE"), full());
        mediaPage.addView(commandButton("KEY 1", "KEY1"), full());
        mediaPage.addView(commandButton("KEY 2", "KEY2"), full());
        mediaPage.addView(commandButton("KEY 3", "KEY3"), full());
        mediaPage.addView(commandButton("KEY 4", "KEY4"), full());

        navPage.addView(commandButton("↑", "UP"), full());
        navPage.addView(commandButton("↓", "DOWN"), full());

        LinearLayout navRow2 = new LinearLayout(this);
        navRow2.setOrientation(LinearLayout.HORIZONTAL);
        navRow2.addView(commandButton("HOME", "HOME"), half());
        navRow2.addView(commandButton("NEXUS", "NEXUS"), half());
        navPage.addView(navRow2, full());
        navPage.addView(commandButton("MENU", "MENU"), full());

        Button touch = button("TOUCH  • / •• / •••");
        touch.setOnClickListener(v -> registerTouchTap());
        allCommandButtons.add(touch);
        navPage.addView(touch, full());

        TextView volumeTitle = label("VOLUME", 12, 0xFF8BF1D8);
        systemPage.addView(volumeTitle, full());
        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.addView(commandButton("−", "VOL_DOWN"), third());
        volRow.addView(commandButton("MUTE", "MUTE"), third());
        volRow.addView(commandButton("+", "VOL_UP"), third());
        systemPage.addView(volRow, full());

        TextView brightnessTitle = label("BRIGHTNESS", 12, 0xFF8BF1D8);
        systemPage.addView(brightnessTitle, full());
        LinearLayout briRow = new LinearLayout(this);
        briRow.setOrientation(LinearLayout.HORIZONTAL);
        briRow.addView(commandButton("−", "BRI_DOWN"), half());
        briRow.addView(commandButton("+", "BRI_UP"), half());
        systemPage.addView(briRow, full());

        appsPage.addView(commandButton("CAMERA", "APP_CAMERA"), full());
        appsPage.addView(commandButton("ROKID AI", "APP_CHAT"), full());
        appsPage.addView(commandButton("VISION AI", "APP_VISION_AI"), full());
        appsPage.addView(commandButton("TRANSLATE", "APP_TRANSLATE"), full());
        appsPage.addView(commandButton("TELEPROMPTER", "APP_PROMPTER"), full());
        appsPage.addView(commandButton("MUSIC", "APP_MUSIC"), full());
        appsPage.addView(commandButton("NAVIGATION", "APP_NAV"), full());
        appsPage.addView(commandButton("GALLERY", "APP_GALLERY"), full());
        appsPage.addView(commandButton("AUDIO", "APP_AUDIO"), full());
        appsPage.addView(commandButton("SETTINGS", "APP_SETTINGS"), full());
        appsPage.addView(commandButton("BLUETOOTH", "APP_BT"), full());

        commandButtons = allCommandButtons.toArray(new Button[0]);
        enableCommands(false);
        showPage(0);
        setContentView(scroll);
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        return page;
    }

    private void showPage(int page) {
        if (mediaPage == null || navPage == null || systemPage == null || appsPage == null) return;
        mediaPage.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        navPage.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        systemPage.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        appsPage.setVisibility(page == 3 ? View.VISIBLE : View.GONE);
    }

    private void registerTouchTap() {
        if (commandCharacteristic == null) {
            lastText.setText("LAST: NOT CONNECTED");
            return;
        }
        touchTapCount++;
        handler.removeCallbacks(touchTapCommit);
        handler.postDelayed(touchTapCommit, 430);
        lastText.setText("TOUCH: " + touchTapCount + " TAP");
    }

    private Button commandButton(String caption, String command) {
        Button button = button(caption);
        button.setOnClickListener(v -> send(command));
        allCommandButtons.add(button);
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

    private LinearLayout.LayoutParams half() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        return lp;
    }

    private LinearLayout.LayoutParams third() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(1), dp(2), dp(1), dp(2));
        return lp;
    }

    private LinearLayout.LayoutParams quarter() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(1), dp(2), dp(1), dp(2));
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
            if (ok) {
                handler.removeCallbacks(autoReconnect);
                handler.postDelayed(autoReconnect, 150);
            }
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
        if (commandCharacteristic != null || scanning) return;
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
        scanning = true;
        scanText.setText("SCAN: AUTO CONNECT");
        linkText.setText("GATT: WAITING");

        seenDevices = 0;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            scanner.startScan(null, settings, scanCallback);
            handler.postDelayed(() -> {
                if (commandCharacteristic == null) {
                    stopScan();
                    scanText.setText("SCAN: " + seenDevices + " devices • AUTO RETRY");
                    handler.removeCallbacks(autoReconnect);
                    handler.postDelayed(autoReconnect, 1200);
                }
            }, 8000);
        } catch (SecurityException e) {
            scanText.setText("SCAN: SECURITY ERROR");
        } catch (Throwable t) {
            scanText.setText("SCAN: ERROR " + t.getClass().getSimpleName());
        }
    }

    private void stopScan() {
        scanning = false;
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
        for (Button b : allCommandButtons) b.setEnabled(enabled);
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
        handler.removeCallbacks(autoReconnect);
        handler.removeCallbacks(touchTapCommit);
        stopScan();
        closeGatt();
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
