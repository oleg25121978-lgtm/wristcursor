package com.drift.bleremoterokid;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

final class RokidAudioRecorder {
    interface Listener { void onStatus(String text); }

    private final Context context;
    private final Listener listener;
    private MediaRecorder recorder;
    private ParcelFileDescriptor outputFd;
    private Uri outputUri;
    private boolean recording;

    RokidAudioRecorder(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized void toggle() {
        if (recording) stop();
        else start();
    }

    synchronized void start() {
        if (recording) return;
        try {
            String name = "RokidRemote_" + System.currentTimeMillis() + ".m4a";
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/RokidRemote");
                values.put(MediaStore.Audio.Media.IS_PENDING, 1);
            }
            outputUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (outputUri == null) throw new IllegalStateException("MediaStore insert failed");
            outputFd = resolver.openFileDescriptor(outputUri, "w");
            if (outputFd == null) throw new IllegalStateException("Output fd unavailable");

            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(outputFd.getFileDescriptor());
            recorder.prepare();
            recorder.start();
            recording = true;
            status("AUDIO: RECORDING");
        } catch (Throwable t) {
            cleanupFailed();
            status("AUDIO ERROR: " + t.getClass().getSimpleName());
        }
    }

    synchronized void stop() {
        if (!recording && recorder == null) return;
        try {
            if (recorder != null) recorder.stop();
            status("AUDIO: SAVED");
        } catch (Throwable t) {
            status("AUDIO STOP ERROR: " + t.getClass().getSimpleName());
        } finally {
            recording = false;
            try { if (recorder != null) recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
            try { if (outputFd != null) outputFd.close(); } catch (Throwable ignored) {}
            outputFd = null;
            if (outputUri != null && Build.VERSION.SDK_INT >= 29) {
                try {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    context.getContentResolver().update(outputUri, done, null, null);
                } catch (Throwable ignored) {}
            }
            outputUri = null;
        }
    }

    synchronized boolean isRecording() { return recording; }

    synchronized void release() {
        if (recording || recorder != null) stop();
    }

    private void cleanupFailed() {
        recording = false;
        try { if (recorder != null) recorder.release(); } catch (Throwable ignored) {}
        recorder = null;
        try { if (outputFd != null) outputFd.close(); } catch (Throwable ignored) {}
        outputFd = null;
        if (outputUri != null) {
            try { context.getContentResolver().delete(outputUri, null, null); } catch (Throwable ignored) {}
        }
        outputUri = null;
    }

    private void status(String text) {
        if (listener != null) listener.onStatus(text);
    }
}
