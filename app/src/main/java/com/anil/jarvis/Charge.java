package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

/**
 * "ఫోన్ 95% ఛార్జ్ అయింది": his phone stops charging by itself at 95%, so Jarvis says so once when it gets there
 * (at night or during a call only a quiet notification). Fed by the battery broadcast in NotifyListener / WakeService.
 */
final class Charge {
    private Charge() {}

    private static boolean plugged, told;
    private static int sessionLow = 101;

    static int at(Context c) { return new Prefs(c).sp.getInt("charge_alert_pct", 95); }

    static synchronized void onBattery(Context c, Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int pct = level * 100 / scale;
        boolean now = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (!now) { plugged = false; told = false; sessionLow = 101; return; }
        if (!plugged) { plugged = true; told = false; sessionLow = pct; } // just plugged in
        sessionLow = Math.min(sessionLow, pct);
        int target = at(c);
        if (target <= 0 || told || pct < target || sessionLow >= target) return; // off, said already, or plugged in already full
        told = true;
        Prefs p = new Prefs(c);
        String text = "ఫోన్ " + pct + " శాతం ఛార్జ్ అయింది. ఛార్జర్ తీసేయొచ్చు.";
        boolean quiet = p.night() || CallControl.busyWithCall() || Rest.resting(c);
        if (!quiet) Announcer.say(c, p.name() + ", " + text);
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_charge", "ఛార్జింగ్ పూర్తి", NotificationManager.IMPORTANCE_DEFAULT));
            nm.notify(141, new Notification.Builder(c, "jarvis_charge").setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                    .setContentTitle("🔋 " + pct + "% ఛార్జ్ అయింది").setContentText("ఛార్జర్ తీసేయొచ్చు").setAutoCancel(true)
                    .setTimeoutAfter(60 * 60000L).build());
        } catch (Exception ignored) {}
    }
}
