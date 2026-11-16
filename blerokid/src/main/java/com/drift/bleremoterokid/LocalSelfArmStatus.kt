package com.drift.bleremoterokid

import android.content.Context

object LocalSelfArmStatus {
    @JvmStatic
    fun reportSimple(context: Context, setupState: String) {
        report(context, setupState = setupState)
    }

    @JvmStatic
    fun report(
        context: Context,
        setupState: String,
        wifiIp: String = "",
        adbPairCode: String = "",
        adbPairHost: String = "",
        adbPairPort: Int = 0,
        adbConnectPort: Int = 0,
        errorMessage: String = "",
    ) {
        val message = label(setupState, adbPairPort, adbConnectPort, errorMessage)
        SelfArmState.status = message
        context.applicationContext
            .getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
            .edit()
            .putString("self_arm_state", setupState)
            .putString("self_arm_message", message)
            .putString("self_arm_error", errorMessage)
            .putLong("self_arm_updated_at", System.currentTimeMillis())
            .apply()
    }

    @JvmStatic
    fun summary(context: Context): String =
        context.applicationContext
            .getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
            .getString("self_arm_message", "")
            .orEmpty()

    private fun label(state: String, pairPort: Int, connectPort: Int, error: String): String =
        when (state) {
            "requested" -> "STARTING"
            "accessibility_service_needed" -> "ENABLE ACCESSIBILITY FIRST"
            "starting_wireless_debugging_setup" -> "OPENING WIRELESS DEBUGGING"
            "enabling_wifi" -> "ENABLING WI-FI"
            "wifi_on" -> "WI-FI ON"
            "opening_developer_options" -> "OPENING DEVELOPER OPTIONS"
            "opening_wireless_debugging" -> "OPENING WIRELESS DEBUGGING"
            "turning_wireless_debugging_on" -> "ENABLING WIRELESS DEBUGGING"
            "waiting_for_pairing_code" -> "WAITING FOR PAIRING CODE"
            "pairing_ready" -> if (pairPort > 0) "PAIRING READY :$pairPort" else "PAIRING READY"
            "self_pairing_started" -> "PAIRING LOCALLY"
            "self_pairing_failed" -> "SELF-ARM FAILED: " + error.ifBlank { "PAIRING FAILED" }
            "wireless_bootstrap_complete" -> "SELF-ARM COMPLETE"
            "auto_rearm_complete" -> "SELF-ARM ACTIVE"
            "usb_debugging_off" -> "USB DEBUGGING OFF"
            "adb_key_missing" -> "SELF-ARM KEY MISSING"
            "adb_loopback_unreachable" -> "ADB LOOPBACK UNREACHABLE"
            "bootstrap_rearm_failed" -> "BOOT RE-ARM FAILED"
            else -> state.replace('_', ' ').uppercase()
        }
}
