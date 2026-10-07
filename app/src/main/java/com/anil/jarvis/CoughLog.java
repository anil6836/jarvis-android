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

    private static JSONObject days(Context c) {
        try { return new JSONObject(sp(c).getString("days", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static int count(Context c, LocalDate day, String kind) {
        JSONObject d = days(c).optJSONObject(day.toString());
        return d == null ? 0 : d.optInt("sneeze".equals(kind) ? "s" : "c");
    }

    /** Coughs between 10 pm and 6 am of the night before this day's morning (night of day-1 into day). */
    static int night(Context c, LocalDate day) {
        int n = 0;
        JSONObject all = days(c);
        JSONObject a = all.optJSONObject(day.minusDays(1).toString()), b = all.optJSONObject(day.toString());
        JSONArray ha = a == null ? null : a.optJSONArray("ch"), hb = b == null ? null : b.optJSONArray("ch");
        for (int h = 22; ha != null && h < 24; h++) n += ha.optInt(h);
        for (int h = 0; hb != null && h < 6; h++) n += hb.optInt(h);
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
                    .put("left", doses < 0 ? 99 : doses).put("next", System.currentTimeMillis() + every);
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
        Reminders.setAlarm(c, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), checkPi(c));
    }

    /** After a reboot or an update: the dose reminders back. */
    static void rearm(Context c) {
        long now = System.currentTimeMillis();
        for (JSONObject d : Notes.list(c, DOSES)) {
            try {
                if (d.optLong("next") < now) { d.put("next", now + 60_000); Notes.update(c, DOSES, d); }
                scheduleDose(c, d);
            } catch (Exception ignored) {}
        }
    }

    static void onAlarm(Context c, String action, String id) throws Exception {
        Prefs p = new Prefs(c);
        if (ACTION_CHECK.equals(action)) {
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
            d.put("left", left).put("next", System.currentTimeMillis() + d.optLong("every"));
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
        // ACTION_DOSE: time for the next one
        String text = d.optString("name") + " వేసుకునే సమయం అయింది.";
        notifyDose(c, d, text);
        boolean quiet = p.night() || CallControl.busyWithCall() || Rest.resting(c);
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
        for (JSONObject o : recentCare(c, 2)) if ("medicine".equals(o.optString("type")) || "note".equals(o.optString("type"))) return true;
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
        b.append("రోజు / Day — దగ్గు / Coughs — రాత్రి (10pm-6am) / Night — తుమ్ములు / Sneezes\n");
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            int n = count(c, d, "cough"), s = count(c, d, "sneeze"), nt = night(c, d);
            if (n + s + nt == 0 && i > 2) continue;
            b.append("• ").append(d.getDayOfMonth()).append("/").append(d.getMonthValue()).append(" (").append(DAYS_TE[d.getDayOfWeek().getValue()]).append(") — ")
                    .append(n).append(" — ").append(nt).append(" — ").append(s).append("\n");
        }
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
        b.append("\nగమనిక / Note: లెక్కలు ఫోన్ మైక్ విన్నంత వరకే, సుమారుగా (ఫోన్ దగ్గర ఉన్నప్పుడు, మైక్ ఆన్‌లో ఉన్నప్పుడు). ఇది వైద్య నిర్ధారణ కాదు.\n"
                + "Counts are approximate: only while the phone was nearby with its listening microphone on. Not a diagnosis.");
        return Cards.letter(c, "దగ్గు రిపోర్ట్ / Cough report · " + p.name(), b.toString(), "Jarvis/health");
    }
}
