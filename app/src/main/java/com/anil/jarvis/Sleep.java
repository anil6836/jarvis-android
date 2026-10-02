package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Sleep, from the phone itself: the screen goes off and the phone lies still (no steps) until he unlocks it again.
 * 90 minutes or more is a sleep; a short look at the phone in between (under 30 minutes) joins the two parts.
 * Riding / walking with the phone in the pocket doesn't count (driving, or steps meanwhile). Kept on the phone.
 * Also the eye break: after 20 minutes of the screen on without a pause, "20 అడుగుల దూరం 20 సెకన్లు చూడండి".
 */
final class Sleep {
    private Sleep() {}

    private static final String KEY = "sleep_sessions";
    private static final long MIN = 60000L;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_sleep", Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- screen events (from the notification listener)

    static void screenOff(Context c) {
        Context app = c.getApplicationContext();
        stopEye();
        SharedPreferences s = sp(app);
        if (s.getBoolean("riding", false)) return; // in a pocket on the bike, not asleep
        long open = s.getLong("off_at", 0), now = System.currentTimeMillis();
        // the screen lit up for a moment with no unlock (a notification, a call, an alarm): the same quiet time goes on
        if (open > 0 && now - open < 14 * 60 * MIN) return;
        startWindow(app, now);
    }

    private static void startWindow(Context app, long now) {
        sp(app).edit().putLong("off_at", now).putFloat("off_steps", -1).apply();
        new Thread(() -> { // the step counter answers on the main thread: read it from here
            float s = Health.counter(app);
            if (sp(app).getLong("off_at", 0) == now) sp(app).edit().putFloat("off_steps", s).apply();
        }, "jarvis-sleep-off").start();
    }

    /** He unlocked the phone: the time it lay unused may have been sleep. */
    static void unlocked(Context c) {
        Context app = c.getApplicationContext();
        startEye(app);
        close(app, System.currentTimeMillis());
    }

    /** The quiet time ends now: kept as sleep if it was long and still (no steps, no ride). */
    private static void close(Context app, long now) {
        SharedPreferences s = sp(app);
        long off = s.getLong("off_at", 0);
        if (off == 0) return;
        float before = s.getFloat("off_steps", -1);
        s.edit().remove("off_at").apply();
        if (s.getBoolean("riding", false) || new Prefs(app).driving()) return;
        if (now - off < 20 * MIN || now - off > 16 * 60 * MIN) return; // too short, or a missed unlock long ago
        new Thread(() -> {
            float after = Health.counter(app);
            if (before >= 0 && after >= before && after - before > 300) return; // he was walking about, not asleep
            record(app, off, now);
        }, "jarvis-sleep-on").start();
    }

    /** A ride started (the quiet time before it may have been sleep; none during it) or ended (a new quiet time if the screen is off). */
    static void ride(Context c, boolean start) {
        Context app = c.getApplicationContext();
        long now = System.currentTimeMillis();
        if (start) {
            close(app, now);
            sp(app).edit().putBoolean("riding", true).apply();
        } else {
            sp(app).edit().putBoolean("riding", false).apply();
            android.os.PowerManager pm = app.getSystemService(android.os.PowerManager.class);
            if (pm != null && !pm.isInteractive()) startWindow(app, now);
        }
    }

    static synchronized void record(Context c, long start, long end) {
        try {
            List<JSONObject> l = Notes.list(c, KEY);
            JSONObject last = l.isEmpty() ? null : l.get(l.size() - 1);
            long minutes = (end - start) / MIN;
            // woke briefly in the night (a look at the phone) and slept on: the same sleep
            if (last != null && minutes >= 60 && start - last.optLong("end") >= 0 && start - last.optLong("end") <= 30 * MIN) {
                last.put("end", end).put("minutes", last.optLong("minutes") + minutes).put("breaks", last.optInt("breaks") + 1);
                Notes.save(c, KEY, l, 120);
                return;
            }
            if (minutes < 90) return;
            Notes.add(c, KEY, new JSONObject().put("start", start).put("end", end).put("minutes", minutes).put("breaks", 0), 120);
        } catch (Exception ignored) {}
    }

    /** Minutes of real sleep (sleeps of 2.5 hours or more) after this moment: for "has he rested since the duty?". */
    static long sleptSince(Context c, long since) {
        long m = 0;
        for (JSONObject o : Notes.list(c, KEY)) {
            if (o.optLong("minutes") < 150) continue; // a long sit with the phone down is not rest enough to count
            long s = Math.max(since, o.optLong("start")), e = o.optLong("end");
            if (e > s) m += Math.min(o.optLong("minutes"), (e - s) / MIN);
        }
        return m;
    }

