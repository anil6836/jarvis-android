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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * Keeps the watch ready (W9): each time the screen lights up (the wrist raised) the mic listens a few seconds for
 * "Hey Jarvis" (Mic.RAISE), and in his chosen hours it listens all the time (Mic.HOURS). Also: whether the watch is
 * on his wrist (told to the phone) and his steps (for W20, see Beat). Phase 4: the steps also feed the walking coach,
 * and the heart rate is read now and then while he sits (see Body). Android lets a background mic work only for a
 * service started while the app is open, so the Jarvis screen starts this one.
 */
public class EarService extends Service {
    static volatile boolean running;
    /** On his wrist (null: this watch can't tell). */
    static volatile Boolean worn;
    /** The step counter now (-1: not known). */
    static volatile float steps = -1;
    /** The service holds the microphone (false: only steps / heart rate, after a restart until Jarvis is opened). */
    static volatile boolean micType;
    /** The running service (for a heart-rate reading and the step flush), or null. */
    static volatile EarService self;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SensorManager sm;
    private HandlerThread hrThread;
    private Pulse pulse;

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
                if (worn == null || worn != on) { worn = on; beat(); if (!on) Body.offWrist(EarService.this); }
            } else if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) { // W42 / W80: a fall, a hard knock
                if (!Boolean.FALSE.equals(worn)) fall.sample(e.values[0], e.values[1], e.values[2], e.timestamp / 1_000_000L, fallOut);
            } else if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
                steps = e.values[0];
                long t = e.timestamp / 1_000_000L, now = SystemClock.elapsedRealtime(); // (the sensor's clock is the boot clock)
                if (t <= 0 || t > now + 5_000L || now - t > 6 * 3600_000L) t = now;
                Body.step(EarService.this, steps, t);
            }
        }
        @Override public void onAccuracyChanged(Sensor s, int a) {}
    };

    private final Fall fall = new Fall();
    private final Fall.Out fallOut = new Fall.Out() {
        @Override public void impact(float g, long t) {
            try { Link.send(EarService.this, Link.P_HEALTH, new JSONObject().put("type", "impact").put("g", g).put("t", System.currentTimeMillis())); } catch (Exception ignored) {}
        }
        @Override public void fell(long t) {
            main.post(() -> {
                try { Link.send(EarService.this, Link.P_HEALTH, new JSONObject().put("type", "fall").put("t", System.currentTimeMillis())); } catch (Exception ignored) {}
                try { Help.open(EarService.this, "fall"); } catch (Exception ignored) {}
            });
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Talk.hours(EarService.this);
            main.postDelayed(this, 60_000);
        }
    };

    static boolean wanted(Context c) { return Link.raise(c) || Link.hours(c) || Link.lost(c) || Link.walk(c) || Link.hr(c) || Link.fall(c); }

    /** Started (or stopped) to match his settings. From the Jarvis screen only (Android's rule for the mic). */
    static void startIfWanted(Context c) {
        if (!wanted(c)) { c.stopService(new Intent(c, EarService.class)); return; }
        if (!Talk.micAllowed(c) && c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
                && !Pulse.allowed(c)) return; // (nothing allowed yet)
        try { c.startForegroundService(new Intent(c, EarService.class)); } catch (Exception ignored) {}
    }

    /** His settings changed (from the phone): stop if nothing needs it; the hours are looked at again. */
    static void refresh(Context c) {
        if (!running) return;
        if (!wanted(c)) { c.stopService(new Intent(c, EarService.class)); return; }
        Talk.hours(c);
        EarService e = self;
        if (e != null) e.sensors(); // (the walking coach turned on / off)
        Body.scheduleHr(c);
    }

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        sm = getSystemService(SensorManager.class);
        self = this;
        Body.sinceEl = SystemClock.elapsedRealtime();
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
            // W31: the kind that wakes the watch (about once a minute, only while he walks), so a walk is seen while it sleeps
            Sensor st = Link.walk(this) ? sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true) : null;
            if (st == null) st = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (st != null) try { sm.registerListener(body, st, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000); } catch (Exception ignored) {}
        }
        // W42 / W80: 25 readings a second, handed over in bunches every 2 seconds (the watch sleeps in between)
        if (Link.fall(this)) {
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc != null) try { sm.registerListener(body, acc, 40_000, 2_000_000); } catch (Exception ignored) {}
        }
    }

    /** The steps waiting in the sensor's own memory are handed over now (before a walk's end is looked at). */
    static void flushSteps() {
        EarService e = self;
        if (e != null && e.sm != null) try { e.sm.flush(e.body); } catch (Exception ignored) {}
    }

    interface Bpm { void got(int bpm); }

    /** The heart rate for a while (the watch kept awake meanwhile); the middle reading, 0 if none (not on the wrist). */
    void measure(long ms, Bpm done) {
        // the watch is kept awake from now (the caller may finish right after asking)
        PowerManager pm = getSystemService(PowerManager.class);
        PowerManager.WakeLock wl = pm == null ? null : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:hr");
        if (wl != null) wl.acquire(ms + 15_000L);
        main.post(() -> {
            if (pulse != null) { if (wl != null && wl.isHeld()) wl.release(); return; } // (one already going)
            if (hrThread == null) { hrThread = new HandlerThread("jarvis-hr"); hrThread.start(); }
            Handler h = new Handler(hrThread.getLooper());
            Pulse p = new Pulse();
            if (!p.start(this, h)) { if (wl != null && wl.isHeld()) wl.release(); done.got(0); return; }
            pulse = p;
            h.postDelayed(() -> {
                p.stop();
                int bpm = p.bpm();
                main.post(() -> { if (pulse == p) pulse = null; });
                try { done.got(bpm); } catch (Exception ignored) {}
                Link.flush(6000); // (sent before the watch may sleep again)
                if (wl != null && wl.isHeld()) wl.release();
            }, ms);
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // phase 4: also a "health" service (steps, heart rate) when he has allowed those; then, if Android refuses the mic
        // in the background, the walking coach and the heart rate still go on
        int health = 0;
        if (Build.VERSION.SDK_INT >= 34 && (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
                || Pulse.allowed(this))) health = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
        boolean boot = intent != null && intent.getBooleanExtra("boot", false); // (after a restart: the mic must wait for Jarvis to be opened)
        if (boot && health == 0) { stopSelf(); return START_NOT_STICKY; }
        boolean wantMic = !boot && (Talk.micAllowed(this) || health == 0), micOk = wantMic;
        try {
            startForeground(Notes.ID_EAR, Notes.ear(this, wantMic), (wantMic ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0) | health);
        } catch (Exception e) {
            micOk = false;
            boolean started = false;
            if (health != 0 && wantMic) try { startForeground(Notes.ID_EAR, Notes.ear(this, false), health); started = true; } catch (Exception ignored) {}
            // Android refused the mic in the background (not started from the open app): he must open Jarvis once
            if (wantMic) {
                Talk.listenBroken = true;
                Notes.broken(this, null);
            }
            if (!started) {
                running = false;
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        running = true;
        micType = micOk;
        sensors();
        Beat.schedule(this);
        Body.scheduleHr(this); // (phase 4: the heart rate every ~15 minutes while he sits)
        if (intent != null && micOk) { // started from the open app: the mic may listen in the background
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
        micType = false;
        if (self == this) self = null;
        Pulse p = pulse;
        if (p != null) p.stop();
        if (hrThread != null) hrThread.quitSafely();
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screen); } catch (Exception ignored) {}
        if (sm != null) try { sm.unregisterListener(body); } catch (Exception ignored) {}
        int k = Mic.kind();
        if (k == Mic.RAISE || k == Mic.HOURS) Mic.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
