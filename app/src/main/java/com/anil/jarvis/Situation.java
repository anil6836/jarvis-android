package com.anil.jarvis;

import android.content.Context;
import android.location.Location;

import org.json.JSONObject;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * What is going on with Anil right now, from what the phone already knows (nothing is fetched): duty, where he is,
 * the bike's charge, the phone's battery, the next alarm and reminder, sleep after duty, and how he felt lately.
 * Added to every question so Jarvis answers for his real situation. Each part is skipped quietly if it is unknown.
 */
final class Situation {
    private Situation() {}

    static final String MOODS = "his_moods";
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    static String of(Context c) {
        StringBuilder s = new StringBuilder();
        LocalDateTime now = LocalDateTime.now();
        try { duty(c, now, s); } catch (Throwable ignored) {}
        try { place(c, s); } catch (Throwable ignored) {}
        try { bike(c, s); } catch (Throwable ignored) {}
        try { phone(c, s); } catch (Throwable ignored) {}
        try { next(c, now, s); } catch (Throwable ignored) {}
        try { weather(c, s); } catch (Throwable ignored) {}
        try { mood(c, s); } catch (Throwable ignored) {}
        try { sight(s); } catch (Throwable ignored) {}
        return s.toString();
    }

    private static String inHours(Duration d) {
        long m = Math.max(0, d.toMinutes());
        return m < 90 ? m + " min" : m < 48 * 60 ? (m / 60) + " h" : (m / 1440) + " days";
    }

    /** On duty now (until when), or at home (the next duty, and rest since the last one). */
    private static void duty(Context c, LocalDateTime now, StringBuilder s) {
        if (!Duty.ready(Duty.load(c))) return;
        LocalDateTime[] d = Duty.nowOrNext(c);
        if (d != null && !now.isBefore(d[0])) {
            s.append("- Duty: ON DUTY now (").append(Duration.between(d[0], d[1]).toHours()).append("-hour duty, ends ")
                    .append(d[1].format(DAY_TIME)).append(", in ").append(inHours(Duration.between(now, d[1]))).append(").\n");
            return;
        }
        LocalDateTime nextStart = d == null ? null : d[0];
        s.append("- Duty: off duty (at home days)");
        if (nextStart != null) s.append("; next duty starts ").append(nextStart.format(DAY_TIME)).append(" (in ").append(inHours(Duration.between(now, nextStart))).append(")");
        long since = Duty.minutesSinceDuty(c);
        if (since >= 0 && since < 36 * 60) {
            long slept = Sleep.sleptSince(c, System.currentTimeMillis() - since * 60_000L);
            s.append("; came off duty ").append(since / 60).append(" h ago, slept about ").append(slept / 60).append(" h ").append(slept % 60).append(" min since");
        }
        s.append(".\n");
    }

    /** At one of his saved places (home, duty, ...), riding, or elsewhere; only from a fix of the last 30 minutes. */
    private static void place(Context c, StringBuilder s) {
        Prefs p = new Prefs(c);
        boolean moving = Bike.riding(c) || DriveService.running || p.driving();
        Location l = Tools.lastLocation(c);
        String at = null;
        if (l != null && System.currentTimeMillis() - l.getTime() < 30 * 60_000L) {
            double best = 300;
            for (JSONObject pl : Places.all(c)) {
                if (!pl.has("lat")) continue;
                double d = Drive.meters(pl.optDouble("lat"), pl.optDouble("lon"), l.getLatitude(), l.getLongitude());
                if (d < best) { best = d; at = pl.optString("name"); }
            }
        }
        if (moving) s.append("- Where: riding / driving now").append(at == null ? "" : " (near " + at + ")").append(".\n");
        else if (at != null) s.append("- Where: at his saved place '").append(at).append("'.\n");
    }

