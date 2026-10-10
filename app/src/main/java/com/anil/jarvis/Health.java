package com.anil.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Steps walked today (the phone's step counter) and water-break reminders. */
final class Health {
    private Health() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("jarvis_health", Context.MODE_PRIVATE);
    }

    private static int today() {
        Calendar k = Calendar.getInstance();
        return k.get(Calendar.YEAR) * 1000 + k.get(Calendar.DAY_OF_YEAR);
    }

    static boolean canCount(Context c) {
        // (Android 8 / 9 have no such permission: the step counter is free to read there)
        return android.os.Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    /** The step counter's total since the phone started, or -1 (waits up to 3 s). */
    static float counter(Context c) {
        if (!canCount(c)) return -1;
        SensorManager sm = c.getSystemService(SensorManager.class);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (s == null) return -1;
        final float[] v = {-1};
        CountDownLatch done = new CountDownLatch(1);
        SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) { v[0] = e.values[0]; done.countDown(); }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, new android.os.Handler(android.os.Looper.getMainLooper()));
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        sm.unregisterListener(l);
        return v[0];
    }

    /** Remembers the counter at the start of each day (called every ~15 minutes). */
    static void recordStepBaseline(Context c) {
        if (sp(c).getInt("day", 0) == today()) return;
        float now = counter(c);
        if (now < 0) return;
        keepFinishedDay(c, now);
        sp(c).edit().putInt("day", today()).putFloat("base", now).apply();
    }

    /** A new day began: keep the steps of the day that just ended (for the weekly report; about 3 weeks kept). */
    private static synchronized void keepFinishedDay(Context c, float now) {
        int day = sp(c).getInt("day", 0);
        if (day == 0 || day == today()) return;
        float base = sp(c).getFloat("base", now);
        int steps = Math.round(now >= base ? now - base : now); // the phone restarted: its counter began again from 0
        try {
            org.json.JSONObject h = new org.json.JSONObject(sp(c).getString("history", "{}"));
            h.put(String.valueOf(day), steps);
            java.util.List<String> keys = new java.util.ArrayList<>();
            java.util.Iterator<String> it = h.keys();
            while (it.hasNext()) keys.add(it.next());
            java.util.Collections.sort(keys);
            for (int i = 0; i < keys.size() - 21; i++) h.remove(keys.get(i));
            sp(c).edit().putString("history", h.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Steps of the last 7 days (today so far included), or null when steps can't be counted. */
    static org.json.JSONObject week(Context c) throws Exception {
        if (!canCount(c)) return null;
        int todaySteps = stepsToday(c);
        if (todaySteps < 0) return null;
        org.json.JSONObject h = new org.json.JSONObject(sp(c).getString("history", "{}"));
        org.json.JSONObject perDay = new org.json.JSONObject();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE d", Locale.ENGLISH);
        int total = 0, counted = 0;
        for (int back = 6; back >= 0; back--) {
            Calendar k = Calendar.getInstance();
            k.add(Calendar.DAY_OF_YEAR, -back);
            int key = k.get(Calendar.YEAR) * 1000 + k.get(Calendar.DAY_OF_YEAR);
            int n = back == 0 ? todaySteps : h.optInt(String.valueOf(key), -1);
            if (n < 0) continue;
            perDay.put(f.format(k.getTime()) + (back == 0 ? " (today so far)" : ""), n);
            total += n;
            counted++;
        }
        return new org.json.JSONObject().put("total", total).put("days_counted", counted)
                .put("daily_average", counted == 0 ? 0 : total / counted).put("per_day", perDay);
    }

    /** Steps today, or -1 when unknown. */
    static int stepsToday(Context c) {
        float now = counter(c);
        if (now < 0) return -1;
        if (sp(c).getInt("day", 0) != today()) {
            keepFinishedDay(c, now);
            sp(c).edit().putInt("day", today()).putFloat("base", now).apply();
            return 0;
        }
        float base = sp(c).getFloat("base", now);
        if (now < base) { // the phone restarted: its counter began again from 0
            sp(c).edit().putFloat("base", 0).apply();
            base = 0;
        }
        return Math.round(now - base);
    }

    // ---------------------------------------------------------------- water

    static void setWater(Context c, boolean on, int everyHours, int from, int to) {
        sp(c).edit().putBoolean("water", on).putInt("water_every", Math.max(1, Math.min(4, everyHours)))
                .putInt("water_from", from).putInt("water_to", to).putLong("water_last", System.currentTimeMillis()).apply();
    }

    static void waterTick(Context c, Prefs p) {
        SharedPreferences s = sp(c);
        if (!s.getBoolean("water", false)) return;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < s.getInt("water_from", 8) || hour >= s.getInt("water_to", 22)) return;
        long every = s.getInt("water_every", 2) * 3600000L;
        if (System.currentTimeMillis() - s.getLong("water_last", 0) < every - 5 * 60000L) return;
        s.edit().putLong("water_last", System.currentTimeMillis()).apply();
        Proactive.say(c, p.name() + ", నీళ్లు తాగే సమయం. ఒక గ్లాసు నీళ్లు తాగండి.", null, null);
    }
}
