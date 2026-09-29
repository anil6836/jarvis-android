package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * His diary: what he says about his day, by date. At night (10 pm by default) Jarvis asks "ఈరోజు ఎలా గడిచింది?"
 * and writes down the answer; later "గత నెల 10న ఏం చేశాను?" reads that day back (with his duty and bike rides that day).
 * Kept in a private file on the phone.
 */
final class Diary {
    private Diary() {}

    private static File file(Context c) { return new File(c.getFilesDir(), "diary.json"); }

    static synchronized List<JSONObject> all(Context c) {
        List<JSONObject> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            JSONArray a = new JSONArray(new String(b, 0, n, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        return out;
    }

    private static synchronized void save(Context c, List<JSONObject> l) {
        JSONArray a = new JSONArray();
        for (JSONObject o : l) a.put(o);
        File tmp = new File(c.getFilesDir(), "diary.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file(c));
    }

    /** Adds his words to that day (today when no date). */
    static synchronized JSONObject add(Context c, String text, String date) throws Exception {
        if (text == null || text.trim().isEmpty()) return null;
        LocalDate d = Debts.parse(date);
        if (d == null || d.isAfter(LocalDate.now())) d = LocalDate.now();
        JSONObject o = new JSONObject().put("id", Notes.id("dy")).put("date", d.toString()).put("text", text.trim()).put("t", System.currentTimeMillis());
        List<JSONObject> l = all(c);
        l.add(o);
        save(c, l);
        return o;
    }

    static boolean hasToday(Context c) {
        String t = LocalDate.now().toString();
        for (JSONObject o : all(c)) if (t.equals(o.optString("date"))) return true;
        return false;
    }

    static List<JSONObject> between(Context c, LocalDate from, LocalDate to) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : all(c)) {
            LocalDate d = Debts.parse(o.optString("date"));
            if (d != null && !d.isBefore(from) && !d.isAfter(to)) out.add(o);
        }
        out.sort((a, b) -> a.optString("date").compareTo(b.optString("date")) != 0 ? a.optString("date").compareTo(b.optString("date"))
                : Long.compare(a.optLong("t"), b.optLong("t")));
        return out;
    }

    static List<JSONObject> search(Context c, String word) {
        List<JSONObject> out = new ArrayList<>();
        String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        if (w.isEmpty()) return out;
        for (JSONObject o : all(c)) if (o.optString("text").toLowerCase(Locale.ROOT).contains(w)) out.add(o);
        return out;
    }

    static boolean remove(Context c, String idOrDate) {
        if (idOrDate == null || idOrDate.trim().isEmpty()) return false;
        List<JSONObject> l = all(c);
        boolean found = l.removeIf(o -> o.optString("id").equalsIgnoreCase(idOrDate.trim()) || o.optString("date").equals(idOrDate.trim()));
        if (found) save(c, l);
        return found;
    }

    /** What else Jarvis knows about that day: his duty, bike rides. */
    static JSONObject dayFacts(Context c, LocalDate d) throws Exception {
        JSONObject f = new JSONObject();
        try {
            Duty.Roster r = Duty.load(c);
            if (Duty.ready(r)) f.put("duty", r.isOn(Duty.ME, d) ? "on duty" : "off (at home)");
        } catch (Exception ignored) {}
        try {
            long from = d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), to = from + 86400000L;
            double km = 0;
            int n = 0;
            for (JSONObject ride : Bike.list(c, "rides", from)) if (ride.optLong("start") < to) { km += ride.optDouble("km"); n++; }
            if (n > 0) f.put("bike", n + " rides, " + Math.round(km) + " km");
        } catch (Exception ignored) {}
        return f;
    }

    // ---------------------------------------------------------------- asking at night (from Proactive)

    static void tick(Context c, Prefs p, boolean quiet) {
        if (!p.diaryAsk()) return;
        int h = java.time.LocalTime.now().getHour();
        if (h < p.diaryHour() || h > 23) return;
        String key = "asked_" + LocalDate.now();
        android.content.SharedPreferences s = c.getSharedPreferences("jarvis_diary", Context.MODE_PRIVATE);
        if (s.getBoolean(key, false)) return;
        s.edit().clear().putBoolean(key, true).apply();
        if (hasToday(c)) return;
        notifyAsk(c);
        if (!quiet) Proactive.say(c, p.name() + ", డైరీ టైమ్.", "ఈరోజు ఎలా గడిచింది?",
                " [diary: when he answers, save his words as they are with the diary tool (action add, today), then say in one short line that it is written. "
                        + "If he says not now / వద్దు, just say okay.]");
    }

    private static void notifyAsk(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_diary", "డైరీ", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, 85, new Intent(c, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_ASK, "ఈరోజు డైరీ రాద్దాం. నన్ను 'ఈరోజు ఎలా గడిచింది?' అని అడిగి, నేను చెప్పింది diary లో add చెయ్.")
                            .putExtra(MainActivity.EXTRA_LABEL, "📔 ఈరోజు డైరీ").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(85, new Notification.Builder(c, "jarvis_diary").setSmallIcon(android.R.drawable.ic_menu_edit)
                    .setContentTitle("📔 ఈరోజు ఎలా గడిచింది?").setContentText("డైరీ రాయడానికి నొక్కండి, లేదా \"Jarvis, డైరీ రాయి\" అనండి.")
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }
}
