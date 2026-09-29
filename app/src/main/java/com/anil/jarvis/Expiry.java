package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Things that run out: bike insurance, driving licence, PUC, bike service, gas cylinder, mobile recharge...
 *   by date: a last date; repeat_days for things that come back (recharge every 28 days, gas every ~30)
 *   by km:   bike service every N km, counted from the rides Jarvis logs
 * Reminded 15 days before (2 days for short repeats), 7 days, 1 day, on the day, and once after it runs out.
 * Only names and dates are kept (no policy or licence numbers).
 */
final class Expiry {
    private Expiry() {}

    static final String KEY = "expiry";

    static List<JSONObject> all(Context c) {
        List<JSONObject> l = Notes.list(c, KEY);
        l.sort((a, b) -> Long.compare(daysLeftOrKm(a), daysLeftOrKm(b)));
        return l;
    }

    private static long daysLeftOrKm(JSONObject o) {
        if (o.has("km_every")) return 100000;
        LocalDate d = Debts.parse(o.optString("date"));
        return d == null ? 99999 : ChronoUnit.DAYS.between(LocalDate.now(), d);
    }

    /** Same id or the same name. */
    static JSONObject exact(Context c, String what) {
        if (what == null || what.trim().isEmpty()) return null;
        String q = what.trim().toLowerCase(Locale.ROOT);
        for (JSONObject o : Notes.list(c, KEY))
            if (o.optString("id").equalsIgnoreCase(q) || o.optString("what").trim().toLowerCase(Locale.ROOT).equals(q)) return o;
        return null;
    }

    /**
     * The one he means: exact, else the only one whose name contains his words (or the other way round), else the only
     * one sharing the most words. Two equally good matches = null (ask him), so "బైక్ PUC" never hits "బైక్ ఇన్సూరెన్స్".
     */
    static JSONObject find(Context c, String what) {
        JSONObject e = exact(c, what);
        if (e != null || what == null || what.trim().isEmpty()) return e;
        String q = what.trim().toLowerCase(Locale.ROOT);
        List<JSONObject> hit = new ArrayList<>();
        for (JSONObject o : Notes.list(c, KEY)) {
            String w = o.optString("what").toLowerCase(Locale.ROOT);
            if (w.contains(q) || q.contains(w)) hit.add(o);
        }
        if (hit.size() == 1) return hit.get(0);
        if (hit.size() > 1) return null;
        int bestHits = 0, ties = 0;
        JSONObject best = null;
        for (JSONObject o : Notes.list(c, KEY)) {
            int hits = 0;
            for (String word : q.split("\\s+")) if (word.length() >= 3 && o.optString("what").toLowerCase(Locale.ROOT).contains(word)) hits++;
            if (hits > bestHits) { bestHits = hits; best = o; ties = 1; }
            else if (hits == bestHits && hits > 0) ties++;
        }
        return ties == 1 ? best : null;
    }

    private static void put(Context c, JSONObject item) {
        List<JSONObject> l = Notes.list(c, KEY);
        boolean found = false;
        for (int i = 0; i < l.size(); i++) if (l.get(i).optString("id").equals(item.optString("id"))) { l.set(i, item); found = true; }
        if (!found) l.add(item);
        Notes.save(c, KEY, l, 200);
    }

    /** By date. repeatDays > 0 for things that come back; beforeDays = first reminder (0 = default). */
    static JSONObject addDate(Context c, String what, String date, int repeatDays, int beforeDays) throws Exception {
        LocalDate d = Debts.parse(date);
        if (what == null || what.trim().isEmpty() || d == null) return null;
        JSONObject o = exact(c, what);
        if (o == null || o.has("km_every")) o = new JSONObject().put("id", Notes.id("x"));
        o.put("what", what.trim()).put("date", d.toString()).put("repeat_days", Math.max(0, repeatDays))
                .put("before_days", beforeDays > 0 ? beforeDays : (repeatDays > 0 && repeatDays <= 45 ? 2 : 15));
        put(c, o);
        return o;
    }