    private static void bike(Context c, StringBuilder s) {
        JSONObject ch = Bike.charging(c);
        if (ch != null && !ch.optBoolean("logged")) {
            s.append("- Bike: charging now (from ").append(ch.optInt("from")).append("%), about ").append(ch.optInt("to")).append("% at ")
                    .append(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ch.optLong("eta")), ZoneId.systemDefault()).format(TIME)).append(".\n");
            return;
        }
        int pct = Bike.estimatePct(c);
        if (pct >= 0) s.append("- Bike: battery about ").append(pct).append("% (estimated from his last charge and rides since; about ")
                .append(Math.round(pct / 100.0 * Bike.fullRangeKm(new Prefs(c)))).append(" km).\n");
    }

    private static void phone(Context c, StringBuilder s) {
        android.os.BatteryManager bm = c.getSystemService(android.os.BatteryManager.class);
        if (bm == null) return;
        int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (pct <= 0) return;
        s.append("- Phone battery: ").append(pct).append("%").append(bm.isCharging() ? ", charging" : "").append(".\n");
    }

    /** The next alarm (any app) and his next Jarvis reminder, within a day. */
    private static void next(Context c, LocalDateTime now, StringBuilder s) {
        android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
        android.app.AlarmManager.AlarmClockInfo a = am == null ? null : am.getNextAlarmClock();
        if (a != null && a.getTriggerTime() - System.currentTimeMillis() < 24 * 3600_000L)
            s.append("- Next alarm: ").append(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(a.getTriggerTime()), ZoneId.systemDefault()).format(DAY_TIME)).append(".\n");
        JSONObject soonest = null;
        long t = System.currentTimeMillis();
        for (JSONObject r : Store.get(c).reminders()) {
            long at = r.optLong("at");
            if (r.optBoolean("done") || at <= t || at - t > 24 * 3600_000L) continue;
            if (soonest == null || at < soonest.optLong("at")) soonest = r;
        }
        if (soonest != null) {
            String text = soonest.optString("text");
            if (text.length() > 60) text = text.substring(0, 60) + "…";
            s.append("- Next reminder: ").append(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(soonest.optLong("at")), ZoneId.systemDefault()).format(TIME))
                    .append(" - ").append(text).append(".\n");
        }
        JSONObject stop = StopAlarm.current(c);
        if (stop != null) s.append("- Stop alarm on for ").append(stop.optString("place")).append(".\n");
    }

    /** How he said he felt in the last 3 days (Jarvis noted it with jarvis_mood). */
    /** The weather the status tiles fetched (if under 3 hours old). */
    private static void weather(Context c, StringBuilder s) {
        android.content.SharedPreferences sp = c.getSharedPreferences("hud_dashboard", Context.MODE_PRIVATE);
        long at = sp.getLong("w_at", 0);
        if (at == 0 || System.currentTimeMillis() - at > 3 * 3600_000L) return;
        s.append("- Weather where he is (").append((System.currentTimeMillis() - at) / 60_000).append(" min ago): ")
                .append(Math.round(sp.getFloat("w_temp", 0))).append("°C, ").append(HudDashboard.sky(sp.getInt("w_code", -1))).append(".\n");
    }

    /** Who the front camera sees in front of the phone right now (people he introduced are named). */
    private static void sight(StringBuilder s) {
        String who = FaceSight.whoNow();
        if (!who.isEmpty()) s.append("- In front of the phone now (front camera): ").append(who).append(".\n");
    }

    private static void mood(Context c, StringBuilder s) {
        List<JSONObject> l = Notes.list(c, MOODS);
        if (l.isEmpty()) return;
        JSONObject m = l.get(l.size() - 1);
        long t = m.optLong("t");
        if (System.currentTimeMillis() - t > 3 * 86400_000L) return;
        s.append("- How he felt lately: ").append(m.optString("feeling")).append(m.optString("why").isEmpty() ? "" : " (" + m.optString("why") + ")")
                .append(", ").append(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), ZoneId.systemDefault()).format(DAY_TIME)).append(".\n");
    }
}
