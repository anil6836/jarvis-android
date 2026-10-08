package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 4: the heart rate the watch reads about every 15 minutes while he sits still, kept on the phone (2 weeks):
 *   W46 stress from it: his own normal is learnt (the middle of his sitting readings over 14 days, after about a day
 *       of wearing); 2 of the last 3 well above it (12 beats or more) while sitting = "stressed?" with the breathing.
 *       Samsung's own stress number is not given to other apps, so this is Jarvis's own estimate, not a medical test.
 *   W26 a cough day with the heart fast at rest: "check for fever" (once a day).
 *   W44 the watch's irregular-rhythm warning (seen in Samsung's notification): kept with the date, a doctor reminder.
 *   W28 the watch still on his wrist for 40 minutes and the heart lower than his normal: asleep (messages wait).
 */
final class HeartLog {
    private HeartLog() {}

    static final String KEY = "watch_hr", IRREGULAR = "irregular_hr";
    private static final long DAY = 86400_000L;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_heart", Context.MODE_PRIVATE); }

    static void add(Context c, int bpm, boolean still, long t) { add(c, bpm, still, false, t); }

    /** lying: still for 40 minutes or more (resting / asleep): kept, but out of his "sitting" normal and the stress check. */
    static void add(Context c, int bpm, boolean still, boolean lying, long t) {
        if (bpm < 30 || bpm > 220) return;
        try { Notes.add(c, KEY, new JSONObject().put("t", t).put("bpm", bpm).put("still", still).put("long", lying), 1600); } catch (Exception ignored) {}
    }

    /** A sitting reading of the day (not lying still 40+ minutes, not 10 pm – 7 am: those are sleep, lower than sitting). */
    static boolean sitting(JSONObject o) {
        if (!o.optBoolean("still") || o.optBoolean("long")) return false;
        int h = java.time.Instant.ofEpochMilli(o.optLong("t")).atZone(ZoneId.systemDefault()).getHour();
        return h >= 7 && h < 22;
    }

    static List<JSONObject> all(Context c) { return Notes.list(c, KEY); }

    // ---------------------------------------------------------------- his normal (pure: tested on a desk)

    /** {middle, spread (IQR), n} of his sitting readings in the 14 days before now; null if fewer than 30 (about a day worn). */
    static int[] baseline(List<JSONObject> l, long now) {
        List<Integer> v = new ArrayList<>();
        for (JSONObject o : l) {
            long t = o.optLong("t");
            if (sitting(o) && t <= now && now - t <= 14 * DAY) v.add(o.optInt("bpm"));
        }
        if (v.size() < 30) return null;
        Collections.sort(v);
        int n = v.size();
        return new int[]{v.get(n / 2), v.get(n * 3 / 4) - v.get(n / 4), n};
    }

    /** Well above his normal: 12 beats, or 1.5 times his usual spread if that is more. */
    static int threshold(int[] base) { return base[0] + Math.max(12, Math.round(base[1] * 1.5f)); }

    /** 2 of his last 3 sitting readings (within 90 minutes) well above his normal. */
    static boolean stressed(List<JSONObject> l, int[] base, long now) {
        if (base == null) return false;
        int seen = 0, high = 0, th = threshold(base);
        for (int i = l.size() - 1; i >= 0 && seen < 3; i--) {
            JSONObject o = l.get(i);
            if (!o.optBoolean("still") || o.optBoolean("long")) continue;
            if (now - o.optLong("t") > 90 * 60_000L) break;
            seen++;
            if (o.optInt("bpm") >= th) high++;
        }
        return high >= 2;
    }

    /** Share (%) of sitting readings in [from, to) well above his normal; -1 with fewer than 4 readings or no normal yet. */
    static int stressPct(List<JSONObject> l, int[] base, long from, long to) {
        if (base == null) return -1;
        int n = 0, high = 0, th = threshold(base);
        for (JSONObject o : l) {
            long t = o.optLong("t");
            if (!sitting(o) || t < from || t >= to) continue;
            n++;
            if (o.optInt("bpm") >= th) high++;
        }
        return n < 4 ? -1 : Math.round(high * 100f / n);
    }

    static String stressWord(int pct) {
        return pct < 0 ? "" : pct < 15 ? "తక్కువ" : pct < 30 ? "మధ్యస్థం" : "ఎక్కువ";
    }

