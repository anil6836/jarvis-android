package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Every morning at his time: today's Bible verse (Telugu IRV) read out with a two-line meaning.
 * On his church day: "చర్చికి టైమ్ అవుతోంది" 45 minutes before the service. Quiet times get a notification only.
 */
final class Faith {
    private Faith() {}

    static final String ACTION_VERSE = "com.anil.jarvis.FAITH_VERSE", ACTION_CHURCH = "com.anil.jarvis.FAITH_CHURCH";
    private static final String[] DAYS_TE = {"సోమవారం", "మంగళవారం", "బుధవారం", "గురువారం", "శుక్రవారం", "శనివారం", "ఆదివారం"};

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_faith", Context.MODE_PRIVATE); }

    /** "07:00" or "" (off). */
    static String verseTime(Context c) { return sp(c).getString("verse_time", "07:00"); } // on by default (he asked for it)

    /** {day 1-7 (Mon..Sun), "HH:mm"} or null. */
    static String church(Context c) { return sp(c).getString("church", ""); }

    static JSONObject set(Context c, String morningVerse, String church) throws Exception {
        SharedPreferences.Editor e = sp(c).edit();
        if (morningVerse != null) {
            String m = morningVerse.trim().toLowerCase(Locale.ROOT);
            if (m.equals("off") || m.equals("no") || m.contains("వద్దు")) e.putString("verse_time", "");
            else {
                String t = hhmm(m);
                if (t == null) return new JSONObject().put("ok", false).put("error", "bad_time").put("message", "Morning verse time as HH:mm, e.g. 07:00.");
                e.putString("verse_time", t);
            }
        }
        if (church != null) {
            String ch = church.trim().toLowerCase(Locale.ROOT);
            if (ch.equals("off") || ch.contains("వద్దు")) e.putString("church", "");
            else {
                int day = 7;
                String[] en = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};
                for (int i = 0; i < 7; i++) if (ch.contains(en[i]) || ch.contains(DAYS_TE[i].substring(0, 2))) day = i + 1;
                String t = hhmm(ch.replaceAll("[^0-9:.]", " ").trim());
                if (t == null) return new JSONObject().put("ok", false).put("error", "bad_time").put("message", "Church service day and time, e.g. 'Sunday 09:00'.");
                e.putString("church", day + " " + t);
            }
        }
        e.apply();
        schedule(c);
        return status(c);
    }

    static JSONObject status(Context c) throws Exception {
        JSONObject o = new JSONObject().put("ok", true).put("morning_verse", verseTime(c).isEmpty() ? "off" : verseTime(c));
        String ch = church(c);
        if (ch.isEmpty()) o.put("church", "off");
        else {
            String[] p = ch.split(" ");
            o.put("church", DAYS_TE[Integer.parseInt(p[0]) - 1] + " " + p[1]).put("church_reminder", "45 minutes before");
        }
        return o;
    }

    /** "7", "7:30", "07.30", "19:00" -> "HH:mm"; null if not a time. */
    private static String hhmm(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})(?:[:.](\\d{2}))?").matcher(s);
        if (!m.find()) return null;
        int h = Integer.parseInt(m.group(1)), mi = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (h > 23 || mi > 59) return null;
        return String.format(Locale.ENGLISH, "%02d:%02d", h, mi);
    }

    // ---------------------------------------------------------------- alarms

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent verse = pi(c, ACTION_VERSE, 161), church = pi(c, ACTION_CHURCH, 162);
        am.cancel(verse);
        am.cancel(church);
        LocalDateTime now = LocalDateTime.now();
        String v = verseTime(c);
        if (!v.isEmpty()) {
            LocalDateTime t = LocalDate.now().atTime(LocalTime.parse(v));
            if (!t.isAfter(now)) t = t.plusDays(1);
            at(am, t, verse);
        }
        String ch = church(c);
        if (!ch.isEmpty()) {
            String[] p = ch.split(" ");
            DayOfWeek d = DayOfWeek.of(Integer.parseInt(p[0]));
            LocalTime service = LocalTime.parse(p[1]);
            for (int k = 0; k < 8; k++) { // the next service day; the reminder is 45 minutes before the service
                LocalDate day = LocalDate.now().plusDays(k);
                if (day.getDayOfWeek() != d) continue;
                LocalDateTime t = day.atTime(service).minusMinutes(45);
                if (t.isAfter(now)) { at(am, t, church); break; }
            }
        }
    }

    private static PendingIntent pi(Context c, String action, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, AlarmReceiver.class).setAction(action), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void at(AlarmManager am, LocalDateTime t, PendingIntent pi) {
        long when = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (Exception e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    /** Asleep, on a call, Do Not Disturb, resting after duty, praying: words would disturb; a notification only. */
    private static boolean quiet(Context c) {
        Prefs p = new Prefs(c);
        if (p.night() || CallControl.busyWithCall() || Rest.resting(c) || SoundService.prayerOn) return true;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            return nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Exception e) { return false; }
    }

    static void fire(Context c, String action) {
        schedule(c); // the next one
        if (ACTION_CHURCH.equals(action)) {
            String ch = church(c);
            if (ch.isEmpty()) return;
            String time = ch.split(" ")[1];
            String text = "చర్చికి టైమ్ అవుతోంది. " + time + " కి ఆరాధన. బైబిల్, కానుక తీసుకెళ్లండి.";
            Reminders.notify(c, "⛪ చర్చి", text, 162);
            if (!quiet(c)) Announcer.say(c, new Prefs(c).name() + ", " + text);
            return;
        }
        new Thread(() -> {
            try {
                JSONObject v = Bible.daily();
                String ref = v.optString("book") + " " + v.optInt("chapter") + ":" + v.optString("verses");
                String verse = v.optString("text").replaceFirst("^\\d+\\.\\s*", "");
                String meaning = "";
                Prefs p = new Prefs(c);
                if (!p.apiKey().isEmpty()) {
                    try {
                        meaning = Brain.oneShot(p, "You explain a Bible verse simply, in Telugu, for an ordinary believer. No other verses, no quotes, 2 short sentences.",
                                "Verse (" + ref + "): " + verse + "\nIn 2 short simple Telugu sentences: what it means and how to live it today.", null, false);
                    } catch (Exception ignored) {}
                }
                String text = "ఈరోజు వచనం, " + ref + ". " + verse + (meaning == null || meaning.isEmpty() ? "" : "\n" + meaning.trim());
                Reminders.notify(c, "📖 ఈరోజు వచనం · " + ref, text, 161);
                if (!quiet(c)) Announcer.say(c, "శుభోదయం " + p.name() + ". " + text);
            } catch (Exception ignored) {}
        }, "jarvis-verse").start();
    }
}