    /** Bike service by km: every N km, counted from now (or from when he last got it serviced). */
    static JSONObject addKm(Context c, String what, int everyKm) throws Exception {
        if (what == null || what.trim().isEmpty() || everyKm < 100) return null;
        JSONObject o = exact(c, what);
        if (o == null || !o.has("km_every")) o = new JSONObject().put("id", Notes.id("x"));
        o.put("what", what.trim()).put("km_every", everyKm).put("km_from", System.currentTimeMillis()).remove("date");
        put(c, o);
        return o;
    }

    /** Renewed / done: next date (given, or + repeat days from today), or the km count starts again. */
    static JSONObject renew(Context c, String what, String newDate) throws Exception {
        JSONObject o = find(c, what);
        if (o == null) return null;
        if (o.has("km_every")) {
            o.put("km_from", System.currentTimeMillis());
        } else {
            LocalDate d = Debts.parse(newDate);
            int rep = o.optInt("repeat_days");
            if (d == null && rep > 0) d = LocalDate.now().plusDays(rep);
            if (d == null) return new JSONObject().put("need_date", true).put("what", o.optString("what"));
            o.put("date", d.toString());
        }
        o.put("renewed", LocalDate.now().toString());
        put(c, o);
        return o;
    }

    static boolean remove(Context c, String what) {
        JSONObject o = find(c, what);
        return o != null && Notes.remove(c, KEY, "id", o.optString("id"));
    }

    static double kmDone(Context c, JSONObject o) {
        double km = 0;
        for (JSONObject r : Bike.list(c, "rides", o.optLong("km_from"))) km += r.optDouble("km");
        return km;
    }

    /** One Telugu line: "బైక్ ఇన్సూరెన్స్: 12 అక్టోబర్ (ఇంకా 13 రోజులు)". */
    static String line(Context c, JSONObject o) {
        if (o.has("km_every")) {
            int every = o.optInt("km_every");
            long done = Math.round(kmDone(c, o));
            return o.optString("what") + ": ప్రతి " + every + " కి.మీ · ఇప్పటికి " + done + " కి.మీ" + (done >= every ? " · టైమ్ అయింది!" : " · ఇంకా " + (every - done) + " కి.మీ");
        }
        LocalDate d = Debts.parse(o.optString("date"));
        if (d == null) return o.optString("what");
        long left = ChronoUnit.DAYS.between(LocalDate.now(), d);
        String when = left < 0 ? (-left) + " రోజుల క్రితమే అయిపోయింది" : left == 0 ? "ఈరోజే ఆఖరు" : left == 1 ? "రేపే ఆఖరు" : "ఇంకా " + left + " రోజులు";
        return o.optString("what") + ": " + Duty.day(d) + " " + d.getYear() + " (" + when + ")"
                + (o.optInt("repeat_days") > 0 ? " · ప్రతి " + o.optInt("repeat_days") + " రోజులకి" : "");
    }

