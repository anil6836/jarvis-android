package com.anil.jarvis.watch;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 4: the watch's heart-rate sensor, read for a short while (the body scan, the breathing, every ~15 minutes while
 * he sits). Readings the watch itself marks unreliable, or off the wrist, are left out. Needs "Body sensors" allowed.
 */
final class Pulse implements SensorEventListener {
    private final List<Float> good = new ArrayList<>(), any = new ArrayList<>();
    private final List<Long> goodAt = new ArrayList<>();
    private SensorManager sm;
    /** The latest reading (0: none yet). */
    volatile int now;

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean has(Context c) {
        SensorManager sm = c.getSystemService(SensorManager.class);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null;
    }

    /** Starts reading (events on h's thread, or the main thread). False: not allowed, or no sensor. */
    boolean start(Context c, Handler h) {
        if (!allowed(c)) return false;
        sm = c.getSystemService(SensorManager.class);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        if (s == null) return false;
        try { return sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, h); } catch (Exception e) { return false; }
    }

    void stop() {
        if (sm != null) try { sm.unregisterListener(this); } catch (Exception ignored) {}
        sm = null;
    }

    @Override public synchronized void onSensorChanged(SensorEvent e) {
        float v = e.values.length > 0 ? e.values[0] : 0;
        if (v < 30 || v > 220) return;
        if (e.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW) { good.add(v); goodAt.add(System.currentTimeMillis()); }
        if (e.accuracy >= SensorManager.SENSOR_STATUS_UNRELIABLE) any.add(v);
        now = Math.round(v);
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    /** How many usable readings so far. */
    synchronized int count() { return good.size() >= 3 ? good.size() : any.size(); }

    /** The middle reading (the first third left out while the sensor settles); 0 if too few. */
    synchronized int bpm() {
        List<Float> v = good.size() >= 3 ? good : any.size() >= 5 ? any : null;
        if (v == null) return 0;
        return median(v.subList(v.size() / 3, v.size()));
    }

    /** The middle reading of those between two times (wall ms); 0 if fewer than 3. */
    synchronized int bpmBetween(long from, long to) {
        List<Float> v = new ArrayList<>();
        for (int i = 0; i < good.size(); i++) if (goodAt.get(i) >= from && goodAt.get(i) <= to) v.add(good.get(i));
        return v.size() < 3 ? 0 : median(v);
    }

    static int median(List<Float> in) {
        if (in.isEmpty()) return 0;
        List<Float> v = new ArrayList<>(in);
        Collections.sort(v);
        return Math.round(v.get(v.size() / 2));
    }
}
