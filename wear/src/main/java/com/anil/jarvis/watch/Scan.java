package com.anil.jarvis.watch;

import android.Manifest;
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
 * W25 "🩺 బాడీ స్కాన్": he sits still for 20 seconds while the watch reads his heart rate (a pulsing ring), then the
 * phone puts it together with his day (his normal, stress, last night's sleep, steps, coughs, oxygen, the last BP,
 * the energy estimate) and says it here. Not a medical test.
 */
public class Scan extends Activity {
    private static final long SECS = 20;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Pulse pulse = new Pulse();
    private Ring ring;
    private TextView title, bpm, hint;
    private long firstAt, openedAt;
    private boolean done, started;

    static void open(Context c) {
        c.startActivity(new Intent(c, Scan.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.refresh(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        ring = new Ring(this);
        root.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int side = Math.round(getResources().getDisplayMetrics().widthPixels * 0.16f);
        col.setPadding(side, 0, side, 0);
        title = WUi.text(this, "🩺 బాడీ స్కాన్", 14, WUi.CYAN, true);
        col.addView(title);
        bpm = WUi.text(this, "♥ --", 30, 0xFFFFFFFF, true);
        col.addView(bpm);
        hint = WUi.text(this, "కదలకుండా కూర్చోండి…", 12.5f, WUi.MUTED, true);
        col.addView(hint);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        root.setOnClickListener(v -> { if (done) finish(); });
        setContentView(root);
        openedAt = SystemClock.elapsedRealtime();
        if (!Pulse.allowed(this)) requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 7);
        else begin();
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (Pulse.allowed(this)) { openedAt = SystemClock.elapsedRealtime(); begin(); }
        else { // (without the heart rate: the rest of the day still)
            hint.setText("గుండె వేగం చూడడానికి అనుమతి లేదు. మిగతా వివరాలు ఫోన్ చెబుతుంది…");
            main.postDelayed(() -> send(0), 1500);
        }
    }

    private void begin() {
        if (started) return;
        started = true;
        if (!Pulse.has(this) || !pulse.start(this, main)) { hint.setText("ఈ వాచ్‌లో గుండె వేగం సెన్సార్ దొరకలేదు"); main.postDelayed(() -> send(0), 1500); return; }
        main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (done) return;
            long now = SystemClock.elapsedRealtime();
            int v = pulse.now;
            if (v > 0 && firstAt == 0) firstAt = now;
            ring.beat(v);
            bpm.setText(v > 0 ? "♥ " + v : "♥ --");
            if (firstAt == 0) {
                hint.setText(now - openedAt > 12_000 ? "వాచ్ మణికట్టుకి గట్టిగా ఉందా? చూస్తున్నాను…" : "కదలకుండా కూర్చోండి…");
                if (now - openedAt > 35_000) { send(pulse.bpm()); return; } // (no reading: the rest of the day still)
            } else {
                long left = SECS - (now - firstAt) / 1000;
                ring.progress = Math.min(1f, (now - firstAt) / (SECS * 1000f));
                hint.setText("కదలకుండా ఉండండి… " + Math.max(0, left));
                if (left <= 0) { send(pulse.bpm()); return; }
            }
            main.postDelayed(this, 250);
        }
    };

    /** To the phone; its answer is said and shown on the Jarvis screen. */
    private void send(int b) {
        if (done) return;
        done = true;
        pulse.stop();
        try {
            Talk.phoneDo(this, new JSONObject().put("what", "scan").put("bpm", b), "🩺 ఫోన్ మీ రోజు చూస్తోంది…");
        } catch (Exception ignored) {}
        startActivity(new Intent(this, WatchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override protected void onDestroy() {
        pulse.stop();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** A ring that fills over the 20 seconds and beats with the heart. */
    static final class Ring extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float progress;
        private long beatAt;
        private int bpm;

        Ring(Context c) { super(c); }

        void beat(int b) { bpm = b; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) * 0.44f;
            long now = SystemClock.elapsedRealtime();
            if (bpm > 0 && now - beatAt > 60_000L / bpm) beatAt = now;
            float pulseK = bpm > 0 ? Math.max(0f, 1f - (now - beatAt) / 300f) : 0f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.05f);
            p.setColor(0x3374E4FF);
            cv.drawCircle(w / 2, h / 2, r, p);
            p.setColor(Theme.accent);
            cv.drawArc(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r, -90, 360 * progress, false, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor((Math.round(40 + 60 * pulseK) << 24) | 0x00FF4D6D);
            cv.drawCircle(w / 2, h / 2, r * (0.55f + 0.06f * pulseK), p);
            if (bpm > 0) postInvalidateDelayed(40);
        }
    }
}
