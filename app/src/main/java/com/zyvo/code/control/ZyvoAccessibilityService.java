package com.zyvo.code.control;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The zyvo phone-control service: lets the AI see and drive OTHER apps'
 * screens with no root and no adb. Ported from Nova's open pattern:
 * readScreen() = plain-text dump for the model; snapshot() = structured
 * element list ([n] kind "label" y% x%) the model can act on by index or by
 * percent coordinates; tap/swipe via dispatchGesture; text via ACTION_SET_TEXT
 * + IME enter; global back/home/recents/notifications/lock. The engine calls
 * it through the loopback bridge: `zyvo-a11y <cmd>` (AdbBridgeServer routes
 * "a11y ..." lines here), so the model needs nothing but shell access.
 */
public class ZyvoAccessibilityService extends AccessibilityService {

    private static volatile ZyvoAccessibilityService instance;

    /** Elements of the LAST snapshot() - tapindex acts on these. */
    private final List<AccessibilityNodeInfo> nodes = new ArrayList<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------ bridge api

    /** Entry point from AdbBridgeServer ("a11y ..." lines). Always returns a
     *  plain-text answer the model can read. */
    public static String runCommand(String args) {
        ZyvoAccessibilityService s = instance;
        if (s == null) {
            return "ACCESSIBILITY OFF - enable 'Zyvo Phone Control' for Zyvo Code in"
                    + " Settings > Accessibility, then retry";
        }
        try {
            return s.dispatch(args == null ? "" : args.trim());
        } catch (Throwable t) {
            return "a11y error: " + (t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    private String dispatch(String args) {
        String[] p = args.split("\\s+", 2);
        String cmd = p[0].toLowerCase(Locale.US);
        String rest = p.length > 1 ? p[1] : "";
        switch (cmd) {
            case "read":
                return readScreen();
            case "elements":
                return snapshot();
            case "tap": {
                String[] c = rest.split("\\s+");
                if (c.length < 2) return "usage: tap <x%> <y%>";
                try {
                    return tapPct(Integer.parseInt(c[0]), Integer.parseInt(c[1]))
                            ? "OK" : "tap failed";
                } catch (NumberFormatException e) {
                    return "usage: tap <x%> <y%>";
                }
            }
            case "tapindex": {
                try {
                    return clickNode(Integer.parseInt(rest.trim()));
                } catch (NumberFormatException e) {
                    return "usage: tapindex <n>";
                }
            }
            case "swipe": {
                String[] c = rest.split("\\s+");
                if (c.length < 4) return "usage: swipe <x1%> <y1%> <x2%> <y2%> [ms]";
                try {
                    long ms = c.length > 4 ? Long.parseLong(c[4]) : 400L;
                    int w = width(), h = height();
                    return stroke(w * pct(c[0]) / 100f, h * pct(c[1]) / 100f,
                            w * pct(c[2]) / 100f, h * pct(c[3]) / 100f, ms)
                            ? "OK" : "swipe failed";
                } catch (NumberFormatException e) {
                    return "usage: swipe <x1%> <y1%> <x2%> <y2%> [ms]";
                }
            }
            case "scroll": {
                boolean up = rest.toLowerCase(Locale.US).contains("up");
                int w = width(), h = height();
                return up
                        ? stroke(w / 2f, h * 0.30f, w / 2f, h * 0.70f, 300) ? "OK" : "scroll failed"
                        : stroke(w / 2f, h * 0.70f, w / 2f, h * 0.30f, 300) ? "OK" : "scroll failed";
            }
            case "text": {
                String t = rest.trim();
                if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\""))
                        || (t.startsWith("'") && t.endsWith("'")))) {
                    t = t.substring(1, t.length() - 1);
                }
                if (t.isEmpty()) return "usage: text \"something\"";
                return setText(t);
            }
            case "submit":
                return imeEnter();
            case "global":
                return global(rest.trim().toLowerCase(Locale.US));
            default:
                return "unknown a11y command: " + cmd
                        + " (read|elements|tap|tapindex|swipe|scroll|text|submit|global)";
        }
    }

    // --------------------------------------------------------- screen read

    /** Plain-text dump of the active window: what the model "hears" the
     *  screen say. Visible nodes only, passwords skipped, deduped, 4500 cap. */
    private String readScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "no window content";
        StringBuilder sb = new StringBuilder();
        HashSet<String> seen = new HashSet<>();
        walkText(root, sb, seen, 0);
        return sb.length() == 0 ? "(screen has no text)" : sb.toString();
    }

    private void walkText(AccessibilityNodeInfo n, StringBuilder sb, HashSet<String> seen, int depth) {
        if (n == null || depth > 40 || sb.length() > 4500 || !n.isVisibleToUser() || n.isPassword()) {
            return;
        }
        String line = n.getText() != null ? n.getText().toString() : "";
        if (line.trim().isEmpty() && n.getContentDescription() != null) {
            line = n.getContentDescription().toString();
        }
        line = line.replace('\n', ' ').trim();
        if (!line.isEmpty() && seen.add(line)) {
            sb.append(line, 0, Math.min(150, line.length())).append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            walkText(n.getChild(i), sb, seen, depth + 1);
        }
    }

    /** Structured element list the model acts on:
     *  [n] kind on/off yNN% xNN% "label" — labels from text, then
     *  contentDescription, then the view id name; passwords masked. */
    private String snapshot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "no window content";
        nodes.clear();
        StringBuilder sb = new StringBuilder();
        collect(root, sb, 0, width(), height());
        return sb.length() == 0 ? "(nothing tappable found)" : sb.toString();
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth, int w, int h) {
        if (n == null || depth > 30 || nodes.size() >= 70 || !n.isVisibleToUser()) {
            return;
        }
        String label = n.getText() != null ? n.getText().toString() : "";
        if (label.replace('\n', ' ').trim().isEmpty() && n.getContentDescription() != null) {
            label = n.getContentDescription().toString();
        }
        label = label.replace('\n', ' ').trim();
        String id = n.getViewIdResourceName();
        if (id != null && id.contains("/")) id = id.substring(id.lastIndexOf('/') + 1);
        if (n.isPassword()) label = "(password field)";
        else if (label.isEmpty()) label = id == null ? "" : id;

        String kind;
        if (n.isEditable()) kind = "edit";
        else if (n.isCheckable()) kind = "toggle";
        else if (n.isClickable()) kind = "button";
        else if (n.isScrollable()) kind = "list";
        else kind = "text";

        boolean useful = !label.isEmpty() || n.isEditable()
                || (n.isClickable() && id != null && !id.isEmpty());
        if (useful) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            int x = Math.max(0, Math.min(100, (r.centerX() * 100) / Math.max(1, w)));
            int y = Math.max(0, Math.min(100, (r.centerY() * 100) / Math.max(1, h)));
            String state = n.isCheckable() ? (n.isChecked() ? "on " : "off ") : "";
            nodes.add(n);
            sb.append('[').append(nodes.size() - 1).append("] ").append(kind)
                    .append(' ').append(state).append("y").append(y).append("% x")
                    .append(x).append("% \"").append(label, 0, Math.min(60, label.length()))
                    .append("\"\n");
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), sb, depth + 1, w, h);
        }
    }

    // --------------------------------------------------------------- actions

    private boolean tapPct(int xPct, int yPct) {
        int w = width(), h = height();
        return stroke(w * clampPct(xPct) / 100f, h * clampPct(yPct) / 100f,
                w * clampPct(xPct) / 100f, h * clampPct(yPct) / 100f, 60L);
    }

    /** Click element [i] from the last snapshot: node ACTION_CLICK first,
     *  then the clickable parent chain, then a coordinate tap at its center. */
    private String clickNode(int i) {
        if (i < 0 || i >= nodes.size()) return "no element [" + i + "] - run elements first";
        AccessibilityNodeInfo n = nodes.get(i);
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "OK";
        AccessibilityNodeInfo p = n.getParent();
        for (int up = 0; up < 3 && p != null; up++) {
            if (p.isClickable() && p.isEnabled() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "OK (parent)";
            }
            p = p.getParent();
        }
        int w = width(), h = height();
        return stroke(r.centerX(), r.centerY(), r.centerX(), r.centerY(), 60L)
                ? "OK (coords)" : "click failed";
    }

    /** Types into the focused editable node (else the first editable found)
     *  via ACTION_SET_TEXT. */
    private String setText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo target = null;
        if (root != null) {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) target = f;
        }
        if (target == null) {
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isEditable()) {
                    target = n;
                    break;
                }
            }
        }
        if (target == null && root != null) target = findEditable(root, 0);
        if (target == null) return "no edit field found - tap the field first (elements + tapindex)";
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                ? "OK" : "set text failed";
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findEditable(n.getChild(i), depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    /** Presses the keyboard's send/enter on the focused field (Android 11+). */
    private String imeEnter() {
        if (Build.VERSION.SDK_INT < 30) {
            return "submit needs Android 11+ - tap the app's own send button instead";
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo target = null;
        if (root != null) {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null) target = f;
        }
        if (target == null) return "no focused field";
        return target.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())
                ? "OK" : "ime enter failed";
    }

    private String global(String kind) {
        int action;
        switch (kind) {
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "recents": action = GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": action = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock":
                if (Build.VERSION.SDK_INT < 28) return "lock needs Android 9+";
                action = GLOBAL_ACTION_LOCK_SCREEN;
                break;
            default:
                return "usage: global back|home|recents|notifications|quick_settings|lock";
        }
        return performGlobalAction(action) ? "OK" : "global action failed";
    }

    // -------------------------------------------------------------- gestures

    private boolean stroke(float x1, float y1, float x2, float y2, long ms) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(40, ms)));
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        dispatchGesture(gb.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                ok[0] = true;
                done.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                done.countDown();
            }
        }, null);
        try {
            done.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return ok[0];
    }

    // --------------------------------------------------------------- helpers

    private int width() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int height() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private static int clampPct(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static int pct(String v) {
        return clampPct(Integer.parseInt(v.trim()));
    }
}
