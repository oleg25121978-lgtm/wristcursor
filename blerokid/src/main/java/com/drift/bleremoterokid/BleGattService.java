package com.drift.bleremoterokid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BleGattService extends Service {
    static final UUID SERVICE_UUID = UUID.fromString("7f6f1000-5f8a-4c3a-9c6d-3f1d9b7a1000");
    static final UUID COMMAND_UUID = UUID.fromString("7f6f1001-5f8a-4c3a-9c6d-3f1d9b7a1000");
    private static final String CHANNEL_ID = "rokid_ble_remote";
    private static final int NOTIFICATION_ID = 2171;

    static volatile String bleState = "STOPPED";
    static volatile String clientState = "NONE";
    static volatile String lastAction = "—";

    private BluetoothManager manager;
    private BluetoothAdapter adapter;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private RokidMediaController mediaController;
    private RokidAudioRecorder audioRecorder;

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            clientState = newState == BluetoothProfile.STATE_CONNECTED
                    ? "CONNECTED " + safeAddress(device)
                    : "DISCONNECTED (" + status + ")";
            updateNotification();
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
            bleState = "ADVERTISING ON";
            updateNotification();
        }

        @Override public void onStartFailure(int errorCode) {
            bleState = "ADVERTISING FAILED " + errorCode;
            updateNotification();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA |
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE |
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            startForeground(NOTIFICATION_ID, buildNotification(), types);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }

        manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();

        mediaController = new RokidMediaController(this, text -> {
            lastAction = text;
            updateNotification();
        });
        audioRecorder = new RokidAudioRecorder(this, text -> {
            lastAction = text;
            updateNotification();
        });
        mediaController.init();
        startBle();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (gattServer == null || advertiser == null) startBle();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void startBle() {
        if (adapter == null || !adapter.isEnabled()) {
            bleState = "BLUETOOTH OFF";
            updateNotification();
            return;
        }
        stopBle();

        try {
            gattServer = manager.openGattServer(this, serverCallback);
            if (gattServer == null) {
                bleState = "GATT UNAVAILABLE";
                updateNotification();
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

            if (!gattServer.addService(service)) {
                bleState = "GATT ADD SERVICE FAILED";
                updateNotification();
                return;
            }

            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                bleState = "ADVERTISER UNAVAILABLE";
                updateNotification();
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

            bleState = "ADVERTISING STARTING";
            advertiser.startAdvertising(settings, data, advertiseCallback);
            updateNotification();
        } catch (SecurityException e) {
            bleState = "BLE PERMISSION ERROR";
            updateNotification();
        } catch (Throwable t) {
            bleState = "BLE ERROR " + t.getClass().getSimpleName();
            updateNotification();
        }
    }

    private void executeCommand(String command) {
        try {
            switch (command) {
                case "PHOTO":
                    mediaController.takePhoto();
                    break;
                case "VIDEO":
                    mediaController.toggleVideo();
                    break;
                case "VOICE":
                    audioRecorder.toggle();
                    break;
                case "MENU":
                    launchHome();
                    setAction("MENU / HOME");
                    break;
                case "NEXUS":
                    Intent nexus = new Intent("com.anezium.rokidbus.glasses.action.OPEN_LAUNCHER");
                    nexus.setPackage("com.anezium.rokidbus.glasses");
                    sendBroadcast(nexus);
                    setAction("NEXUS");
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
                    Intent assistant = new Intent("com.rokid.os.master.assist.server.cmd");
                    assistant.putExtra("cmd_type", "mobile_assistant");
                    assistant.putExtra("open", "true");
                    sendBroadcast(assistant);
                    setAction("HI ROKID");
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
                case "HOME":
                    launchHome();
                    break;
                case "LEFT":
                case "RIGHT":
                case "UP":
                case "DOWN":
                case "OK":
                case "BACK":
                case "TOUCH1":
                case "TOUCH2":
                case "TOUCH3":
                    setAction(command + (BleNavigationService.execute(command) ? " SENT" : " NEED ACCESSIBILITY"));
                    break;
                case "KEY1":
                case "KEY2":
                case "KEY3":
                case "KEY4":
                    setAction(command + " RESERVED");
                    break;
                default:
                    setAction("UNKNOWN " + command);
            }
        } catch (Throwable t) {
            setAction(command + " ERROR " + t.getClass().getSimpleName());
        }
    }

    private void adjustVolume(int direction) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            setAction("AUDIO UNAVAILABLE");
            return;
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        setAction("VOLUME " + audio.getStreamVolume(AudioManager.STREAM_MUSIC) +
                "/" + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private void toggleMute() {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            setAction("AUDIO UNAVAILABLE");
            return;
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
        setAction("MUTE TOGGLED");
    }

    private void adjustBrightness(int deltaLevel) {
        if (!Settings.System.canWrite(this)) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            permission.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(permission);
            setAction("BRIGHTNESS NEED PERMISSION");
            return;
        }
        int current = Settings.System.getInt(
                getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
        int next = Math.max(1, Math.min(255, current + deltaLevel * 17));
        Settings.System.putInt(getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, next);
        setAction("BRIGHTNESS " + Math.round(next * 15f / 255f) + "/15");
    }

    private void launchHome() {
        Intent intent = new Intent();
        intent.setClassName(
                "com.rokid.os.sprite.launcher",
                "com.rokid.os.sprite.launcher.main.SpriteMainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        setAction("HOME");
    }

    private void launchStock(String className, String label) {
        Intent intent = new Intent();
        intent.setClassName("com.rokid.os.sprite.launcher", className);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        setAction("APP " + label);
    }

    private void openOverlayMenu() {
        sendBroadcast(new Intent("com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"));
        new android.os.Handler(getMainLooper()).postDelayed(
                () -> sendBroadcast(new Intent("com.android.action.ACTION_TWO_FINGER_SWIPE_BACK")), 140);
        new android.os.Handler(getMainLooper()).postDelayed(
                () -> sendBroadcast(new Intent("com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD")), 280);
        new android.os.Handler(getMainLooper()).postDelayed(
                () -> sendBroadcast(new Intent("com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD")), 420);
    }

    private void setAction(String text) {
        lastAction = text;
        updateNotification();
    }

    private String safeAddress(BluetoothDevice device) {
        try { return device == null ? "?" : device.getAddress(); }
        catch (SecurityException e) { return "?"; }
    }

    private void stopBle() {
        if (advertiser != null) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (Throwable ignored) {}
            advertiser = null;
        }
        if (gattServer != null) {
            try { gattServer.close(); } catch (Throwable ignored) {}
            gattServer = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Rokid BLE Remote", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Rokid BLE Remote")
                .setContentText(bleState + " • " + clientState)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        try {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        stopBle();
        if (mediaController != null) mediaController.release();
        if (audioRecorder != null) audioRecorder.release();
        bleState = "STOPPED";
        clientState = "NONE";
        super.onDestroy();
    }
}
