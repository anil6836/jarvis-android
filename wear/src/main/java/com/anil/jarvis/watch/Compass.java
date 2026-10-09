package com.anil.jarvis.watch;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * W52: an arrow to where the bike is parked, to home or to a saved place, with the distance; the way comes from the
 * phone's GPS (asked every 4 seconds while this is open) and the watch's own compass. Works without internet.
 * W51: "🗺️ దారి" opens the walking route in Google Maps on the watch (or on the phone when the watch has no Maps).
 * On the bike the dashboard's map stays: the watch only shows the arrow.
 */
public class Compass extends Activity implements SensorEventListener {
    static final String EXTRA_TARGET = "target";

    private static Compass shown;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SensorManager sm;
    private Dial dial;
    private TextView name, far, info;
    private JSONArray targets = new JSONArray();
    private int pick;
    private String want;
    private JSONObject here;
    private boolean riding, arrived;
    private float azimuth = Float.NaN;

    static void open(Context c, String target) {
        c.startActivity(new Intent(c, Compass.class).putExtra(EXTRA_TARGET, target == null ? "" : target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    /** The phone's answer (main thread): its places, or where he is. */
    static void got(Context app, JSONObject o) {
        Compass c = shown;
        if (c == null) return;
        if ("nav".equals(o.optString("kind"))) { c.targets = o.optJSONArray("targets") == null ? new JSONArray() : o.optJSONArray("targets"); c.riding = o.optBoolean("riding"); c.choose(); }
        else if ("here".equals(o.optString("kind"))) c.here = o.has("lat") ? o : null;
        c.update();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Screens.seen(this);
        Theme.refresh(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sm = getSystemService(SensorManager.class);
        int h = getResources().getDisplayMetrics().heightPixels;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        dial = new Dial(this);
        root.addView(dial, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(WUi.dp(this, 28), Math.round(h * 0.10f), WUi.dp(this, 28), 0);
        name = WUi.text(this, "", 14, Theme.accent, true);
        name.setOnClickListener(v -> { if (targets.length() > 1) { pick = (pick + 1) % targets.length(); arrived = false; update(); Talk.buzz(this, 15); } });
        col.addView(name);
        View gap = new View(this);
        col.addView(gap, new LinearLayout.LayoutParams(1, Math.round(h * 0.42f)));
        far = WUi.text(this, "", 19, WUi.TEXT, true);
        col.addView(far);
        info = WUi.text(this, "", 10.5f, WUi.MUTED, true);
        col.addView(info);
        TextView route = WUi.pill(this, "🗺️ దారి", 0xFF0E3A4A);
        route.setTextSize(12);
        route.setPadding(WUi.dp(this, 14), WUi.dp(this, 5), WUi.dp(this, 14), WUi.dp(this, 5));
        route.setOnClickListener(v -> route());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.gravity = Gravity.CENTER_HORIZONTAL;
        rp.topMargin = WUi.dp(this, 2);
        col.addView(route, rp);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        want = getIntent().getStringExtra(EXTRA_TARGET);
        try {
            JSONObject saved = new JSONObject(getSharedPreferences("jarvis_watch_panels", MODE_PRIVATE).getString("nav", "{}"));
            if (saved.has("targets")) { targets = saved.getJSONArray("targets"); riding = saved.optBoolean("riding"); choose(); }
        } catch (Exception ignored) {}
        update();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        want = i.getStringExtra(EXTRA_TARGET);
        arrived = false;
        choose();
        update();
    }

    /** The place he asked for (by key or name), else the bike, else home. */
    private void choose() {
        if (want == null || want.isEmpty()) return;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject t = targets.optJSONObject(i);
            if (t != null && (want.equals(t.optString("key")) || want.equals(t.optString("name")))) { pick = i; want = null; return; }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        shown = this;
        Sensor r = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (r == null && sm != null) r = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        if (r != null) sm.registerListener(this, r, SensorManager.SENSOR_DELAY_UI);
        ask("nav");
        main.post(poll);
    }

    @Override protected void onPause() {
        if (shown == this) shown = null;
        if (sm != null) sm.unregisterListener(this);
        main.removeCallbacks(poll);
        super.onPause();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            ask("here");
            update();
            main.postDelayed(this, 4000);
        }
    };

    private void ask(String kind) { try { Link.send(this, Link.P_ASK, new JSONObject().put("kind", kind)); } catch (Exception ignored) {} }

    private JSONObject target() { return targets.length() == 0 ? null : targets.optJSONObject(Math.min(pick, targets.length() - 1)); }

    private final float[] rot = new float[9], ori = new float[3];

    @Override public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        SensorManager.getOrientation(rot, ori);
        float a = (float) Math.toDegrees(ori[0]);
        if (Float.isNaN(azimuth)) azimuth = a;
        else { // smooth across the 360/0 seam
            float d = ((a - azimuth + 540) % 360) - 180;
            azimuth = (azimuth + d * 0.15f + 360) % 360;
        }
        dial.invalidate();
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) {}

    /** {metres, bearing} from where he is to the place; null when unknown. */
    private float[] way() {
        JSONObject t = target(), h = here;
        if (t == null || h == null) return null;
        float[] r = new float[2];
        Location.distanceBetween(h.optDouble("lat"), h.optDouble("lon"), t.optDouble("lat"), t.optDouble("lon"), r);
        return r;
    }

    private void update() {
        if (isDestroyed()) return;
        JSONObject t = target();
        if (t == null) {
            name.setText("🧭 దారి");
            far.setText(targets.length() == 0 && Link.ok ? "" : "");
            info.setText(Link.ok ? "బండి పెట్టిన చోటు, ఇల్లు ఫోన్‌లో సేవ్ అయ్యాక ఇక్కడ వస్తాయి" : "ఫోన్ అందడం లేదు (Bluetooth?)");
            dial.invalidate();
            return;
        }
        name.setText(t.optString("icon", "📍") + " " + t.optString("name") + (targets.length() > 1 ? "  ▸" : ""));
        float[] w = way();
        if (w == null) {
            far.setText("…");
            info.setText(Link.ok ? "ఫోన్ GPS కోసం చూస్తున్నాను…" : "ఫోన్ అందడం లేదు (Bluetooth?)");
        } else {
            far.setText(dist(w[0]));
            long age = (System.currentTimeMillis() - here.optLong("t")) / 1000;
            int acc = here.optInt("acc", -1);
            String a = (acc > 0 ? "GPS ±" + acc + " మీ" : "GPS") + (age > 30 ? " · " + (age < 120 ? age + " సె" : age / 60 + " ని") + " క్రితం" : "");
            if (riding) a = "బైక్ మీద: డ్యాష్‌బోర్డ్ మ్యాప్ చూడండి · " + a;
            info.setText(a);
            if (w[0] < 25 && !arrived) { arrived = true; far.setText("🎯 చేరుకున్నారు"); Talk.buzz(this, 60, 80, 60, 80, 200); }
        }
        dial.invalidate();
    }

    static String dist(float m) {
        if (m < 995) return Math.max(5, Math.round(m / 5f) * 5) + " మీ";
        return String.format(Locale.ENGLISH, m < 9950 ? "%.1f" : "%.0f", m / 1000f) + " కి.మీ";
    }

    /** W51: the walking route in Google Maps on the watch; no Maps here -> on the phone. On the bike: only the arrow. */
    private void route() {
        JSONObject t = target();
        if (t == null) return;
        if (riding) { info.setText("బైక్ మీద ఉన్నారు: బండి డ్యాష్‌బోర్డ్ (Mappls) చూడండి, వాచ్ బాణం మాత్రమే"); Talk.buzz(this, 25); return; }
        String ll = t.optDouble("lat") + "," + t.optDouble("lon");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + ll + "&mode=w")).setPackage("com.google.android.apps.maps")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (ActivityNotFoundException ignored) {
        } catch (Exception ignored) {}
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + ll + "?q=" + ll)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Exception ignored) {}
        try {
            Link.send(this, Link.P_DO, new JSONObject().put("what", "nav_phone").put("lat", t.optDouble("lat")).put("lon", t.optDouble("lon")).put("name", t.optString("name")));
            info.setText("వాచ్‌లో Maps లేదు: 📱 ఫోన్‌లో దారి తెరుస్తున్నాను");
        } catch (Exception ignored) {}
    }

    /** The dial: north on the ring, and the arrow to the place (turned by the watch's own compass). */
    private final class Dial extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), arrow = new Paint(Paint.ANTI_ALIAS_FLAG), north = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Dial(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            north.setTextAlign(Paint.Align.CENTER);
            north.setFakeBoldText(true);
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h * 0.47f, r = Math.min(w, h) * 0.30f;
            ring.setColor((Theme.accent & 0x00FFFFFF) | 0x55000000);
            ring.setStrokeWidth(WUi.dp(getContext(), 2));
            cv.drawCircle(cx, cy, r, ring);
            float az = Float.isNaN(azimuth) ? 0 : azimuth;
            // ticks: every 30°, turned so north points north
            for (int d = 0; d < 360; d += 30) {
                double a = Math.toRadians(d - az - 90);
                float in = d % 90 == 0 ? r * 0.82f : r * 0.9f;
                cv.drawLine(cx + (float) Math.cos(a) * in, cy + (float) Math.sin(a) * in, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, ring);
            }
            double na = Math.toRadians(-az - 90);
            north.setColor(0xFFF87171);
            north.setTextSize(WUi.dp(getContext(), 12));
            cv.drawText("N", cx + (float) Math.cos(na) * r * 1.16f, cy + (float) Math.sin(na) * r * 1.16f + WUi.dp(getContext(), 4), north);
            float[] wy = way();
            arrow.setColor(wy == null ? 0xFF334155 : Theme.accent);
            float turn = wy == null ? 0 : (wy[1] - az);
            cv.save();
            cv.rotate(turn, cx, cy);
            path.reset();
            path.moveTo(cx, cy - r * 0.78f);
            path.lineTo(cx + r * 0.32f, cy + r * 0.38f);
            path.lineTo(cx, cy + r * 0.18f);
            path.lineTo(cx - r * 0.32f, cy + r * 0.38f);
            path.close();
            cv.drawPath(path, arrow);
            cv.restore();
        }
    }
}