    static JSONObject listJson(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject o : all(c)) a.put(new JSONObject().put("what", o.optString("what")).put("line", line(c, o)).put("id", o.optString("id")));
        return new JSONObject().put("ok", true).put("items", a);
    }

    // ---------------------------------------------------------------- reminders (from Proactive)

    private static SharedPreferences st(Context c) { return c.getSharedPreferences("jarvis_expiry", Context.MODE_PRIVATE); }

    private static boolean once(Context c, String key) {
        SharedPreferences s = st(c);
        if (s.contains(key)) return false;
        SharedPreferences.Editor e = s.edit();
        if (s.getAll().size() > 400) e.clear();
        e.putLong(key, System.currentTimeMillis()).apply();
        return true;
    }

    static void tick(Context c, Prefs p, boolean quiet) {
        int h = java.time.LocalTime.now().getHour();
        if (h < 9 || h >= 13) return; // late morning, once a day per stage
        List<JSONObject> list = Notes.list(c, KEY);
        if (list.isEmpty()) return;
        LocalDate today = LocalDate.now();
        StringBuilder say = new StringBuilder();
        for (JSONObject o : list) {
            String id = o.optString("id"), what = o.optString("what");
            if (o.has("km_every")) {
                int every = o.optInt("km_every");
                double done = kmDone(c, o);
                String from = o.optString("km_from");
                if (done >= every && once(c, id + "|due|" + from)) {
                    notify(c, id.hashCode(), "🛠️ " + what + " టైమ్ అయింది", Math.round(done) + " కి.మీ అయ్యాయి (ప్రతి " + every + " కి.మీ). చేయించాక \"" + what + " అయింది\" అని చెప్పండి.");
                    say.append(what).append(" టైమ్ అయింది, ").append(Math.round(done)).append(" కి.మీ అయ్యాయి. ");
                } else if (done >= every - Math.max(150, every * 0.07) && done < every && once(c, id + "|near|" + from)) {
                    notify(c, id.hashCode(), "🛠️ " + what + " దగ్గర పడింది", Math.round(done) + " / " + every + " కి.మీ.");
                    say.append(what).append(" దగ్గర పడింది, ఇంకా ").append(Math.round(every - done)).append(" కి.మీ. ");
                }
                continue;
            }
            LocalDate d = Debts.parse(o.optString("date"));
            if (d == null) continue;
            long left = ChronoUnit.DAYS.between(today, d);
            int before = Math.max(1, o.optInt("before_days", 15));
            String stage = null;
            if (left == before) stage = "b";
            else if (left == 7 && before > 7) stage = "7";
            else if (left == 1 && before > 1) stage = "1";
            else if (left == 0) stage = "0";
            else if (left == -1) stage = "over";
            else if (left > 0 && left < before && !st(c).contains(id + "|any|" + d)) stage = "any"; // added late: say it once now
            if (stage == null || !once(c, id + "|" + stage + "|" + d)) continue;
            st(c).edit().putLong(id + "|any|" + d, System.currentTimeMillis()).apply();
            String text = left < 0 ? what + " గడువు నిన్నటితో అయిపోయింది" : left == 0 ? what + " గడువు ఈరోజే ఆఖరు"
                    : left == 1 ? what + " గడువు రేపే ఆఖరు" : what + " గడువు ఇంకా " + left + " రోజుల్లో (" + Duty.day(d) + ")";
            notify(c, id.hashCode(), "📄 " + text, o.optInt("repeat_days") > 0 ? "చేశాక \"" + what + " చేశాను\" అని చెప్పండి, తర్వాతి తేదీ పెడతాను."
                    : "రెన్యూ చేశాక కొత్త తేదీ చెప్పండి: \"" + what + " కొత్త గడువు …\".");
            say.append(text).append(". ");
        }
        if (say.length() > 0 && !quiet) Announcer.say(c, p.name() + ", " + say.toString().trim());
    }

    private static void notify(Context c, int id, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_money", "EMI, అప్పులు, గడువులు", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent open = PendingIntent.getActivity(c, id, new Intent(c, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_ASK, "నా గడువులు చెప్పు: ఇన్సూరెన్స్, లైసెన్స్, సర్వీస్, రీఛార్జ్ (expiry list).")
                            .putExtra(MainActivity.EXTRA_LABEL, "📄 గడువులు").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify("expiry", id, new Notification.Builder(c, "jarvis_money").setSmallIcon(android.R.drawable.ic_menu_today)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }

    static List<JSONObject> soon(Context c, int days) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : all(c)) {
            if (o.has("km_every")) { if (kmDone(c, o) >= o.optInt("km_every") * 0.9) out.add(o); continue; }
            if (daysLeftOrKm(o) <= days) out.add(o);
        }
        return out;
    }
}
