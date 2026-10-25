package com.anil.jarvis;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * 🫧 The box: a small round Jarvis over the other apps (Google Maps full screen, a music app) while the car screen is
 * away — the speed inside it, its ring red over the limit. A tap: the car screen comes back. Held: the car screen, and
 * Jarvis listens. Dragged: it stays where he leaves it. Only on the box, only while he keeps it on (⚙️), only with
 * "display over other apps"; never over Jarvis's own screens (it goes the moment one of them shows).
 */
final class CarBubble {
    private CarBubble() {}

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static int started; // (Jarvis's own screens on the display now)
    private static long allStoppedAt; // (when the last of them left the display)
    private static Dot dot;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static final Runnable sync = CarBubble::syncNow;
    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            if (dot == null) return;
            try {
                Prefs p = new Prefs(app);
                int a = p.carAccent() != 0 ? p.carAccent() : CarTheme.of(p.carTheme()).accent;
                dot.set(CarBox.speed(), DriveService.limitNowKmh, a);
                if (CallControl.busyWithCall()) { syncNow(); return; } // (a call began: the bubble goes)
            } catch (Exception ignored) {}
            main.postDelayed(this, 1000);
        }
    };

    /** From the app's start: Jarvis's own screens are counted (the bubble only when none is on the display). */
    static void hook(Application a) {
        app = a.getApplicationContext();
        a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity x) { started++; main.removeCallbacks(sync); syncNow(); }
            @Override public void onActivityStopped(Activity x) {
                started = Math.max(0, started - 1);
                if (started == 0) allStoppedAt = System.currentTimeMillis();
                main.removeCallbacks(sync);
                main.postDelayed(sync, 800);
            }
            @Override public void onActivityCreated(Activity x, Bundle b) {}
            @Override public void onActivityResumed(Activity x) {}
            @Override public void onActivityPaused(Activity x) {}
            @Override public void onActivitySaveInstanceState(Activity x, Bundle b) {}
            @Override public void onActivityDestroyed(Activity x) {}
        });
    }

    /** A screen of Jarvis's own is on the display (the car screen beside Google Maps counts). */
    static boolean ownShown() { return started > 0; }

    /** When the last of Jarvis's screens left the display (0: never). */
    static long allStoppedAt() { return allStoppedAt; }

    /** A setting changed: shown / hidden as it should be now. */
    static void refresh() { main.removeCallbacks(sync); main.post(sync); }

    private static void syncNow() {
        boolean want = false, call = false;
        try {
            Prefs p = new Prefs(app);
            want = started == 0 && p.carMode() && p.carBubble() && android.provider.Settings.canDrawOverlays(app);
            call = CallControl.busyWithCall();
        } catch (Exception ignored) {}
        if (want && !call) show(); else hide();
        if (want && call) { main.removeCallbacks(sync); main.postDelayed(sync, 3000); } // (a call: no bubble — back after it ends)
    }

    private static void show() {
        if (dot != null || app == null) return;
        try {
            wm = app.getSystemService(WindowManager.class);
            int size = Ui.dp(app, 74);
            lp = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            android.content.SharedPreferences sp = app.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
            int h = app.getResources().getDisplayMetrics().heightPixels;
            int w = app.getResources().getDisplayMetrics().widthPixels;
            lp.x = clamp(sp.getInt("car_bubble_x", Ui.dp(app, 14)), w - size); // (the left side, a little below the middle: clear of Google Maps' turn card and its buttons)
            lp.y = clamp(sp.getInt("car_bubble_y", Math.round(h * 0.45f)), h - size);
            dot = new Dot(app);
            wm.addView(dot, lp);
            main.removeCallbacks(tick);
            main.post(tick);
        } catch (Exception e) {
            dot = null;
        }
    }

    private static int clamp(int v, int max) { return Math.max(0, Math.min(Math.max(0, max), v)); }

    private static void hide() {
        main.removeCallbacks(tick);
        if (dot == null) return;
        try { wm.removeView(dot); } catch (Exception ignored) {}
        dot = null;
    }

    /** The car screen back (and listening, when held). The bubble's own window lets Jarvis come to the front. */
    private static void back(boolean listen) {
        if (listen && CallControl.busyWithCall()) listen = false; // (never a listen during a call)
        try {
            Intent i = listen ? new Intent(Intent.ACTION_ASSIST).setClass(app, MainActivity.class)
                    : new Intent(app, MainActivity.class).putExtra(MainActivity.EXTRA_CAR_HOME, true);
            app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception ignored) {}
    }

    // ================================================================ the bubble itself

    static final class Dot extends View {
        private int speed = -1, limit, accent = 0xFF5FF3FF;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int slop;
        private float downX, downY;
        private int startX, startY;
        private boolean dragged, held;
        private final Runnable hold = () -> { held = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); back(true); };

        Dot(Context c) {
            super(c);
            t.setTextAlign(Paint.Align.CENTER);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        void set(int speed, int limit, int accent) {
            if (speed == this.speed && limit == this.limit && accent == this.accent) return;
            this.speed = speed; this.limit = limit; this.accent = accent;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) * 0.44f, cx = w / 2f, cy = h / 2f;
            boolean over = speed >= 0 && limit > 0 && speed > limit + 5;
            int ring = over ? 0xFFFF5A4E : accent;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx, cy + r * 0.1f, r * 1.12f, new int[]{0x99000000, 0x00000000}, new float[]{0.8f, 1}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy + r * 0.1f, r * 1.12f, p);
            p.setShader(new SweepGradient(cx, cy, new int[]{0xFFF4F7FA, 0xFF8C96A1, 0xFFE9EEF3, 0xFF4E5761, 0xFFF4F7FA}, null));
            cv.drawCircle(cx, cy, r, p);
            p.setShader(new RadialGradient(cx, cy - r * 0.3f, r, new int[]{0xFF26303B, 0xFF070A0E}, null, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, r * 0.84f, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.08f);
            p.setColor(CarTheme.withAlpha(ring, 0x44));
            cv.drawCircle(cx, cy, r * 0.76f, p);
            p.setStrokeWidth(Math.max(1, r * 0.035f));
            p.setColor(ring);
            cv.drawCircle(cx, cy, r * 0.76f, p);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            if (speed < 0) { // (no speed yet: Jarvis's letter)
                t.setColor(accent);
                t.setTextSize(r * 0.8f);
                cv.drawText("J", cx, cy + r * 0.28f, t);
                return;
            }
            t.setColor(over ? 0xFFFF8A80 : 0xFFF2F6FA);
            String n = String.valueOf(speed);
            t.setTextSize(r * (n.length() > 2 ? 0.62f : 0.74f));
            cv.drawText(n, cx, cy + r * 0.18f, t);
            t.setTypeface(Typeface.DEFAULT);
            t.setColor(0xFFB9C6D2);
            t.setTextSize(r * 0.2f);
            cv.drawText("కి.మీ/గం", cx, cy + r * 0.5f, t);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY(); startX = lp.x; startY = lp.y; dragged = false; held = false;
                    postDelayed(hold, 600);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragged && Math.hypot(dx, dy) > slop) { dragged = true; removeCallbacks(hold); }
                    if (dragged && !held) { // (kept on the screen: never dragged out of reach)
                        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                        lp.x = clamp(startX + Math.round(dx), dm.widthPixels - getWidth());
                        lp.y = clamp(startY + Math.round(dy), dm.heightPixels - getHeight());
                        try { wm.updateViewLayout(this, lp); } catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(hold);
                    if (dragged) app.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putInt("car_bubble_x", lp.x).putInt("car_bubble_y", lp.y).apply();
                    else if (!held) back(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(hold);
                    return true;
                default:
                    return true;
            }
        }
    }
}
