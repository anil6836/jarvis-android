package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Phase 4: what the watch's own sensors send (P_HEALTH): a walk ended or a km on the way (W31, said on the watch),
 * a heart-rate reading while he sits (HeartLog), still on his wrist for 40 minutes or not (W28), and its step count
 * for today (with each beat too).
 */
final class WatchHealth {
    private WatchHealth() {}

    static final String P_HEALTH = "/jarvis/health";
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_watch_health", Context.MODE_PRIVATE); }

    /** A message from the watch (any thread; the work goes off the main thread). */
    static void got(Context app, JSONObject o) {
        new Thread(() -> {
            try {
                daySteps(app, o);
                switch (o.optString("type")) {
                    case "hr": HeartLog.got(app, o.optInt("bpm"), o.optBoolean("still"), o.optBoolean("long"), o.optLong("t", System.currentTimeMillis())); break;
                    case "walk": walk(app, o); break;
                    case "still": HeartLog.still(app, o.optBoolean("on")); break;
                    default:
                }
            } catch (Exception ignored) {}
        }, "watch-health").start();
    }

    // ---------------------------------------------------------------- the watch's steps today

    /** Keeps the watch's count for today (the most seen), and a few days back for the energy estimate. */
    static void daySteps(Context c, JSONObject o) {
        int n = o.optInt("day_steps", -1);
        if (n < 0) return;
        String key = "d" + LocalDate.now();
        SharedPreferences s = sp(c);
        if (n <= s.getInt(key, -1)) return;
        SharedPreferences.Editor e = s.edit().putInt(key, n);
        for (String k : s.getAll().keySet()) // (only the last week kept)
            if (k.startsWith("d") && k.length() == 11 && k.substring(1).compareTo(LocalDate.now().minusDays(8).toString()) < 0) e.remove(k);
        e.apply();
    }

    /** The watch's steps on a day (-1: not known). */
    static int daySteps(Context c, LocalDate d) { return sp(c).getInt("d" + d, -1); }

    // ---------------------------------------------------------------- W31: walks

    private static void walk(Context app, JSONObject o) throws Exception {
        Wellness.useStride(app);
        long steps = o.optLong("steps"), secs = o.optLong("secs");
        boolean end = o.optBoolean("end");
        if (!WatchHub.walkOn(app)) return;
        if (o.optBoolean("late") && !end) return;
        String text;
        if (end) {
            long t = o.optLong("t", System.currentTimeMillis());
            if (o.optBoolean("late")) { // (a walk the watch couldn't send then: it showed it itself; only kept here)
                for (JSONObject w : Notes.list(app, Wellness.WALKS)) if (w.optLong("t") == t) return;
                Notes.add(app, Wellness.WALKS, new JSONObject().put("t", t).put("steps", steps).put("secs", secs)
                        .put("m", Math.round(steps * Wellness.stride)), 400);
                return;
            }
            Notes.add(app, Wellness.WALKS, new JSONObject().put("t", t).put("steps", steps).put("secs", secs)
                    .put("m", Math.round(steps * Wellness.stride)), 400);
            int day = o.optInt("day_steps", -1);
            text = "నడక: " + Wellness.walkLine(steps, secs) + "." + (day > steps ? " ఈరోజు మొత్తం " + Sums.num(day) + " అడుగులు." : "");
        } else {
            text = Wellness.kmLine(o.optInt("km"), secs);
        }
        tell(app, text, end);
    }

    /**
     * Said on the watch when he can hear it (not riding, not in a call / talk, not at night or in duty mode); otherwise a
     * quiet card on the watch. A km on the way is only a card while he can't be spoken to.
     */
    private static void tell(Context app, String text, boolean end) {
        Prefs p = new Prefs(app);
        int h = LocalTime.now().getHour();
        boolean speak = WatchHub.speakOn(app) && p.voiceReplies() && !WatchHub.riding(app) && !DutyMode.on(app) && !p.night()
                && !CallControl.busyWithCall() && !WatchHub.talking() && h >= 6 && h < 22 && WatchHub.watchHere(app);
        if (speak) { main.post(() -> WatchHub.say(app, text)); return; }
        try {
            JSONObject o = new JSONObject().put("id", end ? 4602 : 4606).put("kind", "info").put("title", "🚶 నడక").put("text", text);
            WatchHub.send(app, WatchAlerts.P_ALERT, o, null, false);
        } catch (Exception ignored) {}
    }
}
