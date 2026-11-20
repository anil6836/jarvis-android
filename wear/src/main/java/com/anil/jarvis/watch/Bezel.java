package com.anil.jarvis.watch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

/**
 * ⌚ (20 Nov) The workshop's showpiece power on the wrist: turning the bezel (or a finger round the edge) sets the machine's
 * power on the phone's stage, 5% a notch. Only a number goes to the phone; nothing is saved.
 */
public class Bezel extends Activity {
    static final String EXTRA_ID = "id";
    private int pct;
    private float notch;
    private long lastSent;
    private Dial dial;

    static void open(Context c, String id) { c.startActivity(new Intent(c, Bezel.class).putExtra(EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }

    /** The power after n notches (5% each, 0..100). Pure: tested on a desk. */
    static int step(int pct, int notches) { return Math.max(0, Math.min(100, pct + 5 * notches)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Screens.seen(this);
        Theme.refresh(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dial = new Dial(this);
        dial.setFocusable(true);
        dial.setFocusableInTouchMode(true);
        setContentView(dial);
        dial.requestFocus();
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            notch += -ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (Math.abs(notch) >= 1f) { set(step(pct, notch > 0 ? 1 : -1)); notch = 0; }
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    private void set(int p) {
        if (p == pct) return;
        pct = p;
        dial.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        dial.invalidate();
        long now = System.currentTimeMillis();
        if (now - lastSent < 120 && pct != 0 && pct != 100) { dial.removeCallbacks(sendR); dial.postDelayed(sendR, 130); return; }
        sendR.run();
    }

    private final Runnable sendR = new Runnable() {
        @Override public void run() {
            lastSent = System.currentTimeMillis();
            try { Link.send(Bezel.this, Link.P_DO, new JSONObject().put("what", "sp_power").put("v", pct / 100.0)); } catch (Exception ignored) {}
        }
    };

    /** A ring that fills with the power; a finger dragged round the edge turns it too. */
    private final class Dial extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double lastAng = Double.NaN;

        Dial(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(10 * d);
            ring.setColor(0x22FFFFFF);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(10 * d);
            fill.setStrokeCap(Paint.Cap.ROUND);
            fill.setColor(Theme.accent);
            txt.setColor(0xFFF2F6FA);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(40 * d);
            small.setColor(0xFF8AA0B4);
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(13 * d);
            setBackgroundColor(0xFF000000);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f - ring.getStrokeWidth() * 1.4f;
            RectF o = new RectF(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r);
            c.drawArc(o, 135, 270, false, ring);
            if (pct > 0) c.drawArc(o, 135, 270 * pct / 100f, false, fill);
            c.drawText(pct + "%", w / 2, h / 2 + txt.getTextSize() * 0.35f, txt);
            c.drawText("⚡ పవర్ · బెజెల్ తిప్పండి", w / 2, h / 2 + txt.getTextSize() * 1.1f, small);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            double ang = Math.atan2(e.getY() - getHeight() / 2.0, e.getX() - getWidth() / 2.0);
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { lastAng = ang; return true; }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !Double.isNaN(lastAng)) {
                double d = ang - lastAng;
                if (d > Math.PI) d -= 2 * Math.PI;
                if (d < -Math.PI) d += 2 * Math.PI;
                if (Math.abs(d) >= 0.2) { set(step(pct, d > 0 ? 1 : -1)); lastAng = ang; }
                return true;
            }
            lastAng = Double.NaN;
            return true;
        }
    }
}
