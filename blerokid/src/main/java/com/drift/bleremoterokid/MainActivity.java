package com.drift.bleremoterokid;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SERVICE_UUID = UUID.fromString("7f6f1000-5f8a-4c3a-9c6d-3f1d9b7a1000");
    private static final UUID COMMAND_UUID = UUID.fromString("7f6f1001-5f8a-4c3a-9c6d-3f1d9b7a1000");
    private static final int REQ_PERMS = 51;

    private BluetoothManager manager;
    private BluetoothAdapter adapter;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;

    private TextView hardwareText;
    private TextView advertiseText;
    private TextView gattText;
    private TextView clientText;
    private TextView commandText;
    private Button startButton;

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            runOnUiThread(() -> clientText.setText(
                    newState == BluetoothProfile.STATE_CONNECTED
                            ? "CLIENT: CONNECTED " + safeAddress(device)
                            : "CLIENT: DISCONNECTED (" + status + ")"));
        }

        @Override public void onCharacteristicWriteRequest(
                BluetoothDevice device,
                int requestId,
                BluetoothGattCharacteristic characteristic,
                boolean preparedWrite,
                boolean responseNeeded,
                int offset,
                byte[] value) {
            if (!COMMAND_UUID.equals(characteristic.getUuid())) return;

            String command = value == null ? "" : new String(value, StandardCharsets.UTF_8).trim();
            runOnUiThread(() -> commandText.setText("LAST COMMAND: " + command));

            if (responseNeeded && gattServer != null) {
                try {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                } catch (SecurityException ignored) {}
            }
        }
    };

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            runOnUiThread(() -> advertiseText.setText("ADVERTISING: ON"));
        }

        @Override public void onStartFailure(int errorCode) {
            runOnUiThread(() -> advertiseText.setText("ADVERTISING: FAILED " + errorCode));
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();

        boolean feature = getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        boolean enabled = adapter != null && adapter.isEnabled();
        boolean advertiserApi = adapter != null && adapter.getBluetoothLeAdvertiser() != null;
        hardwareText.setText(
                "BLE HW: " + (feature ? "YES" : "NO") +
                "   BT: " + (enabled ? "ON" : "OFF") +
                "   ADV: " + (advertiserApi ? "YES" : "NO"));

        requestBlePermissionsIfNeeded();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(20));
        root.setGravity(Gravity.TOP);
        root.setBackgroundColor(Color.BLACK);

        TextView title = label("ROKID BLE RECEIVER", 26, 0xFF8BF1D8);
        root.addView(title, full());

        hardwareText = label("BLE HW: CHECKING", 16, Color.WHITE);
        advertiseText = label("ADVERTISING: IDLE", 16, 0xFFA9B0C0);
        gattText = label("GATT SERVER: IDLE", 16, 0xFFA9B0C0);
        clientText = label("CLIENT: NONE", 16, 0xFFA9B0C0);
        commandText = label("LAST COMMAND: —", 18, Color.WHITE);

        root.addView(hardwareText, full());
        root.addView(advertiseText, full());
        root.addView(gattText, full());
        root.addView(clientText, full());
        root.addView(commandText, full());

        startButton = new Button(this);
        startButton.setText("START BLE RECEIVER");
        startButton.setOnClickListener(v -> startReceiver());
        root.addView(startButton, full());

        TextView note = label(
                "Test only. PHOTO / VIDEO / VOICE / KEY1–KEY4 are displayed here first; actions are not executed yet.",
                14, 0xFFA9B0C0);
        root.addView(note, full());

        setContentView(root);
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

    private void requestBlePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                }, REQ_PERMS);
            }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean ok = true;
            for (int result : grantResults) ok &= result == PackageManager.PERMISSION_GRANTED;
            advertiseText.setText(ok ? "PERMISSIONS: OK" : "PERMISSIONS: DENIED");
        }
    }

    private boolean permissionsReady() {
        return Build.VERSION.SDK_INT < 31 ||
                (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                 checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED);
    }

    private void startReceiver() {
        if (!permissionsReady()) {
            requestBlePermissionsIfNeeded();
            advertiseText.setText("ADVERTISING: NEED PERMISSION");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            advertiseText.setText("ADVERTISING: BLUETOOTH OFF / UNAVAILABLE");
            return;
        }

        stopReceiver();

        try {
            gattServer = manager.openGattServer(this, serverCallback);
            if (gattServer == null) {
                gattText.setText("GATT SERVER: UNAVAILABLE");
                return;
            }

            BluetoothGattService service = new BluetoothGattService(
                    SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            BluetoothGattCharacteristic command = new BluetoothGattCharacteristic(
                    COMMAND_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE |
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            service.addCharacteristic(command);

            boolean added = gattServer.addService(service);
            gattText.setText(added ? "GATT SERVER: READY" : "GATT SERVER: ADD SERVICE FAILED");

            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                advertiseText.setText("ADVERTISING API: UNAVAILABLE");
                return;
            }

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build();

            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .setIncludeDeviceName(false)
                    .build();

            advertiser.startAdvertising(settings, data, advertiseCallback);
            advertiseText.setText("ADVERTISING: STARTING");
        } catch (SecurityException e) {
            advertiseText.setText("BLE: SECURITY ERROR");
        } catch (Throwable t) {
            advertiseText.setText("BLE: ERROR " + t.getClass().getSimpleName());
        }
    }

    private void stopReceiver() {
        if (advertiser != null) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (Throwable ignored) {}
            advertiser = null;
        }
        if (gattServer != null) {
            try { gattServer.close(); } catch (Throwable ignored) {}
            gattServer = null;
        }
    }

    private String safeAddress(BluetoothDevice device) {
        try { return device == null ? "?" : device.getAddress(); }
        catch (SecurityException e) { return "?"; }
    }

    @Override protected void onDestroy() {
        stopReceiver();
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
