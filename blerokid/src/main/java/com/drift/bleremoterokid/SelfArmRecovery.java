package com.drift.bleremoterokid;

import android.content.Context;

/**
 * Compatibility facade. Recovery scheduling and retry behavior now comes directly
 * from the R08-style SelfArmController.
 */
final class SelfArmRecovery {
    private SelfArmRecovery() {}

    static boolean isConfigured(Context context) {
        return SelfArmController.isConfigured(context);
    }

    static void onLaunch(Context context) {
        SelfArmController.armOnLaunch(context);
    }

    static void onBoot(Context context) {
        SelfArmController.armOnBoot(context);
    }

    static void onAccessibilityConnected(Context context) {
        SelfArmController.armOnServiceConnected(context);
    }
}
