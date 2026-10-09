package com.anil.jarvis.watch;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * W59: wrist gestures while a Jarvis screen is on (the gyroscope runs only then): turning the wrist out-in-out quickly
 * (a double twist) -> Jarvis listens; a sharp flick on the alarm screen -> 5 more minutes.
 */
final class Gestures implements SensorEventListener {
    interface Out { void twist(); void flick(); }

    /** Pure (tested on a desk): 1 = a double twist, 2 = a flick, 0 = nothing. Gyro in rad/s, t in ms. */
    static final class Detector {
        private final long[] peakT = new long[4];
        private final int[] peakSign = new int[4];
        private int peaks;
        private long lastFire = -10_000, armedT = -1;
        private int armedSign;

        int add(float wx, float wy, float wz, long t) {
            if (t - lastFire < 1500) return 0;
            float ax = Math.abs(wx), other = Math.max(Math.abs(wy), Math.abs(wz)), all = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (all > 11 && ax < 1.5f * other) { lastFire = t; peaks = 0; return 2; } // a flick (not a twist of the forearm)
            if (ax > 4 && ax > 1.5f * other) {
                int sign = wx > 0 ? 1 : -1;
                if (armedT < 0 || sign != armedSign) { armedT = t; armedSign = sign; record(sign, t); }
            } else if (ax < 1.5f) armedT = -1;
            // three alternating turns within 1.2 s
            if (peaks >= 3 && t - peakT[(peaks - 3) % 4] <= 1200) { lastFire = t; peaks = 0; return 1; }
            return 0;
        }

        private void record(int sign, long t) {
            if (peaks > 0 && peakSign[(peaks - 1) % 4] == sign) return;
            peakT[peaks % 4] = t;
            peakSign[peaks % 4] = sign;
            peaks++;
        }
    }

    private final Detector d = new Detector();
    private final Out out;
    private final SensorManager sm;

    private Gestures(Context c, Out out) { this.out = out; sm = c.getSystemService(SensorManager.class); }

    /** Started while a screen is shown (its onResume), only when he wants gestures. Null when off / no gyroscope. */
    static Gestures start(Context c, Out out) {
        if (!Link.cfg(c).optBoolean("gestures", true)) return null;
        Gestures g = new Gestures(c, out);
        Sensor gy = g.sm == null ? null : g.sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gy == null) return null;
        g.sm.registerListener(g, gy, SensorManager.SENSOR_DELAY_GAME);
        return g;
    }

    void stop() { if (sm != null) sm.unregisterListener(this); }

    @Override public void onSensorChanged(SensorEvent e) {
        int r = d.add(e.values[0], e.values[1], e.values[2], e.timestamp / 1_000_000L);
        if (r == 1) out.twist(); else if (r == 2) out.flick();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}
}
