package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lets Jarvis see the screen Anil was looking at: the text on it and a screenshot.
 * A capture is taken the moment the wake word is heard, before Jarvis opens on top.
 */
public class JarvisAccessibility extends AccessibilityService {

    static final class Capture {
        String jpeg;      // base64, may be null (Android 10 and older, or when the system refuses)
        String text = "";
        String pkg = "";
        long time;
    }

    private static volatile JarvisAccessibility instance;
    private static volatile Capture last;
    private static volatile String currentPkg = "";

    static boolean enabled() { return instance != null; }

    /** The app that was last in front (not Jarvis itself), or "" if unknown. */
    static String currentPackage() { return currentPkg; }

    /** Presses the Home button, like Anil would. */
    static boolean goHome() {
        JarvisAccessibility s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    // ------------------------------------------------------------------ force stop

    /** How the "Force stop" button reads on the phone, in the languages Anil's phone may use. */
    private static final String[] FORCE_STOP = {
            "force stop", "బలవంతంగా ఆపు", "బలవంతంగా ఆపివేయి", "ఫోర్స్ స్టాప్",
            "बलपूर्वक रोकें", "ज़बरदस्ती रोकें", "फ़ोर्स स्टॉप", "फोर्स स्टॉप"};
    /** The "OK" button of the "Force stop?" question. */
    private static final String[] CONFIRM = {"ok", "force stop", "సరే", "బలవంతంగా ఆపు", "ठीक है", "हां"};

    /**
     * Closes an app completely, the way Anil would by hand: opens its "App info" page,
     * presses "Force stop", answers OK, and goes back. Call from a background thread.
     * Returns "stopped", "already_stopped", "no_accessibility", "no_button" or "no_confirm".
     */
    static String forceStop(String pkg) {
        JarvisAccessibility s = instance;
        if (s == null) return "no_accessibility";
        android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + pkg))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | android.content.Intent.FLAG_ACTIVITY_NO_HISTORY | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try { s.startActivity(i); } catch (Exception e) { return "no_button"; }

