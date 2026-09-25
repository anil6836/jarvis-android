package com.anil.jarvis;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.SystemClock;

/**
 * Phone gestures: shake twice to call Jarvis; lay the phone face down to silence it
 * (turning it back up restores the sound).
 */
final class Motion implements SensorEventListener {
    interface Listener { void onShake(); }

    private final Context ctx;
    private final Listener listener;
    private final SensorManager sm;
    private boolean on;
    private long lastSpike, lastShake, downSince, upSince;
    private int spikes;
    private boolean silencedByUs;
    private int ringerBefore = -1;

    Motion(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        sm = ctx.getSystemService(SensorManager.class);
    }

    void start() {
        Prefs p = new Prefs(ctx);
        if (on || sm == null || (!p.shakeWake() && !p.faceDownSilent())) return;
        Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (a == null) return;
        sm.registerListener(this, a, SensorManager.SENSOR_DELAY_UI);
        on = true;
    }

    void stop() {
        if (on && sm != null) sm.unregisterListener(this);
        on = false;
    }

    @Override public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        long now = SystemClock.elapsedRealtime();
        Prefs p = new Prefs(ctx);

        // shake: two strong jolts within a second
        if (p.shakeWake()) {
            double g = Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH;
            if (g > 2.7 && now - lastSpike > 200) {
                spikes = now - lastSpike < 900 ? spikes + 1 : 1;
                lastSpike = now;
                if (spikes >= 2 && now - lastShake > 4000 && !MainActivity.inConversation) {
                    spikes = 0;
                    lastShake = now;
                    listener.onShake();
                }
            }
        }

        // face down: screen towards the table, lying still
        if (p.faceDownSilent()) {
            boolean down = z < -8.5f && Math.abs(x) < 2.5f && Math.abs(y) < 2.5f;
            boolean up = z > 3f;
            if (down) { upSince = 0; if (downSince == 0) downSince = now; } else downSince = 0;
            if (up) { if (upSince == 0) upSince = now; } else upSince = 0;
            if (down && !silencedByUs && now - downSince > 2000 && !CallControl.busyWithCall()) silence(true);
            if (up && silencedByUs && now - upSince > 800) silence(false);
        }
    }

    private void silence(boolean quiet) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return;
        try {
            if (quiet) {
                int mode = am.getRingerMode();
                if (mode != AudioManager.RINGER_MODE_NORMAL) return; // already quiet: leave it
                ringerBefore = mode;
                try { am.setRingerMode(AudioManager.RINGER_MODE_SILENT); }
                catch (SecurityException e) { am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE); }
                silencedByUs = true;
            } else {
                am.setRingerMode(ringerBefore >= 0 ? ringerBefore : AudioManager.RINGER_MODE_NORMAL);
                silencedByUs = false;
            }
        } catch (Exception ignored) {}
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
