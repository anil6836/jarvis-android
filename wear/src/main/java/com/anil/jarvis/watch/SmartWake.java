package com.anil.jarvis.watch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;

import org.json.JSONObject;

/**
 * W68: the smart alarm. From 25 minutes before his alarm, the watch feels for movement (light sleep, turning over); two
 * restless half-minutes close together (after the first 3 minutes) -> the phone rings the alarm on his wrist now. Not
 * stirring -> nothing changes: the alarm rings at its time. Jarvis's own estimate (Samsung's live sleep stages aren't shared).
 */
final class SmartWake {
    private SmartWake() {}

    /** Restless (pure: tested on a desk): the spread of the wrist's acceleration over half a minute, in g. */
    static boolean restless(double sd) { return sd > 0.05; }

    private static SensorEventListener listener;
    private static PowerManager.WakeLock lock;

    static void start(Context c, JSONObject o) {
        Context app = c.getApplicationContext();
        stop(app);
        String id = o.optString("id");
        long at = o.optLong("at");
        if (at - System.currentTimeMillis() < 5 * 60_000L || Boolean.FALSE.equals(EarService.worn)) return;
        SensorManager sm = app.getSystemService(SensorManager.class);
        Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) return;
        long begin = System.currentTimeMillis();
        PowerManager pm = app.getSystemService(PowerManager.class);
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:smart");
        lock.acquire(at - begin + 60_000L);
        final double[] acc3 = new double[3]; // sum, sumSq, n of this half-minute
        final long[] win = {begin, 0}; // window start, last restless window end
        listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                long now = System.currentTimeMillis();
                if (now >= at) { stop(app); return; }
                double g = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81;
                acc3[0] += g; acc3[1] += g * g; acc3[2]++;
                if (now - win[0] < 30_000L) return;
                double mean = acc3[0] / acc3[2], sd = Math.sqrt(Math.max(0, acc3[1] / acc3[2] - mean * mean));
                acc3[0] = acc3[1] = acc3[2] = 0;
                win[0] = now;
                if (now - begin < 3 * 60_000L || !restless(sd)) return;
                if (win[1] > 0 && now - win[1] < 2 * 60_000L) { // twice close together: he is stirring
                    stop(app);
                    try { Link.send(app, Link.P_DO, new JSONObject().put("what", "smart_wake").put("id", id)); } catch (Exception ignored) {}
                    Link.flush(5000);
                    return;
                }
                win[1] = now;
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };
        try { sm.registerListener(listener, acc, 100_000, 10_000_000); } catch (Exception ignored) {}
    }

    static void stop(Context c) {
        SensorManager sm = c.getSystemService(SensorManager.class);
        if (listener != null && sm != null) try { sm.unregisterListener(listener); } catch (Exception ignored) {}
        listener = null;
        if (lock != null && lock.isHeld()) try { lock.release(); } catch (Exception ignored) {}
        lock = null;
    }
}
