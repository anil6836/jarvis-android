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
    // D (7 Nov): the charger in / out on the phone: the words and the picture (never at the first look after a start)
    private static boolean seen, wasPlugged;
    private static long lastChange;

    static int at(Context c) { return new Prefs(c).sp.getInt("charge_alert_pct", 95); }

    static void onBattery(Context c, Intent i) { onBattery(c, i, false); }

    /** sticky: the intent Android hands over when a receiver registers (the state now, not a change just now). */
    static synchronized void onBattery(Context c, Intent i, boolean sticky) {
        if (i == null) return;
        try { Power.onBattery(c, i); } catch (Exception ignored) {} // O35 / W74: low before / during a duty, will it last
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int pct = level * 100 / scale;
        boolean now = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (!seen || sticky) { seen = true; wasPlugged = now; } // (a start / a receiver coming back: no "just plugged in")
        else if (now != wasPlugged) { wasPlugged = now; try { plugChanged(c, now, pct); } catch (Exception ignored) {} }
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

    /**
     * The charger went in (on) or out: "చార్జింగ్ ఎక్కుతోంది, ఇప్పుడు 34 శాతం" / "చార్జర్ తీశారు, 34 నుంచి 80 శాతం ఎక్కింది",
     * and the liquid card. Phone only (the home tablet and the car box: nothing new). No fixed quiet hours (his word):
     * quiet only in his night mode / rest, never in a call; a loose cable is said once. His two switches in Settings.
     */
    private static void plugChanged(Context c, boolean on, int pct) {
        Prefs p = new Prefs(c);
        if (p.homeMode() || p.carMode()) return;
        long t = System.currentTimeMillis();
        int from = p.sp.getInt("charge_from_pct", -1);
        if (System.currentTimeMillis() - p.sp.getLong("charge_from_at", 0) > 2 * 24 * 3600_000L) from = -1; // (an old note: not this charge)
        if (on) p.sp.edit().putInt("charge_from_pct", pct).putLong("charge_from_at", t).apply();
        else p.sp.edit().remove("charge_from_pct").remove("charge_from_at").apply();
        boolean call = CallControl.busyWithCall() || CallCare.inCall(c);
        boolean fresh = MainLook.sayNow(t, lastChange); // (a loose cable: only its first change)
        lastChange = t;
        if (!fresh) return;
        if (p.sp.getBoolean("charge_say", true) && !p.night() && !call && !Rest.resting(c))
            Announcer.say(c, on ? MainLook.plugWords(pct) : MainLook.unplugWords(from, pct));
        if (p.sp.getBoolean("charge_show", true) && !call) ChargeShow.show(c, on, pct, on ? -1 : from);
    }
}
