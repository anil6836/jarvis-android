package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;

/**
 * V14: a short caring word at the right moment, like a companion would: once when a duty ends ("ఇంటికి జాగ్రత్తగా
 * వెళ్ళండి"), once after a long ride ("నీళ్లు తాగండి"). No question, nothing to answer, never twice for the same duty or
 * ride, nothing at night / in a call / while he rests. His switch in Settings → రోజువారీ హెచ్చరికలు.
 */
final class Companion {
    private Companion() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_companion", Context.MODE_PRIVATE); }

    static boolean on(Context c) { return new Prefs(c).sp.getBoolean("companion", true); }

    static void tick(Context c, Prefs p, boolean hush) {
        if (hush || !on(c) || MainActivity.busyTalking()) return;
        try { afterDuty(c, p); } catch (Throwable ignored) {}
        try { afterRide(c, p); } catch (Throwable ignored) {}
    }

    /** 5 to 90 minutes after a duty ended, once per duty. */
    private static void afterDuty(Context c, Prefs p) {
        long m = Duty.minutesSinceDuty(c);
        if (m < 5 || m > 90) return;
        String key = "duty_" + java.time.LocalDateTime.now().minusMinutes(m).toLocalDate(); // (one word per duty: the day it ended)
        if (sp(c).contains(key)) return;
        sp(c).edit().putLong(key, System.currentTimeMillis()).apply();
        Proactive.say(c, dutyLine(p.name(), Calendar.getInstance().get(Calendar.HOUR_OF_DAY), java.time.LocalDate.now().getDayOfYear()), null, null);
    }

    /** Within 45 minutes after a ride of an hour or more, once per ride. */
    private static void afterRide(Context c, Prefs p) {
        long now = System.currentTimeMillis();
        List<JSONObject> rides = Bike.list(c, "rides", now - 4 * 3600_000L);
        if (rides.isEmpty() || Bike.riding(c)) return;
        JSONObject r = rides.get(rides.size() - 1);
        long end = r.optLong("end"), start = r.optLong("start");
        int minutes = r.optInt("minutes");
        if (minutes < 60 || now - end > 45 * 60_000L) return;
        String key = "ride_" + start;
        if (sp(c).contains(key)) return;
        sp(c).edit().putLong(key, now).apply();
        Proactive.say(c, rideLine(p.name(), minutes, r.optDouble("km"), Calendar.getInstance().get(Calendar.HOUR_OF_DAY), (int) (start % 7)), null, null);
    }

    /** (pure) the word after a duty: by the hour, with a little variety so it isn't the same line every time. */
    static String dutyLine(String name, int hour, int seed) {
        String[] lines = hour >= 20 || hour < 5
                ? new String[]{name + ", డ్యూటీ అయిపోయింది. రాత్రి రోడ్డు, నెమ్మదిగా జాగ్రత్తగా ఇంటికి వెళ్ళండి; వచ్చాక బాగా పడుకోండి.",
                        name + ", ఇంత రాత్రి డ్యూటీ అయ్యింది. బండి జాగ్రత్త; ఇంటికి వెళ్ళగానే కాస్త తిని పడుకోండి."}
                : hour < 11
                ? new String[]{name + ", డ్యూటీ అయిపోయింది. ఇంటికి జాగ్రత్తగా వెళ్ళండి; ఈరోజు కాసేపు విశ్రాంతి తీసుకోండి.",
                        name + ", పొద్దున డ్యూటీ ముగిసింది. ముందు టిఫిన్, తర్వాత ఒక కునుకు: ఈరోజు పెద్ద పనులు వద్దు."}
                : new String[]{name + ", డ్యూటీ అయిపోయింది. జాగ్రత్తగా ఇంటికి వెళ్ళండి; నీళ్లు తాగి కాసేపు కూర్చోండి.",
                        name + ", ఈ డ్యూటీ కూడా ముగిసింది. ఇంటి దారిలో బండి జాగ్రత్త; వచ్చాక విశ్రాంతి."};
        return lines[Math.abs(seed) % lines.length];
    }

    /** (pure) the word after a long ride. */
    static String rideLine(String name, int minutes, double km, int hour, int seed) {
        String ride = Math.round(km) > 0 ? Math.round(km) + " కి.మీ, " + Status.hours(minutes) : Status.hours(minutes);
        String[] lines = hour >= 11 && hour < 17
                ? new String[]{name + ", పెద్ద రైడ్ (" + ride + ") అయింది. ఎండలో వచ్చారు: నీళ్లు తాగి, కాసేపు నీడలో కూర్చోండి.",
                        name + ", " + ride + " రైడ్ ముగిసింది. ముందు నీళ్లు, తర్వాత కాళ్ళు కాస్త సాగదీయండి."}
                : new String[]{name + ", పెద్ద రైడ్ (" + ride + ") అయింది. కాస్త నీళ్లు తాగి, భుజాలు, కాళ్ళు సాగదీయండి.",
                        name + ", " + ride + " రైడ్ ముగిసింది. బండి బాగా నడిపారు; ఇప్పుడు కాసేపు విశ్రాంతి."};
        return lines[Math.abs(seed) % lines.length];
    }
}
