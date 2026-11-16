package com.drift.bleremoterokid;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Core navigation order adapted from Anezium/R08-Access-Bridge (Apache-2.0).
 * Keeps Rokid launcher handling separate, then uses explicit focus/scroll fallbacks.
 */
final class R08StyleNavigator {
    private static final int MIN_NODE_SIZE = 6;
    private static final int MAX_TRAVERSED_NODES = 90;
    private static final long TREE_BUDGET_MS = 55L;
    private static final int ACTION_SET_PROGRESS =
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId();

    private final BleNavigationService service;
    private final GestureDispatcher gestures;
    private final LauncherNavigator launcher;

    R08StyleNavigator(BleNavigationService service) {
        this.service = service;
        this.gestures = new GestureDispatcher(service);
        this.launcher = new LauncherNavigator(service, gestures);
    }

    void forward() {
        if (launcher.isActive()) {
            launcher.move(true, 1);
            return;
        }
        if (adjustFocusedRange(true)) return;
        if (moveFocus(true)) return;
        if (tryScroll(true)) return;
        gestures.verticalSwipe(true);
    }

    void backward() {
        if (launcher.isActive()) {
            launcher.move(false, 1);
            return;
        }
        if (adjustFocusedRange(false)) return;
        if (moveFocus(false)) return;
        if (tryScroll(false)) return;
        gestures.verticalSwipe(false);
    }

    void activate() {
        if (launcher.isActive() && launcher.activateCenter()) return;

        AccessibilityNodeInfo current = findCurrentFocus();
        if (current == null) {
            List<AccessibilityNodeInfo> nodes = collectCandidates();
            if (!nodes.isEmpty()) {
                current = nodes.get(0);
                focusNode(current);
            }
        }
        AccessibilityNodeInfo clickable = findClickable(current);
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;

        Rect bounds = new Rect();
        if (current != null) current.getBoundsInScreen(bounds);
        if (!bounds.isEmpty()) gestures.tap(bounds.centerX(), bounds.centerY());
        else {
            DisplayMetrics m = service.getResources().getDisplayMetrics();
            gestures.tap(m.widthPixels / 2f, m.heightPixels / 2f);
        }
    }

    void back() {
        if (!service.performGlobalAction(BleNavigationService.GLOBAL_ACTION_BACK)) {
            gestures.horizontalSwipe(false);
        }
    }

    private boolean moveFocus(boolean forward) {
        List<AccessibilityNodeInfo> nodes = collectCandidates();
        if (nodes.isEmpty()) return false;
        AccessibilityNodeInfo current = findCurrentFocus();
        int index = indexOf(nodes, current);
        if (index < 0) return focusNode(forward ? nodes.get(0) : nodes.get(nodes.size() - 1));

        int next = index + (forward ? 1 : -1);
        if (next >= 0 && next < nodes.size()) return focusNode(nodes.get(next));
        if (tryScrollFrom(current, forward)) return true;
        return focusNode(forward ? nodes.get(0) : nodes.get(nodes.size() - 1));
    }

