package com.drift.bleremoterokid;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SelfArmAutomator {
    private static final String TAG = "RokidSelfArmUi";
    private static final Pattern ENDPOINT = Pattern.compile(
            "(?:\\d{1,3}\\.){3}\\d{1,3}:(\\d{4,5})");
    private static final Pattern SIX_DIGIT = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");

    private final BleNavigationService service;
    private final Handler handler;
    private final Runnable stepRunnable = this::step;

    private boolean active;
    private boolean bootstrapping;
    private int connectPort;
    private int noProgress;
    private long deadline;

    SelfArmAutomator(BleNavigationService service, Handler handler) {
        this.service = service;
        this.handler = handler;
    }

    void start() {
        if (Build.VERSION.SDK_INT < 30) {
            SelfArmState.status = "NEED ANDROID 11+";
            return;
        }
        if (SelfArmState.running) return;
        active = true;
        bootstrapping = false;
        connectPort = 0;
        noProgress = 0;
        deadline = SystemClock.uptimeMillis() + 120_000L;
        SelfArmState.running = true;
        if (!wifiEnabled()) {
            SelfArmState.status = "ENABLING WI-FI";
            openWifiSettings();
        } else {
            SelfArmState.status = "OPENING DEVELOPER OPTIONS";
            openDeveloperOptions();
        }
        schedule(900);
    }

    void stop() {
        active = false;
        SelfArmState.running = false;
        handler.removeCallbacks(stepRunnable);
    }

    void onAccessibilityEvent() {
        if (active && !bootstrapping) schedule(180);
    }

    private void step() {
        if (!active || bootstrapping) return;
        if (SystemClock.uptimeMillis() > deadline) {
            fail("SELF-ARM TIMEOUT");
            return;
        }

        AccessibilityNodeInfo root = AccessibilityWindowRoots.getNavigationRoot(service);
        if (root == null) {
            schedule(400);
            return;
        }

        if (!wifiEnabled()) {
            AccessibilityNodeInfo wifiToggle = findFirst(root, n ->
                    n.isCheckable() &&
                    (nodeText(n).toLowerCase(Locale.ROOT).contains("wi-fi") ||
                     nodeText(n).toLowerCase(Locale.ROOT).contains("wifi") ||
                     className(n).toLowerCase(Locale.ROOT).contains("switch")));
            if (wifiToggle != null && !wifiToggle.isChecked() && clickNode(wifiToggle)) {
                SelfArmState.status = "ENABLING WI-FI";
                schedule(1000);
                return;
            }
            SelfArmState.status = "WAITING FOR WI-FI";
            schedule(600);
            return;
        } else if (SelfArmState.status.startsWith("ENABLING WI-FI") ||
                   SelfArmState.status.startsWith("WAITING FOR WI-FI")) {
            SelfArmState.status = "OPENING DEVELOPER OPTIONS";
            openDeveloperOptions();
            schedule(900);
            return;
        }

        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        String joined = String.join("\n", texts);
        String lower = joined.toLowerCase(Locale.ROOT);

        // R08 behavior: if Developer options are actually OFF, go directly to
        // Device info and enable them there instead of getting stuck on the disabled page.
        boolean buildEntryVisible =
                lower.contains("build number") ||
                lower.contains("номер сборки") ||
                lower.contains("software version") ||
                lower.contains("версия по");
        boolean developerOptionsEnabled;
        try {
            developerOptionsEnabled = Settings.Global.getInt(
                    service.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                    0) == 1;
        } catch (Throwable ignored) {
            developerOptionsEnabled = true;
        }
        if (!developerOptionsEnabled && !buildEntryVisible) {
            SelfArmState.status = "ENABLING DEVELOPER OPTIONS";
            openDeviceInfo();
            noProgress = 0;
            schedule(900);
            return;
        }

        String code = pairingCode(root, joined);
        int endpointPort = SelfArmWirelessAdbController.INSTANCE.readWirelessPort();
        if (endpointPort <= 0) endpointPort = firstEndpointPort(joined);

        boolean pairingDialog = code != null && (
                hasViewId(root, "com.android.settings:id/pairing_code") ||
                lower.contains("pairing code") ||
                lower.contains("wi-fi pairing code") ||
                lower.contains("wifi pairing code") ||
                lower.contains("pair with device") ||
                lower.contains("код подключения") ||
                lower.contains("код сопряжения") ||
                lower.contains("код подключения wi-fi") ||
                lower.contains("код подключения wifi") ||
                lower.contains("ip address") ||
                lower.contains("адрес ip"));

        if (pairingDialog) {
            int pairPort = pairingEndpointPort(root, connectPort);
            if (connectPort <= 0) {
                connectPort = SelfArmWirelessAdbController.INSTANCE.readWirelessPort();
            }
            if (connectPort <= 0) {
                SelfArmState.status = "WAITING FOR WIRELESS ADB PORT";
                schedule(600);
                return;
            }
            if (pairPort <= 0) {
                SelfArmState.status = "WAITING FOR PAIR PORT";
                schedule(350);
                return;
            }
            bootstrap(code, pairPort, connectPort);
            return;
        }

        if (isWirelessPage(lower)) {
            if (endpointPort > 0) connectPort = endpointPort;

            AccessibilityNodeInfo toggle = findFirst(root, n ->
                    n.isCheckable() &&
                            (nodeText(n).toLowerCase(Locale.ROOT).contains("wireless") ||
                             nodeText(n).toLowerCase(Locale.ROOT).contains("беспровод") ||
                             className(n).toLowerCase(Locale.ROOT).contains("switch")));
            if (toggle != null && !toggle.isChecked()) {
                if (clickNode(toggle)) {
                    SelfArmState.status = "ENABLING WIRELESS DEBUGGING";
                    schedule(1200);
                    return;
                }
            }

            if (connectPort <= 0) {
                SelfArmState.status = "WAITING FOR WIRELESS ADB PORT";
                schedule(700);
                return;
            }

            AccessibilityNodeInfo pair = findByText(root,
                    "pair device with pairing code",
                    "pair with device",
                    "pairing code",
                    "подключение устройства с помощью кода подключения",
                    "подключить устройство с помощью кода подключения",
                    "код подключения",
                    "код сопряжения");
            if (pair != null && clickNode(pair)) {
                SelfArmState.status = "READING PAIRING CODE";
                schedule(900);
                return;
            }

            SelfArmState.status = "SEARCHING PAIRING CODE";
            if (scrollForward(root)) {
                schedule(450);
                return;
            }
            schedule(600);
            return;
        }

        AccessibilityNodeInfo wireless = findByText(root,
                "wireless debugging",
                "отладка по wi-fi",
                "отладка по wi fi",
                "отладка по wifi",
                "беспроводная отладка");
        if (wireless != null && clickNode(wireless)) {
            SelfArmState.status = "OPENING WIRELESS DEBUGGING";
            noProgress = 0;
            schedule(900);
            return;
        }

        if (isDeveloperPage(lower)) {
            SelfArmState.status = "SEARCHING WIRELESS DEBUGGING";
            if (scrollForward(root)) {
                noProgress++;
                schedule(400);
                return;
            }
            noProgress++;
            if (noProgress >= 5) {
                openDeviceInfo();
                SelfArmState.status = "ENABLING DEVELOPER OPTIONS";
                noProgress = 0;
                schedule(900);
                return;
            }
            schedule(500);
            return;
        }

        AccessibilityNodeInfo build = findByText(root,
                "build number",
                "номер сборки",
                "software version",
                "версия по");
        if (build != null) {
            SelfArmState.status = "ENABLING DEVELOPER OPTIONS";
            tapBuildNumber(build, 0);
            return;
        }

        noProgress++;
        if (noProgress % 5 == 0) {
            openDeveloperOptions();
        }
        schedule(500);
    }

    private void tapBuildNumber(AccessibilityNodeInfo node, int count) {
        if (!active) return;
        if (count >= 8) {
            handler.postDelayed(() -> {
                openDeveloperOptions();
                schedule(900);
            }, 900);
            return;
        }
        clickNode(node);
        handler.postDelayed(() -> tapBuildNumber(node, count + 1), 180);
    }

    private void bootstrap(String code, int pairPort, int connPort) {
        if (bootstrapping) return;
        bootstrapping = true;
        SelfArmState.status = "PAIRING LOCALLY";
        Thread t = new Thread(() -> {
            try {
                String out = new SelfArmBootstrapper(service).bootstrap(pairPort, code, connPort);
                Log.i(TAG, "Self-arm complete: " + out);
                SelfArmState.status = "SELF-ARM COMPLETE";
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            } catch (Throwable ex) {
                Log.e(TAG, "Self-arm failed", ex);
                SelfArmState.status = "SELF-ARM FAILED: " + shortMessage(ex);
            } finally {
                active = false;
                bootstrapping = false;
                SelfArmState.running = false;
            }
        }, "rokid-self-arm-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    private void fail(String reason) {
        SelfArmState.status = reason;
        stop();
    }

    private boolean wifiEnabled() {
        try {
            WifiManager wifi = (WifiManager) service.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            return wifi != null && wifi.isWifiEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void openWifiSettings() {
        try {
            service.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
            try {
                service.startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored2) {}
        }
    }

    private void openDeveloperOptions() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(i);
        } catch (Throwable ignored) {
            openDeviceInfo();
        }
    }

    private void openDeviceInfo() {
        try {
            Intent i = new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(i);
        } catch (Throwable ignored) {
            try {
                service.startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored2) {}
        }
    }

    private void performGlobalBack() {
        try { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); }
        catch (Throwable ignored) {}
    }

    private boolean isWirelessPage(String lower) {
        return lower.contains("pair device with pairing code") ||
                lower.contains("use wireless debugging") ||
                lower.contains("ip address & port") ||
                lower.contains("ip address and port") ||
                lower.contains("код подключения") ||
                lower.contains("использовать отладку по wi-fi") ||
                lower.contains("беспроводная отладка");
    }

    private boolean isDeveloperPage(String lower) {
        return lower.contains("developer options") ||
                lower.contains("режим разработчика") ||
                lower.contains("параметры разработчика") ||
                lower.contains("отладка");
    }

    private int pairingEndpointPort(AccessibilityNodeInfo root, int knownConnectPort) {
        try {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId("com.android.settings:id/ip_addr");
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    int p = firstEndpointPort(nodeText(node));
                    if (p > 0 && p != knownConnectPort) return p;
                    p = firstStandalonePort(nodeText(node), null, knownConnectPort);
                    if (p > 0) return p;
                }
            }
        } catch (Throwable ignored) {}

        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        String all = String.join("\n", texts);

        Matcher m = ENDPOINT.matcher(all);
        while (m.find()) {
            try {
                int p = Integer.parseInt(m.group(1));
                if (p >= 1024 && p <= 65535 && p != knownConnectPort) return p;
            } catch (Throwable ignored) {}
        }

        String code = pairingCode(root, all);
        return firstStandalonePort(all, code, knownConnectPort);
    }

    private int firstStandalonePort(String text, String pairingCode, int knownConnectPort) {
        Matcher m = Pattern.compile("(?<!\\d)(\\d{4,5})(?!\\d)").matcher(text == null ? "" : text);
        while (m.find()) {
            try {
                String digits = m.group(1);
                if (pairingCode != null && pairingCode.equals(digits)) continue;
                int p = Integer.parseInt(digits);
                if (p >= 1024 && p <= 65535 && p != knownConnectPort) return p;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    private String pairingCode(AccessibilityNodeInfo root, String fallbackText) {
        try {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId("com.android.settings:id/pairing_code");
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    String code = firstSixDigit(nodeText(node));
                    if (code != null) return code;
                }
            }
        } catch (Throwable ignored) {}
        return firstSixDigit(fallbackText);
    }

    private boolean hasViewId(AccessibilityNodeInfo root, String id) {
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            return nodes != null && !nodes.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int firstEndpointPort(String text) {
        Matcher m = ENDPOINT.matcher(text);
        if (!m.find()) return 0;
        try {
            int p = Integer.parseInt(m.group(1));
            return p >= 1024 && p <= 65535 ? p : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private String firstSixDigit(String text) {
        Matcher m = SIX_DIGIT.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private void collectTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && text.length() > 0) out.add(text.toString());
        if (desc != null && desc.length() > 0) out.add(desc.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectTexts(child, out);
        }
    }

    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo root, String... needles) {
        return findFirst(root, n -> {
            String t = nodeText(n).toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (t.contains(needle.toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        });
    }

    private AccessibilityNodeInfo findFirst(AccessibilityNodeInfo node, NodePredicate predicate) {
        if (node == null) return null;
        if (predicate.matches(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findFirst(child, predicate);
            if (found != null) return found;
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 6; i++) {
            if (current.isClickable() &&
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        return false;
    }

    private boolean scrollForward(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo scroll = findFirst(root, AccessibilityNodeInfo::isScrollable);
        return scroll != null &&
                scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    private String nodeText(AccessibilityNodeInfo node) {
        StringBuilder b = new StringBuilder();
        if (node.getText() != null) b.append(node.getText()).append(' ');
        if (node.getContentDescription() != null) b.append(node.getContentDescription());
        return b.toString();
    }

    private String className(AccessibilityNodeInfo node) {
        return node.getClassName() == null ? "" : node.getClassName().toString();
    }

    private String shortMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m.trim();
    }

    private void schedule(long ms) {
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(stepRunnable, ms);
    }

    private interface NodePredicate {
        boolean matches(AccessibilityNodeInfo node);
    }
}
