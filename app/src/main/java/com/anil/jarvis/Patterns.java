package com.anil.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Jarvis learning his routines from the phone itself (not only from what he asked Jarvis): calls he makes to the
 * same person at about the same time, on duty days, home days or any day. Once in an evening at most, one new
 * routine is offered as an automation ("డ్యూటీ రోజుల్లో 9:50 కి అమ్మకి కాల్ చేస్తారు; 9:45 కి గుర్తు చేయనా?");
 * it is set only if he says yes, and the same routine is never offered twice. Everything is read on the phone.
 */
final class Patterns {
    private Patterns() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_patterns", Context.MODE_PRIVATE); }

    /** The evening look (from the regular check): at most one offer a day. */
    static void evening(Context c, Prefs p) {
        int h = LocalDateTime.now().getHour();
        if (h < 19 || h >= 21) return;
        String today = LocalDate.now().toString();
        if (today.equals(sp(c).getString("looked", ""))) return;
        sp(c).edit().putString("looked", today).apply();
        String[] offer = callRoutine(c);
        if (offer == null) return;
        sp(c).edit().putBoolean("told_" + offer[0], true).apply();
        Proactive.say(c, p.name() + ", " + offer[1], offer[2], " [suggestion: " + offer[3] + " if he says yes]");
    }

    private static String last10(String n) {
        String d = n == null ? "" : n.replaceAll("[^0-9]", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    /**
     * Calls he made (answered) in the last 4 weeks: a person called within ±30 minutes of the same time on 4+ days
     * (3+ when it is only on duty days). Returns {key, what Jarvis noticed, the question, the automation to add} or null.
     */
    static String[] callRoutine(Context c) {
        if (c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null;
        long since = System.currentTimeMillis() - 28 * 86400_000L;
        Map<String, List<long[]>> byNumber = new HashMap<>(); // number -> {epoch day, minute of day}
        Map<String, String> names = new HashMap<>();
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME},
                CallLog.Calls.DATE + " >= ?", new String[]{String.valueOf(since)}, CallLog.Calls.DATE + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                if (cur.getInt(3) != CallLog.Calls.OUTGOING_TYPE || cur.getLong(2) <= 0) continue;
                String k = last10(cur.getString(0));
                String name = cur.getString(4);
                if (k.length() < 10 || name == null || name.trim().isEmpty()) continue; // only saved contacts
                LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(cur.getLong(1)), ZoneId.systemDefault());
                byNumber.computeIfAbsent(k, x -> new ArrayList<>()).add(new long[]{t.toLocalDate().toEpochDay(), t.getHour() * 60L + t.getMinute()});
                names.put(k, name.trim());
            }
        } catch (Exception e) {
            return null;
        }
        Duty.Roster r = Duty.load(c);
        boolean duty = Duty.ready(r);
        for (Map.Entry<String, List<long[]>> e : byNumber.entrySet()) {
            List<long[]> calls = e.getValue();
            if (calls.size() < 3) continue;
            for (long[] anchor : calls) {
                List<Long> mins = new ArrayList<>();
                Set<Long> allDays = new HashSet<>(), dutyDays = new HashSet<>(), homeDays = new HashSet<>();
                for (long[] x : calls) {
                    if (Math.abs(x[1] - anchor[1]) > 30) continue;
                    if (!allDays.add(x[0])) continue; // one call a day counts
                    mins.add(x[1]);
                    if (duty) {
                        if (r.isOn(Duty.ME, LocalDate.ofEpochDay(x[0]))) dutyDays.add(x[0]); else homeDays.add(x[0]);
                    }
                }
                String kind;
                if (duty && dutyDays.size() >= 3 && homeDays.isEmpty()) kind = "duty";
                else if (duty && homeDays.size() >= 4 && dutyDays.isEmpty()) kind = "home";
                else if (allDays.size() >= 4) kind = "daily";
                else continue;
                Collections.sort(mins);
                long med = mins.get(mins.size() / 2), at = Math.max(0, (med / 5) * 5 - 5); // five minutes before, on a round time
                String key = e.getKey() + "_" + kind + "_" + (med / 60);
                if (sp(c).getBoolean("told_" + key, false)) continue;
                String who = names.get(e.getKey());
                String hhmm = String.format(Locale.ENGLISH, "%02d:%02d", at / 60, at % 60), when = String.format(Locale.ENGLISH, "%d:%02d", med / 60, med % 60);
                String days = kind.equals("duty") ? "డ్యూటీ రోజుల్లో" : kind.equals("home") ? "ఇంట్లో ఉండే రోజుల్లో" : "రోజూ";
                return new String[]{key,
                        days + " దాదాపు " + when + " కి " + who + " కి కాల్ చేస్తారని గమనించాను.",
                        hhmm + " కి గుర్తు చేయనా?",
                        "automation add trigger=time at=" + hhmm + " days=" + kind + " act=say what='" + who + " కి కాల్ చేసే టైమ్ అయింది.' text='"
                                + days + " " + hhmm + " కి " + who + " కి కాల్ గుర్తు'"};
            }
        }
        return null;
    }
}