    private boolean focusNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        clearAccessibilityFocus();
        boolean a = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean b = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        return a || b;
    }

    private List<AccessibilityNodeInfo> collectCandidates() {
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        TraversalBudget budget = new TraversalBudget();
        collectCandidates(AccessibilityWindowRoots.getNavigationRoot(service), nodes, budget);
        Collections.sort(nodes, new NodeComparator());
        return nodes;
    }

    private void collectCandidates(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, TraversalBudget budget) {
        if (node == null || budget.exhausted()) return;
        budget.visit();
        if (isCandidate(node)) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectCandidates(node.getChild(i), out, budget);
        }
    }

    private boolean isCandidate(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser() || !node.isEnabled()) return false;
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() < MIN_NODE_SIZE || rect.height() < MIN_NODE_SIZE) return false;

        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        boolean huge = rect.width() > metrics.widthPixels * 0.95f
                && rect.height() > metrics.heightPixels * 0.85f;
        if (huge && !node.isClickable() && !supports(node, AccessibilityNodeInfo.ACTION_CLICK)) return false;
        if (node.isClickable() || supports(node, AccessibilityNodeInfo.ACTION_CLICK)) return true;
        if (node.isScrollable() || isContainerClass(node)) return false;
        if (hasActionableDescendant(node, new TraversalBudget())) return false;
        return node.isFocusable() || supports(node, AccessibilityNodeInfo.ACTION_FOCUS);
    }

    private AccessibilityNodeInfo findCurrentFocus() {
        AccessibilityNodeInfo root = AccessibilityWindowRoots.getNavigationRoot(service);
        if (root == null) return null;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        return focused;
    }

    private void clearAccessibilityFocus() {
        AccessibilityNodeInfo root = AccessibilityWindowRoots.getNavigationRoot(service);
        if (root == null) return;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (focused != null) focused.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
    }

    private int indexOf(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo current) {
        if (current == null) return -1;
        Rect cr = new Rect();
        current.getBoundsInScreen(cr);
        for (int i = 0; i < nodes.size(); i++) {
            Rect r = new Rect();
            nodes.get(i).getBoundsInScreen(r);
            if (r.equals(cr)
                    && textEquals(current.getText(), nodes.get(i).getText())
                    && textEquals(current.getContentDescription(), nodes.get(i).getContentDescription())) {
                return i;
            }
        }
        return -1;
    }

    private boolean tryScroll(boolean forward) {
        AccessibilityNodeInfo current = findCurrentFocus();
        return tryScrollFrom(current, forward)
                || tryScrollTree(AccessibilityWindowRoots.getNavigationRoot(service), forward, new TraversalBudget());
    }

    private boolean tryScrollFrom(AccessibilityNodeInfo node, boolean forward) {
        AccessibilityNodeInfo cursor = node;
        while (cursor != null) {
            if (performScroll(cursor, forward)) return true;
            cursor = cursor.getParent();
        }
        return false;
    }

    private boolean tryScrollTree(AccessibilityNodeInfo node, boolean forward, TraversalBudget budget) {
        if (node == null || budget.exhausted()) return false;
        budget.visit();
        if (performScroll(node, forward)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (tryScrollTree(node.getChild(i), forward, budget)) return true;
        }
        return false;
    }

    private boolean performScroll(AccessibilityNodeInfo node, boolean forward) {
        if (node == null) return false;
        int action = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        return (node.isScrollable() || supports(node, action)) && node.performAction(action);
    }

    private boolean adjustFocusedRange(boolean forward) {
        AccessibilityNodeInfo node = findAdjustable(findCurrentFocus());
        if (node == null) return false;
        if (performScroll(node, forward)) return true;

        AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
        if (range == null || !supports(node, ACTION_SET_PROGRESS)) return false;
        float min = range.getMin(), max = range.getMax(), current = range.getCurrent();
        if (max <= min) return false;
        float span = max - min;
        float step = range.getType() == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT
                ? Math.max(1f, Math.round(span / 16f))
                : Math.max(span / 20f, 0.01f);
        float target = Math.max(min, Math.min(max, current + (forward ? step : -step)));
        if (target == current) return false;
        Bundle args = new Bundle();
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target);
        return node.performAction(ACTION_SET_PROGRESS, args);
    }

    private AccessibilityNodeInfo findAdjustable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = node;
        while (cursor != null) {
            if (cursor.isVisibleToUser() && cursor.isEnabled() && cursor.getRangeInfo() != null) return cursor;
            cursor = cursor.getParent();
        }
        return null;
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = node;
        while (cursor != null) {
            if ((cursor.isClickable() || supports(cursor, AccessibilityNodeInfo.ACTION_CLICK))
                    && cursor.isEnabled()) return cursor;
            cursor = cursor.getParent();
        }
        return null;
    }

    private boolean supports(AccessibilityNodeInfo node, int actionId) {
        if (node == null || node.getActionList() == null) return false;
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getId() == actionId) return true;
        }
        return false;
    }

    private boolean isContainerClass(AccessibilityNodeInfo node) {
        CharSequence cn = node.getClassName();
        if (cn == null) return false;
        String n = cn.toString();
        return n.contains("RecyclerView") || n.contains("GridView") || n.contains("ListView")
                || n.contains("ViewPager") || n.contains("ScrollView");
    }

    private boolean hasActionableDescendant(AccessibilityNodeInfo node, TraversalBudget budget) {
        if (node == null || budget.exhausted()) return false;
        budget.visit();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            if (child.isVisibleToUser() && child.isEnabled()
                    && (child.isClickable() || supports(child, AccessibilityNodeInfo.ACTION_CLICK))) return true;
            if (hasActionableDescendant(child, budget)) return true;
        }
        return false;
    }

    private boolean textEquals(CharSequence a, CharSequence b) {
        if (a == null && b == null) return true;
        return a != null && b != null && a.toString().contentEquals(b);
    }

    private static final class TraversalBudget {
        private final long deadline = SystemClock.uptimeMillis() + TREE_BUDGET_MS;
        private int visited;
        void visit() { visited++; }
        boolean exhausted() { return visited >= MAX_TRAVERSED_NODES || SystemClock.uptimeMillis() > deadline; }
    }

    private static final class NodeComparator implements Comparator<AccessibilityNodeInfo> {
        @Override public int compare(AccessibilityNodeInfo left, AccessibilityNodeInfo right) {
            Rect a = new Rect(), b = new Rect();
            left.getBoundsInScreen(a);
            right.getBoundsInScreen(b);
            int top = a.top - b.top;
            if (Math.abs(top) > 18) return top;
            int x = a.left - b.left;
            if (x != 0) return x;
            int h = a.height() - b.height();
            return h != 0 ? h : a.width() - b.width();
        }
    }
}
