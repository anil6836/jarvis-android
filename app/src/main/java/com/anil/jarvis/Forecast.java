package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * V11 / V15: a suit's "time to completion" and "if this continues".
 * <p>
 * etaLines: what is running now and when it finishes (phone charge, the bike's charge, the cooker's last whistle, the
 * duty's end, the next medicine). In the status, said by voice.
 * <p>
 * ifContinues: a few honest projections from his own numbers (battery drain → when it dies, and before the duty ends?;
 * medicines running out; the month's spending at this pace against the budget; sleep owed; the bike's service km at
 * this week's riding). All marked as estimates; nothing is sent anywhere.
 */
final class Forecast {
    private Forecast() {}

    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;

    // ================================================================ V11: when does it finish?

    static List<String> etaLines(Context c) {
        List<String> l = new ArrayList<>();
        long now = System.currentTimeMillis();
        Context app = c.getApplicationContext();
        try { // the phone's charge
            Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int pct = lvl < 0 ? -1 : Math.round(lvl * 100f / scale);
                if (st == BatteryManager.BATTERY_STATUS_CHARGING && pct >= 0 && pct < 100) {
                    long ms = chargeMsLeft(app, pct);
                    if (ms > 0) l.add("🔌 ఫోన్ 100% అయ్యేది సుమారు " + Status.hours(Math.max(1, ms / MIN)) + " లో (" + clock(now + ms) + ", అంచనా)");
                }
            }
        } catch (Exception ignored) {}
        try { // the bike's charge
            JSONObject ch = Bike.charging(app);
            if (ch != null) {
                long eta = ch.optLong("eta");
                if (eta > now) l.add("🛵 బైక్ " + ch.optInt("to") + "% అయ్యేది " + Offline.sayWhen(at(eta), LocalDateTime.now()) + " (ఇంకా " + Status.hours((eta - now) / MIN) + ")");
                else if (now - eta < 2 * HOUR && !ch.optBoolean("logged")) l.add("🛵 బైక్ " + ch.optInt("to") + "% అయి ఉండాలి (" + clock(eta) + " కి)");
            }
        } catch (Exception ignored) {}
        try { // the cooker
            if (Sounds.cookerActive(app)) {
                JSONObject k = Sounds.cookerStatus(app);
                SharedPreferences s = app.getSharedPreferences("jarvis_sounds", Context.MODE_PRIVATE);
                String e = cookerEta(k.optInt("whistles_heard"), k.optInt("target"), s.getLong("cooker_start", now), now);
                if (!e.isEmpty()) l.add(e);
            }
        } catch (Exception ignored) {}
        try { // the duty
            LocalDateTime[] d = Duty.ready(Duty.load(app)) ? Duty.nowOrNext(app) : null;
            LocalDateTime ln = LocalDateTime.now();
            if (d != null && !ln.isBefore(d[0]) && ln.isBefore(d[1]))
                l.add("🛡️ డ్యూటీ అయ్యేది " + Offline.sayWhen(d[1], ln) + " (ఇంకా " + Status.hours(java.time.Duration.between(ln, d[1]).toMinutes()) + ")");
        } catch (Exception ignored) {}
        try { // the next medicine today
            JSONObject meds = Medicine.listJson(app);
            Calendar k = Calendar.getInstance();
            String nm = nextMedicine(meds.optJSONArray("medicines"), k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE));
            if (!nm.isEmpty()) l.add(nm);
        } catch (Exception ignored) {}
        try { if (Stopwatch.running(app)) l.add("⏱️ స్టాప్‌వాచ్ నడుస్తోంది: " + Stopwatch.say(Stopwatch.elapsed(app))); } catch (Exception ignored) {}
        return l;
    }

    /** Milliseconds until the phone is full: Android's own figure (Android 9+), else from how fast it rose on this charge; -1 unknown. */
    static long chargeMsLeft(Context c, int pct) {
        try {
            BatteryManager bm = c.getSystemService(BatteryManager.class);
            if (bm != null) { // computeChargeTimeRemaining is API 28: reached by name so the app still builds for the tablet's Android 8
                Object r = BatteryManager.class.getMethod("computeChargeTimeRemaining").invoke(bm);
                if (r instanceof Long && (Long) r > 0) return (Long) r;
            }
        } catch (Throwable ignored) {}
        try {
            double perHour = Power.chargePerHour(new JSONArray(c.getSharedPreferences("jarvis_power", Context.MODE_PRIVATE).getString("csamples", "[]")), System.currentTimeMillis());
            return msToFull(pct, perHour);
        } catch (Exception e) {
            return -1;
        }
    }

    /** (pure) ms to 100% at perHour %/h; -1 when the rate isn't known. */
    static long msToFull(int pct, double perHour) {
        if (perHour <= 0 || pct < 0 || pct >= 100) return -1;
        return (long) ((100 - pct) / perHour * HOUR);
    }

    /** (pure) "🍲 చివరి విజిల్ సుమారు N ని. లో" from the whistles so far; "" before the first one. */
    static String cookerEta(int heard, int target, long startMs, long now) {
        if (target <= 0 || heard >= target) return "";
        if (heard < 1 || now <= startMs) return "🍲 కుక్కర్: మొదటి విజిల్ కోసం వింటున్నాను (" + target + " లెక్కపెట్టాలి)";
        long per = (now - startMs) / heard;
        long left = per * (target - heard);
        long min = Math.max(1, Math.round(left / (double) MIN));
        return "🍲 కుక్కర్ చివరి విజిల్ సుమారు " + min + " ని. లో (" + heard + " / " + target + " అయ్యాయి)";
    }

    /** (pure) the next dose today that isn't taken, nearest after now: "💊 తర్వాతి మందు: Name 8:00 PM (ఇంకో 40 నిమిషాలు)". */
    static String nextMedicine(JSONArray meds, int nowMin) {
        if (meds == null) return "";
        String bestName = null, bestTime = null;
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < meds.length(); i++) {
            JSONObject m = meds.optJSONObject(i);
            if (m == null) continue;
            JSONArray t = m.optJSONArray("times"), done = m.optJSONArray("taken_today");
            for (int k = 0; t != null && k < t.length(); k++) {
                String hm = t.optString(k);
                boolean taken = false;
                for (int j = 0; done != null && j < done.length(); j++) if (done.optString(j).equals(hm)) taken = true;
                if (taken) continue;
                int mins;
                try { String[] p = hm.split(":"); mins = Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim()); } catch (Exception e) { continue; }
                if (mins < nowMin - 30) continue; // long past: the medicine reminder's own business
                if (mins < best) { best = mins; bestName = m.optString("name"); bestTime = hm; }
            }
        }
        if (bestName == null) return "";
        int left = best - nowMin;
        return "💊 తర్వాతి మందు: " + bestName + " " + sayTime(bestTime) + (left > 0 ? " (ఇంకో " + Status.hours(left) + ")" : " (ఇప్పుడు)");
    }

    /** (pure) "20:00" → "రాత్రి 8:00". */
    static String sayTime(String hm) {
        try {
            String[] p = hm.split(":");
            int h = Integer.parseInt(p[0].trim()), m = Integer.parseInt(p[1].trim());
            String part = h < 5 ? "తెల్లవారుజామున" : h < 12 ? "ఉదయం" : h < 16 ? "మధ్యాహ్నం" : h < 19 ? "సాయంత్రం" : "రాత్రి";
            int h12 = h % 12 == 0 ? 12 : h % 12;
            return part + " " + h12 + ":" + String.format(Locale.ENGLISH, "%02d", m);
        } catch (Exception e) {
            return hm;
        }
    }

    // ================================================================ V15: if this continues

    /** Each: {text, level} - level 2 = worth a word, 1 = good to know, 0 = fine. */
    static List<Object[]> ifContinues(Context c) {
        List<Object[]> out = new ArrayList<>();
        Context app = c.getApplicationContext();
        long now = System.currentTimeMillis();
        try { // the phone's battery at this drain
            Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int lvl = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b == null ? 100 : b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            boolean plugged = b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int pct = lvl < 0 ? -1 : Math.round(lvl * 100f / scale);
            if (pct >= 0 && !plugged) {
                double rate = Power.drainPerHour(new JSONArray(app.getSharedPreferences("jarvis_power", Context.MODE_PRIVATE).getString("samples", "[]")), now);
                if (rate > 0) {
                    long minsLeft = Math.round(pct / rate * 60);
                    LocalDateTime[] d = Duty.ready(Duty.load(app)) ? Duty.nowOrNext(app) : null;
                    LocalDateTime ln = LocalDateTime.now(), dies = ln.plusMinutes(minsLeft);
                    boolean onDuty = d != null && !ln.isBefore(d[0]) && ln.isBefore(d[1]);
                    String t = "🔋 ఫోన్ ఈ వేగంతో (" + String.format(Locale.US, "%.0f", rate) + "%/గం) సుమారు " + Status.hours(minsLeft) + " వస్తుంది (" + Offline.sayWhen(dies, ln) + " వరకు)";
                    int level = minsLeft < 3 * 60 ? 2 : minsLeft < 8 * 60 ? 1 : 0;
                    if (onDuty && dies.isBefore(d[1])) { t += ": డ్యూటీ అయ్యే ముందే అయిపోతుంది, ఛార్జ్ పెట్టండి"; level = 2; }
                    out.add(new Object[]{t, level});
                }
            }
        } catch (Exception ignored) {}
        try { // medicines running out
            JSONArray meds = Medicine.listJson(app).optJSONArray("medicines");
            for (int i = 0; meds != null && i < meds.length(); i++) {
                JSONObject m = meds.optJSONObject(i);
                if (m == null || !m.has("days_left")) continue;
                int d = m.optInt("days_left");
                if (d <= 7) out.add(new Object[]{"💊 " + m.optString("name") + " ఇలాగే వాడితే ఇంకా " + d + " రోజులకే సరిపోతుంది" + (d <= 3 ? ": ఈరోజే కొనండి" : ""), d <= 3 ? 2 : 1});
            }
        } catch (Exception ignored) {}
        try { // the month's spending at this pace
            long[] sp = Day5.spending(app);
            LocalDate today = LocalDate.now();
            int day = today.getDayOfMonth(), days = YearMonth.from(today).lengthOfMonth();
            if (sp[0] >= 0 && sp[1] > 0 && day >= 5) {
                long proj = spendProjection(sp[0], day, days);
                if (proj > sp[1]) out.add(new Object[]{"💸 ఈ వేగంతో నెల చివరికి సుమారు ₹" + Sums.num(proj) + " ఖర్చు అవుతుంది, బడ్జెట్ ₹" + Sums.num(sp[1]) + " కంటే ₹" + Sums.num(proj - sp[1]) + " ఎక్కువ (SMS లెక్క, అంచనా)", proj > sp[1] * 1.2 ? 2 : 1});
                else out.add(new Object[]{"💸 ఈ వేగంతో నెల ఖర్చు సుమారు ₹" + Sums.num(proj) + ": బడ్జెట్ ₹" + Sums.num(sp[1]) + " లోపే", 0});
            }
        } catch (Exception ignored) {}
        try { // sleep owed
            double[] sd = Day5.sleepDebt(app);
            if (sd[1] >= 3) out.add(new Object[]{"😴 గత 2 రోజుల్లో " + String.format(Locale.US, "%.0f", sd[0]) + " గంటలే నిద్ర; ఇలాగే కొనసాగితే అలసట, ఏకాగ్రత తగ్గుతుంది. ఈరోజు త్వరగా పడుకోండి", sd[1] >= 6 ? 2 : 1});
        } catch (Exception ignored) {}
        try { // the bike's service km at this week's riding
            long[] svc = Travel.serviceDue(app);
            if (svc != null && svc[2] > 0) {
                double km14 = 0;
                for (JSONObject r : Bike.list(app, "rides", now - 14 * DAY)) km14 += r.optDouble("km");
                int days = kmDaysLeft(svc[2], km14 / 14.0);
                if (days >= 0 && days < svc[0]) out.add(new Object[]{"🛵 ఇలాగే తిరిగితే బైక్ సర్వీస్ కి.మీ సుమారు " + days + " రోజుల్లో నిండుతుంది (తేదీ ప్రకారం " + svc[0] + " రోజులు); ముందే సర్వీస్ బుక్ చేయండి", days <= 7 ? 2 : 1});
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** (pure) the month's spend at this pace. */
    static long spendProjection(long spent, int dayOfMonth, int daysInMonth) {
        if (dayOfMonth <= 0) return spent;
        return Math.round(spent * (double) daysInMonth / dayOfMonth);
    }

    /** (pure) days until kmLeft is used up at kmPerDay; -1 when he hardly rides. */
    static int kmDaysLeft(double kmLeft, double kmPerDay) {
        if (kmPerDay < 0.5 || kmLeft <= 0) return -1;
        return (int) Math.ceil(kmLeft / kmPerDay);
    }

    static String ifContinuesText(Context c) {
        List<Object[]> l = ifContinues(c);
        if (l.isEmpty()) return "ఇప్పుడు చెప్పేంత ధోరణి ఏదీ లేదు: బ్యాటరీ, మందులు, ఖర్చు, నిద్ర, బైక్ సర్వీస్ అన్నీ సరిగా ఉన్నాయి (లేదా లెక్కకి సరిపడా సమాచారం లేదు).";
        StringBuilder b = new StringBuilder("🔮 ఇలాగే కొనసాగితే:\n");
        for (Object[] o : l) b.append((int) o[1] >= 2 ? "🔴 " : (int) o[1] == 1 ? "🟠 " : "🟢 ").append(o[0]).append("\n");
        return b.toString().trim() + "\n(అన్నీ అంచనాలే.)";
    }

    // ---- the evening word (his switch)

    static boolean eveningOn(Context c) { return new Prefs(c).sp.getBoolean("forecast_evening", true); }

    /** Between 6 and 9 in the evening, once a day, only when something is red. */
    static void tick(Context c, Prefs p, boolean hush) {
        if (hush || !eveningOn(c) || MainActivity.busyTalking()) return;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 18 || hour >= 21) return;
        SharedPreferences s = c.getSharedPreferences("jarvis_power", Context.MODE_PRIVATE);
        String day = LocalDate.now().toString();
        if (day.equals(s.getString("forecast_said", ""))) return;
        List<String> red = new ArrayList<>();
        for (Object[] o : ifContinues(c)) if ((int) o[1] >= 2) red.add((String) o[0]);
        s.edit().putString("forecast_said", day).apply(); // (looked once a day, said or not)
        if (red.isEmpty()) return;
        String text = "🔮 ఇలాగే కొనసాగితే: " + red.get(0) + (red.size() > 1 ? ". " + red.get(1) : "") + ". (అంచనా)";
        Proactive.say(c, text, null, null);
    }

    // ---- small helpers

    private static LocalDateTime at(long ms) { return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()); }

    private static String clock(long ms) { return new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date(ms)); }
}
