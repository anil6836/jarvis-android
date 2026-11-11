package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 🔁 E (Anil, 12 Nov "A,B,C,D,E చేయి"), next to Habits (which offers music / a call / an app at its usual time each day):
 * Jarvis notices an everyday job HE asks Jarvis for at about the same time on many days
 * ("AC ఆన్ చేయి" around 10 pm, "వాతావరణం చెప్పు" around 7 am) and asks him once: "ఇది రోజూ HH:MM కి నేనే చేయనా?". Only his own
 * words to Jarvis that ran one of a few everyday jobs are noted (never his messages, calls or other apps); only on this phone,
 * out of backups, a month at most. Nothing is set up without his yes (the automation is added by the brain on his answer);
 * each habit is asked about once. Switch ⚙️ (on).
 */
final class HabitAuto {
    private HabitAuto() {}

    static final String PREFS = "jarvis_habit_auto";

    /** The everyday jobs worth repeating on a clock (no messages, calls, payments, alarms or anything that sends; music: Habits). */
    static final Set<String> TOOLS = new HashSet<>(java.util.Arrays.asList("smart_home", "phone_setting", "night_mode", "get_weather", "news",
            "local_news", "market_prices", "air_quality", "day_summary", "calendar_events", "bills_due", "steps_today"));

    static boolean on(Context c) { return new Prefs(c).sp.getBoolean("habit_suggest", true); }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** After one of his requests ran a job (Tools.execute, its result fine): noted with the time. */
    static synchronized void note(Context c, String tool, JSONObject args, String heard) {
        if (!TOOLS.contains(tool) || heard == null) return;
        String w = heard.trim();
        if (w.isEmpty() || w.startsWith("(") || w.startsWith("[") || w.length() > 120) return; // (Jarvis's own runs: automations, places, cards)
        try {
            JSONArray a = new JSONArray(sp(c).getString("log", "[]")), out = new JSONArray();
            long now = System.currentTimeMillis(), old = now - 30L * 86400000L;
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && o.optLong("t") > old) out.put(o); }
            out.put(new JSONObject().put("s", signature(tool, args)).put("t", now).put("w", w));
            while (out.length() > 400) out.remove(0);
            sp(c).edit().putString("log", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    static synchronized void clear(Context c) { sp(c).edit().clear().apply(); }

    /** From Proactive's regular check (only with his own-ideas switch on): at most one question a day, 9 am – 9 pm, never twice per habit. */
    static void tick(Context c, Prefs p, boolean hush) {
        if (hush || !on(c) || p.homeMode() || CarBox.on(c)) return;
        LocalDateTime now = LocalDateTime.now();
        if (now.getHour() < 9 || now.getHour() >= 21) return;
        SharedPreferences s = sp(c);
        if (now.toLocalDate().toString().equals(s.getString("asked_day", ""))) return;
        Set<String> asked = new HashSet<>(java.util.Arrays.asList(s.getString("asked", "").split("\n")));
        List<JSONObject> log = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(s.getString("log", "[]"));
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) log.add(a.optJSONObject(i));
        } catch (Exception ignored) {}
        Found f = find(log, System.currentTimeMillis(), ZoneId.systemDefault(), asked);
        if (f == null) return;
        String at = String.format(Locale.ENGLISH, "%02d:%02d", f.minute / 60, f.minute % 60);
        boolean made = Proactive.say(c, p.name() + ", గత " + f.days + " రోజులుగా దాదాపు " + at + " కి \"" + f.words + "\" అంటున్నారు.",
                "ఇది రోజూ " + at + " కి నేనే చేయనా?",
                " [habit: he asked Jarvis \"" + f.words + "\" at about " + at + " on " + f.days + " different days in the last two weeks. If he says yes "
                        + "(or names other days / another time), call automation action add trigger time at " + at + " (or his time) days daily (or his days) act do what \""
                        + f.words + "\" text \"(his yes)\", then say the rule back in one line. If he says no / వద్దు, say సరే and nothing else — Jarvis won't ask about it again.]");
        if (!made) return;
        asked.add(f.sig);
        asked.remove("");
        s.edit().putString("asked_day", now.toLocalDate().toString()).putString("asked", String.join("\n", asked)).apply();
    }

    // ================================================================ pure

    /** The job and what it was asked for, the same however he worded it: "smart_home|device=hall light|on=true". */
    static String signature(String tool, JSONObject args) {
        List<String> keys = new ArrayList<>();
        if (args != null) { java.util.Iterator<String> it = args.keys(); while (it.hasNext()) keys.add(it.next()); }
        java.util.Collections.sort(keys);
        StringBuilder b = new StringBuilder(tool);
        for (String k : keys) {
            if (k.equals("alexa_phrase") || k.equals("command") && args.has("device")) continue; // (wordings of the same thing)
            b.append('|').append(k).append('=').append(String.valueOf(args.opt(k)).trim().toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    static final class Found {
        String sig, words;
        int minute, days;
    }

    /**
     * A habit: the same job asked on 4+ different days in the last 14, each time within 45 minutes of its usual time (the
     * middle of them); the one on the most days. Never one already asked about. Null when none. Pure.
     */
    static Found find(List<JSONObject> log, long now, ZoneId zone, Set<String> asked) {
        Map<String, List<JSONObject>> by = new HashMap<>();
        long from = now - 14L * 86400000L;
        for (JSONObject o : log) {
            if (o.optLong("t") < from || o.optLong("t") > now) continue;
            String s = o.optString("s");
            if (s.isEmpty() || asked != null && asked.contains(s)) continue;
            by.computeIfAbsent(s, k -> new ArrayList<>()).add(o);
        }
        Found best = null;
        for (Map.Entry<String, List<JSONObject>> e : by.entrySet()) {
            List<Integer> mins = new ArrayList<>();
            for (JSONObject o : e.getValue()) { LocalDateTime t = at(o.optLong("t"), zone); mins.add(t.getHour() * 60 + t.getMinute()); }
            List<Integer> sorted = new ArrayList<>(mins);
            java.util.Collections.sort(sorted);
            int mid = sorted.get(sorted.size() / 2);
            Set<LocalDate> days = new HashSet<>();
            JSONObject last = null;
            for (int i = 0; i < mins.size(); i++) {
                if (Math.abs(mins.get(i) - mid) > 45) continue;
                JSONObject o = e.getValue().get(i);
                days.add(at(o.optLong("t"), zone).toLocalDate());
                if (last == null || o.optLong("t") > last.optLong("t")) last = o;
            }
            if (days.size() < 4 || best != null && days.size() <= best.days) continue;
            Found f = new Found();
            f.sig = e.getKey();
            f.words = last.optString("w");
            f.minute = Math.round(mid / 5f) * 5 % (24 * 60);
            f.days = days.size();
            best = f;
        }
        return best;
    }

    private static LocalDateTime at(long t, ZoneId z) { return LocalDateTime.ofInstant(Instant.ofEpochMilli(t), z); }
}
