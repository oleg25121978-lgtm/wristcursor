package com.drift.bleremoterokid;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

public class BleNavigationService extends AccessibilityService {
    private static volatile BleNavigationService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isReady() { return instance != null; }

    public static boolean execute(String command) {
        BleNavigationService service = instance;
        if (service == null) return false;
        service.main.post(() -> service.run(command));
        return true;
    }

    private void run(String command) {
        switch (command) {
            case "LEFT":  swipeHorizontal(false); break;
            case "RIGHT": swipeHorizontal(true); break;
            case "UP":    swipeVertical(true); break;
            case "DOWN":  swipeVertical(false); break;
            case "OK":
            case "TOUCH1": tapCenter(45); break;
            case "TOUCH2":
                tapCenter(45);
                main.postDelayed(() -> tapCenter(45), 130);
                break;
            case "TOUCH3":
                tapCenter(45);
                main.postDelayed(() -> tapCenter(45), 120);
                main.postDelayed(() -> tapCenter(45), 240);
                break;
            case "BACK": performGlobalAction(GLOBAL_ACTION_BACK); break;
            case "HOME": performGlobalAction(GLOBAL_ACTION_HOME); break;
        }
    }

    private float width() { return getResources().getDisplayMetrics().widthPixels; }
    private float height() { return getResources().getDisplayMetrics().heightPixels; }

    private void tapCenter(long duration) {
        float x = width() * 0.5f;
        float y = height() * 0.5f;
        Path path = new Path();
        path.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                .build(), null, null);
    }

    private void swipeHorizontal(boolean moveRight) {
        float w = width(), h = height(), y = h * 0.55f;
        if (moveRight) gestureLine(w * 0.78f, y, w * 0.22f, y, 180);
        else gestureLine(w * 0.22f, y, w * 0.78f, y, 180);
    }

    private void swipeVertical(boolean up) {
        float w = width(), h = height(), x = w * 0.5f;
        if (up) gestureLine(x, h * 0.78f, x, h * 0.22f, 180);
        else gestureLine(x, h * 0.22f, x, h * 0.78f, 180);
    }

    private void gestureLine(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                .build(), null, null);
    }
}