    /** Sleeps in [from, to): {count, total minutes}. */
    static long[] between(Context c, long from, long to) {
        long n = 0, m = 0;
        for (JSONObject o : Notes.list(c, KEY)) {
            long e = o.optLong("end");
            if (e < from || e >= to) continue;
            n++;
            m += o.optLong("minutes");
        }
        return new long[]{n, m};
    }

    /** The last N days: each sleep with its times, the average a night, and the last one. */
    static JSONObject summary(Context c, int days) throws Exception {
        long from = Life.dayStart() - (Math.max(1, days) - 1) * 86400000L;
        SimpleDateFormat d = new SimpleDateFormat("EEE d MMM", Locale.ENGLISH), t = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        JSONArray a = new JSONArray();
        long total = 0;
        java.util.Set<String> nights = new java.util.HashSet<>();
        for (JSONObject o : Notes.list(c, KEY)) {
            long e = o.optLong("end");
            if (e < from) continue;
            long m = o.optLong("minutes");
            total += m;
            nights.add(d.format(new Date(e)));
            a.put(new JSONObject().put("woke", d.format(new Date(e))).put("from", t.format(new Date(o.optLong("start"))))
                    .put("to", t.format(new Date(e))).put("hours", Math.round(m / 6.0) / 10.0).put("woke_up_between", o.optInt("breaks")));
        }
        JSONObject r = new JSONObject().put("ok", true).put("sleeps", a);
        if (a.length() == 0) return r.put("note", "No sleep found yet: it is counted from when the phone lies unused (screen off, no steps) until he unlocks it.");
        r.put("average_hours_a_day", Math.round(total / 6.0 / Math.max(1, nights.size())) / 10.0).put("last", a.getJSONObject(a.length() - 1));
        long sinceDuty = Duty.minutesSinceDuty(c);
        if (sinceDuty >= 0) r.put("since_duty_ended_hours", Math.round(sinceDuty / 6.0) / 10.0)
                .put("slept_since_duty_hours", Math.round(sleptSince(c, System.currentTimeMillis() - sinceDuty * MIN) / 6.0) / 10.0);
        return r.put("note", "Estimated from phone use (screen off and still), not a medical sleep test.");
    }

    // ---------------------------------------------------------------- eye break (20-20-20)

    static int eyeMinutes(Context c) { return sp(c).getInt("eye_minutes", 20); }

    static void setEye(Context c, int minutes) {
        sp(c).edit().putInt("eye_minutes", minutes <= 0 ? 0 : Math.max(10, Math.min(120, minutes))).apply();
        Context app = c.getApplicationContext();
        if (minutes <= 0) main.post(Sleep::stopEye); else startEye(app);
    }

    private static Runnable eye;

    private static void stopEye() {
        if (eye != null) main.removeCallbacks(eye);
        eye = null;
    }

    private static void startEye(Context app) {
        main.post(() -> {
            stopEye();
            int m = eyeMinutes(app);
            if (m <= 0) return;
            eye = new Runnable() {
                @Override public void run() {
                    if (eye != this) return;
                    android.os.PowerManager pm = app.getSystemService(android.os.PowerManager.class);
                    android.app.KeyguardManager km = app.getSystemService(android.app.KeyguardManager.class);
                    if (pm == null || !pm.isInteractive() || (km != null && km.isKeyguardLocked())) { eye = null; return; }
                    if (!new Prefs(app).driving() && !CallControl.busyWithCall() && !MainActivity.busyTalking() && !SoundService.prayerOn) remind(app);
                    main.postDelayed(this, eyeMinutes(app) * MIN);
                }
            };
            main.postDelayed(eye, m * MIN);
        });
    }

    private static void remind(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel("jarvis_eyes", "కళ్లకు బ్రేక్", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 120});
            nm.createNotificationChannel(ch);
            nm.notify(211, new Notification.Builder(c, "jarvis_eyes").setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("👀 కళ్లకు చిన్న బ్రేక్")
                    .setContentText(eyeMinutes(c) + " నిమిషాలుగా స్క్రీన్ చూస్తున్నారు. 20 అడుగుల దూరంలో ఉన్నదాన్ని 20 సెకన్లు చూడండి, కళ్లు రెప్పవేయండి.")
                    .setStyle(new Notification.BigTextStyle().bigText(eyeMinutes(c) + " నిమిషాలుగా స్క్రీన్ చూస్తున్నారు. 20 అడుగుల దూరంలో ఉన్నదాన్ని 20 సెకన్లు చూడండి, కళ్లు రెప్పవేయండి."))
                    .setTimeoutAfter(30000).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }
}