        // 1) wait for the App info page and find its "Force stop" button
        AccessibilityNodeInfo button = null;
        long end = SystemClock.uptimeMillis() + 7000;
        while (button == null && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(300);
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null || s.getPackageName().contentEquals(String.valueOf(root.getPackageName()))) continue;
            button = find(root, FORCE_STOP, false);
        }
        if (button == null) { s.performGlobalAction(GLOBAL_ACTION_BACK); return "no_button"; }
        AccessibilityNodeInfo target = clickable(button);
        if (!button.isEnabled() || (target != null && !target.isEnabled())) {
            s.performGlobalAction(GLOBAL_ACTION_BACK); // greyed out: the app is not running any more
            return "already_stopped";
        }
        if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            s.performGlobalAction(GLOBAL_ACTION_BACK);
            return "no_button";
        }

        // 2) answer the "Force stop?" question with OK
        boolean confirmed = false;
        end = SystemClock.uptimeMillis() + 5000;
        while (!confirmed && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(300);
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) continue;
            AccessibilityNodeInfo ok = null;
            List<AccessibilityNodeInfo> b1 = root.findAccessibilityNodeInfosByViewId("android:id/button1");
            if (b1 != null && !b1.isEmpty()) ok = b1.get(0);
            if (ok == null) {
                // Only a dialog has a "cancel" button next to it; don't press the page's own button again.
                List<AccessibilityNodeInfo> b2 = root.findAccessibilityNodeInfosByViewId("android:id/button2");
                if (b2 != null && !b2.isEmpty()) ok = find(root, CONFIRM, true);
            }
            if (ok == null) continue;
            AccessibilityNodeInfo t = clickable(ok);
            confirmed = t != null && t.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        SystemClock.sleep(700);
        s.performGlobalAction(GLOBAL_ACTION_BACK); // leave the App info page
        return confirmed ? "stopped" : "no_confirm";
    }

    /** A short label (a button, not a sentence) matching one of the words. */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String[] words, boolean exact) {
        for (String w : words) {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(w);
            if (list == null) continue;
            for (AccessibilityNodeInfo n : list) {
                CharSequence t = n.getText();
                if (t == null) t = n.getContentDescription();
                if (t == null) continue;
                String label = t.toString().trim().toLowerCase(Locale.ROOT);
                if (label.length() > 24) continue; // a sentence such as the warning text
                if (exact ? label.equals(w) : label.contains(w)) return n;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        int hops = 0;
        while (n != null && !n.isClickable() && hops++ < 5) n = n.getParent();
        return n;
    }

    // ------------------------------------------------------------------ tap "Send"

    /** How the send button reads / is named in the messaging and mail apps. */
    private static final String[] SEND_DESC = {"send", "పంపు", "పంపించు", "मैसेज भेजें", "भेजें", "पेजें", "send message", "send sms"};

    /**
     * Types are already filled in by the app (WhatsApp/Telegram share link, Gmail draft). This taps
     * that app's Send button once the compose screen is up. Call from a background thread.
     * Returns "sent", "no_accessibility", or "no_button".
     */
    static String clickSend(String pkg, long timeoutMs) {
        JarvisAccessibility s = instance;
        if (s == null) return "no_accessibility";
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(350);
            AccessibilityNodeInfo root = s.windowRoot(pkg);
            if (root == null) continue;
            AccessibilityNodeInfo btn = findSend(root, pkg);
            if (btn == null) continue;
            AccessibilityNodeInfo target = clickable(btn);
            if (target != null && target.isEnabled() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "sent";
        }
        return "no_button";
    }

    /** The visible window that belongs to pkg (its compose screen), or the active one if it matches. */
    /**
     * Makes sure the message is in the app's message box: if the app did not fill it in from the
     * link, types it there. Returns "typed", "already", "no_box" or "no_accessibility".
     */
    static String typeInto(String pkg, String text, long timeoutMs) {
        JarvisAccessibility s = instance;
        if (s == null) return "no_accessibility";
        String want = text == null ? "" : text.trim();
        String head = want.substring(0, Math.min(12, want.length()));
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(400);
            AccessibilityNodeInfo root = s.windowRoot(pkg);
            if (root == null) continue;
            AccessibilityNodeInfo box = messageBox(root);
            if (box == null) continue;
            CharSequence cur = box.getText();
            boolean hint = Build.VERSION.SDK_INT >= 26 && box.isShowingHintText();
            if (!hint && cur != null && cur.toString().contains(head)) return "already";
            SystemClock.sleep(300); // give the app a moment to fill it in itself
            cur = box.getText();
            hint = Build.VERSION.SDK_INT >= 26 && box.isShowingHintText();
            if (!hint && cur != null && cur.toString().contains(head)) return "already";
            android.os.Bundle b = new android.os.Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, want);
            box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT)) return "typed";
        }
        return "no_box";
    }

    /** The message box: the focused text field, otherwise the lowest one on the screen. */
    private static AccessibilityNodeInfo messageBox(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> boxes = new java.util.ArrayList<>();
        collectEditable(root, boxes, 0);
        for (AccessibilityNodeInfo n : boxes) if (n.isFocused()) return n;
        AccessibilityNodeInfo best = null;
        int bestY = -1;
        android.graphics.Rect r = new android.graphics.Rect();
        for (AccessibilityNodeInfo n : boxes) {
            n.getBoundsInScreen(r);
            if (r.bottom > bestY) { bestY = r.bottom; best = n; }
        }
        return best;
    }

    private static void collectEditable(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 50) return;
        if (n.isEditable() && n.isVisibleToUser()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectEditable(n.getChild(i), out, depth + 1);
    }

    private AccessibilityNodeInfo windowRoot(String pkg) {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && pkg.contentEquals(String.valueOf(r.getPackageName()))) return r;
            }
        } catch (Exception ignored) {}
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && pkg.contentEquals(String.valueOf(active.getPackageName()))) return active;
        return null;
    }

    /** The Send button: first by a resource id ending in /send, then by its "Send" label. */
    private static AccessibilityNodeInfo findSend(AccessibilityNodeInfo root, String pkg) {
        AccessibilityNodeInfo byId = findBySendId(root);
        if (byId != null) return byId;
        // by label, but never an editable text box (some apps label the box "Message")
        for (String w : SEND_DESC) {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(w);
            if (list == null) continue;
            for (AccessibilityNodeInfo n : list) {
                if (n.isEditable()) continue;
                CharSequence d = n.getContentDescription();
                if (d == null) d = n.getText();
                if (d == null) continue;
                String label = d.toString().trim().toLowerCase(Locale.ROOT);
                if (label.length() > 16) continue; // a sentence, not a button
                if (label.equals(w) || label.startsWith(w)) return n;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findBySendId(AccessibilityNodeInfo n) {
        if (n == null) return null;
        String id = n.getViewIdResourceName();
        if (id != null && n.isVisibleToUser() && !n.isEditable()) {
            String last = id.substring(id.indexOf('/') + 1);
            // WhatsApp "send", Gmail "send", Google Messages "send_message_button_icon", Samsung "send_button1"...
            if (last.equals("send") || last.equals("fab_send") || last.startsWith("send_button")
                    || last.startsWith("send_message_button") || last.equals("send_icon")) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findBySendId(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    @Override protected void onServiceConnected() { instance = this; }

    @Override public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e != null && e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            String p = e.getPackageName().toString();
            if (!p.equals(getPackageName())) currentPkg = p;
        }
    }

    @Override public void onInterrupt() {}

    /** The last capture if it is newer than maxAgeMs. */
    static Capture recent(long maxAgeMs) {
        Capture c = last;
        return c != null && SystemClock.elapsedRealtime() - c.time < maxAgeMs ? c : null;
    }

    /** Captures now and runs {@code then} on the main thread when done (or right away if unavailable). */
    static void capture(Runnable then) {
        JarvisAccessibility s = instance;
        if (s == null) { then.run(); return; }
        Capture c = new Capture();
        c.time = SystemClock.elapsedRealtime();
        c.pkg = currentPkg;
        try { c.text = s.screenText(); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT < 30) { last = c; then.run(); return; }
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    try {
                        HardwareBuffer hb = r.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        if (hw != null) {
                            c.jpeg = encode(hw);
                            hw.recycle();
                        }
                        hb.close();
                    } catch (Exception ignored) {}
                    last = c;
                    then.run();
                }
                @Override public void onFailure(int errorCode) {
                    last = c;
                    then.run();
                }
            });
        } catch (Exception e) {
            last = c;
            then.run();
        }
    }

    /** Capture from a background thread, waiting up to timeoutMs. */
    static Capture captureBlocking(long timeoutMs) {
        CountDownLatch done = new CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> capture(done::countDown));
        try { done.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return recent(timeoutMs + 2000);
    }

    private static String encode(Bitmap hw) {
        int w = hw.getWidth(), h = hw.getHeight();
        float scale = Math.min(1f, 1100f / Math.max(w, h));
        int sw = Math.max(1, Math.round(w * scale)), sh = Math.max(1, Math.round(h * scale));
        Bitmap soft = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(soft);
        cv.scale(scale, scale);
        Bitmap copy = hw.copy(Bitmap.Config.ARGB_8888, false);
        cv.drawBitmap(copy, 0, 0, null);
        copy.recycle();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        soft.compress(Bitmap.CompressFormat.JPEG, 80, out);
        soft.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private String screenText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        walk(root, sb, 0);
        return sb.toString().trim();
    }

    private static void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || sb.length() > 4000 || depth > 40) return;
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if (t != null && t.length() > 0) sb.append(t).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null) walk(ch, sb, depth + 1);
        }
    }
}
