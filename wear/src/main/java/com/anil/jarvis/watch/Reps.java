package com.anil.jarvis.watch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * W71: counting an exercise from the wrist's movement (rough: squats, push-ups, curls, jumping jacks). A buzz every 5;
 * "✓ అయిపోయింది" writes it on the phone.
 */
public class Reps extends Activity implements SensorEventListener {
    /** name, the swing (g) that makes one, the shortest time between two (ms). */
    static final Object[][] KINDS = {{"స్క్వాట్స్", 0.18f, 1100}, {"పుష్-అప్స్", 0.15f, 1000}, {"బైసెప్ కర్ల్స్", 0.14f, 900}, {"జంపింగ్ జాక్స్", 0.55f, 450}};
    private final Counter counter = new Counter();
    private SensorManager sm;
    private int kind = -1;
    private TextView big, name;
    private LinearLayout col;

    static void open(Context c) { c.startActivity(new Intent(c, Reps.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }

    /** Counts swings of the smoothed size of the movement (pure: tested on a desk). */
    static final class Counter {
        float swing = 0.18f;
        long gap = 1000;
        private double slow = 1, fast = 1;
        private boolean up;
        private long last = -100_000;
        int n;

        /** g: the size of the acceleration (in g) at t (ms); true when this made one more. */
        boolean add(double g, long t) {
            slow += (g - slow) * 0.02;  // the resting level
            fast += (g - fast) * 0.3;   // the movement, smoothed
            double d = fast - slow;
            if (!up && d > swing) {
                up = true;
                if (t - last >= gap) { last = t; n++; return true; }
            } else if (up && d < -swing * 0.3) up = false;
            return false;
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Screens.seen(this);
        Theme.refresh(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sm = getSystemService(SensorManager.class);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF000000);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Math.round(w * 0.12f), Math.round(h * 0.14f), Math.round(w * 0.12f), Math.round(h * 0.22f));
        sc.addView(col);
        setContentView(sc);
        pick();
    }

    private void pick() {
        col.removeAllViews();
        col.addView(WUi.text(this, "🏋️ ఏ వ్యాయామం?", 15, Theme.accent, true));
        for (int i = 0; i < KINDS.length; i++) {
            int k = i;
            TextView p = WUi.pill(this, (String) KINDS[i][0], 0xFF0B2230);
            p.setOnClickListener(v -> start(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = WUi.dp(this, 5);
            col.addView(p, lp);
        }
        col.addView(WUi.text(this, "వాచ్ ఉన్న చేతి కదలికతో లెక్క (సుమారు).", 10.5f, WUi.FAINT, true));
    }

    private void start(int k) {
        kind = k;
        counter.swing = (float) KINDS[k][1];
        counter.gap = (int) KINDS[k][2];
        counter.n = 0;
        col.removeAllViews();
        name = WUi.text(this, (String) KINDS[k][0], 14, Theme.accent, true);
        col.addView(name);
        big = WUi.text(this, "0", 44, WUi.TEXT, true);
        col.addView(big);
        TextView done = WUi.pill(this, "✓ అయిపోయింది", 0xFF166534);
        done.setOnClickListener(v -> finishSet());
        col.addView(done, new LinearLayout.LayoutParams(-1, -2));
        listen(true);
    }

    /** Counting only while this screen is in front (left with the crown: no counting, no buzzing on a walk). */
    private void listen(boolean on) {
        if (sm == null) return;
        sm.unregisterListener(this);
        Sensor acc = on && kind >= 0 ? sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        if (acc != null) sm.registerListener(this, acc, 20_000);
    }

    @Override protected void onResume() {
        super.onResume();
        if (kind >= 0 && big != null) listen(true);
    }

    @Override protected void onPause() {
        listen(false);
        super.onPause();
    }

    private void finishSet() {
        if (sm != null) sm.unregisterListener(this);
        if (kind >= 0 && counter.n > 0) {
            try { Link.send(this, Link.P_DO, new JSONObject().put("what", "reps").put("name", KINDS[kind][0]).put("count", counter.n)); } catch (Exception ignored) {}
        }
        finish();
    }

    @Override public void onSensorChanged(SensorEvent e) {
        double g = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81;
        if (counter.add(g, e.timestamp / 1_000_000L)) {
            big.setText(String.valueOf(counter.n));
            Talk.buzz(this, counter.n % 5 == 0 ? 120 : 15);
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    @Override protected void onDestroy() {
        if (sm != null) sm.unregisterListener(this);
        super.onDestroy();
    }
}
