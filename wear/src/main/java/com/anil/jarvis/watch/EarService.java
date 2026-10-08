package com.anil.jarvis.watch;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps the watch ready to listen (W9): each time the screen lights up (the wrist raised) the mic listens a few
 * seconds for "Hey Jarvis" (Mic.RAISE), and in his chosen hours it listens all the time (Mic.HOURS). Android lets a
 * background mic work only for a service started while the app is open, so the Jarvis screen starts this one.
 */
public class EarService extends Service {
    static volatile boolean running;
    private final Handler main = new Handler(Looper.getMainLooper());

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

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Talk.hours(EarService.this);
            main.postDelayed(this, 60_000);
        }
    };

    /** Started (or stopped) to match his settings. From the Jarvis screen only (Android's rule for the mic). */
    static void startIfWanted(Context c) {
        if (!Link.raise(c) && !Link.hours(c)) { c.stopService(new Intent(c, EarService.class)); return; }
        try { c.startForegroundService(new Intent(c, EarService.class)); } catch (Exception ignored) {}
    }

    /** His settings changed (from the phone): stop if nothing needs it; the hours are looked at again. */
    static void refresh(Context c) {
        if (!running) return;
        if (!Link.raise(c) && !Link.hours(c)) { c.stopService(new Intent(c, EarService.class)); return; }
        Talk.hours(c);
    }

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
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
        if (intent != null) { // started from the open app: the mic may listen in the background
            Talk.listenBroken = false;
            Notes.cancelBroken(this);
        }
        main.removeCallbacks(tick);
        main.post(tick);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screen); } catch (Exception ignored) {}
        int k = Mic.kind();
        if (k == Mic.RAISE || k == Mic.HOURS) Mic.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
