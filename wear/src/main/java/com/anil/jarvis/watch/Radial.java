package com.anil.jarvis.watch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * W60: press and hold the orb: Jarvis's actions around the edge; turn the bezel to pick, tap it (or tap any one).
 * Tap the middle to close.
 */
final class Radial extends View {
    interface Pick { void pick(String what); }

    static final String[][] ITEMS = {{"🎙️", "talk"}, {"☀️", "morning"}, {"🏍️", "duty"}, {"⏱️", "timer"}, {"🧭", "compass"},
            {"🎵", "music"}, {"⚡", "protocols"}, {"💧", "water"}};
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Pick pick;
    int sel;

    Radial(Context c, Pick pick) {
        super(c);
        this.pick = pick;
        t.setTextAlign(Paint.Align.CENTER);
        setBackgroundColor(0xE6000000);
    }

    void turn(int dir) {
        sel = (sel + dir + ITEMS.length) % ITEMS.length;
        invalidate();
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    @Override protected void onDraw(Canvas cv) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * 0.72f, item = Math.min(cx, cy) * 0.2f;
        t.setTextSize(item * 0.9f);
        for (int i = 0; i < ITEMS.length; i++) {
            double a = Math.toRadians(i * 360.0 / ITEMS.length - 90);
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            p.setColor(i == sel ? Theme.accent : 0xFF0B2230);
            cv.drawCircle(x, y, item, p);
            cv.drawText(ITEMS[i][0], x, y + item * 0.32f, t);
        }
        p.setColor(0xFF1F2937);
        cv.drawCircle(cx, cy, item * 0.9f, p);
        t.setTextSize(item * 0.55f);
        t.setColor(0xFFDCEEF5);
        cv.drawText("✕", cx, cy + item * 0.2f, t);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, dx = e.getX() - cx, dy = e.getY() - cy;
        if (Math.hypot(dx, dy) < Math.min(cx, cy) * 0.3f) { pick.pick(null); return true; }
        double a = (Math.toDegrees(Math.atan2(dy, dx)) + 90 + 360) % 360;
        int i = (int) Math.round(a / (360.0 / ITEMS.length)) % ITEMS.length;
        if (i == sel) pick.pick(ITEMS[i][1]); else { sel = i; invalidate(); }
        return true;
    }
}
