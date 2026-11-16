package com.drift.bleremoterokid

import android.content.Context

/**
 * Compatibility facade used by the existing UI automator.
 * The actual implementation is now the R08-style bootstrap/controller.
 */
internal class SelfArmBootstrapper(context: Context) {
    private val appContext = context.applicationContext

    fun bootstrap(pairPort: Int, pairingCode: String, connectPort: Int): String {
        val result = SelfArmLocalAdbBootstrapper(appContext)
            .bootstrap(pairPort, pairingCode, connectPort)
        return result.output + "; " + result.postBootstrap
    }

    fun rearmLoopback(): String =
        SelfArmController.rearmNow(appContext)

    companion object {
        const val PREFS = BridgeProtocol.PREFS_BRIDGE
        const val KEY_ARMED = BridgeProtocol.PREF_BRIDGE_ARMED
        const val LOOPBACK_PORT = BridgeProtocol.DEFAULT_ADB_PORT
    }
}
