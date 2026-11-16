package com.drift.bleremoterokid;

import org.json.JSONObject;

final class NativeRecordingState {
    boolean video;
    boolean audio;
    boolean known;

    boolean update(JSONObject message) {
        String type = message.optString("type");
        JSONObject data = message.optJSONObject("data");
        if (data == null) return false;

        if ("cmd_notify_scene_status".equals(type)) {
            if (!data.has("videoRecordRunning") || !data.has("audioRecordRunning")) return false;
            video = data.optBoolean("videoRecordRunning");
            audio = data.optBoolean("audioRecordRunning");
            known = true;
            return true;
        }

        boolean isVideo = "result_video_record".equals(type);
        if (!isVideo && !"result_audio_record".equals(type)) return false;

        String status = data.optString("status");
        boolean running;
        if ("start".equals(status)) running = true;
        else if ("success".equals(status) || "error".equals(status)) running = false;
        else return false;

        if (isVideo) video = running;
        else audio = running;
        known = true;
        return true;
    }

    void reset() {
        video = false;
        audio = false;
        known = false;
    }
}
