package com.drift.bleremoterokid;

import android.os.Handler;

/** Compatibility wrapper around the full R08 self-arm automator. */
final class SelfArmAutomator {
    private final SelfArmWirelessDebuggingAutomator delegate;

    SelfArmAutomator(BleNavigationService service, Handler handler) {
        delegate = new SelfArmWirelessDebuggingAutomator(service, handler);
    }

    void start() {
        delegate.start();
    }

    void stop() {
        delegate.stop();
    }

    void onAccessibilityEvent() {
        delegate.onAccessibilityEvent(null);
    }
}
