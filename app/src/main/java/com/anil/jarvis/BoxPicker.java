package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * A see-through sheet over the whole screen for one pick: drag a box around the part he means ("✂️ ఈ భాగం మాత్రమే",
 * "🔎 ఇది ఏంటి?"), or tap the paragraph to read from ("👆 ఇక్కడి నుంచి చదువు"). Gives back screen coordinates; null when
 * he cancels (✕, or the back of the sheet after 20 s untouched).
 */
final class BoxPicker extends View {
    interface Done { void picked(Rect r); }

    private final WindowManager wm;
    private final Done done;
    private final boolean point;
    private final String hint, wholeLabel;
    private final float d;
    private final Paint dim = new Paint(), box = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), btn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float x0, y0, x1, y1;
    private boolean dragging, finished;
    private final RectF cancelBtn = new RectF(), wholeBtn = new RectF();
    private final Runnable timeout = () -> finish(null);

    /** point: one tap; wholeLabel: a second button that picks the whole screen (null = none). */
    static void pick(AccessibilityService svc, boolean point, String hint, String wholeLabel, Done done) {
        WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        BoxPicker v = new BoxPicker(svc, wm, point, hint, wholeLabel, done);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (current != null) current.finish(null); // one at a time
        try {
            wm.addView(v, lp);
            current = v;
            v.postDelayed(v.timeout, 20_000);
            try {
                svc.registerReceiver(v.screenOff, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
                v.listening = true;
            } catch (Exception ignored) {}
        } catch (Exception e) {
            done.picked(null);
        }
    }

    private static BoxPicker current;

    /** Gone, as if he pressed ✕ (the floating button was switched off). Main thread. */
    static void cancel() { if (current != null) current.finish(null); }

    /** The screen went off: nothing is picked (the sheet must not wait over the lock screen). */
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, android.content.Intent i) { finish(null); }
    };
    private boolean listening;

    private BoxPicker(Context c, WindowManager wm, boolean point, String hint, String wholeLabel, Done done) {
        super(c);
        this.wm = wm;
        this.done = done;
        this.point = point;
        this.hint = hint;
        this.wholeLabel = wholeLabel;
        d = c.getResources().getDisplayMetrics().density;
        dim.setColor(0x66000000);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(3 * d);
        box.setColor(Ui.CYAN);
        fill.setColor(0x2200E5FF);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(16 * d);
        text.setTextAlign(Paint.Align.CENTER);
        btn.setColor(0xE6061424);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, dim);
        if (dragging || (x1 != x0 && y1 != y0)) {
            RectF r = new RectF(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
            c.drawRect(r, fill);
            c.drawRect(r, box);
        }
        // the hint at the top, the buttons at the bottom
        float top = 70 * d;
        c.drawRoundRect(new RectF(16 * d, top - 30 * d, w - 16 * d, top + 14 * d), 14 * d, 14 * d, btn);
        c.drawText(hint, w / 2f, top, text);
        float by = h - 90 * d;
        cancelBtn.set(w / 2f - (wholeLabel == null ? 60 : 150) * d, by, w / 2f + (wholeLabel == null ? 60 : -10) * d, by + 46 * d);
        c.drawRoundRect(cancelBtn, 16 * d, 16 * d, btn);
        c.drawText("✕ వద్దు", cancelBtn.centerX(), cancelBtn.centerY() + 6 * d, text);
        if (wholeLabel != null) {
            wholeBtn.set(w / 2f + 10 * d, by, w / 2f + 150 * d, by + 46 * d);
            c.drawRoundRect(wholeBtn, 16 * d, 16 * d, btn);
            c.drawText(wholeLabel, wholeBtn.centerX(), wholeBtn.centerY() + 6 * d, text);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                removeCallbacks(timeout);
                if (cancelBtn.contains(x, y)) { finish(null); return true; }
                if (wholeLabel != null && wholeBtn.contains(x, y)) {
                    finish(new Rect(0, 0, getWidth(), getHeight()));
                    return true;
                }
                x0 = x1 = x;
                y0 = y1 = y;
                dragging = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                x1 = x;
                y1 = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                if (!dragging) return true;
                dragging = false;
                x1 = x;
                y1 = y;
                int[] at = new int[2];
                getLocationOnScreen(at);
                if (point) {
                    finish(new Rect(at[0] + (int) x, at[1] + (int) y, at[0] + (int) x + 1, at[1] + (int) y + 1));
                    return true;
                }
                Rect r = new Rect(at[0] + (int) Math.min(x0, x1), at[1] + (int) Math.min(y0, y1), at[0] + (int) Math.max(x0, x1), at[1] + (int) Math.max(y0, y1));
                if (r.width() < 40 * d || r.height() < 30 * d) { // a tap, not a box: try again
                    x1 = x0;
                    y1 = y0;
                    invalidate();
                    postDelayed(timeout, 20_000);
                    return true;
                }
                finish(r);
                return true;
            }
            default:
                return true;
        }
    }

    private void finish(Rect r) {
        if (finished) return;
        finished = true;
        if (current == this) current = null;
        removeCallbacks(timeout);
        if (listening) { listening = false; try { getContext().unregisterReceiver(screenOff); } catch (Exception ignored) {} }
        try { wm.removeView(this); } catch (Exception ignored) {}
        done.picked(r);
    }
}
