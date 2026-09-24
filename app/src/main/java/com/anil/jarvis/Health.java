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
        return c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
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
        sp(c).edit().putInt("day", today()).putFloat("base", now).apply();
    }

    /** Steps today, or -1 when unknown. */
    static int stepsToday(Context c) {
        float now = counter(c);
        if (now < 0) return -1;
        if (sp(c).getInt("day", 0) != today()) {
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
