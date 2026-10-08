package com.anil.jarvis.watch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * W33: timers on the watch itself: from the timer screen's quick buttons, or "5 నిమిషాల టైమర్" said to the watch (the
 * phone sends it here). When one ends the wrist vibrates like the alarm (no sound) until "ఆపు", or quiet after 3 minutes.
 * Kept over a restart of the watch (Boot puts them back).
 */
public class Timers extends BroadcastReceiver {
    static final String ACTION = "com.anil.jarvis.watch.TIMER_DONE";

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_watch_timers", Context.MODE_PRIVATE); }

    static synchronized List<JSONObject> running(Context c) {
        List<JSONObject> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optLong("at") > now - 60_000L) l.add(o);
            }
        } catch (Exception ignored) {}
        return l;
    }

    private static synchronized void save(Context c, List<JSONObject> l) {
        JSONArray a = new JSONArray();
        for (JSONObject o : l) a.put(o);
        sp(c).edit().putString("list", a.toString()).apply();
    }

    /** A new timer: seconds, and what it is for ("టైమర్", "పాలు"…). */
    static void start(Context c, int seconds, String label) {
        Context app = c.getApplicationContext();
        long at = System.currentTimeMillis() + seconds * 1000L;
        int id = (int) (System.nanoTime() & 0x3FFFFFFF);
        List<JSONObject> l = running(app);
        try { l.add(new JSONObject().put("id", id).put("at", at).put("secs", seconds).put("label", label == null || label.isEmpty() ? "టైమర్" : label)); } catch (Exception ignored) {}
        save(app, l);
        schedule(app, id, at, label);
        Talk.buzz(app, 30, 60, 30);
    }

    static void cancel(Context c, int id) {
        Context app = c.getApplicationContext();
        List<JSONObject> l = running(app);
        l.removeIf(o -> o.optInt("id") == id);
        save(app, l);
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pending(app, id, ""));
    }

    private static PendingIntent pending(Context c, int id, String label) {
        return PendingIntent.getBroadcast(c, id, new Intent(c, Timers.class).setAction(ACTION).putExtra("id", id).putExtra("label", label),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void schedule(Context c, int id, long at, String label) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = pending(c, id, label);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else {
                PendingIntent show = PendingIntent.getActivity(c, 7400, new Intent(c, Panel.class).putExtra(Panel.EXTRA_KIND, "timer")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
            }
        } catch (Exception e) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); } catch (Exception ignored) {}
        }
    }

    /** After the watch restarts: the ones still to come are set again (ones that passed meanwhile ring now). */
    static void restore(Context c) {
        long now = System.currentTimeMillis();
        for (JSONObject o : running(c)) {
            long at = o.optLong("at");
            schedule(c, o.optInt("id"), Math.max(at, now + 2000), o.optString("label"));
        }
    }

    @Override public void onReceive(Context c, Intent i) {
        if (!ACTION.equals(i.getAction())) return;
        int id = i.getIntExtra("id", 0);
        String label = "";
        for (JSONObject o : running(c)) if (o.optInt("id") == id) label = o.optString("label");
        if (label.isEmpty()) label = i.getStringExtra("label");
        List<JSONObject> l = running(c);
        l.removeIf(o -> o.optInt("id") == id);
        save(c, l);
        try {
            JSONObject o = new JSONObject().put("id", "t" + id).put("title", "⏱️ " + (label == null || label.isEmpty() ? "టైమర్" : label) + " అయిపోయింది")
                    .put("snooze", false).put("local", true);
            AlarmScreen.ring(c, o);
        } catch (Exception ignored) {}
    }

    /** "4:59" / "1:02:03". */
    static String left(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(java.util.Locale.ROOT, "%d:%02d", m, sec);
    }
}
