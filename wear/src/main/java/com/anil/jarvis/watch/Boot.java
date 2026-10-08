package com.anil.jarvis.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The watch restarted (or Jarvis was updated): Android lets the mic listen in the background only after the app has
 * been opened, so a small note asks him to open Jarvis once (only when wrist-raise or hours listening is on). The
 * walking coach and the heart rate (phase 4) start again by themselves.
 */
public class Boot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Beat.schedule(c); // ("still here" to the phone: alarms are cleared by a restart)
        Timers.restore(c); // (his watch timers too)
        // (only when it can run as a "health" service: Android 14+ and steps allowed; else it waits for Jarvis to be opened)
        if ((Link.walk(c) || Link.hr(c)) && android.os.Build.VERSION.SDK_INT >= 34
                && c.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            try { c.startForegroundService(new Intent(c, EarService.class).putExtra("boot", true)); } catch (Exception ignored) {}
        if (!Link.raise(c) && !Link.hours(c)) return;
        Talk.listenBroken = true;
        Notes.broken(c, null);
    }
}
