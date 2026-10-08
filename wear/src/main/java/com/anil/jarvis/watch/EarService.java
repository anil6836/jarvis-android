package com.anil.jarvis.watch;

import android.Manifest;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Keeps the watch ready (W9): each time the screen lights up (the wrist raised) the mic listens a few seconds for
 * "Hey Jarvis" (Mic.RAISE), and in his chosen hours it listens all the time (Mic.HOURS). Also: whether the watch is
 * on his wrist (told to the phone) and his steps (for W20, see Beat). Android lets a background mic work only for a
 * service started while the app is open, so the Jarvis screen starts this one.
 */
public class EarService extends Service {
    static volatile boolean running;
    /** On his wrist (null: this watch can't tell). */
    static volatile Boolean worn;
    /** The step counter now (-1: not known). */
    static volatile float steps = -1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SensorManager sm;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) Talk.raised(EarService.this);
            else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                if (Mic.kind() == Mic.RAISE) Mic.stop(); // (the wrist went down without a word)
                // W13: the screen went dark while Jarvis was speaking with its screen up: his palm covered the watch
                if (Talk.state == Talk.SPEAKING && Talk.screenUp) Talk.stop(EarService.this);
            }
        }
    };

    private final SensorEventListener body = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            if (e.sensor.getType() == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
                boolean on = e.values[0] >= 0.5f;
                if (worn == null || worn != on) { worn = on; beat(); }
            } else if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
                steps = e.values[0];
            }
        }
        @Override public void onAccuracyChanged(Sensor s, int a) {}
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Talk.hours(EarService.this);
            main.postDelayed(this, 60_000);
        }
    };

    static boolean wanted(Context c) { return Link.raise(c) || Link.hours(c) || Link.lost(c); }

    /** Started (or stopped) to match his settings. From the Jarvis screen only (Android's rule for the mic). */
    static void startIfWanted(Context c) {
        if (!wanted(c)) { c.stopService(new Intent(c, EarService.class)); return; }
        try { c.startForegroundService(new Intent(c, EarService.class)); } catch (Exception ignored) {}
    }

    /** His settings changed (from the phone): stop if nothing needs it; the hours are looked at again. */
    static void refresh(Context c) {
        if (!running) return;
        if (!wanted(c)) { c.stopService(new Intent(c, EarService.class)); return; }
        Talk.hours(c);
    }

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        sm = getSystemService(SensorManager.class);
        sensors();
    }

    /** The on-wrist sensor, and the step counter once he has allowed it (looked at again on each start). */
    private void sensors() {
        if (sm == null) return;
        try { sm.unregisterListener(body); } catch (Exception ignored) {}
        Sensor off = sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true);
        if (off == null) off = sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
        if (off != null) try { sm.registerListener(body, off, SensorManager.SENSOR_DELAY_NORMAL); } catch (Exception ignored) {}
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            Sensor st = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (st != null) try { sm.registerListener(body, st, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000); } catch (Exception ignored) {}
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(Notes.ID_EAR, Notes.ear(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Exception e) {
            // Android refused the mic in the background (not started from the open app): he must open Jarvis once
            running = false;
            Talk.listenBroken = true;
            Notes.broken(this, null);
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        sensors();
        Beat.schedule(this);
        if (intent != null) { // started from the open app: the mic may listen in the background
            Talk.listenBroken = false;
            Notes.cancelBroken(this);
        }
        main.removeCallbacks(tick);
        main.post(tick);
        return START_STICKY;
    }

    /** On the wrist or not, told to the phone at once when it changes (the regular "still here" is Beat's). */
    private void beat() {
        JSONObject o = new JSONObject();
        try { if (worn != null) o.put("worn", worn); } catch (Exception ignored) {}
        Link.send(this, Link.P_BEAT, o);
    }

    @Override public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screen); } catch (Exception ignored) {}
        if (sm != null) try { sm.unregisterListener(body); } catch (Exception ignored) {}
        int k = Mic.kind();
        if (k == Mic.RAISE || k == Mic.HOURS) Mic.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