    /** The low end of his sitting heart rate in [from, to) (the 10th percentile; near his resting rate); -1 if fewer than 5. */
    static int low(List<JSONObject> l, long from, long to) {
        List<Integer> v = new ArrayList<>();
        for (JSONObject o : l) {
            long t = o.optLong("t");
            if (o.optBoolean("still") && t >= from && t < to) v.add(o.optInt("bpm"));
        }
        if (v.size() < 5) return -1;
        Collections.sort(v);
        return v.get(v.size() / 10);
    }

    /** The latest reading {t, bpm, still}, or null. */
    static JSONObject last(List<JSONObject> l) { return l.isEmpty() ? null : l.get(l.size() - 1); }

    /** Fever hint (pure): a cough day (5+ coughs) and sitting heart rate 15 over his normal and 90+ (100+ before his normal is known). */
    static boolean feverHint(int bpm, int[] base, int coughsToday) {
        if (coughsToday < 5) return false;
        return base == null ? bpm >= 100 : bpm >= Math.max(base[0] + 15, 90);
    }

    /** The stress card may come now (pure): 3 hours apart, 3 a day, 7 am – 10 pm. */
    static boolean alertAllowed(long lastAt, int todayCount, int hour, long now) {
        return now - lastAt >= 3 * 3600_000L && todayCount < 3 && hour >= 7 && hour < 22;
    }

    // ---------------------------------------------------------------- what to do with a new reading

    /** A reading from the watch (background thread). */
    static void got(Context c, int bpm, boolean still, boolean lying, long t) {
        Context app = c.getApplicationContext();
        add(app, bpm, still, lying, t);
        if (!still) return;
        List<JSONObject> l = all(app);
        long now = System.currentTimeMillis();
        int[] base = baseline(l, now);
        sp(app).edit().putInt("last_bpm", bpm).putLong("last_t", t).putInt("base", base == null ? -1 : base[0]).apply(); // (for asleep(): no list read)
        fever(app, bpm, base);
        if (!lying && stressed(l, base, now)) stressCard(app, bpm, base);
    }

    private static void fever(Context app, int bpm, int[] base) {
        try {
            int h = LocalTime.now().getHour();
            if (h < 7 || h >= 22) return;
            int coughs = CoughLog.count(app, LocalDate.now(), "cough");
            if (!feverHint(bpm, base, coughs)) return;
            String today = LocalDate.now().toString();
            if (today.equals(sp(app).getString("fever_day", ""))) return;
            sp(app).edit().putString("fever_day", today).apply();
            String text = "ఈరోజు " + coughs + " సార్లు దగ్గారు, కూర్చున్నప్పుడు కూడా గుండె వేగం " + bpm
                    + (base != null ? " (మీ మామూలు " + base[0] + ")" : "") + ". జ్వరం ఉందేమో థర్మామీటర్‌తో చూడండి. "
                    + "100°F దాటితే విశ్రాంతి, నీళ్లు; 103°F దాటినా, ఊపిరి ఇబ్బంది ఉన్నా, 3 రోజులు తగ్గకపోయినా డాక్టర్‌ని చూడండి.";
            Reminders.notify(app, "🤒 జ్వరం ఉందేమో చూడండి", text, 4603);
        } catch (Exception ignored) {}
    }

