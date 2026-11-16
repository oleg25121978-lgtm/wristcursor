package com.drift.bleremoterokid;

final class SelfArmState {
    static volatile String status = "NOT ARMED";
    static volatile boolean running = false;
    private SelfArmState() {}
}
