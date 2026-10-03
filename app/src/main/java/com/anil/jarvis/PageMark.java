package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Follows a page being read aloud on the app's own screen (Chrome, a news app...): the paragraph being read gets a
 * soft glowing frame, the page scrolls to keep it in view, and when the screen's text runs out it scrolls on and
 * reads what comes next. The frame never takes a touch; it goes away when the reading pauses or ends.
 */
final class PageMark implements ScreenReader.Follow {
    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService bg = Executors.newSingleThreadExecutor(); // one for all readings (the screen's node calls)
    private final float d;
    private final List<AccessibilityNodeInfo> nodes;   // line i of the reading -> its place on the screen
    private JarvisAccessibility.Page page;
    private final Set<String> read = new HashSet<>();
    private View mark;
    private WindowManager.LayoutParams lp;
    private volatile AccessibilityNodeInfo now;
    private volatile boolean on;

    PageMark(AccessibilityService svc, JarvisAccessibility.Page page) {
        this.svc = svc;
        this.page = page;
        this.nodes = new java.util.ArrayList<>(page.nodes);
        wm = (WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE);
        d = svc.getResources().getDisplayMetrics().density;
        for (String l : page.text.split("\n")) read.add(l.trim());
    }

    @Override public void onPart(int line, int start, int end) {
        if (line < 0 || line >= nodes.size()) { hide(); return; }
        final AccessibilityNodeInfo n = nodes.get(line);
        now = n;
        on = true;
        bg.execute(() -> {
            try {
                if (now != n) return;
                n.refresh();
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                int h = svc.getResources().getDisplayMetrics().heightPixels;
                if (!n.isVisibleToUser() || r.top < h * 0.12f || r.bottom > h * 0.82f) { // bring it into view
                    n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                    Thread.sleep(350);
                    if (now != n) return;
                    n.refresh();
                    n.getBoundsInScreen(r);
                }
                final Rect at = new Rect(r);
                final boolean seen = n.isVisibleToUser();
                main.post(() -> { if (now == n && on) { if (seen) show(at); else hide(); } });
            } catch (Exception ignored) {}
        });
        main.removeCallbacks(track);
        main.postDelayed(track, 600);
    }

    /** While a paragraph is read, the frame follows it if he scrolls by hand. */
    private final Runnable track = new Runnable() {
        @Override public void run() {
            final AccessibilityNodeInfo n = now;
            if (!on || n == null) return;
            bg.execute(() -> {
                try {
                    n.refresh();
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    final boolean seen = n.isVisibleToUser();
                    main.post(() -> { if (now == n && on) { if (seen) show(r); else hide(); } });
                } catch (Exception ignored) {}
            });
            main.postDelayed(this, 600);
        }
    };

    @Override public void onQuiet() {
        on = false;
        now = null;
        main.removeCallbacks(track);
        hide();
    }

    @Override public String more() {
        JarvisAccessibility.Page next = JarvisAccessibility.morePage(page, read);
        if (next == null) return null;
        page = next;
        main.post(() -> nodes.addAll(next.nodes)); // before the reader adds the text (its post comes after this one)
        for (String l : next.text.split("\n")) read.add(l.trim());
        return next.text;
    }

    private void show(Rect r) {
        int sw = svc.getResources().getDisplayMetrics().widthPixels, sh = svc.getResources().getDisplayMetrics().heightPixels;
        Rect c = new Rect(Math.max(0, r.left), Math.max(0, r.top), Math.min(sw, r.right), Math.min(sh, r.bottom));
        if (c.width() < 8 || c.height() < 8) { hide(); return; }
        int pad = Math.round(4 * d);
        try {
            if (mark == null) {
                mark = new View(svc);
                GradientDrawable g = new GradientDrawable();
                g.setColor(FaceRig.withAlpha(Ui.CYAN, 0x26));
                g.setStroke(Math.max(1, Math.round(2 * d)), FaceRig.withAlpha(Ui.CYAN, 0xCC));
                g.setCornerRadius(6 * d);
                mark.setBackground(g);
                lp = new WindowManager.LayoutParams(c.width() + 2 * pad, c.height() + 2 * pad, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
                lp.x = c.left - pad;
                lp.y = c.top - pad;
                wm.addView(mark, lp);
            } else {
                lp.x = c.left - pad;
                lp.y = c.top - pad;
                lp.width = c.width() + 2 * pad;
                lp.height = c.height() + 2 * pad;
                wm.updateViewLayout(mark, lp);
            }
        } catch (Exception e) {
            mark = null;
        }
    }

    private void hide() {
        if (mark == null) return;
        try { wm.removeView(mark); } catch (Exception ignored) {}
        mark = null;
    }
}
