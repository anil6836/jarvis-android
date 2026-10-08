package com.anil.jarvis.watch;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Phase 4 on the watch, from its own sensors (the phone keeps the record and decides what to say):
 *   W31 the walking coach: every walk (found by itself from the steps) told with its steps and metres / km, and a
 *       word at each km; today's steps for "ఈరోజు ఎంత నడిచాను?". A walk the phone didn't get is sent again later.
 *   W46 / W26 / W43 the heart rate for half a minute about every 15 minutes while he sits still (the phone learns his
 *       normal and sees stress, a fever hint and the resting rate). Only with the step count (to know he is still).
 *   W28 sitting / lying still on his wrist for 40 minutes (the phone puts it with his heart rate: asleep or not).
 * The step counter wakes the watch about once a minute only while he walks; nothing runs while he is still.
 */
final class Body {
    private Body() {}

    static final String WALK_CHECK = "com.anil.jarvis.watch.WALK_CHECK", HR_CHECK = "com.anil.jarvis.watch.HR_CHECK";
    static final int NOTE_ID = 61;
    private static final WalkCoach coach = new WalkCoach();
    private static boolean loaded;
    /** When the steps last moved (elapsedRealtime); 0: not since the service started. */
    static volatile long lastStepEl;
    /** When the service began counting (elapsedRealtime). */
    static volatile long sinceEl = SystemClock.elapsedRealtime();
    private static volatile long hrAtEl;
    private static volatile Boolean still40;
    /** The step count when he was found still (-1: not known yet). */
    private static volatile float stillFrom = -1;
    /** The counter now (kept here; written down every 15 steps), and when the walk's end alarm is set for. */
    private static volatile float memLast = -1;
    private static float savedCount = -1;
    private static long armedEl;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_watch_body", Context.MODE_PRIVATE); }

    private static void load(Context c) {
        if (loaded) return;
        loaded = true;
        try { coach.load(new JSONObject(sp(c).getString("coach", "{}"))); } catch (Exception ignored) {}
    }

