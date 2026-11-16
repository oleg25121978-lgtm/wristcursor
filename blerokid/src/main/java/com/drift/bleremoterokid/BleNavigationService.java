package com.drift.bleremoterokid;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.View;

public class BleNavigationService extends AccessibilityService {
    private static volatile BleNavigationService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private R08StyleNavigator navigator;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        navigator = new R08StyleNavigator(this);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityWindowRoots.noteEvent(event, getPackageName());
    }
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
        R08StyleNavigator nav = navigator;
        switch (command) {
            case "LEFT":
            case "UP":
                if (nav != null) nav.backward();
                break;
            case "RIGHT":
            case "DOWN":
                if (nav != null) nav.forward();
                break;
            case "OK":
                if (nav != null) nav.activate();
                else tapCenter(45);
                break;
            case "TOUCH1":
                tapCenter(45);
                break;
            case "TOUCH2":
                tapCenter(45);
                main.postDelayed(() -> tapCenter(45), 130);
                break;
            case "TOUCH3":
                tapCenter(45);
                main.postDelayed(() -> tapCenter(45), 120);
                main.postDelayed(() -> tapCenter(45), 240);
                break;
            case "BACK":
                if (nav != null) nav.back();
                else performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case "HOME":
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
        }
    }

    // R08's GestureDispatcher suppresses re-reading its own injected gestures.
    // This service does not filter touch events, so no additional suppression is needed.
    void suppressInjectedGestures(long durationMs) {}

    private boolean moveFocus(boolean forward) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo current = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (current == null) current = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (current == null) current = root;
            AccessibilityNodeInfo next = current.focusSearch(forward ? View.FOCUS_FORWARD : View.FOCUS_BACKWARD);
            if (next != null) {
                boolean focused = next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                if (!focused) focused = next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                if (focused) return true;
            }
            // Fallback for lists/pages that expose scrolling rather than keyboard focus.
            return root.performAction(forward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean clickFocused() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (node == null) node = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            while (node != null) {
                if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                node = node.getParent();
            }
        } catch (Throwable ignored) {}
        return false;
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

    // Rokid BaseActivity converts vertical touch movement to the same FRONT/BEHIND
    // actions used by the temple controls. Horizontal movement means double-swipe.
    private void swipeRokidNext() {
        float w = width(), h = height(), x = w * 0.5f;
        gestureLine(x, h * 0.28f, x, h * 0.76f, 180);
    }

    private void swipeRokidPrevious() {
        float w = width(), h = height(), x = w * 0.5f;
        gestureLine(x, h * 0.76f, x, h * 0.28f, 180);
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
