package com.drift.bleremoterokid;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import org.json.JSONObject;

final class NativeRecordingBridge {
    interface Listener { void onStatus(String text); }

    private static final String PACKAGE = "com.rokid.os.sprite.assistserver";
    private static final String SERVICE_CLASS = "com.rokid.os.sprite.assist.MasterAssistService";
    private static final String SERVER = "com.rokid.os.sprite.assist.server.IAssistServer";
    private static final String CLIENT = "com.rokid.os.sprite.assist.client.IAssistClient";

    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final NativeRecordingState state = new NativeRecordingState();

    private IBinder server;
    private boolean bound;
    private boolean started;
    private int serverUid = -1;
    private long pendingUntil;
    private boolean requestedVideo;
    private boolean requestedAudio;

    NativeRecordingBridge(Context context, Handler handler, Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.listener = listener;
    }

    private final Binder callback = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(CLIENT);
                return true;
            }
            if (serverUid >= 0 && Binder.getCallingUid() != serverUid) return false;
            data.enforceInterface(CLIENT);

            if (code == 1) {
                data.readInt();
                reply.writeNoException();
                return true;
            }

            if (code == 2) {
                if (data.readInt() != 0) {
                    data.readLong();
                    data.readString();
                    data.readString();
                    data.readLong();
                    String message = data.readString();
                    if (message != null && message.length() <= 65536) {
                        handler.post(() -> receive(message));
                    }
                }
                reply.writeNoException();
                reply.writeInt(1);
                return true;
            }

            if (code == 3) {
                data.readString();
                data.readString();
                byte[] bytes = data.createByteArray();
                reply.writeNoException();
                reply.writeByteArray(bytes);
                return true;
            }

            return super.onTransact(code, data, reply, flags);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!started) return;
            server = binder;
            try {
                if (!SERVER.equals(binder.getInterfaceDescriptor())) {
                    server = null;
                    status("NATIVE MEDIA: WRONG SERVICE");
                    return;
                }
                transact(1, null);
                status("NATIVE MEDIA: READY");
            } catch (Throwable t) {
                server = null;
                status("NATIVE MEDIA ERROR: " + t.getClass().getSimpleName());
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            server = null;
            state.reset();
            status("NATIVE MEDIA: DISCONNECTED");
        }

        @Override public void onBindingDied(ComponentName name) {
            stop();
            start();
        }

        @Override public void onNullBinding(ComponentName name) {
            server = null;
            status("NATIVE MEDIA: NULL BINDING");
        }
    };

    void start() {
        if (bound) return;
        started = true;
        try {
            serverUid = context.getPackageManager().getApplicationInfo(PACKAGE, 0).uid;
            Intent intent = new Intent().setComponent(new ComponentName(PACKAGE, SERVICE_CLASS));
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            status(bound ? "NATIVE MEDIA: BINDING" : "NATIVE MEDIA: BIND FAILED");
        } catch (Throwable t) {
            bound = false;
            status("NATIVE MEDIA BIND ERROR: " + t.getClass().getSimpleName());
        }
    }

    void stop() {
        started = false;
        if (server != null) {
            try { transact(2, null); } catch (Throwable ignored) {}
        }
        server = null;
        state.reset();
        pendingUntil = 0L;
        if (bound) {
            try { context.unbindService(connection); } catch (Throwable ignored) {}
        }
        bound = false;
    }

    boolean takePhoto() {
        boolean ok = send("cmd_take_picture", "cameraOpenType", "picture_with_ui");
        status(ok ? "PHOTO: NATIVE REQUESTED" : "PHOTO: NATIVE NOT READY");
        return ok;
    }

    boolean toggleVideo() {
        if (server == null) {
            status("VIDEO: NATIVE NOT READY");
            return false;
        }
        if (SystemClock.uptimeMillis() < pendingUntil) return true;

        boolean running = state.known ? state.video : requestedVideo;
        boolean start = !running;
        boolean ok = command(false, start);
        if (ok) {
            requestedVideo = start;
            status(start ? "VIDEO: NATIVE START" : "VIDEO: NATIVE STOP");
        }
        return ok;
    }

    boolean toggleAudio() {
        if (server == null) {
            status("AUDIO: NATIVE NOT READY");
            return false;
        }
        if (SystemClock.uptimeMillis() < pendingUntil) return true;

        boolean running = state.known ? state.audio : requestedAudio;
        boolean start = !running;
        boolean ok = command(true, start);
        if (ok) {
            requestedAudio = start;
            status(start ? "AUDIO: NATIVE START" : "AUDIO: NATIVE STOP");
        }
        return ok;
    }

    boolean openAiAssist() {
        if (server == null) {
            status("ROKID AI: NATIVE NOT READY");
            return false;
        }
        try {
            JSONObject payload = new JSONObject()
                    .put("sceneKey", "ai_assist")
                    .put("useTips", true);
            transact(3, new JSONObject()
                    .put("type", "cmd_open_scene_with_ignore_tips")
                    .put("data", payload)
                    .toString());
            status("ROKID AI: NATIVE OPEN");
            return true;
        } catch (Throwable t) {
            status("ROKID AI ERROR: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private boolean command(boolean audio, boolean start) {
        String type = "cmd_" + (start ? "start_" : "stop_") +
                (audio ? "audio_record" : "video_record");
        String key = audio ? "audioOpenType" : "cameraOpenType";
        String value = audio ? "audio_with_ui" : "video_with_ui";
        boolean ok = send(type, key, value);
        if (ok) pendingUntil = SystemClock.uptimeMillis() + 3000L;
        return ok;
    }

    private boolean send(String type, String key, String value) {
        if (server == null) return false;
        try {
            JSONObject payload = new JSONObject().put(key, value);
            JSONObject request = new JSONObject().put("type", type).put("data", payload);
            transact(3, request.toString());
            return true;
        } catch (Throwable t) {
            status("NATIVE MEDIA SEND ERROR: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private void transact(int code, String json) throws RemoteException {
        IBinder target = server;
        if (target == null) throw new RemoteException("server unavailable");

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVER);
            data.writeString(context.getPackageName());
            if (code == 1) data.writeStrongBinder(callback);
            else if (code == 3) data.writeString(json);

            if (!target.transact(code, data, reply, 0)) {
                throw new RemoteException("unsupported transaction " + code);
            }
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void receive(String message) {
        try {
            if (state.update(new JSONObject(message))) {
                requestedVideo = state.video;
                requestedAudio = state.audio;
                pendingUntil = 0L;
                status("MEDIA STATE: VIDEO " + (state.video ? "ON" : "OFF") +
                        " AUDIO " + (state.audio ? "ON" : "OFF"));
            }
        } catch (Throwable ignored) {}
    }

    private void status(String text) {
        if (listener != null) listener.onStatus(text);
    }
}