    /** The watch counts steps (allowed and has the sensor): only then "still" means anything. */
    static boolean stepsKnown(Context c) {
        if (c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return false;
        SensorManager sm = c.getSystemService(SensorManager.class);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
    }

    // ---------------------------------------------------------------- steps

    /** A step-counter reading (main thread): t on the elapsedRealtime clock. */
    static synchronized void step(Context c, float count, long t) {
        load(c);
        Context app = c.getApplicationContext();
        float before = memLast >= 0 ? memLast : sp(app).getFloat("last", -1);
        if (before < 0 || count != before) lastStepEl = t;
        daySteps(app, count, before);
        memLast = count;
        if (Link.walk(app)) coach.sample(count, t, out(app));
        else coach.drop();
        if (Boolean.TRUE.equals(still40)) {
            if (stillFrom < 0) stillFrom = count;
            else if (count - stillFrom >= 10) still(app, false); // (up and walking: awake)
        }
        if (Math.abs(count - savedCount) >= 15 || !coach.active()) save(app);
        if (coach.active()) { // the walk's end is looked at even if the watch sleeps (set again only when it moves by 30 s)
            long at = coach.lastStep() + WalkCoach.PAUSE + 20_000L;
            if (Math.abs(at - armedEl) > 30_000L) { armedEl = at; walkCheckAt(app, at); }
        }
    }

    private static void save(Context c) {
        savedCount = memLast;
        sp(c).edit().putFloat("last", memLast).putString("coach", coach.json().toString()).apply();
    }

    /** Today's steps on this watch (-1: not known yet). */
    static int today(Context c) {
        SharedPreferences s = sp(c);
        if (s.getInt("day", 0) != day()) return -1;
        float last = memLast >= 0 ? memLast : s.getFloat("last", -1), base = s.getFloat("base", -1);
        return last < 0 || base < 0 ? -1 : Math.round(Math.max(0, last - base));
    }

    private static int day() {
        Calendar k = Calendar.getInstance();
        return k.get(Calendar.YEAR) * 1000 + k.get(Calendar.DAY_OF_YEAR);
    }

    private static void daySteps(Context c, float count, float before) {
        SharedPreferences s = sp(c);
        if (s.getInt("day", 0) != day()) // (the counter at the end of yesterday: today's steps start from there)
            s.edit().putInt("day", day()).putFloat("base", before >= 0 && before <= count ? before : count).apply();
        else if (s.getFloat("base", -1) > count) s.edit().putFloat("base", 0).apply(); // the watch restarted: its counter began again
    }

    private static WalkCoach.Out out(Context app) {
        return new WalkCoach.Out() {
            @Override public void km(int km, int steps, long ms) { walk(app, false, km, steps, ms); }
            @Override public void ended(int steps, long ms, long endT) { walk(app, true, 0, steps, ms); }
        };
    }

    /** A walk ended (or a km on the way): to the phone, which says it; if the phone isn't there, a note here (sent later). */
    private static void walk(Context app, boolean end, int km, int steps, long ms) {
        try {
            JSONObject o = new JSONObject().put("type", "walk").put("end", end).put("km", km).put("steps", steps)
                    .put("secs", ms / 1000).put("t", System.currentTimeMillis()).put("day_steps", today(app));
            Link.send(app, Link.P_HEALTH, o, () -> {
                if (!end) { Talk.buzz(app, 40, 80, 40); return; } // (a km: just a buzz)
                note(app, "🚶 నడక", WalkCoach.line(steps, ms) + ".");
                keep(app, o);
            });
        } catch (Exception ignored) {}
    }

    /** A walk the phone didn't get, kept to send again (the last 10). */
    private static synchronized void keep(Context c, JSONObject o) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("pending", "[]"));
            a.put(o);
            while (a.length() > 10) a.remove(0);
            sp(c).edit().putString("pending", a.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** The walks the phone didn't get, sent now (not said again: only kept there). */
    private static synchronized void resend(Context app) {
        try {
            JSONArray a = new JSONArray(sp(app).getString("pending", "[]"));
            if (a.length() == 0) return;
            sp(app).edit().putString("pending", "[]").apply();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i).put("late", true);
                Link.send(app, Link.P_HEALTH, o, () -> keep(app, o));
            }
        } catch (Exception ignored) {}
    }

    /** The walk's end looked at again in a while, even if the watch sleeps (it ends after 75 s without steps). */
    private static void walkCheckAt(Context c, long atEl) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        try { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, Math.max(atEl, SystemClock.elapsedRealtime() + 30_000L), pi(c, WALK_CHECK)); }
        catch (Exception ignored) {}
    }

    static PendingIntent pi(Context c, String action) {
        return PendingIntent.getBroadcast(c, action.hashCode(), new Intent(c, Beat.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The walk alarm (any thread, after the step counter was flushed). */
    static synchronized void walkCheck(Context c) {
        load(c);
        Context app = c.getApplicationContext();
        coach.check(SystemClock.elapsedRealtime(), out(app));
        if (memLast >= 0) save(app);
        if (coach.active()) { armedEl = coach.lastStep() + WalkCoach.PAUSE + 20_000L; walkCheckAt(app, armedEl); }
    }

    // ---------------------------------------------------------------- every ~15 minutes (its own alarm, and Beat)

    /** The next heart-rate look in 15 minutes (an alarm that also runs while the watch sleeps). */
    static void scheduleHr(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        if (!Link.hr(c)) { am.cancel(pi(c, HR_CHECK)); return; }
        try { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 15 * 60_000L, pi(c, HR_CHECK)); }
        catch (Exception ignored) {}
    }

    /** Still on his wrist (any thread): the heart rate for 30 s, and "still for 40 minutes" to the phone. */
    static void beat(Context c) {
        Context app = c.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        long since = lastStepEl > 0 ? lastStepEl : sinceEl;
        boolean steps = stepsKnown(app), onWrist = !Boolean.FALSE.equals(EarService.worn);
        boolean s40 = steps && Boolean.TRUE.equals(EarService.worn) && now - since >= 40 * 60_000L;
        if (still40 == null || s40 != still40) still(app, s40);
        resend(app);
        EarService e = EarService.self;
        if (e != null && Link.hr(app) && steps && onWrist && now - since >= 10 * 60_000L && now - hrAtEl >= 13 * 60_000L && Pulse.allowed(app)) {
            hrAtEl = now;
            final boolean lying = s40; // (still for 40 minutes: resting or asleep; kept out of his "sitting" normal)
            e.measure(30_000L, bpm -> {
                if (bpm <= 0) return;
                try {
                    Link.send(app, Link.P_HEALTH, new JSONObject().put("type", "hr").put("bpm", bpm).put("still", true).put("long", lying)
                            .put("t", System.currentTimeMillis()).put("day_steps", today(app)));
                } catch (Exception ignored) {}
            });
        }
    }

    /** He took it off his wrist. */
    static void offWrist(Context c) { if (Boolean.TRUE.equals(still40)) still(c.getApplicationContext(), false); }

    private static void still(Context app, boolean on) {
        still40 = on;
        stillFrom = on ? EarService.steps : -1;
        try { Link.send(app, Link.P_HEALTH, new JSONObject().put("type", "still").put("on", on).put("day_steps", today(app))); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- a note on the watch itself

    static void note(Context c, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel("jarvis_body", "Jarvis ఆరోగ్యం", NotificationManager.IMPORTANCE_DEFAULT));
        Notification n = new Notification.Builder(c, "jarvis_body").setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(Notes.open(c, 30)).setAutoCancel(true).setTimeoutAfter(6 * 3600_000L).build();
        try { nm.notify(NOTE_ID, n); } catch (Exception ignored) {}
        Talk.buzz(c, 40, 80, 40);
    }
}
