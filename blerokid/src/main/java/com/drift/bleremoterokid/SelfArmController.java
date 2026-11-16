package com.drift.bleremoterokid;

import android.content.Context;

import java.io.File;

/**
 * Java-facing facade for the Kotlin R08 self-arm implementation.
 * KADB itself is compiled for Java 21, so all direct KADB access stays in Kotlin.
 */
final class SelfArmController {
    static final String SETTING_ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time";

    private SelfArmController() {}

    static void armOnLaunch(Context context) {
        R08SelfArmController.armOnLaunch(context);
    }

    static void armOnBoot(Context context) {
        R08SelfArmController.armOnBoot(context);
    }

    static void armOnServiceConnected(Context context) {
        R08SelfArmController.armOnServiceConnected(context);
    }

    static boolean isConfigured(Context context) {
        return R08SelfArmController.isConfigured(context);
    }

    static String afterLocalBootstrap(Context context) {
        return R08SelfArmController.afterLocalBootstrap(context);
    }

    static String rearmNow(Context context) throws Exception {
        return R08SelfArmController.rearmNow(context);
    }

    static File internalKeyFile(Context context) {
        return R08SelfArmController.internalKeyFile(context);
    }
}
