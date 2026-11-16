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
    private static volatile MainActivity activeInstance;

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
    private LinearLayout screenHost;
    private int seenDevices = 0;
    private int touchTapCount = 0;
    private final Runnable touchTapCommit = () -> {
        int count = touchTapCount;
        touchTapCount = 0;
        if (count <= 1) send("TOUCH1");
        else if (count == 2) send("TOUCH2");
        else send("TOUCH3");
    };

    private final Runnable autoConnectRunnable = () -> {
        if (commandCharacteristic == null) startScan();
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
                    linkText.setText("GATT: DISCONNECTED (" + status + ") • RECONNECTING");
                    enableCommands(false);
                    handler.removeCallbacks(autoConnectRunnable);
                    handler.postDelayed(autoConnectRunnable, 900);
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
        activeInstance = this;
        buildUi();

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();

        boolean feature = getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        boolean enabled = adapter != null && adapter.isEnabled();
        hardwareText.setText("BLE HW: " + (feature ? "YES" : "NO") + "   BT: " + (enabled ? "ON" : "OFF"));

        requestBlePermissionsIfNeeded();
        handler.postDelayed(autoConnectRunnable, 350);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(18));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = label("ROKID REMOTE · R09", 18, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, full());

        hardwareText = label("BLE HW: CHECKING", 9, 0xFF98A2B3);
        scanText = label("SCAN: IDLE", 9, 0xFF98A2B3);
        linkText = label("GATT: IDLE", 10, 0xFF41D17D);
        lastText = label("LAST: —", 9, 0xFF98A2B3);
        root.addView(hardwareText, full());
        root.addView(scanText, full());
        root.addView(linkText, full());
        root.addView(lastText, full());

        scanButton = button("ПОДКЛЮЧИТЬ ОЧКИ");
        scanButton.setOnClickListener(v -> startScan());
        scanButton.setBackgroundColor(0xFF0A66FF);
        root.addView(scanButton, full());

        screenHost = new LinearLayout(this);
        screenHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(screenHost, full());

        commandButtons = allCommandButtons.toArray(new Button[0]);
        enableCommands(false);
        setContentView(scroll);
        showHomeScreen();
    }

    private void clearScreen(String title, Runnable back) {
        screenHost.removeAllViews();
        allCommandButtons.clear();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (back != null) {
            Button backButton = navButton("‹");
            backButton.setTextSize(22);
            backButton.setOnClickListener(v -> back.run());
            header.addView(backButton, new LinearLayout.LayoutParams(dp(52), dp(48)));
        }
        TextView heading = label(title, 17, Color.WHITE);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        screenHost.addView(header, full());
    }

    private void showHomeScreen() {
        clearScreen("Rokid Glasses", null);

        TextView state = label("Быстрый доступ", 11, 0xFF8BF1D8);
        screenHost.addView(state, full());

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.addView(commandButton("Hi Rokid", "HI_ROKID"), third());
        quick.addView(commandButton("Домой", "HOME"), third());
        quick.addView(commandButton("Камера", "APP_CAMERA"), third());
        screenHost.addView(quick, full());

        Button menu = navButton("МЕНЮ");
        menu.setTextSize(16);
        menu.setBackgroundColor(0xFF172033);
        menu.setOnClickListener(v -> showMenuScreen());
        screenHost.addView(menu, full());
    }

    private void showMenuScreen() {
        clearScreen("Меню", this::showHomeScreen);
        addSectionButton("Управление", this::showControlScreen);
        addSectionButton("Камера", this::showCameraScreen);
        addSectionButton("Медиа", this::showMediaScreen);
        addSectionButton("Приложения", this::showAppsScreen);
    }

    private void showControlScreen() {
        clearScreen("Управление", this::showMenuScreen);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(commandButton("Домой", "HOME"), third());
        row1.addView(commandButton("Назад", "BACK"), third());
        row1.addView(commandButton("Меню", "MENU"), third());
        screenHost.addView(row1, full());

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(commandButton("Hi Rokid", "HI_ROKID"), half());
        row2.addView(commandButton("Nexus", "NEXUS"), half());
        screenHost.addView(row2, full());

        TextView navTitle = label("Навигация по экрану", 11, 0xFF8BF1D8);
        screenHost.addView(navTitle, full());
        screenHost.addView(commandButton("↑", "UP"), full());

        LinearLayout arrows = new LinearLayout(this);
        arrows.setOrientation(LinearLayout.HORIZONTAL);
        arrows.addView(commandButton("←", "LEFT"), third());
        arrows.addView(commandButton("OK", "OK"), third());
        arrows.addView(commandButton("→", "RIGHT"), third());
        screenHost.addView(arrows, full());
        screenHost.addView(commandButton("↓", "DOWN"), full());

        Button touch = button("TOUCH  • / •• / •••");
        touch.setOnClickListener(v -> registerTouchTap());
        touch.setEnabled(commandCharacteristic != null);
        allCommandButtons.add(touch);
        screenHost.addView(touch, full());

        TextView volumeTitle = label("Громкость", 11, 0xFF8BF1D8);
        screenHost.addView(volumeTitle, full());
        LinearLayout vol = new LinearLayout(this);
        vol.setOrientation(LinearLayout.HORIZONTAL);
        vol.addView(commandButton("−", "VOL_DOWN"), third());
        vol.addView(commandButton("MUTE", "MUTE"), third());
        vol.addView(commandButton("+", "VOL_UP"), third());
        screenHost.addView(vol, full());

        TextView brightnessTitle = label("Яркость", 11, 0xFF8BF1D8);
        screenHost.addView(brightnessTitle, full());
        LinearLayout bri = new LinearLayout(this);
        bri.setOrientation(LinearLayout.HORIZONTAL);
        bri.addView(commandButton("−", "BRI_DOWN"), half());
        bri.addView(commandButton("+", "BRI_UP"), half());
        screenHost.addView(bri, full());
    }

    private void showCameraScreen() {
        clearScreen("Камера", this::showMenuScreen);

        Button open = commandButton("ОТКРЫТЬ РОДНУЮ КАМЕРУ", "APP_CAMERA");
        open.setBackgroundColor(0xFF0A66FF);
        screenHost.addView(open, full());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(commandButton("Фото", "PHOTO"), half());
        row.addView(commandButton("Видео START / STOP", "VIDEO"), half());
        screenHost.addView(row, full());

        screenHost.addView(commandButton("Аудиозапись", "VOICE"), full());
    }

    private void showMediaScreen() {
        clearScreen("Медиа", this::showMenuScreen);
        TextView note = label("Сохранены все команды R08", 11, 0xFF8BF1D8);
        screenHost.addView(note, full());
        screenHost.addView(commandButton("Фото", "PHOTO"), full());
        screenHost.addView(commandButton("Видео START / STOP", "VIDEO"), full());
        screenHost.addView(commandButton("Аудиозапись", "VOICE"), full());

        LinearLayout keys1 = new LinearLayout(this);
        keys1.setOrientation(LinearLayout.HORIZONTAL);
        keys1.addView(commandButton("KEY 1", "KEY1"), half());
        keys1.addView(commandButton("KEY 2", "KEY2"), half());
        screenHost.addView(keys1, full());
        LinearLayout keys2 = new LinearLayout(this);
        keys2.setOrientation(LinearLayout.HORIZONTAL);
        keys2.addView(commandButton("KEY 3", "KEY3"), half());
        keys2.addView(commandButton("KEY 4", "KEY4"), half());
        screenHost.addView(keys2, full());
    }

    private void showAppsScreen() {
        clearScreen("Приложения", this::showMenuScreen);
        screenHost.addView(commandButton("Камера Rokid", "APP_CAMERA"), full());
        screenHost.addView(commandButton("Rokid AI", "APP_CHAT"), full());
        screenHost.addView(commandButton("Hi Rokid", "HI_ROKID"), full());
        screenHost.addView(commandButton("Переводчик", "APP_TRANSLATE"), full());
        screenHost.addView(commandButton("Телесуфлёр", "APP_PROMPTER"), full());
        screenHost.addView(commandButton("Музыка", "APP_MUSIC"), full());
        screenHost.addView(commandButton("Навигация", "APP_NAV"), full());
        screenHost.addView(commandButton("Галерея", "APP_GALLERY"), full());
        screenHost.addView(commandButton("Аудио", "APP_AUDIO"), full());
        screenHost.addView(commandButton("Настройки", "APP_SETTINGS"), full());
        screenHost.addView(commandButton("Bluetooth", "APP_BT"), full());
    }

    private void addSectionButton(String title, Runnable action) {
        Button b = navButton(title + "  ›");
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setOnClickListener(v -> action.run());
        screenHost.addView(b, full());
    }

    private Button navButton(String text) {
        Button b = button(text);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF172033);
        return b;
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
        button.setEnabled(commandCharacteristic != null);
        allCommandButtons.add(button);
        return button;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF172033);
        b.setMinHeight(dp(46));
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
            if (ok) handler.postDelayed(autoConnectRunnable, 250);
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

        seenDevices = 0;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            scanner.startScan(null, settings, scanCallback);
            handler.postDelayed(() -> {
                if (commandCharacteristic == null) {
                    stopScan();
                    scanText.setText("SCAN: " + seenDevices + " devices • RETRYING");
                    handler.removeCallbacks(autoConnectRunnable);
                    handler.postDelayed(autoConnectRunnable, 1800);
                }
            }, 15000);
        } catch (SecurityException e) {
            scanText.setText("SCAN: SECURITY ERROR");
        } catch (Throwable t) {
            scanText.setText("SCAN: ERROR " + t.getClass().getSimpleName());
        }
    }

    private void stopScan() {
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

    static boolean sendQuickIfConnected(String command) {
        MainActivity current = activeInstance;
        if (current == null || current.gatt == null || current.commandCharacteristic == null) return false;
        current.runOnUiThread(() -> current.send(command));
        return true;
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
        handler.removeCallbacks(autoConnectRunnable);
        handler.removeCallbacks(touchTapCommit);
        stopScan();
        closeGatt();
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
