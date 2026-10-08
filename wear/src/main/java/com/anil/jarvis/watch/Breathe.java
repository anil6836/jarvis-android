package com.anil.jarvis.watch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * W30 "🌬️ శ్వాస": two minutes of slow breathing with the watch: 4 seconds in while the circle grows (one tap on the
 * wrist), 6 seconds out while it shrinks (two taps), 12 times. Eyes can stay closed: the taps say when. The heart rate
 * at the start and the end is shown when the watch can read it. Tap to stop.
 */
public class Breathe extends Activity {
    private static final long IN = 4000, OUT = 6000, CYCLE = IN + OUT;
    private static final int ROUNDS = 12;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Pulse pulse = new Pulse();
    private Circle circle;
    private TextView say, count;
    private long startAt, startWall;
    private int lastPhase = -1;
    private boolean finished;

    static void open(Context c) {
        c.startActivity(new Intent(c, Breathe.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.refresh(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        circle = new Circle(this);
        root.addView(circle, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int side = Math.round(getResources().getDisplayMetrics().widthPixels * 0.16f);
        col.setPadding(side, 0, side, 0);
        say = WUi.text(this, "సిద్ధమా…", 19, 0xFFFFFFFF, true);
        col.addView(say);
        count = WUi.text(this, "", 12.5f, WUi.MUTED, true);
        col.addView(count);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        root.setOnClickListener(v -> { if (finished) finish(); else end(true); });
        setContentView(root);
        pulse.start(this, main); // (only if allowed; the breathing works without it)
        startAt = SystemClock.elapsedRealtime() + 1500;
        startWall = System.currentTimeMillis() + 1500;
        main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (finished) return;
            long t = SystemClock.elapsedRealtime() - startAt;
            if (t < 0) { main.postDelayed(this, 50); return; }
            int round = (int) (t / CYCLE);
            if (round >= ROUNDS) { end(false); return; }
            long in = t % CYCLE;
            boolean inhale = in < IN;
            int phase = round * 2 + (inhale ? 0 : 1);
            if (phase != lastPhase) {
                lastPhase = phase;
                if (inhale) Talk.buzz(Breathe.this, 45);
                else Talk.buzz(Breathe.this, 30, 90, 30);
            }
            float k = inhale ? in / (float) IN : 1f - (in - IN) / (float) OUT;
            circle.size = (float) (0.5 - 0.5 * Math.cos(Math.PI * k)); // (smooth, like a breath)
            circle.invalidate();
            long left = inhale ? (IN - in + 999) / 1000 : (CYCLE - in + 999) / 1000;
            say.setText((inhale ? "శ్వాస పీల్చండి" : "నెమ్మదిగా వదలండి") + "\n" + left);
            count.setText((round + 1) + " / " + ROUNDS + (pulse.now > 0 ? "   ♥ " + pulse.now : ""));
            main.postDelayed(this, 50);
        }
    };

    /** Done (or stopped): the heart rate before and after, and a line to the phone for his week. */
    private void end(boolean stopped) {
        if (finished) return;
        finished = true;
        long secs = Math.max(0, (SystemClock.elapsedRealtime() - startAt) / 1000);
        long nowWall = System.currentTimeMillis();
        // (the start and the end: 20 seconds each, only when it ran long enough for them to differ)
        int before = secs >= 60 ? pulse.bpmBetween(startWall, startWall + 20_000L) : 0, after = secs >= 60 ? pulse.bpmBetween(nowWall - 20_000L, nowWall) : 0;
        pulse.stop();
        circle.size = 0.35f;
        circle.invalidate();
        String hr = before > 0 && after > 0 ? "\n♥ " + before + " → " + after : "";
        say.setText((stopped && secs < 60 ? "సరే, ఆపాను." : "బాగుంది! " + Math.max(1, Math.round(secs / 60f)) + " నిమిషాలు శ్వాస వ్యాయామం.") + hr);
        count.setText("నొక్కితే మూసేస్తాను");
        Talk.buzz(this, 60, 80, 60, 80, 60);
        if (secs >= 30) {
            try {
                Link.send(this, Link.P_DO, new JSONObject().put("what", "breathed").put("secs", secs).put("before", before).put("after", after));
            } catch (Exception ignored) {}
        }
        main.postDelayed(this::finish, 20_000);
    }

    @Override protected void onDestroy() {
        pulse.stop();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** The circle that grows with the breath in and shrinks with the breath out. */
    static final class Circle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float size;

        Circle(Context c) { super(c); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), max = Math.min(w, h) * 0.46f;
            float r = max * (0.42f + 0.58f * size);
            p.setStyle(Paint.Style.FILL);
            p.setColor((Theme.accent & 0x00FFFFFF) | 0x22000000);
            cv.drawCircle(w / 2, h / 2, max, p);
            p.setColor((Theme.accent & 0x00FFFFFF) | (Math.round(70 + 90 * size) << 24));
            cv.drawCircle(w / 2, h / 2, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(max * 0.025f);
            p.setColor(Theme.accent);
            cv.drawCircle(w / 2, h / 2, r, p);
        }
    }
}
