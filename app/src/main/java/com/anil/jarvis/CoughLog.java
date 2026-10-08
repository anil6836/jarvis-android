package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * His cough / sneeze record and the care around it, all on the phone:
 * - every cough and sneeze the microphone hears is counted, by day and by hour (not while a video / song plays);
 * - "ఈరోజు ఎన్నిసార్లు దగ్గాను?", days in a row, going up or down;
 * - a doctor's summary PDF (Telugu + English): counts by day and at night, medicines he took, what he said, home
 *   remedies, BP / sugar readings and his medical card;
 * - a tablet or syrup he took: the next dose reminded (with ✅ / ⏰ buttons) and "దగ్గు తగ్గిందా?" 3 hours later;
 * - on cough days, home remedies reminded: warm water in the day, gargling morning and night (skipped if Jarvis heard him
 *   gargle), steam before sleep; quiet at night, in calls, while riding; only a notification on duty days.
 */
final class CoughLog {
    private CoughLog() {}

    static final String ACTION_DOSE = "com.anil.jarvis.COUGH_DOSE", ACTION_DOSE_TAKEN = "com.anil.jarvis.COUGH_DOSE_TAKEN",
            ACTION_DOSE_LATER = "com.anil.jarvis.COUGH_DOSE_LATER", ACTION_CHECK = "com.anil.jarvis.COUGH_CHECK";
    private static final String CARE = "cough_care", DOSES = "cough_doses", CHANNEL = "jarvis_cough_care";
    private static final String[] DAYS_TE = {"", "సోమ", "మంగళ", "బుధ", "గురు", "శుక్ర", "శని", "ఆది"};

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_cough", Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- counting

