package com.anil.jarvis.watch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * The watch's own clock for two things that must happen while it sleeps: "still here" (and on the wrist or not) to
 * the phone about every 15 minutes, and W20 "phone forgotten": when the phone drops out of Bluetooth reach (Inbox
 * hears it at once), it is looked at again every 2 minutes; still out of reach after 2 minutes on his wrist and he
 * has walked on (40 steps; without the step count: 6 minutes) → "📱 ఫోన్ మర్చిపోయారా?". Phase 4: each beat also reads the
 * heart rate while he sits (Body), and a walk's end is looked at here (WALK_CHECK) even while the watch sleeps.
 */
public class Beat extends BroadcastReceiver {
    private static final String BEAT = "com.anil.jarvis.watch.BEAT", CHECK = "com.anil.jarvis.watch.CHECK";

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 15 * 60_000L, 15 * 60_000L, pi(c, BEAT));
    }

    /** Looks at the phone again in a moment (even if the watch sleeps meanwhile). */
    static void checkSoon(Context c, long ms) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ms, pi(c, CHECK));
    }

    private static PendingIntent pi(Context c, String action) {
        return PendingIntent.getBroadcast(c, action.hashCode(), new Intent(c, Beat.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context c, Intent i) {
        final Context app = c.getApplicationContext();
        final boolean beat = BEAT.equals(i.getAction());
        final PendingResult pr = goAsync();
        if (Body.WALK_CHECK.equals(i.getAction()) || Body.HR_CHECK.equals(i.getAction())) {
            final boolean hr = Body.HR_CHECK.equals(i.getAction());
            new Thread(() -> {
                try {
                    EarService.flushSteps(); // (steps still in the sensor's memory come in first)
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                    Body.walkCheck(app);
                    if (hr) { Body.beat(app); Body.scheduleHr(app); }
                    Link.flush(6000); // (the walk's words reach the phone before the watch sleeps)
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                } finally {
                    pr.finish();
                }
            }, "jarvis-watch-walk").start();
            return;
        }
        new Thread(() -> {
            try {
                if (beat) {
                    EarService.flushSteps();
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                    Body.walkCheck(app);
                    Body.beat(app);
                }
                boolean near = Link.phoneNear(app);
                if (beat && near) {
                    JSONObject o = new JSONObject();
                    try {
                        if (EarService.worn != null) o.put("worn", EarService.worn);
                        o.put("day_steps", Body.today(app));
                        android.os.BatteryManager bm = app.getSystemService(android.os.BatteryManager.class);
                        if (bm != null) o.put("bat", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("chg", bm.isCharging());
                    } catch (Exception ignored) {}
                    Link.send(app, Link.P_BEAT, o);
                }
                lostCheck(app, near);
                Link.flush(6000);
            } finally {
                pr.finish();
            }
        }, "jarvis-watch-beat").start();
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_watch_lost", Context.MODE_PRIVATE); }

    /** The phone is near or not (any thread). */
    static synchronized void lostCheck(Context app, boolean near) {
        SharedPreferences s = sp(app);
        long since = s.getLong("since", 0), now = System.currentTimeMillis();
        boolean told = s.getBoolean("told", false);
        if (near) {
            if (told) Alerts.gone(app, 777);
            if (since != 0 || told) s.edit().clear().apply();
            return;
        }
        if (!Link.lost(app)) return;
        float steps = EarService.steps;
        if (since == 0) {
            s.edit().putLong("since", now).putFloat("steps", steps).apply();
            checkSoon(app, 2 * 60_000L);
            return;
        }
        if (told || now - since > 60 * 60_000L) return; // (told once; after an hour away it is just how the day is)
        float at = s.getFloat("steps", -1);
        boolean walked = steps >= 0 && at >= 0 ? steps - at >= 40 : now - since >= 6 * 60_000L;
        if (now - since >= 2 * 60_000L && walked && !Boolean.FALSE.equals(EarService.worn)) {
            s.edit().putBoolean("told", true).apply();
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> Alerts.phoneLost(app, since));
            return;
        }
        checkSoon(app, 2 * 60_000L);
    }
}
