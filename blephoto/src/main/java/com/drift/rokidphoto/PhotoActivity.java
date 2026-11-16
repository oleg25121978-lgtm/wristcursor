package com.drift.rokidphoto;

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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PhotoActivity extends Activity {
    private static final UUID SERVICE_UUID = UUID.fromString("7f6f1000-5f8a-4c3a-9c6d-3f1d9b7a1000");
    private static final UUID COMMAND_UUID = UUID.fromString("7f6f1001-5f8a-4c3a-9c6d-3f1d9b7a1000");
    private static final int REQ = 73;
    private static final String COMMAND = "PHOTO";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning;
    private boolean sent;

    private final Runnable timeout = () -> finishNow();

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (sent || result.getScanRecord() == null || result.getScanRecord().getServiceUuids() == null) return;
            boolean match = false;
            for (ParcelUuid uuid : result.getScanRecord().getServiceUuids()) {
                if (SERVICE_UUID.equals(uuid.getUuid())) { match = true; break; }
            }
            if (!match) return;
            stopScan();
            connect(result.getDevice());
        }
        @Override public void onScanFailed(int errorCode) { finishNow(); }
    };

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { current.discoverServices(); } catch (Throwable t) { finishNow(); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !sent) {
                finishNow();
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt current, int status) {
            BluetoothGattService service = current.getService(SERVICE_UUID);
            BluetoothGattCharacteristic ch = service == null ? null : service.getCharacteristic(COMMAND_UUID);
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null) {
                finishNow();
                return;
            }
            write(current, ch);
        }

        @Override public void onCharacteristicWrite(
                BluetoothGatt current, BluetoothGattCharacteristic characteristic, int status) {
            sent = status == BluetoothGatt.GATT_SUCCESS;
            handler.postDelayed(PhotoActivity.this::finishNow, 120);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (permissionsReady()) startScan();
        else requestPermissionsNow();
        handler.postDelayed(timeout, 8000);
    }

    private boolean permissionsReady() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsNow() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQ);
        } else {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, REQ);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ) return;
        boolean ok = results.length > 0;
        for (int r : results) ok &= r == PackageManager.PERMISSION_GRANTED;
        if (ok) startScan(); else finishNow();
    }

    private void startScan() {
        if (scanning || sent || adapter == null || !adapter.isEnabled()) {
            if (adapter == null || !adapter.isEnabled()) finishNow();
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { finishNow(); return; }
        try {
            scanning = true;
            scanner.startScan(null,
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scanCallback);
        } catch (Throwable t) {
            finishNow();
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
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            finishNow();
        }
    }

    @SuppressWarnings("deprecation")
    private void write(BluetoothGatt current, BluetoothGattCharacteristic ch) {
        byte[] data = COMMAND.getBytes(StandardCharsets.UTF_8);
        try {
            boolean queued;
            if (Build.VERSION.SDK_INT >= 33) {
                queued = current.writeCharacteristic(
                        ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS;
            } else {
                ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                ch.setValue(data);
                queued = current.writeCharacteristic(ch);
            }
            if (!queued) finishNow();
        } catch (Throwable t) {
            finishNow();
        }
    }

    private void finishNow() {
        handler.removeCallbacks(timeout);
        stopScan();
        if (gatt != null) {
            try { gatt.close(); } catch (Throwable ignored) {}
            gatt = null;
        }
        if (!isFinishing()) finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        finishNow();
        super.onDestroy();
    }
}