    /** One cough / sneeze heard now. */
    static synchronized void add(Context c, String kind) {
        try {
            JSONObject days = days(c);
            String key = LocalDate.now().toString();
            JSONObject d = days.optJSONObject(key);
            if (d == null) d = new JSONObject();
            boolean sneeze = "sneeze".equals(kind);
            d.put(sneeze ? "s" : "c", d.optInt(sneeze ? "s" : "c") + 1);
            JSONArray h = d.optJSONArray(sneeze ? "sh" : "ch");
            if (h == null || h.length() != 24) { h = new JSONArray(); for (int i = 0; i < 24; i++) h.put(0); }
            int hour = LocalTime.now().getHour();
            h.put(hour, h.optInt(hour) + 1);
            d.put(sneeze ? "sh" : "ch", h);
            if (sneeze) { // where he was: on the bike, on duty or at home (for "when / where do the sneezes come")
                JSONObject sx = d.optJSONObject("sx");
                if (sx == null) sx = new JSONObject();
                String place = place(c);
                sx.put(place, sx.optInt(place) + 1);
                d.put("sx", sx);
            }
            days.put(key, d);
            // keep 60 days
            LocalDate oldest = LocalDate.now().minusDays(60);
            JSONArray names = days.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                try { if (LocalDate.parse(names.getString(i)).isBefore(oldest)) days.remove(names.getString(i)); } catch (Exception ignored) {}
            }
            sp(c).edit().putString("days", days.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static String place(Context c) {
        try {
            if (Bike.riding(c) || new Prefs(c).driving()) return "bike";
            Duty.Roster r = Duty.load(c);
            if (Duty.ready(r) && r.isOn(Duty.ME, LocalDate.now())) return "duty";
        } catch (Exception ignored) {}
        return "home";
    }

    /** One body sound (sniff, throat, wheeze, hiccup, burp) counted for the day. */
    static synchronized void addBody(Context c, String kind) {
        try {
            JSONObject days = days(c);
            String key = LocalDate.now().toString();
            JSONObject d = days.optJSONObject(key);
            if (d == null) d = new JSONObject();
            JSONObject b = d.optJSONObject("b");
            if (b == null) b = new JSONObject();
            b.put(kind, b.optInt(kind) + 1);
            d.put("b", b);
            days.put(key, d);
            sp(c).edit().putString("days", days.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** A minute with snoring heard (each minute once), by hour like the coughs. */
    static synchronized void snoreMinute(Context c) {
        try {
            long minute = System.currentTimeMillis() / 60_000L;
            if (sp(c).getLong("snore_last_min", 0) == minute) return;
            sp(c).edit().putLong("snore_last_min", minute).apply();
            JSONObject days = days(c);
            String key = LocalDate.now().toString();
            JSONObject d = days.optJSONObject(key);
            if (d == null) d = new JSONObject();
            JSONArray h = d.optJSONArray("zh");
            if (h == null || h.length() != 24) { h = new JSONArray(); for (int i = 0; i < 24; i++) h.put(0); }
            int hour = LocalTime.now().getHour();
            h.put(hour, h.optInt(hour) + 1);
            d.put("zh", h);
            days.put(key, d);
            sp(c).edit().putString("days", days.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Snoring minutes between 10 pm and 7 am of the night ending on this day's morning. */
    static int nightSnore(Context c, LocalDate day) {
        int n = 0;
        JSONObject all = days(c);
        JSONObject a = all.optJSONObject(day.minusDays(1).toString()), b = all.optJSONObject(day.toString());
        JSONArray ha = a == null ? null : a.optJSONArray("zh"), hb = b == null ? null : b.optJSONArray("zh");
        for (int h = 22; ha != null && h < 24; h++) n += ha.optInt(h);
        for (int h = 0; hb != null && h < 7; h++) n += hb.optInt(h);
        return n;
    }

    static int body(Context c, LocalDate day, String kind) {
        JSONObject d = days(c).optJSONObject(day.toString());
        JSONObject b = d == null ? null : d.optJSONObject("b");
        return b == null ? 0 : b.optInt(kind);
    }

    /** When and where the sneezes come (last 14 days): counts, and one plain line when a pattern is clear. */
    static JSONObject sneezePattern(Context c) throws Exception {
        int[] part = new int[4]; // early morning 5-9, day 9-17, evening 17-22, night 22-5
        Map<String, Integer> where = new java.util.HashMap<>();
        int total = 0;
        JSONObject all = days(c);
        for (int i = 0; i < 14; i++) {
            JSONObject d = all.optJSONObject(LocalDate.now().minusDays(i).toString());
            if (d == null) continue;
            JSONArray h = d.optJSONArray("sh");
            for (int k = 0; h != null && k < 24; k++) {
                int v = h.optInt(k);
                total += v;
                part[k >= 5 && k < 9 ? 0 : k >= 9 && k < 17 ? 1 : k >= 17 && k < 22 ? 2 : 3] += v;
            }
            JSONObject sx = d.optJSONObject("sx");
            if (sx != null) for (java.util.Iterator<String> it = sx.keys(); it.hasNext(); ) { String k = it.next(); where.put(k, where.getOrDefault(k, 0) + sx.optInt(k)); }
        }
        JSONObject o = new JSONObject().put("sneezes_14_days", total)
                .put("by_time", new JSONObject().put("early_morning_5_9", part[0]).put("day_9_17", part[1]).put("evening_17_22", part[2]).put("night_22_5", part[3]))
                .put("by_place", new JSONObject(where));
        if (total >= 8) {
            String[] times = {"ఉదయం లేవగానే (5-9 గంటలు)", "పగలు", "సాయంత్రం", "రాత్రి"};
            String[] why = {"చల్ల గాలి, దుమ్ము, దిండు/దుప్పట్ల దుమ్ము అలర్జీ కావచ్చు", "ఇంట్లో/పని చోట దుమ్ము కావచ్చు", "బయట దుమ్ము, పొగ కావచ్చు", "ఫ్యాన్/AC చల్ల గాలి, దుమ్ము కావచ్చు"};
            int best = 0;
            for (int k = 1; k < 4; k++) if (part[k] > part[best]) best = k;
            StringBuilder b = new StringBuilder();
            if (part[best] * 2 >= total) b.append("తుమ్ములు ఎక్కువగా ").append(times[best]).append(" వస్తున్నాయి (").append(Math.round(part[best] * 100f / total)).append("%) - ").append(why[best]).append(". ");
            int bike = where.getOrDefault("bike", 0), duty = where.getOrDefault("duty", 0);
            if (bike * 3 >= total && bike >= 3) b.append("బైక్ మీద ఉన్నప్పుడు కూడా ఎక్కువ: రోడ్డు దుమ్ము, పొగ - మాస్క్ / హెల్మెట్ విజర్ దించుకుంటే తగ్గొచ్చు. ");
            if (duty * 2 >= total && duty >= 4) b.append("డ్యూటీ రోజుల్లో ఎక్కువ: అక్కడి దుమ్ము / చల్ల గాలి కావచ్చు. ");
            if (b.length() > 0) o.put("insight_telugu", b.toString().trim());
        }
        return o;
    }

    private static JSONObject days(Context c) {
        try { return new JSONObject(sp(c).getString("days", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static int count(Context c, LocalDate day, String kind) {
        JSONObject d = days(c).optJSONObject(day.toString());
        return d == null ? 0 : d.optInt("sneeze".equals(kind) ? "s" : "c");
    }

    /** Coughs between 10 pm and 7 am of the night before this day's morning (night of day-1 into day), like the night listening. */
    static int night(Context c, LocalDate day) {
        int n = 0;
        JSONObject all = days(c);
        JSONObject a = all.optJSONObject(day.minusDays(1).toString()), b = all.optJSONObject(day.toString());
        JSONArray ha = a == null ? null : a.optJSONArray("ch"), hb = b == null ? null : b.optJSONArray("ch");
        for (int h = 22; ha != null && h < 24; h++) n += ha.optInt(h);
        for (int h = 0; hb != null && h < 7; h++) n += hb.optInt(h);
        return n;
    }

    /** Days in a row (up to today, or up to yesterday if none yet today) with 2 or more coughs. */
    static int streak(Context c) {
        LocalDate d = LocalDate.now();
        if (count(c, d, "cough") < 2) d = d.minusDays(1);
        int n = 0;
        while (n < 60 && count(c, d, "cough") >= 2) { n++; d = d.minusDays(1); }
        return n;
    }

    private static String dayName(LocalDate d) {
        LocalDate t = LocalDate.now();
        if (d.equals(t)) return "ఈరోజు";
        if (d.equals(t.minusDays(1))) return "నిన్న";
        return DAYS_TE[d.getDayOfWeek().getValue()] + " " + d.getDayOfMonth() + "/" + d.getMonthValue();
    }

    /** "ఈ వారం దగ్గు: సో 2 · మం 5 …" for Settings (empty if nothing heard this week). */
    static String weekLine(Context c) {
        StringBuilder b = new StringBuilder();
        int total = 0;
        for (int i = 6; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            int n = count(c, d, "cough"), s = count(c, d, "sneeze");
            total += n + s;
            if (b.length() > 0) b.append(" · ");
            b.append(DAYS_TE[d.getDayOfWeek().getValue()]).append(' ').append(n).append(s > 0 ? "+" + s + "🤧" : "");
        }
        return total == 0 ? "" : "📊 ఈ వారం దగ్గు (+తుమ్ములు): " + b;
    }

    /** For the brain: today, the last 7 days, the trend. */
    static JSONObject summary(Context c, int days) throws Exception {
        JSONArray a = new JSONArray();
        int recent = 0, before = 0;
        for (int i = 0; i < Math.max(1, Math.min(30, days)); i++) {
            LocalDate d = LocalDate.now().minusDays(i);
            int n = count(c, d, "cough"), s = count(c, d, "sneeze");
            a.put(new JSONObject().put("day", dayName(d)).put("coughs", n).put("sneezes", s).put("night_coughs", night(c, d)));
            if (i >= 1 && i <= 2) recent += n;
            if (i >= 3 && i <= 4) before += n;
        }
        JSONObject o = new JSONObject().put("ok", true)
                .put("today", new JSONObject().put("coughs", count(c, LocalDate.now(), "cough")).put("sneezes", count(c, LocalDate.now(), "sneeze")))
                .put("by_day", a).put("days_in_a_row", streak(c));
        if (recent + before >= 6) o.put("trend", recent > before * 1.3 ? "going up" : recent < before * 0.7 ? "going down" : "about the same");
        o.put("last_night", new JSONObject().put("coughs", night(c, LocalDate.now())).put("snoring_minutes", nightSnore(c, LocalDate.now())));
        JSONObject bodyToday = new JSONObject();
        for (String k : new String[]{"sniff", "throat", "wheeze", "hiccup", "burp"}) { int v = body(c, LocalDate.now(), k); if (v > 0) bodyToday.put(k, v); }
        if (bodyToday.length() > 0) o.put("other_sounds_today", bodyToday);
        try { JSONObject sp = sneezePattern(c); if (sp.has("insight_telugu")) o.put("sneeze_pattern", sp.getString("insight_telugu")); } catch (Exception ignored) {}
        JSONArray care = new JSONArray();
        for (JSONObject x : recentCare(c, 3)) care.put(x.optString("when") + " " + x.optString("text"));
        if (care.length() > 0) o.put("care_last_3_days", care);
        o.put("note", "Counts are what the phone's microphone heard while the wake word was listening (approximate). "
                + "Say today first in one short Telugu line, then the trend; if coughing 3+ days or going up, gently suggest a doctor and offer the report (cough_log report).");
        return o;
    }

    // ---------------------------------------------------------------- care log (medicines, remedies, what he said)

    static void care(Context c, String type, String text) {
        try {
            JSONObject o = new JSONObject().put("t", System.currentTimeMillis()).put("type", type).put("text", text == null ? "" : text.trim());
            Notes.add(c, CARE, o, 120);
            if ("better".equals(type)) sp(c).edit().putLong("better_at", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }

    private static List<JSONObject> recentCare(Context c, int days) {
        long since = System.currentTimeMillis() - days * 86400000L;
        List<JSONObject> l = Notes.list(c, CARE), out = new java.util.ArrayList<>();
        for (JSONObject o : l) {
            if (o.optLong("t") < since) continue;
            LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(o.optLong("t")), ZoneId.systemDefault());
            try { o.put("when", dayName(t.toLocalDate()) + " " + String.format(Locale.ENGLISH, "%d:%02d", t.getHour(), t.getMinute())); } catch (Exception ignored) {}
            out.add(o);
        }
        return out;
    }

    static void remedyDone(Context c, String which, boolean heard) {
        String w = which == null ? "" : which.trim().toLowerCase(Locale.ROOT);
        String text = w.startsWith("garg") || w.contains("పుక్కి") ? "ఉప్పు నీళ్లతో పుక్కిలించారు" : w.startsWith("steam") || w.contains("ఆవిరి") ? "ఆవిరి పట్టారు"
                : w.startsWith("water") || w.contains("నీళ్లు") ? "గోరువెచ్చని నీళ్లు తాగారు" : which;
        care(c, "remedy", text + (heard ? " (Jarvis విన్నాడు)" : ""));
        sp(c).edit().putLong("remedy_" + (w.startsWith("garg") || w.contains("పుక్కి") ? "gargle" : w.startsWith("steam") || w.contains("ఆవిరి") ? "steam" : "water"),
                System.currentTimeMillis()).apply();
    }

    static void better(Context c) {
        care(c, "better", "దగ్గు తగ్గిందని చెప్పారు");
        try {
            for (JSONObject d : Notes.list(c, DOSES)) cancelDose(c, d.optString("id"));
            Notes.save(c, DOSES, new java.util.ArrayList<>(), 10);
        } catch (Exception ignored) {}
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(checkPi(c));
    }

    // ---------------------------------------------------------------- medicine follow-up

    /** He took a cough tablet / syrup now: kept, the next dose reminded (if he said how often), "తగ్గిందా?" in 3 hours. */
    static JSONObject tookMedicine(Context c, String name, double everyHours, int doses) throws Exception {
        String n = name == null || name.trim().isEmpty() ? "దగ్గు మందు" : name.trim();
        care(c, "medicine", n + " వేసుకున్నారు");
        JSONObject out = new JSONObject().put("ok", true).put("noted", n);
        if (everyHours >= 1 && everyHours <= 24 && doses != 0) {
            long every = Math.round(everyHours * 3600_000L);
            JSONObject d = new JSONObject().put("id", Notes.id("cd")).put("name", n).put("every", every)
                    .put("left", doses < 0 ? 99 : doses).put("next", daytime(System.currentTimeMillis() + every));
            // the same medicine again replaces its old reminder
            for (JSONObject old : Notes.list(c, DOSES)) if (old.optString("name").equalsIgnoreCase(n)) { cancelDose(c, old.optString("id")); Notes.remove(c, DOSES, "id", old.optString("id")); }
            Notes.add(c, DOSES, d, 10);
            scheduleDose(c, d);
            out.put("next_dose", clock(d.optLong("next")));
        }
        scheduleCheck(c, 3 * 3600_000L);
        out.put("note", "Tell him in one line it is noted" + (out.has("next_dose") ? " and Jarvis will remind the next dose at " + out.optString("next_dose") : "")
                + ", and that Jarvis will ask in about 3 hours how the cough is.");
        return out;
    }

    private static String clock(long t) {
        LocalDateTime d = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), ZoneId.systemDefault());
        int h = d.getHour();
        String part = h < 12 ? "ఉదయం" : h < 16 ? "మధ్యాహ్నం" : h < 19 ? "సాయంత్రం" : "రాత్రి";
        return (d.toLocalDate().equals(LocalDate.now()) ? "" : dayName(d.toLocalDate()) + " ") + part + " " + (h % 12 == 0 ? 12 : h % 12)
                + (d.getMinute() == 0 ? "" : String.format(Locale.ENGLISH, ":%02d", d.getMinute()));
    }

    private static PendingIntent dosePi(Context c, String action, String id) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action).putExtra("id", id).setData(Uri.parse("jarvis://cough/" + action + "/" + id));
        return PendingIntent.getBroadcast(c, (action + id).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent checkPi(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_CHECK).setData(Uri.parse("jarvis://cough/check"));
        return PendingIntent.getBroadcast(c, 7320, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void scheduleDose(Context c, JSONObject d) {
        Reminders.setAlarm(c, d.optLong("next"), dosePi(c, ACTION_DOSE, d.optString("id")));
    }

    /** A dose time out of the night: 10 pm - 7 am -> 7 am (he isn't woken for a cough syrup). */
    private static long daytime(long t) {
        LocalDateTime at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), ZoneId.systemDefault());
        if (at.getHour() >= 22) at = at.toLocalDate().plusDays(1).atTime(7, 0);
        else if (at.getHour() < 7) at = at.toLocalDate().atTime(7, 0);
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** The next dose after now, stepping by the gap, out of the night. */
    private static long nextDose(JSONObject d) {
        long now = System.currentTimeMillis(), every = Math.max(3600_000L, d.optLong("every")), next = d.optLong("next");
        while (next <= now + 30_000) next += every;
        return daytime(next);
    }

    private static void cancelDose(Context c, String id) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.cancel(dosePi(c, ACTION_DOSE, id));
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id.hashCode());
    }

    /** "దగ్గు తగ్గిందా?" after the delay, moved out of the night (10 pm - 7 am -> 8 am). */
    static void scheduleCheck(Context c, long delay) {
        LocalDateTime at = LocalDateTime.now().plusSeconds(delay / 1000);
        if (at.getHour() >= 22) at = at.toLocalDate().plusDays(1).atTime(8, 0);
        else if (at.getHour() < 7) at = at.toLocalDate().atTime(8, 0);
        long t = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        sp(c).edit().putLong("check_at", t).apply(); // brought back after a reboot
        Reminders.setAlarm(c, t, checkPi(c));
    }

    /** After a reboot or an update: the dose reminders back. */
    static void rearm(Context c) {
        long now = System.currentTimeMillis();
        for (JSONObject d : Notes.list(c, DOSES)) {
            try {
                if (d.optLong("next") < now) { d.put("next", nextDose(d)); Notes.update(c, DOSES, d); } // missed while off: the next one, not "now"
                scheduleDose(c, d);
            } catch (Exception ignored) {}
        }
        long check = sp(c).getLong("check_at", 0);
        if (check > now) Reminders.setAlarm(c, check, checkPi(c));
    }

    static void onAlarm(Context c, String action, String id) throws Exception {
        Prefs p = new Prefs(c);
        if (ACTION_CHECK.equals(action)) {
            sp(c).edit().remove("check_at").apply();
            if (p.night() || CallControl.busyWithCall() || Rest.resting(c) || MainActivity.busyTalking() || p.driving()) { scheduleCheck(c, 3600_000L); return; }
            int today = count(c, LocalDate.now(), "cough");
            int days = streak(c);
            Proactive.say(c, p.name() + ", దగ్గు ఎలా ఉంది? కొంచెం తగ్గిందా?", "తగ్గకపోతే ఇంకో చిట్కా చెప్పనా?",
                    " [health: Jarvis is checking on his cough after the medicine (today the microphone heard " + today + " coughs"
                            + (days > 1 ? "; coughing " + days + " days in a row" : "") + "). If he says it is better / తగ్గింది -> cough_log better, then a warm line. "
                            + "If not better: health_advice (cough) home remedies again; if 3 or more days, gently suggest a doctor and offer the doctor's summary (cough_log report). "
                            + "If he took another dose now -> cough_log took_medicine.]");
            return;
        }
        JSONObject d = null;
        for (JSONObject x : Notes.list(c, DOSES)) if (x.optString("id").equals(id)) d = x;
        if (d == null) return;
        if (ACTION_DOSE_TAKEN.equals(action)) {
            care(c, "medicine", d.optString("name") + " వేసుకున్నారు (రిమైండర్ తర్వాత)");
            int left = d.optInt("left") - 1;
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(id.hashCode());
            if (left <= 0) { Notes.remove(c, DOSES, "id", id); return; }
            d.put("left", left).put("unanswered", 0).put("next", daytime(System.currentTimeMillis() + d.optLong("every")));
            Notes.update(c, DOSES, d);
            scheduleDose(c, d);
            return;
        }
        if (ACTION_DOSE_LATER.equals(action)) {
            d.put("next", System.currentTimeMillis() + 15 * 60_000L);
            Notes.update(c, DOSES, d);
            scheduleDose(c, d);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(id.hashCode());
            return;
        }
        // ACTION_DOSE: time for this one; the one after is set now (if he never taps ✅, the reminders still go on)
        String text = d.optString("name") + " వేసుకునే సమయం అయింది.";
        notifyDose(c, d, text);
        int unanswered = d.optInt("unanswered") + 1;
        if (unanswered >= 3) { // three in a row with no ✅: he has stopped it himself; this is the last one
            Notes.remove(c, DOSES, "id", id);
        } else {
            d.put("unanswered", unanswered).put("next", nextDose(d));
            Notes.update(c, DOSES, d);
            scheduleDose(c, d);
        }
        int h = LocalTime.now().getHour();
        boolean dnd = false;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            dnd = nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Exception ignored) {}
        boolean quiet = p.night() || dnd || h >= 22 || h < 7 || CallControl.busyWithCall() || Rest.resting(c);
        if (!quiet) Announcer.say(c, p.name() + ", " + text);
    }

    private static void notifyDose(Context c, JSONObject d, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "దగ్గు మందు, ఇంటి చిట్కాలు", NotificationManager.IMPORTANCE_HIGH));
        String id = d.optString("id");
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("💊 " + d.optString("name")).setContentText(text)
                .addAction(new Notification.Action.Builder(null, "✅ వేసుకున్నాను", dosePi(c, ACTION_DOSE_TAKEN, id)).build())
                .addAction(new Notification.Action.Builder(null, "⏰ 15 నిమిషాల తర్వాత", dosePi(c, ACTION_DOSE_LATER, id)).build())
                .setCategory(Notification.CATEGORY_REMINDER).build();
        nm.notify(id.hashCode(), n);
    }

    // ---------------------------------------------------------------- home remedies on cough days

    static boolean remediesOn(Context c) { return sp(c).getBoolean("remedies", true); }

    static void setRemedies(Context c, boolean on) { sp(c).edit().putBoolean("remedies", on).apply(); }

    /** A cough day: coughs today or yesterday, or he spoke of the cough lately, and not "తగ్గింది" since. */
    static boolean coughDays(Context c) {
        long better = sp(c).getLong("better_at", 0);
        int today = count(c, LocalDate.now(), "cough"), yesterday = count(c, LocalDate.now().minusDays(1), "cough");
        if (System.currentTimeMillis() - better < 24 * 3600_000L) return today >= 8; // he said it settled: only a real return
        if (today >= 3 || yesterday >= 5) return true;
        for (JSONObject o : recentCare(c, 2))
            if (o.optLong("t") > better && ("medicine".equals(o.optString("type")) || "note".equals(o.optString("type")))) return true;
        return false;
    }

    /** From Proactive (about every 15 minutes). */
    static void remedyTick(Context c, Prefs p, boolean hush) {
        if (hush || !remediesOn(c) || !p.coughAsk() || p.driving() || MainActivity.busyTalking() || CallControl.busyWithCall()) return;
        LocalTime now = LocalTime.now();
        String slot = null, say = null;
        int m = now.getHour() * 60 + now.getMinute();
        if (m >= 9 * 60 + 30 && m < 11 * 60) { slot = "gargle_am"; }
        else if (m >= 11 * 60 && m < 12 * 60 + 30) { slot = "water_11"; }
        else if (m >= 15 * 60 && m < 16 * 60 + 30) { slot = "water_15"; }
        else if (m >= 18 * 60 && m < 19 * 60 + 30) { slot = "water_18"; }
        else if (m >= 20 * 60 + 30 && m < 21 * 60 + 15) { slot = "gargle_pm"; }
        else if (m >= 21 * 60 + 15 && m < 22 * 60) { slot = "steam"; }
        if (slot == null) return;
        String key = "said_" + slot, today = LocalDate.now().toString();
        if (today.equals(sp(c).getString(key, ""))) return;
        if (!coughDays(c)) return;
        long nowMs = System.currentTimeMillis();
        if (slot.startsWith("gargle")) {
            if (nowMs - sp(c).getLong("remedy_gargle", 0) < 4 * 3600_000L) { sp(c).edit().putString(key, today).apply(); return; }
            say = "దగ్గు తగ్గడానికి గోరువెచ్చని ఉప్పు నీళ్లతో ఒకసారి పుక్కిలించండి.";
        } else if (slot.equals("steam")) {
            if (nowMs - sp(c).getLong("remedy_steam", 0) < 6 * 3600_000L) { sp(c).edit().putString(key, today).apply(); return; }
            say = "పడుకునే ముందు 5 నిమిషాలు ఆవిరి పట్టండి, రాత్రి దగ్గు తగ్గుతుంది.";
        } else {
            if (nowMs - sp(c).getLong("remedy_water", 0) < 2 * 3600_000L) { sp(c).edit().putString(key, today).apply(); return; }
            say = "గోరువెచ్చని నీళ్లు ఒక గ్లాస్ తాగండి, గొంతుకి మంచిది.";
        }
        sp(c).edit().putString(key, today).apply();
        boolean duty = false;
        try {
            Duty.Roster r = Duty.load(c);
            duty = Duty.ready(r) && r.isOn(Duty.ME, LocalDate.now());
        } catch (Exception ignored) {}
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "దగ్గు మందు, ఇంటి చిట్కాలు", NotificationManager.IMPORTANCE_HIGH));
            nm.notify(7321, new Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("🍯 దగ్గుకి చిట్కా").setContentText(say).setAutoCancel(true).build());
        }
        if (!duty) Announcer.say(c, p.name() + ", " + say); // on duty: only the notification
    }

    // ---------------------------------------------------------------- the night, in the morning

    /** From Proactive: once in the morning (6-11, screen on), a quiet note about the night's coughs and snoring, if there was much. */
    static void morningTick(Context c, Prefs p) {
        int h = LocalTime.now().getHour();
        if (h < 7 || h >= 11) return; // after the night listening ends (7 am)
        String today = LocalDate.now().toString();
        if (today.equals(sp(c).getString("night_told", ""))) return;
        try {
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            if (pm != null && !pm.isInteractive()) return; // when he's up and looking at the phone
        } catch (Exception ignored) {}
        int coughs = night(c, LocalDate.now()), snore = nightSnore(c, LocalDate.now());
        sp(c).edit().putString("night_told", today).apply();
        if (coughs < 3 && snore < 10) return;
        String text = nightLine(coughs, snore);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "దగ్గు మందు, ఇంటి చిట్కాలు", NotificationManager.IMPORTANCE_DEFAULT));
        nm.notify(7322, new Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("🌙 రాత్రి నిద్రలో").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build());
    }

    static String nightLine(int coughs, int snore) {
        StringBuilder b = new StringBuilder();
        if (coughs > 0) b.append("రాత్రి ").append(coughs).append(" సార్లు దగ్గారు");
        if (snore > 0) b.append(b.length() > 0 ? ", " : "").append("గురక సుమారు ").append(snore).append(" నిమిషాలు");
        if (coughs >= 10) b.append(". రాత్రి దగ్గు ఎక్కువగా ఉంది, పడుకునే ముందు ఆవిరి పట్టండి; తగ్గకపోతే డాక్టర్‌కి చూపించండి");
        return b.append(".").toString();
    }

    /** For the morning briefing (English data line), empty if nothing heard. */
    static String nightData(Context c) {
        int coughs = night(c, LocalDate.now()), snore = nightSnore(c, LocalDate.now());
        if (coughs + snore == 0) return "";
        return "Last night the phone's microphone heard: " + coughs + " coughs, snoring about " + snore + " minutes.";
    }

    // ---------------------------------------------------------------- the doctor's summary

    static Coder.Made report(Context c) throws Exception {
        Prefs p = new Prefs(c);
        int days = Math.max(7, Math.min(21, streak(c) + 2));
        StringBuilder b = new StringBuilder();
        b.append("పేరు / Name: ").append(p.name()).append("\n");
        b.append("తేదీ / Date: ").append(LocalDate.now()).append("\n");
        if (!p.medBlood().isEmpty()) b.append("బ్లడ్ గ్రూప్ / Blood group: ").append(p.medBlood()).append("\n");
        if (!p.medAllergy().isEmpty()) b.append("అలర్జీలు / Allergies: ").append(p.medAllergy()).append("\n");
        if (!p.medNotes().isEmpty()) b.append("ఆరోగ్య విషయాలు, వాడే మందులు / Conditions, regular medicines: ").append(p.medNotes()).append("\n");
        int st = streak(c);
        b.append("\nదగ్గు / Cough: ").append(st > 0 ? st + " రోజులుగా వరుసగా / " + st + " days in a row" : "ఇప్పుడు వరుసగా లేదు / not continuous now").append("\n");
        b.append("\nరోజువారీ లెక్క (ఫోన్ మైక్ విన్నవి) / Daily count heard by the phone microphone:\n");
        b.append("రోజు / Day — దగ్గు / Coughs — రాత్రి (10pm-7am) / Night — తుమ్ములు / Sneezes — గురక నిమిషాలు / Snoring min\n");
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            int n = count(c, d, "cough"), s = count(c, d, "sneeze"), nt = night(c, d), z = nightSnore(c, d);
            if (n + s + nt + z == 0 && i > 2) continue;
            b.append("• ").append(d.getDayOfMonth()).append("/").append(d.getMonthValue()).append(" (").append(DAYS_TE[d.getDayOfWeek().getValue()]).append(") — ")
                    .append(n).append(" — ").append(nt).append(" — ").append(s).append(" — ").append(z).append("\n");
        }
        int[] bt = new int[5];
        String[] bk = {"sniff", "throat", "wheeze", "hiccup", "burp"};
        String[] bn = {"ముక్కు ఎగబీల్చడం / Sniffing", "గొంతు సవరణ / Throat clearing", "గురగుర, ఆయాసం / Wheeze, breathlessness", "ఎక్కిళ్లు / Hiccups", "త్రేన్పులు / Burps"};
        for (int i = 0; i < days; i++) for (int k = 0; k < 5; k++) bt[k] += body(c, LocalDate.now().minusDays(i), bk[k]);
        StringBuilder other = new StringBuilder();
        for (int k = 0; k < 5; k++) if (bt[k] > 0) other.append("• ").append(bn[k]).append(": ").append(bt[k]).append("\n");
        if (other.length() > 0) b.append("\nఇతర శబ్దాలు (" + days + " రోజుల్లో) / Other sounds (" + days + " days):\n").append(other);
        try {
            JSONObject sp = sneezePattern(c);
            if (sp.has("insight_telugu")) b.append("\nతుమ్ములు / Sneeze pattern: ").append(sp.getString("insight_telugu")).append("\n");
        } catch (Exception ignored) {}
        // when in the day
        int[] hours = new int[24];
        for (int i = 0; i < days; i++) {
            JSONObject d = days(c).optJSONObject(LocalDate.now().minusDays(i).toString());
            JSONArray h = d == null ? null : d.optJSONArray("ch");
            for (int k = 0; h != null && k < 24; k++) hours[k] += h.optInt(k);
        }
        int[] part = new int[4]; // morning, afternoon, evening, night
        for (int k = 0; k < 24; k++) part[k >= 6 && k < 12 ? 0 : k >= 12 && k < 17 ? 1 : k >= 17 && k < 22 ? 2 : 3] += hours[k];
        int tot = part[0] + part[1] + part[2] + part[3];
        if (tot > 0) {
            String[] names = {"ఉదయం / Morning", "మధ్యాహ్నం / Afternoon", "సాయంత్రం / Evening", "రాత్రి / Night"};
            b.append("\nఎప్పుడు ఎక్కువ / When: ");
            for (int k = 0; k < 4; k++) b.append(k > 0 ? ", " : "").append(names[k]).append(" ").append(Math.round(part[k] * 100f / tot)).append("%");
            b.append("\n");
        }
        List<JSONObject> care = recentCare(c, days);
        StringBuilder meds = new StringBuilder(), said = new StringBuilder(), rem = new StringBuilder();
        for (JSONObject o : care) {
            String line = "• " + o.optString("when") + ": " + o.optString("text") + "\n";
            switch (o.optString("type")) {
                case "medicine": meds.append(line); break;
                case "note": case "better": said.append(line); break;
                case "remedy": rem.append(line); break;
                default: break;
            }
        }
        if (meds.length() > 0) b.append("\nవేసుకున్న మందులు / Medicines taken:\n").append(meds);
        try {
            StringBuilder reg = new StringBuilder();
            for (JSONObject m : Medicine.all(c)) reg.append("• ").append(m.optString("name")).append(m.optString("dose").isEmpty() ? "" : " " + m.optString("dose"))
                    .append(" (").append(m.optJSONArray("times")).append(")\n");
            if (reg.length() > 0) b.append("\nరోజూ వేసుకునే మందులు / Regular medicines:\n").append(reg);
        } catch (Exception ignored) {}
        if (said.length() > 0) b.append("\nఆయన చెప్పినవి / What he reported:\n").append(said);
        if (rem.length() > 0) b.append("\nఇంటి చిట్కాలు / Home remedies:\n").append(rem);
        try {
            JSONObject v = Vitals.summary(c, "", 30);
            JSONArray r = v.optJSONArray("readings");
            if (r != null && r.length() > 0) {
                b.append("\nBP / షుగర్ / బరువు (చివరి 30 రోజులు, last 30 days):\n");
                for (int i = 0; i < r.length() && i < 8; i++) b.append("• ").append(r.optString(i)).append("\n");
            }
        } catch (Exception ignored) {}
        try { b.append(Wellness.doctor(c, days)); } catch (Exception ignored) {} // W29: the watch's part (sleep, heart, oxygen, ECG)
        b.append("\nగమనిక / Note: లెక్కలు ఫోన్ మైక్ విన్నంత వరకే, సుమారుగా (ఫోన్ దగ్గర ఉన్నప్పుడు, మైక్ ఆన్‌లో ఉన్నప్పుడు). ఇది వైద్య నిర్ధారణ కాదు.\n"
                + "Counts are approximate: only while the phone was nearby with its listening microphone on. Not a diagnosis.");
        return Cards.letter(c, "దగ్గు రిపోర్ట్ / Cough report · " + p.name(), b.toString(), "Jarvis/health");
    }
}
