package com.drift.bleremoterokid;

final class BridgeProtocol {
    static final String PREFS_BRIDGE = "self_arm";
    static final String PREF_BRIDGE_ARMED = "armed";
    static final int DEFAULT_ADB_PORT = 5555;
    static final String SELF_ARM_ADB_KEY_FILE = "adbkey.pem";
    static final String REMOTE_SCRIPT = "/data/local/tmp/rokid-ble-shortcut-bridge.sh";
    static final String REMOTE_WATCHDOG_SCRIPT = "/data/local/tmp/rokid-ble-a11y-watchdog.sh";

    static final String BRIDGE_DIR_NAME = "shortcut_bridge";
    static final String REQUEST_FILE = "request";
    static final String RESPONSE_FILE = "response";
    static final String HEARTBEAT_FILE = "heartbeat";
    static final String DOORBELL_FILE = "doorbell";
    static final String ENTRY_CAPABILITY_FILE = "entry_capability";
    static final String ENTRY_REQUEST_FILE = "entry_request";
    static final String ENTRY_RESPONSE_FILE = "entry_response";
    static final String ENTRY_PROTOCOL_VERSION = "1";
    static final String FEATURES_FILE = "features";

    static final String FEATURE_FUNCTION_KEY = "function_key";
    static final String FEATURE_AI_KEY = "ai_key";
    static final String FEATURE_ASSISTANT_PIN = "assistant_pin";
    static final String FEATURE_ASSISTANT_SCROLL = "assistant_scroll";
    static final String FEATURE_TOUCHPAD_KEYS = "touchpad_keys";
    static final String FEATURE_CAMERA_ACTIVITY = "camera_activity";

    static final String COMMAND_TP_UP = "tp_up";
    static final String COMMAND_TP_DOWN = "tp_down";
    static final String COMMAND_TP_LEFT = "tp_left";
    static final String COMMAND_TP_RIGHT = "tp_right";
    static final String COMMAND_TP_ENTER = "tp_enter";
    static final String COMMAND_TP_BACK = "tp_back";

    static final String COMMAND_SHORTCUT = "shortcut";
    static final String COMMAND_FUNCTION_KEY_SHORT = "function_key_short";
    static final String COMMAND_FUNCTION_KEY_LONG = "function_key_long";
    static final String COMMAND_AI_KEY = "ai_key";
    static final String COMMAND_WIFI_ENABLE = "wifi_enable";
    static final String COMMAND_WIFI_DISABLE = "wifi_disable";
    static final String COMMAND_CAMERA_ACTIVITY = "camera_activity";

    private BridgeProtocol() {}
}
