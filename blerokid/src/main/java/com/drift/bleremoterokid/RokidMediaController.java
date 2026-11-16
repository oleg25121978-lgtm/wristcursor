package com.drift.bleremoterokid;

import android.content.Context;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Environment;

import com.rokid.security.glass3.open.sdk.GlassSdk;
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback;
import com.rokid.security.glass3.sdk.base.data.media.RecordConfig;
import com.rokid.security.system.server.IClientCallback;
import com.rokid.security.system.server.media.callback.PhotoFileCallback;
import com.rokid.security.system.server.media.callback.VideoCallback;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

final class RokidMediaController {
    interface Listener {
        void onStatus(String text);
    }

    private final Context context;
    private final Listener listener;
    private volatile boolean ready = false;
    private volatile boolean recording = false;

    RokidMediaController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    private final IClientCallback clientCallback = new IClientCallback.Stub() {
        @Override public void onReady() {
            ready = true;
            try {
                if (GlassSdk.getGlassMediaService() != null) {
                    GlassSdk.getGlassMediaService().addPhotoCallback(photoCallback);
                }
            } catch (Throwable ignored) {}
            status("ROKID SDK: READY");
        }
    };

    private final PhotoFileCallback photoCallback = new PhotoFileCallback.Stub() {
        @Override public void onTakePhoto(String path) {
            publishFile(path, true);
        }

        @Override public String getCallbackId() {
            return "ble-remote-photo";
        }

        @Override public void onTakePhotoV2(String path, int width, int height) {
            publishFile(path, true);
        }
    };

    private final VideoCallback videoCallback = new VideoCallback.Stub() {
        @Override public void onError() {
            recording = false;
            status("VIDEO: ERROR");
        }

        @Override public void onFinish() {
            recording = false;
            status("VIDEO: FINISHED");
        }

        @Override public void onNewFile(long startTime, long endTime, String path, boolean isLast) {
            publishFile(path, false);
        }

        @Override public void onErrorWithDetail(int code, String errorMsg) {
            recording = false;
            status("VIDEO ERROR " + code + ": " + errorMsg);
        }

        @Override public void onStart() {
            recording = true;
            status("VIDEO: RECORDING");
        }
    };

    void init() {
        try {
            if (GlassSdk.isReady()) {
                GlassSdk.registerClient("BleRokidRemote", clientCallback);
                return;
            }
            GlassSdk.bindSecurityService(context, new IServiceConnectionCallback() {
                @Override public void onServiceConnected() {
                    try {
                        GlassSdk.registerClient("BleRokidRemote", clientCallback);
                    } catch (Throwable t) {
                        status("ROKID SDK REGISTER ERROR");
                    }
                }

                @Override public void onServiceDisconnected() {
                    ready = false;
                    status("ROKID SDK: DISCONNECTED");
                }

                @Override public void onBindingDied() {
                    ready = false;
                    status("ROKID SDK: BINDING DIED");
                }
            });
        } catch (Throwable t) {
            status("ROKID SDK INIT: " + t.getClass().getSimpleName());
        }
    }

    void takePhoto() {
        new Thread(() -> {
            try {
                if (!ready || GlassSdk.getGlassMediaService() == null) {
                    status("PHOTO: SDK NOT READY");
                    init();
                    return;
                }
                File dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (dir == null) throw new IllegalStateException("Picture dir unavailable");
                if (!dir.exists()) dir.mkdirs();
                String path = new File(dir, "RokidRemote_" + System.currentTimeMillis() + ".jpg").getAbsolutePath();
                // Official demo maps legacy type 2 to 1280x720.
                GlassSdk.getGlassMediaService().takePhoto(2, path);
                status("PHOTO: REQUESTED");
            } catch (Throwable t) {
                status("PHOTO ERROR: " + t.getClass().getSimpleName());
            }
        }, "RokidPhoto").start();
    }

    void toggleVideo() {
        new Thread(() -> {
            try {
                if (!ready || GlassSdk.getGlassMediaService() == null) {
                    status("VIDEO: SDK NOT READY");
                    init();
                    return;
                }
                if (recording) {
                    GlassSdk.getGlassMediaService().stopRecord();
                    status("VIDEO: STOP REQUESTED");
                } else {
                    File movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                    if (movies == null) throw new IllegalStateException("Movie dir unavailable");
                    File videoDir = new File(movies, "video");
                    if (!videoDir.exists()) videoDir.mkdirs();
                    String path = videoDir.getAbsolutePath();
                    RecordConfig config = new RecordConfig(path, 1, true, 1280, 720, 20, true);
                    GlassSdk.getGlassMediaService().startRecord(videoCallback, config);
                    status("VIDEO: START REQUESTED");
                }
            } catch (Throwable t) {
                status("VIDEO ERROR: " + t.getClass().getSimpleName());
            }
        }, "RokidVideo").start();
    }

    private void publishFile(String path, boolean photo) {
        new Thread(() -> {
            try {
                File source = new File(path);
                if (!source.exists() || source.length() == 0) {
                    status((photo ? "PHOTO" : "VIDEO") + ": FILE NOT FOUND");
                    return;
                }

                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
                if (photo) {
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                    if (android.os.Build.VERSION.SDK_INT >= 29)
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RokidRemote");
                } else {
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                    if (android.os.Build.VERSION.SDK_INT >= 29)
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RokidRemote");
                }

                Uri collection = photo
                        ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                if (android.os.Build.VERSION.SDK_INT >= 29)
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri uri = context.getContentResolver().insert(collection, values);
                if (uri == null) throw new IllegalStateException("MediaStore insert failed");

                try (FileInputStream in = new FileInputStream(source);
                     OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("Output stream unavailable");
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }

                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    context.getContentResolver().update(uri, done, null, null);
                } else {
                    MediaScannerConnection.scanFile(context, new String[] { source.getAbsolutePath() }, null, null);
                }
                status((photo ? "PHOTO" : "VIDEO") + ": SAVED");
            } catch (Throwable t) {
                status((photo ? "PHOTO" : "VIDEO") + " PUBLISH ERROR: " + t.getClass().getSimpleName());
            }
        }, photo ? "PublishPhoto" : "PublishVideo").start();
    }

    boolean isReady() {
        return ready;
    }

    void release() {
        try {
            if (GlassSdk.getGlassMediaService() != null) {
                GlassSdk.getGlassMediaService().removePhotoCallback(photoCallback);
            }
        } catch (Throwable ignored) {}
        try { GlassSdk.release(); } catch (Throwable ignored) {}
        ready = false;
    }

    private void status(String text) {
        if (listener != null) listener.onStatus(text);
    }
}
