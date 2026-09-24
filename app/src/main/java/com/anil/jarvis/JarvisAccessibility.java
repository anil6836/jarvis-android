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
