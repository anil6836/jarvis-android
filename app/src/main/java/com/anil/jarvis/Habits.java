package com.anil.jarvis;

import android.content.Context;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Learns Anil's routines from what he asks Jarvis to do (music, calls, apps) and when.
 * If he did the same thing around this time on 3+ of the last 14 days, and not yet today,
 * Jarvis offers it.
 */
final class Habits {
    private Habits() {}

    private static final String KEY = "habits";

    static void log(Context c, String kind, String value, String detail) {
        if (value == null || value.trim().isEmpty()) return;
        try {
            Notes.add(c, KEY, new JSONObject().put("k", kind).put("v", value.trim()).put("d", detail == null ? "" : detail)
                    .put("t", System.currentTimeMillis()), 600);
        } catch (Exception ignored) {}
    }

    private static int minuteOfDay(long t) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(t);
        return k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE);
    }

    private static int day(long t) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(t);
        return k.get(Calendar.YEAR) * 1000 + k.get(Calendar.DAY_OF_YEAR);
    }

    /** A habit due around now, or null: {key, text, question, action}. */
    static JSONObject suggestion(Context c, Calendar now) {
        List<JSONObject> all = Notes.list(c, KEY);
        long t0 = now.getTimeInMillis();
        int nowMin = minuteOfDay(t0), today = day(t0);
        Map<String, Set<Integer>> days = new HashMap<>();
        Map<String, JSONObject> last = new HashMap<>();
        Set<String> doneToday = new HashSet<>();
        for (JSONObject o : all) {
            long t = o.optLong("t");
            String key = o.optString("k") + ":" + o.optString("v").toLowerCase();
            if (day(t) == today) { doneToday.add(key); continue; }
            if (t0 - t > 14 * 86400000L) continue;
            if (Math.abs(minuteOfDay(t) - nowMin) > 40) continue;
            days.computeIfAbsent(key, x -> new HashSet<>()).add(day(t));
            last.put(key, o);
        }
        for (Map.Entry<String, Set<Integer>> e : days.entrySet()) {
            if (e.getValue().size() < 3 || doneToday.contains(e.getKey())) continue;
            JSONObject o = last.get(e.getKey());
            String kind = o.optString("k"), v = o.optString("v"), d = o.optString("d");
            try {
                JSONObject s = new JSONObject().put("key", e.getKey());
                switch (kind) {
                    case "music":
                        return s.put("text", "రోజూ ఈ సమయానికి " + v + " లో పాటలు వింటారు.")
                                .put("question", "పెట్టమంటారా?")
                                .put("action", "play_youtube app=" + v + (d.isEmpty() ? "" : " query=" + d));
                    case "call":
                        return s.put("text", "సాధారణంగా ఈ టైమ్‌కి " + v + " కి కాల్ చేస్తారు.")
                                .put("question", "కాల్ చేయమంటారా?").put("action", "call_contact who=" + v);
                    case "app":
                        return s.put("text", "సాధారణంగా ఈ టైమ్‌కి " + v + " తెరుస్తారు.")
                                .put("question", "తెరవమంటారా?").put("action", "open_app app=" + v);
                    default:
                        break;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }
}
