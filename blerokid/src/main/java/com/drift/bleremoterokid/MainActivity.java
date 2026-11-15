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
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.provider.Settings;
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
    private boolean videoSceneOpen = false;

    private static final String ACTION_CMD = "com.rokid.os.master.assist.server.cmd";

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
            executeCommand(command);

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

    private void executeCommand(String command) {
        runOnUiThread(() -> {
            try {
                switch (command) {
                    case "PHOTO":
                        sendRokidScene("ar_picture", true);
                        commandText.setText("ACTION: PHOTO → ar_picture");
                        break;
                    case "VIDEO":
                        videoSceneOpen = !videoSceneOpen;
                        sendRokidScene("mix_record", videoSceneOpen);
                        commandText.setText("ACTION: VIDEO → " + (videoSceneOpen ? "START" : "STOP"));
                        break;
                    case "VOICE":
                        sendRokidScene("audio_record", true);
                        commandText.setText("ACTION: RECORDER → audio_record");
                        break;
                    case "NEXUS":
                        Intent nexus = new Intent("com.anezium.rokidbus.glasses.action.OPEN_LAUNCHER");
                        nexus.setPackage("com.anezium.rokidbus.glasses");
                        sendBroadcast(nexus);
                        commandText.setText("ACTION: NEXUS → OPEN");
                        break;
                    case "VOL_UP":
                        adjustVolume(AudioManager.ADJUST_RAISE);
                        break;
                    case "VOL_DOWN":
                        adjustVolume(AudioManager.ADJUST_LOWER);
                        break;
                    case "MUTE":
                        toggleMute();
                        break;
                    case "BRI_UP":
                        adjustBrightness(1);
                        break;
                    case "BRI_DOWN":
                        adjustBrightness(-1);
                        break;
                    case "APP_CAMERA":
                        launchStock("com.rokid.os.sprite.launcher.page.camera.CameraPageActivity", "CAMERA");
                        break;
                    case "APP_CHAT":
                        launchStock("com.rokid.os.sprite.launcher.page.chat.ChatPageActivity", "AI CHAT");
                        break;
                    case "APP_TRANSLATE":
                        launchStock("com.rokid.os.sprite.launcher.page.translate.TranslatePageActivity", "TRANSLATE");
                        break;
                    case "APP_PROMPTER":
                        launchStock("com.rokid.os.sprite.launcher.page.wordtips.WordTipsPageActivity", "WORD TIPS");
                        break;
                    case "APP_MUSIC":
                        launchStock("com.rokid.os.sprite.launcher.page.music.MusicPageActivity", "MUSIC");
                        break;
                    case "APP_NAV":
                        launchStock("com.rokid.os.sprite.launcher.page.navigation.NavigationOverseaPageActivity", "NAVIGATION");
                        break;
                    case "APP_GALLERY":
                        launchStock("com.rokid.os.sprite.launcher.page.gallery.StorageImageShowActivity", "GALLERY");
                        break;
                    case "APP_SETTINGS":
                        launchStock("com.rokid.os.sprite.launcher.setting.SettingPageActivity", "SETTINGS");
                        break;
                    case "APP_BT":
                        launchStock("com.rokid.os.sprite.launcher.setting.bluetooth.SettingBluetoothActivity", "BLUETOOTH");
                        break;
                    case "APP_AUDIO":
                        launchStock("com.rokid.os.sprite.launcher.page.audio.AudioPageActivity", "AUDIO");
                        break;
                    case "LEFT":
                    case "RIGHT":
                    case "UP":
                    case "DOWN":
                    case "OK":
                    case "BACK":
                    case "HOME":
                    case "TOUCH1":
                    case "TOUCH2":
                    case "TOUCH3":
                        boolean navOk = BleNavigationService.execute(command);
                        commandText.setText("NAV: " + command + (navOk ? " → SENT" : " → ENABLE ACCESSIBILITY"));
                        break;
                    case "KEY1":
                    case "KEY2":
                    case "KEY3":
                    case "KEY4":
                        commandText.setText("ACTION: " + command + " → RESERVED");
                        break;
                    default:
                        commandText.setText("UNKNOWN: " + command);
                        break;
                }
            } catch (Throwable t) {
                commandText.setText("ACTION ERROR: " + t.getClass().getSimpleName());
            }
        });
    }

    private void adjustVolume(int direction) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            commandText.setText("SYSTEM: AUDIO UNAVAILABLE");
            return;
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        commandText.setText("VOLUME: " + now + " / " + max);
    }

    private void toggleMute() {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            commandText.setText("SYSTEM: AUDIO UNAVAILABLE");
            return;
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
        commandText.setText("VOLUME: MUTE TOGGLED");
    }

    private void adjustBrightness(int deltaLevel) {
        if (!Settings.System.canWrite(this)) {
            commandText.setText("BRIGHTNESS: NEED WRITE SETTINGS");
            try {
                Intent permission = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                permission.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(permission);
            } catch (Throwable ignored) {}
            return;
        }
        int current = Settings.System.getInt(getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, 128);
        int step = 17; // 255 / 15, matching Rokid's 0..15 UI levels.
        int next = Math.max(1, Math.min(255, current + deltaLevel * step));
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, next);
        int level = Math.round(next * 15f / 255f);
        commandText.setText("BRIGHTNESS: " + level + " / 15");
    }

    private void launchStock(String className, String label) {
        Intent intent = new Intent();
        intent.setClassName("com.rokid.os.sprite.launcher", className);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        commandText.setText("APP: " + label);
    }

    private void sendRokidScene(String scene, boolean open) {
        Intent intent = new Intent(ACTION_CMD);
        intent.putExtra("cmd_type", "control_scene");
        intent.putExtra("scene", scene);
        intent.putExtra("open", open ? "true" : "false");
        sendBroadcast(intent);
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
