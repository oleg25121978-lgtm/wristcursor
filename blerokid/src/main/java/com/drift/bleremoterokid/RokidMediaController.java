package com.drift.bleremoterokid;

import android.content.Context;
import android.os.Environment;

import com.rokid.security.glass3.open.sdk.GlassSdk;
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback;
import com.rokid.security.glass3.sdk.base.data.media.RecordConfig;
import com.rokid.security.system.server.IClientCallback;
import com.rokid.security.system.server.media.callback.PhotoFileCallback;
import com.rokid.security.system.server.media.callback.VideoCallback;

import java.io.File;

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
            status("PHOTO SAVED: " + path);
        }

        @Override public String getCallbackId() {
            return "ble-remote-photo";
        }

        @Override public void onTakePhotoV2(String path, int width, int height) {
            status("PHOTO SAVED: " + width + "x" + height);
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
            status("VIDEO FILE: " + path);
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
                File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                File dir = new File(dcim, "Camera");
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
                    File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    String path = new File(pictures, "video").getAbsolutePath();
                    RecordConfig config = new RecordConfig(path, 1, true, 1280, 720, 20, true);
                    GlassSdk.getGlassMediaService().startRecord(videoCallback, config);
                    status("VIDEO: START REQUESTED");
                }
            } catch (Throwable t) {
                status("VIDEO ERROR: " + t.getClass().getSimpleName());
            }
        }, "RokidVideo").start();
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
