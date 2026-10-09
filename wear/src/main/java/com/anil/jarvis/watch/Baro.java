package com.anil.jarvis.watch;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * W75: the watch's own barometer, read once about every 15 minutes while he sits still; the air pressure falling 3 hPa or
 * more within 3 hours often comes before a storm -> "⛈️ గాలివాన రావచ్చు" on the wrist (a hint, works without internet).
 */
final class Baro {
    private Baro() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_watch_baro", Context.MODE_PRIVATE); }

    /** The fall in hPa over the last 3 hours (pure: tested on a desk): the highest reading then minus now; 0 if too few. */
    static float drop(JSONArray a, long now) {
        float max = -1, last = -1;
        long lastT = 0;
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || now - o.optLong("t") > 3 * 3600_000L) continue;
            float p = (float) o.optDouble("p");
            max = Math.max(max, p);
            if (o.optLong("t") >= lastT) { lastT = o.optLong("t"); last = p; }
            n++;
        }
        return n < 4 || max < 0 ? 0 : max - last;
    }

    /** From Beat (background thread). */
    static void check(Context c) {
        if (!Link.cfg(c).optBoolean("storm", true) || Boolean.FALSE.equals(EarService.worn)) return;
        long since = Body.lastStepEl > 0 ? Body.lastStepEl : Body.sinceEl;
        if (SystemClock.elapsedRealtime() - since < 10 * 60_000L) return; // (walking / riding: the height changes, not the weather)
        SensorManager sm = c.getSystemService(SensorManager.class);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (s == null) return;
        final float[] got = {-1};
        CountDownLatch done = new CountDownLatch(1);
        SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) { got[0] = e.values[0]; done.countDown(); }
            @Override public void onAccuracyChanged(Sensor x, int a) {}
        };
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL);
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        sm.unregisterListener(l);
        if (got[0] < 800 || got[0] > 1100) return;
        long now = System.currentTimeMillis();
        try {
            JSONArray a = new JSONArray(sp(c).getString("p", "[]")), keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (now - a.getJSONObject(i).optLong("t") < 6 * 3600_000L) keep.put(a.getJSONObject(i));
            keep.put(new JSONObject().put("t", now).put("p", got[0]));
            sp(c).edit().putString("p", keep.toString()).apply();
            float d = drop(keep, now);
            if (d >= 3 && now - sp(c).getLong("told", 0) > 6 * 3600_000L) {
                sp(c).edit().putLong("told", now).apply();
                Body.note(c, "⛈️ గాలివాన రావచ్చు", "గాలి ఒత్తిడి 3 గంటల్లో " + Math.round(d) + " hPa పడింది (వాచ్ బారోమీటర్ అంచనా). బయట ఉంటే జాగ్రత్త, బట్టలు లోపల పెట్టండి.");
                Alerts.buzz(c, "weather");
            }
        } catch (Exception ignored) {}
    }
}
