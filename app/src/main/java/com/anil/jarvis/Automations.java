package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * His own "when this, then that" rules, set by voice: "every Monday at 8 tell me the cotton and gold prices",
 * "tell me when the bike is below 20%", "when I leave for duty, tell me if it will rain".
 * Triggers: a time (daily / weekdays / duty days / home days / once), before leaving for duty, after a duty,
 * the bike's or the phone's battery below a %, rain in the next hours. Optional condition: only if rain is
 * expected. Action: say a line, or have Jarvis do it (the request goes through Jarvis like his own words, so
 * sending, calling and paying still wait for his yes).
 */
final class Automations {
    private Automations() {}

    static final String KEY = "automations";
    static final String ACTION_FIRE = "com.anil.jarvis.AUTOMATION";
    private static final String[] DAY = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};

    static List<JSONObject> all(Context c) { return Notes.list(c, KEY); }

    /** Checks and saves a rule; returns an error text, or null when it is fine. */
    static String add(Context c, JSONObject r) throws Exception {
        String trig = r.optString("trigger");
        if (!trig.matches("time|before_duty|after_duty|bike_below|phone_below|rain")) return "trigger must be time, before_duty, after_duty, bike_below, phone_below or rain";
        if (trig.equals("time") && !r.optString("at").matches("\\d{1,2}:\\d{2}")) return "time needs at = HH:mm";
        if (trig.endsWith("_below") && (r.optInt("percent", -1) <= 0 || r.optInt("percent") >= 100)) return "needs percent (1-99)";
        if (r.optString("what").trim().isEmpty()) return "needs what to say or do";
        if (trig.endsWith("_duty") && !Duty.ready(Duty.load(c))) return "his duty calendar is not set up yet (duty setup first)";
        String days = r.optString("days", "daily").trim().toLowerCase(Locale.ROOT);
        r.put("days", days.isEmpty() ? "daily" : days);
        r.put("id", Notes.id("au")).put("on", true).put("made", System.currentTimeMillis()).put("last", 0L);
        Notes.add(c, KEY, r, 60);
        schedule(c);
        return null;
    }

    static boolean remove(Context c, String id) {
        boolean ok = Notes.remove(c, KEY, "id", id);
        schedule(c);
        return ok;
    }

    static boolean setOn(Context c, String id, boolean on) {
        boolean found = false;
        for (JSONObject r : all(c)) {
            if (!r.optString("id").equals(id)) continue;
            try { r.put("on", on); Notes.update(c, KEY, r); found = true; } catch (Exception ignored) {}
        }
        schedule(c);
        return found;
    }

    /** A line for him: what it does and when. */
    static String line(JSONObject r) {
        String t = r.optString("trigger"), d = r.optString("days");
        String when;
        switch (t) {
            case "time": when = r.optString("at") + " (" + d + ")"; break;
            case "before_duty": when = r.optInt("minutes", 30) + " min before leaving for duty"; break;
            case "after_duty": when = r.optInt("minutes", 0) + " min after a duty ends"; break;
            case "bike_below": when = "bike below " + r.optInt("percent") + "%"; break;
            case "phone_below": when = "phone battery below " + r.optInt("percent") + "%"; break;
            default: when = "rain expected in the next " + r.optInt("hours", 2) + " h"; break;
        }
        return when + (r.optBoolean("if_rain") ? ", only if rain is expected" : "") + " -> " + r.optString("action", "say") + ": " + r.optString("what")
                + (r.optBoolean("on", true) ? "" : " (paused)");
    }

    // ---------------------------------------------------------------- the time rules: exact alarms

    private static PendingIntent pi(Context c, String id) {
        return PendingIntent.getBroadcast(c, ("au" + id).hashCode(), new Intent(c, AlarmReceiver.class).setAction(ACTION_FIRE).putExtra("id", id)
                .setData(android.net.Uri.parse("jarvis-auto://" + id)), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The next moment a time rule's clock comes round (its day check happens when it fires). */
    private static LocalDateTime nextTime(JSONObject r, LocalDateTime now) {
        String[] hm = r.optString("at", "08:00").split(":");
        LocalTime at = LocalTime.of(Math.min(23, Integer.parseInt(hm[0])), Math.min(59, Integer.parseInt(hm[1])));
        String d = r.optString("days");
        if (d.startsWith("once:")) {
            try {
                LocalDateTime t = LocalDate.parse(d.substring(5).trim()).atTime(at);
                return t.isAfter(now) ? t : null;
            } catch (Exception e) { return null; }
        }
        for (int k = 0; k < 8; k++) {
            LocalDateTime t = now.toLocalDate().plusDays(k).atTime(at);
            if (!t.isAfter(now)) continue;
            if (weekdayOk(d, t.getDayOfWeek())) return t;
        }
        return null;
    }

    private static boolean weekdayOk(String days, DayOfWeek w) {
        if (!days.matches(".*\\b(mon|tue|wed|thu|fri|sat|sun)\\b.*")) return true; // daily / duty / home: any weekday
        return days.contains(DAY[w.getValue() - 1]);
    }

    /** Duty days / home days, as his calendar says on that date. */
    private static boolean dayKindOk(Context c, String days, LocalDate d) {
        boolean duty = days.contains("duty"), home = days.contains("home");
        if (!duty && !home) return true;
        Duty.Roster r = Duty.load(c);
        if (!Duty.ready(r)) return false;
        boolean on = r.isOn(Duty.ME, d);
        return duty ? on : !on;
    }

    /** Sets the next alarm of every time rule that is on (after a change, a restart and each firing). */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        LocalDateTime now = LocalDateTime.now();
        for (JSONObject r : all(c)) {
            if (!"time".equals(r.optString("trigger"))) continue;
            PendingIntent p = pi(c, r.optString("id"));
            am.cancel(p);
            if (!r.optBoolean("on", true)) continue;
            LocalDateTime t = nextTime(r, now);
            if (t == null) continue;
            long when = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            try {
                if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
            } catch (Exception e) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
            }
        }
    }

    /** A time rule's alarm (on a worker thread: the rain check uses the internet). */
    static void fired(Context c, String id) {
        for (JSONObject r : all(c)) {
            if (!r.optString("id").equals(id) || !r.optBoolean("on", true)) continue;
            String d = r.optString("days");
            if (dayKindOk(c, d, LocalDate.now())) act(c, r);
            if (d.startsWith("once:")) { try { r.put("on", false); Notes.update(c, KEY, r); } catch (Exception ignored) {} }
        }
        schedule(c);
    }

    // ---------------------------------------------------------------- the other rules: checked about every 15 minutes

    static void tick(Context c) {
        List<JSONObject> rules = all(c);
        if (rules.isEmpty()) return;
        long now = System.currentTimeMillis();
        LocalDateTime ldt = LocalDateTime.now();
        for (JSONObject r : rules) {
            if (!r.optBoolean("on", true)) continue;
            try {
                switch (r.optString("trigger")) {
                    case "before_duty": {
                        LocalDateTime[] d = Duty.nowOrNext(c);
                        if (d == null || !ldt.isBefore(d[0])) break;
                        LocalDateTime leave = d[0].minusMinutes(Duty.load(c).leaveBefore);
                        LocalDateTime from = leave.minusMinutes(r.optInt("minutes", 30));
                        long fromMs = from.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                        if (ldt.isBefore(from) || !ldt.isBefore(from.plusMinutes(25)) || r.optLong("last") >= fromMs) break;
                        mark(c, r, now);
                        act(c, r);
                        break;
                    }
                    case "after_duty": {
                        long since = Duty.minutesSinceDuty(c);
                        int m = r.optInt("minutes", 0);
                        if (since < m || since > m + 30 || now - r.optLong("last") < 12 * 3600_000L) break;
                        mark(c, r, now);
                        act(c, r);
                        break;
                    }
                    case "bike_below": {
                        int pct = Bike.estimatePct(c);
                        if (pct < 0 || pct >= r.optInt("percent")) { if (pct >= 0 && r.optLong("last") > 0) mark(c, r, 0); break; } // charged again: ready for next time
                        if (r.optLong("last") > 0) break;
                        mark(c, r, now);
                        act(c, r);
                        break;
                    }
                    case "phone_below": {
                        android.os.BatteryManager bm = c.getSystemService(android.os.BatteryManager.class);
                        if (bm == null) break;
                        int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                        if (bm.isCharging() || pct >= r.optInt("percent")) { if (r.optLong("last") > 0) mark(c, r, 0); break; }
                        if (pct <= 0 || r.optLong("last") > 0) break;
                        mark(c, r, now);
                        act(c, r);
                        break;
                    }
                    case "rain": {
                        if (now - r.optLong("last") < 6 * 3600_000L || ldt.getHour() < 6 || ldt.getHour() >= 22) break;
                        if (now - r.optLong("checked") < 55 * 60_000L) break; // the forecast once an hour
                        r.put("checked", now);
                        Notes.update(c, KEY, r);
                        if (!rainSoon(c, r.optInt("hours", 2))) break;
                        mark(c, r, now);
                        act(c, r);
                        break;
                    }
                    default: break;
                }
            } catch (Exception ignored) {}
        }
    }

    private static void mark(Context c, JSONObject r, long t) throws Exception {
        r.put("last", t);
        Notes.update(c, KEY, r);
    }

    /** Rain (60% or more, or 1 mm) where he is in the next hours; false when it can't be told. */
    private static boolean rainSoon(Context c, int hours) {
        Location l = Tools.lastLocation(c);
        if (l == null) return false;
        double[] w = Duty.rain(l.getLatitude(), l.getLongitude(), LocalDateTime.now(), LocalDateTime.now().plusHours(Math.max(1, hours)));
        return w != null && (w[0] >= 60 || w[1] >= 1);
    }

    /** Says it, or has Jarvis do it; with if_rain, only when rain is expected in the next 3 hours. */
    private static void act(Context c, JSONObject r) {
        if (r.optBoolean("if_rain") && !rainSoon(c, 3)) return;
        String what = r.optString("what");
        Prefs p = new Prefs(c);
        if ("do".equals(r.optString("action"))) {
            Proactive.run(c, "(" + p.name() + " పెట్టిన ఆటోమేషన్) " + what);
            return;
        }
        Reminders.notify(c, "⚙️ Jarvis ఆటోమేషన్", what, ("au" + r.optString("id")).hashCode());
        if (!p.night()) Announcer.say(c, p.name() + ", " + what);
    }

    static JSONArray listJson(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject r : all(c)) a.put(new JSONObject().put("id", r.optString("id")).put("rule", line(r)).put("his_words", r.optString("text")));
        return a;
    }
}
