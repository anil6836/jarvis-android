package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Medicine reminders: each medicine with its times, dose and how many tablets are left. At the time,
 * a notification with "✅ వేసుకున్నాను" / "⏰ 10 నిమిషాల తర్వాత" and Jarvis says it; if it isn't marked
 * taken in 30 minutes, one more reminder. Taking one counts the stock down and warns before it runs out.
 * Everything stays on the phone.
 */
final class Medicine {
    private Medicine() {}

    static final String ACTION_DUE = "com.anil.jarvis.MED_DUE", ACTION_TAKEN = "com.anil.jarvis.MED_TAKEN",
            ACTION_SNOOZE = "com.anil.jarvis.MED_SNOOZE", ACTION_CHECK = "com.anil.jarvis.MED_CHECK";
    static final String LIST = "medicines", LOG = "med_log";
    private static final String CHANNEL = "jarvis_meds";

    static List<JSONObject> all(Context c) { return Notes.list(c, LIST); }

    static JSONObject find(Context c, String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) return null;
        List<JSONObject> l = all(c);
        for (JSONObject m : l) if (m.optString("name").toLowerCase(Locale.ROOT).equals(n)) return m;
        for (JSONObject m : l) {
            String k = m.optString("name").toLowerCase(Locale.ROOT);
            if (k.contains(n) || n.contains(k)) return m;
        }
        return null;
    }

    /** "08:00, 20:00" / "8 am" -> ["08:00", "20:00"] (sorted, no repeats). */
    static JSONArray times(String text) {
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|AM|PM)?").matcher(text == null ? "" : text);
        while (m.find()) {
            int h = Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            String ap = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT);
            if (ap.equals("pm") && h < 12) h += 12;
            if (ap.equals("am") && h == 12) h = 0;
            if (h > 23 || min > 59) continue;
            set.add(String.format(Locale.ENGLISH, "%02d:%02d", h, min));
        }
        JSONArray a = new JSONArray();
        for (String t : set) a.put(t);
        return a;
    }

    /** Adds (or replaces) a medicine and sets its alarms. */
    static JSONObject add(Context c, String name, String timesText, String dose, String food, int stock, int perDose) throws Exception {
        JSONArray t = times(timesText);
        if (name == null || name.trim().isEmpty() || t.length() == 0) return null;
        JSONObject old = find(c, name);
        if (old != null && old.optString("name").equalsIgnoreCase(name.trim())) remove(c, old.optString("name"));
        JSONObject m = new JSONObject().put("id", Notes.id("m")).put("name", name.trim()).put("times", t)
                .put("dose", dose == null ? "" : dose.trim()).put("food", food == null ? "" : food.trim())
                .put("stock", stock).put("per_dose", Math.max(1, perDose)).put("since", System.currentTimeMillis());
        Notes.add(c, LIST, m, 100);
        schedule(c, m);
        return m;
    }

    static boolean remove(Context c, String name) {
        JSONObject m = find(c, name);
        if (m == null) return false;
        cancel(c, m);
        Notes.remove(c, LIST, "id", m.optString("id"));
        return true;
    }

    static JSONObject setStock(Context c, String name, int stock) throws Exception {
        JSONObject m = find(c, name);
        if (m == null) return null;
        update(c, m.optString("id"), "stock", stock);
        return find(c, name);
    }

    private static synchronized void update(Context c, String id, String key, Object value) throws Exception {
        List<JSONObject> l = all(c);
        for (JSONObject m : l) if (m.optString("id").equals(id)) m.put(key, value);
        Notes.save(c, LIST, l, 100);
    }

    // ---------------------------------------------------------------- alarms

    private static PendingIntent pi(Context c, String action, String id, String time, int extra) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action).putExtra("id", id).putExtra("time", time)
                .setData(Uri.parse("jarvis://med/" + action + "/" + id + "/" + time));
        return PendingIntent.getBroadcast(c, (id + time + action + extra).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The next time today or tomorrow at HH:mm. */
    private static long nextAt(String hhmm) {
        Calendar k = Calendar.getInstance();
        String[] p = hhmm.split(":");
        k.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0]));
        k.set(Calendar.MINUTE, Integer.parseInt(p[1]));
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        if (k.getTimeInMillis() <= System.currentTimeMillis() + 5000) k.add(Calendar.DAY_OF_MONTH, 1);
        return k.getTimeInMillis();
    }

    static void schedule(Context c, JSONObject m) {
        JSONArray t = m.optJSONArray("times");
        for (int i = 0; t != null && i < t.length(); i++) {
            String time = t.optString(i);
            Reminders.setAlarm(c, nextAt(time), pi(c, ACTION_DUE, m.optString("id"), time, 0));
        }
    }

    private static void cancel(Context c, JSONObject m) {
        android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
        JSONArray t = m.optJSONArray("times");
        for (int i = 0; am != null && t != null && i < t.length(); i++) {
            String time = t.optString(i);
            am.cancel(pi(c, ACTION_DUE, m.optString("id"), time, 0));
            am.cancel(pi(c, ACTION_SNOOZE, m.optString("id"), time, 1));
            am.cancel(pi(c, ACTION_CHECK, m.optString("id"), time, 2));
        }
    }

    /** After a reboot or an update. */
    static void rescheduleAll(Context c) { for (JSONObject m : all(c)) schedule(c, m); }

    private static JSONObject byId(Context c, String id) {
        for (JSONObject m : all(c)) if (m.optString("id").equals(id)) return m;
        return null;
    }

    private static String slot(String time) { return new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date()) + " " + time; }

    private static boolean takenAt(Context c, String id, String slot) {
        for (JSONObject e : Notes.list(c, LOG)) if (e.optString("id").equals(id) && e.optString("slot").equals(slot)) return true;
        return false;
    }

    /** From AlarmReceiver: due, snoozed, the 30-minute check, or "taken" from the notification button. */
    static void onAlarm(Context c, String action, String id, String time) {
        JSONObject m = byId(c, id);
        if (m == null || time == null) return;
        String slot = slot(time);
        switch (action) {
            case ACTION_DUE:
                Reminders.setAlarm(c, nextAt(time), pi(c, ACTION_DUE, id, time, 0)); // tomorrow at the same time
                if (takenAt(c, id, slot)) return;
                remind(c, m, time, false);
                // (the home tablet asks అమ్మగారు again every 10 minutes; the phone once after 30)
                Reminders.setAlarm(c, System.currentTimeMillis() + (HomeCare.on(c) ? 10 : 30) * 60_000L, pi(c, ACTION_CHECK, id, time, 2));
                break;
            case ACTION_SNOOZE:
                cancelNote(c, m, time);
                Reminders.setAlarm(c, System.currentTimeMillis() + 10 * 60_000L, pi(c, ACTION_CHECK, id, time, 2));
                break;
            case ACTION_CHECK:
                if (takenAt(c, id, slot)) break;
                if (HomeCare.on(c)) { // asked again (2nd, 3rd time); not taken after the 3rd -> Anil is told once
                    android.content.SharedPreferences t = c.getSharedPreferences("jarvis_med_tries", Context.MODE_PRIVATE);
                    int tries = t.getInt(slot + id, 1) + 1;
                    android.content.SharedPreferences.Editor te = t.edit().putInt(slot + id, tries);
                    String day = slot.substring(0, 10);
                    for (String k : t.getAll().keySet()) if (!k.startsWith(day)) te.remove(k); // (other days' counts go: not one more key every dose)
                    te.apply();
                    if (tries > 3) {
                        HomeCare.alert(c, "💊 " + HomeCare.who(c) + " " + m.optString("name") + " (" + time + ") ఇంకా వేసుకున్నట్టు చెప్పలేదు; 3 సార్లు అడిగాను. ఒకసారి ఫోన్ చేసి గుర్తుచేయండి.");
                        break;
                    }
                    remind(c, m, time, true);
                    Reminders.setAlarm(c, System.currentTimeMillis() + 10 * 60_000L, pi(c, ACTION_CHECK, id, time, 2));
                    break;
                }
                remind(c, m, time, true);
                break;
            case ACTION_TAKEN:
                taken(c, m, time);
                break;
            default:
                break;
        }
    }

    private static void remind(Context c, JSONObject m, String time, boolean again) {
        String name = m.optString("name");
        String how = (m.optString("dose").isEmpty() ? "" : m.optString("dose")) + (m.optString("food").isEmpty() ? "" : (m.optString("dose").isEmpty() ? "" : ", ") + m.optString("food"));
        String title = again ? "💊 " + name + " ఇంకా వేసుకోలేదా?" : "💊 " + name + " వేసుకునే టైమ్ (" + time + ")";
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "మందుల రిమైండర్లు", NotificationManager.IMPORTANCE_HIGH));
                PendingIntent open = PendingIntent.getActivity(c, (m.optString("id") + time).hashCode(), new Intent(c, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Notification n = new Notification.Builder(c, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle(title)
                        .setContentText(how.isEmpty() ? "వేసుకున్నాక ✅ నొక్కండి" : how)
                        .setContentIntent(open)
                        .setCategory(Notification.CATEGORY_REMINDER)
                        .addAction(new Notification.Action.Builder(null, "✅ వేసుకున్నాను", pi(c, ACTION_TAKEN, m.optString("id"), time, 3)).build())
                        .addAction(new Notification.Action.Builder(null, "⏰ 10 నిమిషాల తర్వాత", pi(c, ACTION_SNOOZE, m.optString("id"), time, 1)).build())
                        .setAutoCancel(true)
                        .build();
                nm.notify("med", (m.optString("id") + time).hashCode(), n);
            }
        } catch (Exception ignored) {}
        Prefs p = new Prefs(c);
        if (HomeCare.on(c)) { // the home tablet: అమ్మగారు is asked, and her "వేసుకున్నాను" (or the 💊 button) marks it
            // tablets at the same time are asked once, together (their alarms come a second apart)
            synchronized (HOME_ASKED) {
                long now = System.currentTimeMillis();
                Long at = HOME_ASKED.get(time);
                if (at != null && now - at < 2 * 60_000L) return;
                HOME_ASKED.put(time, now);
            }
            List<JSONObject> same = untakenAt(c, time);
            if (same.isEmpty()) same.add(m);
            String w = HomeCare.who(c), names = names(same), food = same.size() == 1 ? how : sameFood(same);
            String what = names + (same.size() == 1 ? " టాబ్లెట్" : " టాబ్లెట్లు");
            HomeCare.ask(c, again ? w + ", " + what + " వేసుకున్నారా? ఇంకా అయితే ఇప్పుడే వేసుకోండి."
                    : w + ", " + what + " వేసుకునే టైమ్ అయింది" + (food.isEmpty() ? "." : ", " + food + ".") + " వేసుకున్నాక చెప్పండి.",
                    "med:" + m.optString("id") + ":" + time, again ? "worried" : "caring");
            return;
        }
        if (!dnd(c)) Announcer.say(c, p.name() + ", " + (again ? name + " ఇంకా వేసుకోలేదు. ఇప్పుడు వేసుకోండి." : name + " వేసుకునే టైమ్ అయింది" + (how.isEmpty() ? "." : ", " + how + ".")));
    }

    private static boolean dnd(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    private static void cancelNote(Context c, JSONObject m, String time) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel("med", (m.optString("id") + time).hashCode());
    }

    /** Marks this dose taken (the notification button or "BP మాత్ర వేసుకున్నాను"); counts the stock down. */
    static JSONObject taken(Context c, JSONObject m, String time) {
        try {
            if (time == null || time.isEmpty()) time = nearestTime(m);
            String slot = slot(time);
            cancelNote(c, m, time);
            android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
            if (am != null) am.cancel(pi(c, ACTION_CHECK, m.optString("id"), time, 2));
            if (takenAt(c, m.optString("id"), slot)) return new JSONObject().put("already", true).put("slot", slot);
            Notes.add(c, LOG, new JSONObject().put("id", m.optString("id")).put("name", m.optString("name")).put("slot", slot)
                    .put("t", System.currentTimeMillis()), 2000);
            JSONObject r = new JSONObject().put("taken", m.optString("name")).put("slot", slot);
            int stock = m.optInt("stock", -1);
            if (stock >= 0) {
                stock = Math.max(0, stock - m.optInt("per_dose", 1));
                update(c, m.optString("id"), "stock", stock);
                int perDay = Math.max(1, m.optJSONArray("times") == null ? 1 : m.optJSONArray("times").length()) * m.optInt("per_dose", 1);
                int daysLeft = stock / perDay;
                r.put("stock_left", stock).put("days_left", daysLeft);
                if (daysLeft <= 5) {
                    String t = "💊 " + m.optString("name") + " ఇంకా " + stock + " మాత్రలే ఉన్నాయి (సుమారు " + daysLeft + " రోజులకి)";
                    Reminders.notify(c, t, "అయిపోకముందే కొనండి. \"షాపింగ్ లిస్ట్‌లో చేర్చు\" అని Jarvis ని అడగొచ్చు.", ("low" + m.optString("id")).hashCode());
                    if (HomeCare.on(c)) HomeCare.alertOnce(c, "low_" + m.optString("id"), t.replace("💊 ", "💊 " + HomeCare.who(c) + " ") + ". కొనాలి."); // (the home tablet: Anil buys them)
                    r.put("low_stock", true);
                }
            }
            return r;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** The dose time closest to now (for "వేసుకున్నాను" without a time). */
    private static String nearestTime(JSONObject m) {
        JSONArray t = m.optJSONArray("times");
        Calendar now = Calendar.getInstance();
        int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), best = Integer.MAX_VALUE;
        String pick = t != null && t.length() > 0 ? t.optString(0) : "08:00";
        for (int i = 0; t != null && i < t.length(); i++) {
            String[] p = t.optString(i).split(":");
            int d = Math.abs(Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]) - mins);
            if (d < best) { best = d; pick = t.optString(i); }
        }
        return pick;
    }

    // ---------------------------------------------------------------- reports

    /** Each medicine: times, dose, stock and days left, and which of today's doses are taken. */
    static JSONObject listJson(Context c) throws Exception {
        JSONArray a = new JSONArray();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date());
        for (JSONObject m : all(c)) {
            JSONObject o = new JSONObject().put("name", m.optString("name")).put("times", m.optJSONArray("times"))
                    .put("dose", m.optString("dose")).put("food", m.optString("food"));
            JSONArray done = new JSONArray();
            JSONArray t = m.optJSONArray("times");
            for (int i = 0; t != null && i < t.length(); i++) if (takenAt(c, m.optString("id"), today + " " + t.optString(i))) done.put(t.optString(i));
            o.put("taken_today", done);
            int stock = m.optInt("stock", -1);
            if (stock >= 0) {
                int perDay = Math.max(1, t == null ? 1 : t.length()) * m.optInt("per_dose", 1);
                o.put("stock_left", stock).put("days_left", stock / perDay);
            }
            a.put(o);
        }
        return new JSONObject().put("ok", true).put("medicines", a);
    }

    /** How many doses were taken of those due in the last N days. */
    static JSONObject history(Context c, int days) throws Exception {
        long since = System.currentTimeMillis() - days * 86400000L;
        JSONArray a = new JSONArray();
        for (JSONObject m : all(c)) {
            int taken = 0;
            for (JSONObject e : Notes.list(c, LOG)) if (e.optString("id").equals(m.optString("id")) && e.optLong("t") >= since) taken++;
            JSONArray t = m.optJSONArray("times");
            long from = Math.max(since, m.optLong("since"));
            int due = (int) Math.max(1, Math.round((System.currentTimeMillis() - from) / 86400000.0)) * Math.max(1, t == null ? 1 : t.length());
            a.put(new JSONObject().put("name", m.optString("name")).put("taken", taken).put("due_about", due));
        }
        return new JSONObject().put("ok", true).put("days", days).put("medicines", a)
                .put("note", "Taken = marked with ✅ or told to Jarvis; doses not marked may still have been taken.");
    }

    static JSONObject byIdPublic(Context c, String id) { return byId(c, id); }


    /** The next dose not taken yet: {name, at millis}, or null when no medicines are set (the home screen's "next" line). */
    static Object[] nextDose(Context c) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        String today = day.format(new Date());
        long best = Long.MAX_VALUE;
        String name = null;
        for (JSONObject m : all(c)) {
            JSONArray t = m.optJSONArray("times");
            for (int i = 0; t != null && i < t.length(); i++) {
                String tm = t.optString(i);
                long at;
                try { at = nextAt(tm); } catch (Exception e) { continue; }
                // already taken (early) today: its next time is tomorrow
                if (day.format(new Date(at)).equals(today) && takenAt(c, m.optString("id"), today + " " + tm)) at += 86_400_000L;
                if (at < best) { best = at; name = m.optString("name"); }
            }
        }
        return name == null ? null : new Object[]{name, best};
    }

    private static final java.util.Map<String, Long> HOME_ASKED = new java.util.HashMap<>();

    /** One day's doses (yyyy-MM-dd): {taken, due} for the medicines set now (the weekly report). */
    static int[] dayDoses(Context c, String day) {
        int taken = 0, due = 0;
        List<JSONObject> log = Notes.list(c, LOG);
        for (JSONObject m : all(c)) {
            JSONArray t = m.optJSONArray("times");
            for (int i = 0; t != null && i < t.length(); i++) {
                due++;
                String slot = day + " " + t.optString(i);
                for (JSONObject e : log) if (e.optString("id").equals(m.optString("id")) && e.optString("slot").equals(slot)) { taken++; break; }
            }
        }
        return new int[]{taken, due};
    }

    /** Today's not-yet-taken medicines due at this time (same-time tablets are asked and marked together). */
    static List<JSONObject> untakenAt(Context c, String time) {
        List<JSONObject> out = new ArrayList<>();
        if (time == null || time.isEmpty()) return out;
        String slot = slot(time);
        for (JSONObject m : all(c)) {
            JSONArray t = m.optJSONArray("times");
            for (int i = 0; t != null && i < t.length(); i++)
                if (t.optString(i).equals(time)) {
                    if (!takenAt(c, m.optString("id"), slot)) out.add(m);
                    break;
                }
        }
        return out;
    }

    /** "Metformin, Glimepiride". */
    static String names(List<JSONObject> ms) {
        StringBuilder b = new StringBuilder();
        for (JSONObject m : ms) b.append(b.length() == 0 ? "" : ", ").append(m.optString("name"));
        return b.toString();
    }

    /** The food note when all of them have the same one ("తిన్న తర్వాత"), else "". */
    private static String sameFood(List<JSONObject> ms) {
        String f = null;
        for (JSONObject m : ms) {
            String x = m.optString("food");
            if (f == null) f = x;
            else if (!f.equals(x)) return "";
        }
        return f == null ? "" : f;
    }

    /** Today's dose nearest now (within 3 hours) not yet taken: {medicine, {"time": "HH:mm"}}, or null. */
    static JSONObject[] nearestDue(Context c) {
        Calendar now = Calendar.getInstance();
        int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), best = 181;
        JSONObject[] out = null;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date());
        for (JSONObject m : all(c)) {
            JSONArray t = m.optJSONArray("times");
            for (int i = 0; t != null && i < t.length(); i++) {
                String[] p = t.optString(i).split(":");
                int d = Math.abs(Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]) - mins);
                if (d < best && !takenAt(c, m.optString("id"), today + " " + t.optString(i))) {
                    best = d;
                    try { out = new JSONObject[]{m, new JSONObject().put("time", t.optString(i))}; } catch (Exception ignored) {}
                }
            }
        }
        return out;
    }

    /** Today's doses with the medicine's name ("Metformin: 08:00 ✅  20:00 ⬜"). */
    static List<String> todayLinesNamed(Context c) {
        List<String> out = new ArrayList<>(), lines = todayLines(c);
        List<JSONObject> ms = all(c);
        for (int i = 0; i < lines.size() && i < ms.size(); i++) {
            int stock = ms.get(i).optInt("stock", -1);
            out.add(ms.get(i).optString("name") + ": " + lines.get(i) + (stock >= 0 ? " (" + stock + " మాత్రలు మిగిలాయి)" : ""));
        }
        return out;
    }

    static List<String> todayLines(Context c) {
        List<String> out = new ArrayList<>();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date());
        for (JSONObject m : all(c)) {
            JSONArray t = m.optJSONArray("times");
            StringBuilder b = new StringBuilder();
            for (int i = 0; t != null && i < t.length(); i++) {
                boolean ok = takenAt(c, m.optString("id"), today + " " + t.optString(i));
                b.append(i == 0 ? "" : "  ").append(t.optString(i)).append(ok ? " ✅" : " ⬜");
            }
            out.add(b.toString());
        }
        return out;
    }
}