    /** "Stressed?" on the wrist, a gentle tap; it opens the breathing. Not while riding, exercising or in a call. */
    private static void stressCard(Context app, int bpm, int[] base) {
        try {
            if (!WatchHub.stressOn(app) || !WatchHub.known(app) || Boolean.FALSE.equals(WatchHub.worn(app))) return;
            Prefs p = new Prefs(app);
            if (WatchHub.riding(app) || Exercise.running() || CallControl.busyWithCall() || p.night()) return;
            SharedPreferences s = sp(app);
            String today = LocalDate.now().toString();
            int count = today.equals(s.getString("stress_day", "")) ? s.getInt("stress_n", 0) : 0;
            long now = System.currentTimeMillis();
            if (!alertAllowed(s.getLong("stress_at", 0), count, LocalTime.now().getHour(), now)) return;
            s.edit().putLong("stress_at", now).putString("stress_day", today).putInt("stress_n", count + 1).apply();
            JSONObject o = new JSONObject().put("id", 4601).put("kind", "care").put("title", "💓 కొంచెం ఒత్తిడిగా ఉందా?")
                    .put("text", "కూర్చున్నప్పుడు కూడా గుండె వేగం " + bpm + " (మీ మామూలు " + base[0] + "). 2 నిమిషాలు నాతో శ్వాస తీసుకుందామా?")
                    .put("open", "breathe");
            WatchHub.send(app, WatchAlerts.P_ALERT, o, null, true);
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- W28: asleep (from the watch)

    /** The watch: still on his wrist for 40 minutes (true) or moving / off the wrist again (false). */
    static void still(Context c, boolean on) {
        sp(c).edit().putBoolean("still40", on).putLong("still40_at", System.currentTimeMillis()).apply();
    }

    /**
     * Asleep by the watch: still on his wrist for 40 minutes, the watch near, and his last sitting heart rate (within 35
     * minutes) lower than his normal. Cheap (a few saved numbers): Rest asks it often.
     */
    static boolean asleep(Context c) {
        try {
            SharedPreferences s = sp(c);
            if (!s.getBoolean("still40", false) || !WatchHub.watchHere(c) || Boolean.FALSE.equals(WatchHub.worn(c))) return false;
            int base = s.getInt("base", -1), bpm = s.getInt("last_bpm", 0);
            long at = s.getLong("last_t", 0), now = System.currentTimeMillis();
            return asleepBy(base, bpm, now - at);
        } catch (Exception e) {
            return false;
        }
    }

    /** Pure: his normal known, the last sitting reading within 35 minutes and 3 or more below it. */
    static boolean asleepBy(int base, int bpm, long age) {
        return base > 0 && bpm > 0 && age >= 0 && age <= 35 * 60_000L && bpm <= base - 3;
    }

    // ---------------------------------------------------------------- W44: the watch's irregular-rhythm warning

    /** Samsung's notification about an irregular heart rhythm (from NotifyListener): kept, and a calm reminder once a day. */
    static void irregular(Context c, String text, String key) {
        Context app = c.getApplicationContext();
        try {
            long now = System.currentTimeMillis();
            synchronized (HeartLog.class) { // (the same notification posted again / seen again when the listener reconnects)
                if (key != null && key.equals(sp(app).getString("irregular_key", ""))) return;
                sp(app).edit().putString("irregular_key", key == null ? "" : key).apply();
            }
            List<JSONObject> l = Notes.list(app, IRREGULAR);
            JSONObject last = last(l);
            if (last != null && now - last.optLong("t") < 10 * 60_000L) return; // (the same warning on the watch and the phone)
            Notes.add(app, IRREGULAR, new JSONObject().put("t", now).put("text", text == null ? "" : text.length() > 200 ? text.substring(0, 200) : text), 200);
            String today = LocalDate.now().toString();
            if (today.equals(sp(app).getString("irregular_day", ""))) return;
            sp(app).edit().putString("irregular_day", today).apply();
            int n = count(l, now - 30 * DAY) + 1;
            String msg = "వాచ్ గుండె లయ సక్రమంగా లేదని చెప్పింది" + (n > 1 ? " (30 రోజుల్లో " + n + " సార్లు)" : "") + ". తేదీతో రాసుకున్నాను, డాక్టర్ PDF లో ఉంటుంది. "
                    + "Samsung Health Monitor లో ECG తీసి Jarvis కి షేర్ చేస్తే అదీ దాచుతాను. ఒకటికి మించి వస్తే కార్డియాలజిస్ట్‌ని చూడండి. "
                    + "ఛాతి నొప్పి, ఊపిరి ఆడకపోవడం, కళ్లు తిరగడం, స్పృహ తప్పినట్టు ఉంటే వెంటనే 108.";
            Reminders.notify(app, "❤️ గుండె లయ హెచ్చరిక", msg, 4604);
        } catch (Exception ignored) {}
    }

    static int count(List<JSONObject> l, long since) {
        int n = 0;
        for (JSONObject o : l) if (o.optLong("t") >= since) n++;
        return n;
    }

    /** Samsung's irregular-rhythm notification (its words in English or Telugu). */
    static boolean isIrregular(String pkg, String text) {
        if (pkg == null || !pkg.startsWith("com.samsung.android.shealth") && !pkg.startsWith("com.samsung.android.app.shealth")
                && !pkg.startsWith("com.samsung.android.wear") && !pkg.startsWith("com.samsung.android.watch")) return false;
        String t = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        return t.matches("(?s).*(irregular\\s+(heart\\s+)?rhythm|atrial\\s+fibrillation|\\bafib\\b|\\ba-fib\\b|క్రమరహిత|సక్రమంగా\\s*లేని|అసాధారణ\\s*గుండె\\s*లయ).*");
    }

    /** Days since the epoch in his zone (for per-day sums). */
    static long dayStart(LocalDate d) { return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
}
