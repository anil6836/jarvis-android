package com.anil.jarvis;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Phase 4: what Samsung Health (watch and phone) shares through Health Connect, read on the phone (Android 14+): sleep
 * with its stages, oxygen (SpO2), steps, heart rate and resting heart rate, weight, body fat and blood pressure; and (his
 * "అన్నీ కలుపు") height, workouts with their distance and calories, VO2 max, sugar, food logged and BMR. Only
 * read, never written; kept nowhere but where Jarvis uses it. Samsung keeps its own stress number, ECG and energy score
 * to itself, so those are not here. Every call blocks (up to ~8 s): background threads only. Null / -1: not allowed,
 * not shared (Samsung Health → Settings → Health Connect), or none.
 */
final class HealthData {
    private HealthData() {}

    static final String[] PERMS = {
            "android.permission.health.READ_STEPS", "android.permission.health.READ_HEART_RATE", "android.permission.health.READ_RESTING_HEART_RATE",
            "android.permission.health.READ_SLEEP", "android.permission.health.READ_OXYGEN_SATURATION", "android.permission.health.READ_WEIGHT",
            "android.permission.health.READ_BODY_FAT", "android.permission.health.READ_BLOOD_PRESSURE",
            // (his choice, "అన్నీ కలుపు": the rest of what Samsung Health shares)
            "android.permission.health.READ_HEIGHT", "android.permission.health.READ_DISTANCE", "android.permission.health.READ_EXERCISE",
            "android.permission.health.READ_TOTAL_CALORIES_BURNED", "android.permission.health.READ_VO2_MAX", "android.permission.health.READ_BLOOD_GLUCOSE",
            "android.permission.health.READ_NUTRITION", "android.permission.health.READ_BASAL_METABOLIC_RATE"};
    /** Android 15+: reading when Jarvis isn't on the screen (the morning report, the weekly report). */
    static final String BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND";
    /** Android 15+: data older than the 30 days before access was given (his height, set long ago). */
    static final String HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY";

    /** The last trouble, for Settings / "Jarvis చెక్". */
    static volatile String lastError = "";

    static boolean available(Context c) {
        if (Build.VERSION.SDK_INT < 34) return false;
        try { return Api34.manager(c) != null; } catch (Throwable e) { return false; }
    }

    static boolean granted(Context c, String perm) { return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    /** At least sleep or steps can be read. */
    static boolean connected(Context c) {
        return available(c) && (granted(c, PERMS[0]) || granted(c, PERMS[3]) || granted(c, PERMS[4]));
    }

    /** The permissions still to ask (Health Connect's own page shows them all together). */
    static String[] toAsk(Context c) {
        List<String> l = new ArrayList<>();
        for (String p : PERMS) if (!granted(c, p)) l.add(p);
        if (Build.VERSION.SDK_INT >= 35 && !granted(c, BACKGROUND)) l.add(BACKGROUND);
        return l.toArray(new String[0]);
    }

    /** Android 15+: older data not allowed yet (asked on its own after the others, so a phone without it loses nothing else). */
    static boolean historyToAsk(Context c) { return Build.VERSION.SDK_INT >= 35 && available(c) && !granted(c, HISTORY); }

    /** Why a kind can't be read now (in Telugu), or null when it can: not connected yet, or the last read failed. */
    static String notReady(Context c, int perm) {
        if (Build.VERSION.SDK_INT < 34 || !available(c)) return status(c);
        if (!granted(c, PERMS[perm])) return "ఇది ఇంకా Jarvis కి కలపలేదు: Settings → ⌚ వాచ్ → ❤️ ఆరోగ్యం → \"🔗 Health Connect కలపండి\" నొక్కి Allow ఇవ్వండి.";
        return null;
    }

    /** A line for Settings: what is connected. */
    static String status(Context c) {
        if (Build.VERSION.SDK_INT < 34) return "Health Connect కి Android 14 కావాలి";
        if (!available(c)) return "ఈ ఫోన్‌లో Health Connect దొరకలేదు";
        int n = 0;
        for (String p : PERMS) if (granted(c, p)) n++;
        String s = n == 0 ? "✗ ఇంకా అనుమతి ఇవ్వలేదు (కింది బటన్)" : "✓ " + n + " / " + PERMS.length + " రకాలు చదవగలను";
        if (Build.VERSION.SDK_INT >= 35 && n > 0 && !granted(c, BACKGROUND)) s += " · బ్యాక్‌గ్రౌండ్‌లో చదవడానికి అనుమతి లేదు (ఉదయం రిపోర్ట్‌కి కావాలి)";
        if (!lastError.isEmpty()) s += "\n· " + lastError;
        return s;
    }

    // ---------------------------------------------------------------- readers (background threads)

    /** The last sleep that ended in the last 20 hours: {start, end, minutes, deep, rem, light, awake (minutes)}; null if none. */
    static JSONObject lastSleep(Context c) {
        if (!can(c, 3)) return null;
        try { return Api34.lastSleep(c); } catch (Throwable e) { fail(e); return null; }
    }

    /** The sleeps that ended in the last N days, oldest first (each like lastSleep's). */
    static JSONArray sleeps(Context c, int days) {
        if (!can(c, 3)) return new JSONArray();
        try { return Api34.sleeps(c, days); } catch (Throwable e) { fail(e); return new JSONArray(); }
    }

    /** Oxygen in [from, to): {min, avg, n, last, at}; null if none. */
    static JSONObject spo2(Context c, long from, long to) {
        if (!can(c, 4)) return null;
        try { return Api34.spo2(c, from, to); } catch (Throwable e) { fail(e); return null; }
    }

    /** Steps in [from, to) from all of Samsung Health (watch and phone, counted once); -1 if not known. */
    static long steps(Context c, long from, long to) {
        if (!can(c, 0)) return -1;
        try { return Api34.steps(c, from, to); } catch (Throwable e) { fail(e); return -1; }
    }

    /** Resting heart rate: Samsung's own (or the lowest of the heart-rate samples) in [from, to); -1 if none. */
    static int restingHr(Context c, long from, long to) {
        if (!can(c, 2) && !can(c, 1)) return -1;
        try { return Api34.resting(c, from, to); } catch (Throwable e) { fail(e); return -1; }
    }

    /** Readings of the last N days, oldest first: weight [{t, kg}], body fat [{t, pct}], blood pressure [{t, sys, dia}]. */
    static JSONArray weights(Context c, int days) {
        if (!can(c, 5)) return new JSONArray();
        try { return Api34.weights(c, days); } catch (Throwable e) { fail(e); return new JSONArray(); }
    }

    static JSONArray bodyFat(Context c, int days) {
        if (!can(c, 6)) return new JSONArray();
        try { return Api34.fat(c, days); } catch (Throwable e) { fail(e); return new JSONArray(); }
    }

    static JSONArray bloodPressure(Context c, int days) {
        if (!can(c, 7)) return new JSONArray();
        try { return Api34.bp(c, days); } catch (Throwable e) { fail(e); return new JSONArray(); }
    }

    /**
     * The night's sleep out of the sleeps that ended lately (pure): sleeps less than 3 hours apart are one night (a
     * night broken by waking up); the night is the group with the most sleep (so an afternoon nap is not "last night").
     */
    static JSONObject mainSleep(List<JSONObject> l) throws Exception {
        if (l.isEmpty()) return null;
        List<JSONObject> s = new ArrayList<>(l);
        s.sort((x, y) -> Long.compare(x.optLong("start"), y.optLong("start")));
        JSONObject best = null, cur = null;
        for (JSONObject o : s) {
            if (cur != null && o.optLong("start") < cur.optLong("end")) {
                // overlapping (the same night written by two apps): only the part after the group's end is added
                long extra = Math.max(0, o.optLong("end") - cur.optLong("end")) / 60_000L;
                cur.put("minutes", cur.optLong("minutes") + Math.min(extra, o.optLong("minutes")));
                cur.put("end", Math.max(cur.optLong("end"), o.optLong("end")));
            } else if (cur != null && o.optLong("start") - cur.optLong("end") <= 3 * 3600_000L) {
                cur.put("end", o.optLong("end"));
                for (String k : new String[]{"minutes", "deep", "rem", "light", "awake"}) cur.put(k, cur.optLong(k) + o.optLong(k));
            } else {
                cur = new JSONObject(o.toString());
            }
            cur.put("minutes", Math.min(cur.optLong("minutes"), (cur.optLong("end") - cur.optLong("start")) / 60_000L));
            if (best == null || cur.optLong("minutes") >= best.optLong("minutes")) best = cur;
        }
        return best;
    }

    // ---------------------------------------------------------------- the rest of Samsung Health ("అన్నీ కలుపు")

    /** His height in cm (the latest), -1 if none. */
    static double heightCm(Context c) {
        if (!can(c, 8)) return -1;
        try { return Api34.height(c); } catch (Throwable e) { fail(e); return -1; }
    }

    /** Distance in [from, to) in metres (Samsung sends it for workouts: a walk he starts, or one it found itself); -1 if not known. */
    static double distanceM(Context c, long from, long to) {
        if (!can(c, 9)) return -1;
        try { return Api34.distance(c, from, to); } catch (Throwable e) { fail(e); return -1; }
    }

    /** Calories burned in [from, to) in kcal (Samsung sends its workouts' calories); -1 if not known. */
    static double kcalBurned(Context c, long from, long to) {
        if (!can(c, 11)) return -1;
        try { return Api34.kcal(c, from, to); } catch (Throwable e) { fail(e); return -1; }
    }

    /** Workouts in [from, to), oldest first: [{start, end, type, title, m, kcal}] (m / kcal -1 when not shared). */
    static JSONArray exercises(Context c, long from, long to) {
        if (!can(c, 10)) return new JSONArray();
        try { return Api34.exercises(c, from, to); } catch (Throwable e) { fail(e); return new JSONArray(); }
    }

    /** The latest VO2 max in the last N days {v, t}, or null. */
    static JSONObject vo2max(Context c, int days) {
        if (!can(c, 12)) return null;
        try { return Api34.vo2(c, days); } catch (Throwable e) { fail(e); return null; }
    }

    /** Sugar readings of the last N days, oldest first: [{t, mgdl, when: fasting / after_food / random}]. */
    static JSONArray glucose(Context c, int days) {
        if (!can(c, 13)) return new JSONArray();
        try { return Api34.glucose(c, days); } catch (Throwable e) { fail(e); return new JSONArray(); }
    }

    /** What he logged eating in [from, to): {kcal, protein, carbs, fat (grams)}; null if nothing. */
    static JSONObject food(Context c, long from, long to) {
        if (!can(c, 14)) return null;
        try { return Api34.food(c, from, to); } catch (Throwable e) { fail(e); return null; }
    }

    /** His resting calories a day (BMR, from body composition), -1 if none. */
    static double bmrKcal(Context c) {
        if (!can(c, 15)) return -1;
        try { return Api34.bmr(c); } catch (Throwable e) { fail(e); return -1; }
    }

    /** Energy from Health Connect (always small calories) to kcal. */
    static double kcal(double calories) { return calories / 1000.0; }

    /** BMR as power (watts) to kcal a day. */
    static double bmrPerDay(double watts) { return watts * 86_400 / 4184.0; }

    private static boolean can(Context c, int perm) { return available(c) && granted(c, PERMS[perm]); }

    private static void fail(Throwable e) {
        String m = String.valueOf(e.getMessage());
        lastError = "Health Connect: " + (m.length() > 120 ? m.substring(0, 120) : m);
    }

    /** All of it here, so phones before Android 14 never load these classes. */
    @android.annotation.TargetApi(34)
    private static final class Api34 {
        static android.health.connect.HealthConnectManager manager(Context c) { return c.getSystemService(android.health.connect.HealthConnectManager.class); }

        private static android.health.connect.TimeInstantRangeFilter range(long from, long to) {
            return new android.health.connect.TimeInstantRangeFilter.Builder().setStartTime(java.time.Instant.ofEpochMilli(from))
                    .setEndTime(java.time.Instant.ofEpochMilli(to)).build();
        }

        static <T extends android.health.connect.datatypes.Record> List<T> read(Context c, Class<T> type, long from, long to) throws Exception {
            android.health.connect.HealthConnectManager m = manager(c);
            if (m == null) throw new IllegalStateException("Health Connect లేదు");
            final List<T> out = new ArrayList<>();
            long token = -1;
            for (int page = 0; page < 20; page++) { // (all pages: 14 days of heart rate can be more than one)
                android.health.connect.ReadRecordsRequestUsingFilters.Builder<T> b = new android.health.connect.ReadRecordsRequestUsingFilters.Builder<>(type)
                        .setTimeRangeFilter(range(from, to)).setPageSize(2000);
                if (token != -1) b.setPageToken(token);
                final long[] next = {-1};
                final Exception[] err = {null};
                final CountDownLatch done = new CountDownLatch(1);
                m.readRecords(b.build(), Runnable::run, new android.os.OutcomeReceiver<android.health.connect.ReadRecordsResponse<T>, android.health.connect.HealthConnectException>() {
                    @Override public void onResult(android.health.connect.ReadRecordsResponse<T> r) { out.addAll(r.getRecords()); next[0] = r.getNextPageToken(); done.countDown(); }
                    @Override public void onError(android.health.connect.HealthConnectException e) { err[0] = e; done.countDown(); }
                });
                if (!done.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Health Connect జవాబు ఇవ్వలేదు");
                if (err[0] != null) throw err[0];
                token = next[0];
                if (token == -1) break;
            }
            lastError = "";
            return out;
        }

        static JSONObject lastSleep(Context c) throws Exception {
            long now = System.currentTimeMillis();
            List<JSONObject> l = new ArrayList<>();
            for (android.health.connect.datatypes.SleepSessionRecord r : read(c, android.health.connect.datatypes.SleepSessionRecord.class, now - 36 * 3600_000L, now))
                if (r.getEndTime().toEpochMilli() >= now - 22 * 3600_000L) l.add(sleepJson(r));
            return mainSleep(l);
        }

        static JSONArray sleeps(Context c, int days) throws Exception {
            long now = System.currentTimeMillis();
            List<android.health.connect.datatypes.SleepSessionRecord> l = read(c, android.health.connect.datatypes.SleepSessionRecord.class,
                    now - (days + 1) * 86400_000L, now);
            l.sort((x, y) -> x.getEndTime().compareTo(y.getEndTime()));
            JSONArray a = new JSONArray();
            for (android.health.connect.datatypes.SleepSessionRecord r : l)
                if (r.getEndTime().toEpochMilli() >= now - days * 86400_000L) a.put(sleepJson(r));
            return a;
        }

        private static JSONObject sleepJson(android.health.connect.datatypes.SleepSessionRecord best) throws Exception {
            long s = best.getStartTime().toEpochMilli(), e = best.getEndTime().toEpochMilli();
            long deep = 0, rem = 0, light = 0, awake = 0;
            for (android.health.connect.datatypes.SleepSessionRecord.Stage st : best.getStages()) {
                long m = (st.getEndTime().toEpochMilli() - st.getStartTime().toEpochMilli()) / 60_000L;
                switch (st.getType()) {
                    case 5: deep += m; break;  // STAGE_TYPE_SLEEPING_DEEP
                    case 6: rem += m; break;   // STAGE_TYPE_SLEEPING_REM
                    case 4: case 2: light += m; break; // light / sleeping
                    case 1: case 3: case 7: awake += m; break;
                    default:
                }
            }
            long minutes = (e - s) / 60_000L - awake;
            return new JSONObject().put("start", s).put("end", e).put("minutes", Math.max(0, minutes)).put("deep", deep).put("rem", rem)
                    .put("light", light).put("awake", awake);
        }

        static JSONObject spo2(Context c, long from, long to) throws Exception {
            List<android.health.connect.datatypes.OxygenSaturationRecord> l = read(c, android.health.connect.datatypes.OxygenSaturationRecord.class, from, to);
            if (l.isEmpty()) return null;
            double min = 101, sum = 0, last = 0;
            long at = 0;
            int n = 0;
            for (android.health.connect.datatypes.OxygenSaturationRecord r : l) {
                double v = r.getPercentage().getValue();
                if (v < 50 || v > 100) continue;
                min = Math.min(min, v);
                sum += v;
                n++;
                long t = r.getTime().toEpochMilli();
                if (t >= at) { at = t; last = v; }
            }
            if (at == 0) return null;
            return new JSONObject().put("min", Math.round(min)).put("avg", Math.round(sum / n)).put("n", n).put("last", Math.round(last)).put("at", at);
        }

        static long steps(Context c, long from, long to) throws Exception {
            android.health.connect.HealthConnectManager m = manager(c);
            if (m == null) return -1;
            android.health.connect.AggregateRecordsRequest<Long> req = new android.health.connect.AggregateRecordsRequest.Builder<Long>(range(from, to))
                    .addAggregationType(android.health.connect.datatypes.StepsRecord.STEPS_COUNT_TOTAL).build();
            final long[] out = {-1};
            final Exception[] err = {null};
            final CountDownLatch done = new CountDownLatch(1);
            m.aggregate(req, Runnable::run, new android.os.OutcomeReceiver<android.health.connect.AggregateRecordsResponse<Long>, android.health.connect.HealthConnectException>() {
                @Override public void onResult(android.health.connect.AggregateRecordsResponse<Long> r) {
                    Long v = r.get(android.health.connect.datatypes.StepsRecord.STEPS_COUNT_TOTAL);
                    out[0] = v == null ? -1 : v;
                    done.countDown();
                }
                @Override public void onError(android.health.connect.HealthConnectException e) { err[0] = e; done.countDown(); }
            });
            if (!done.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Health Connect జవాబు ఇవ్వలేదు");
            if (err[0] != null) throw err[0];
            return out[0];
        }

        static int resting(Context c, long from, long to) throws Exception {
            if (granted(c, PERMS[2])) {
                long sum = 0;
                int n = 0;
                for (android.health.connect.datatypes.RestingHeartRateRecord r : read(c, android.health.connect.datatypes.RestingHeartRateRecord.class, from, to)) {
                    sum += r.getBeatsPerMinute();
                    n++;
                }
                if (n > 0) return (int) Math.round(sum / (double) n);
            }
            if (!granted(c, PERMS[1])) return -1;
            List<Long> v = new ArrayList<>();
            for (android.health.connect.datatypes.HeartRateRecord r : read(c, android.health.connect.datatypes.HeartRateRecord.class, from, to))
                for (android.health.connect.datatypes.HeartRateRecord.HeartRateSample s : r.getSamples()) v.add(s.getBeatsPerMinute());
            if (v.size() < 5) return -1;
            java.util.Collections.sort(v);
            return (int) (long) v.get(v.size() / 10); // the low end of the day (10th percentile)
        }

        private static <T> T aggregate(Context c, android.health.connect.datatypes.AggregationType<T> type, long from, long to) throws Exception {
            android.health.connect.HealthConnectManager m = manager(c);
            if (m == null) return null;
            android.health.connect.AggregateRecordsRequest<T> req = new android.health.connect.AggregateRecordsRequest.Builder<T>(range(from, to))
                    .addAggregationType(type).build();
            final Object[] out = {null};
            final Exception[] err = {null};
            final CountDownLatch done = new CountDownLatch(1);
            m.aggregate(req, Runnable::run, new android.os.OutcomeReceiver<android.health.connect.AggregateRecordsResponse<T>, android.health.connect.HealthConnectException>() {
                @Override public void onResult(android.health.connect.AggregateRecordsResponse<T> r) { out[0] = r.get(type); done.countDown(); }
                @Override public void onError(android.health.connect.HealthConnectException e) { err[0] = e; done.countDown(); }
            });
            if (!done.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Health Connect జవాబు ఇవ్వలేదు");
            if (err[0] != null) throw err[0];
            @SuppressWarnings("unchecked") T v = (T) out[0];
            return v;
        }

        static double height(Context c) throws Exception {
            long now = System.currentTimeMillis();
            android.health.connect.datatypes.HeightRecord best = null;
            for (android.health.connect.datatypes.HeightRecord r : read(c, android.health.connect.datatypes.HeightRecord.class, now - 5 * 365 * 86400_000L, now))
                if (best == null || r.getTime().isAfter(best.getTime())) best = r;
            return best == null ? -1 : best.getHeight().getInMeters() * 100;
        }

        static double distance(Context c, long from, long to) throws Exception {
            android.health.connect.datatypes.units.Length l = aggregate(c, android.health.connect.datatypes.DistanceRecord.DISTANCE_TOTAL, from, to);
            return l == null ? -1 : l.getInMeters();
        }

        static double kcal(Context c, long from, long to) throws Exception {
            android.health.connect.datatypes.units.Energy e = aggregate(c, android.health.connect.datatypes.TotalCaloriesBurnedRecord.ENERGY_TOTAL, from, to);
            return e == null ? -1 : HealthData.kcal(e.getInCalories());
        }

        static JSONArray exercises(Context c, long from, long to) throws Exception {
            List<android.health.connect.datatypes.ExerciseSessionRecord> l = read(c, android.health.connect.datatypes.ExerciseSessionRecord.class, from, to);
            l.sort((x, y) -> x.getStartTime().compareTo(y.getStartTime()));
            JSONArray a = new JSONArray();
            for (int i = 0; i < l.size(); i++) {
                android.health.connect.datatypes.ExerciseSessionRecord r = l.get(i);
                long s = r.getStartTime().toEpochMilli(), e = r.getEndTime().toEpochMilli();
                boolean recent = i >= l.size() - 10; // (each workout's distance / calories: only the last 10, it takes a look each)
                double m = -1, k = -1;
                if (recent && granted(c, PERMS[9])) try { m = distance(c, s, e); } catch (Exception ignored) {} // (one failed look: just that number unknown)
                if (recent && granted(c, PERMS[11])) try { k = kcal(c, s, e); } catch (Exception ignored) {}
                a.put(new JSONObject().put("start", s).put("end", e).put("type", r.getExerciseType())
                        .put("title", r.getTitle() == null ? "" : r.getTitle().toString()).put("m", Math.round(m)).put("kcal", Math.round(k)));
            }
            return a;
        }

        static JSONObject vo2(Context c, int days) throws Exception {
            long now = System.currentTimeMillis();
            android.health.connect.datatypes.Vo2MaxRecord best = null;
            for (android.health.connect.datatypes.Vo2MaxRecord r : read(c, android.health.connect.datatypes.Vo2MaxRecord.class, now - days * 86400_000L, now))
                if (best == null || r.getTime().isAfter(best.getTime())) best = r;
            return best == null ? null : new JSONObject().put("v", Math.round(best.getVo2MillilitersPerMinuteKilogram() * 10) / 10.0)
                    .put("t", best.getTime().toEpochMilli());
        }

        static JSONArray glucose(Context c, int days) throws Exception {
            long now = System.currentTimeMillis();
            List<android.health.connect.datatypes.BloodGlucoseRecord> l = read(c, android.health.connect.datatypes.BloodGlucoseRecord.class, now - days * 86400_000L, now);
            l.sort((x, y) -> x.getTime().compareTo(y.getTime()));
            JSONArray a = new JSONArray();
            for (android.health.connect.datatypes.BloodGlucoseRecord r : l) {
                int rel = r.getRelationToMeal();
                a.put(new JSONObject().put("t", r.getTime().toEpochMilli()).put("mgdl", Math.round(r.getLevel().getInMillimolesPerLiter() * 18.016))
                        .put("when", rel == 2 ? "fasting" : rel == 4 ? "after_food" : "random"));
            }
            return a;
        }

        static JSONObject food(Context c, long from, long to) throws Exception {
            android.health.connect.datatypes.units.Energy e = aggregate(c, android.health.connect.datatypes.NutritionRecord.ENERGY_TOTAL, from, to);
            if (e == null || e.getInCalories() <= 0) return null;
            android.health.connect.datatypes.units.Mass p = aggregate(c, android.health.connect.datatypes.NutritionRecord.PROTEIN_TOTAL, from, to),
                    cb = aggregate(c, android.health.connect.datatypes.NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL, from, to),
                    f = aggregate(c, android.health.connect.datatypes.NutritionRecord.TOTAL_FAT_TOTAL, from, to);
            return new JSONObject().put("kcal", Math.round(HealthData.kcal(e.getInCalories())))
                    .put("protein", p == null ? -1 : Math.round(p.getInGrams())).put("carbs", cb == null ? -1 : Math.round(cb.getInGrams()))
                    .put("fat", f == null ? -1 : Math.round(f.getInGrams()));
        }

        static double bmr(Context c) throws Exception {
            long now = System.currentTimeMillis();
            android.health.connect.datatypes.BasalMetabolicRateRecord best = null;
            for (android.health.connect.datatypes.BasalMetabolicRateRecord r : read(c, android.health.connect.datatypes.BasalMetabolicRateRecord.class, now - 365 * 86400_000L, now))
                if (best == null || r.getTime().isAfter(best.getTime())) best = r;
            return best == null ? -1 : bmrPerDay(best.getBasalMetabolicRate().getInWatts());
        }

        static JSONArray weights(Context c, int days) throws Exception {
            long now = System.currentTimeMillis();
            JSONArray a = new JSONArray();
            List<android.health.connect.datatypes.WeightRecord> l = read(c, android.health.connect.datatypes.WeightRecord.class, now - days * 86400_000L, now);
            l.sort((x, y) -> x.getTime().compareTo(y.getTime()));
            for (android.health.connect.datatypes.WeightRecord r : l)
                a.put(new JSONObject().put("t", r.getTime().toEpochMilli()).put("kg", Math.round(r.getWeight().getInGrams() / 100.0) / 10.0));
            return a;
        }

        static JSONArray fat(Context c, int days) throws Exception {
            long now = System.currentTimeMillis();
            JSONArray a = new JSONArray();
            List<android.health.connect.datatypes.BodyFatRecord> l = read(c, android.health.connect.datatypes.BodyFatRecord.class, now - days * 86400_000L, now);
            l.sort((x, y) -> x.getTime().compareTo(y.getTime()));
            for (android.health.connect.datatypes.BodyFatRecord r : l)
                a.put(new JSONObject().put("t", r.getTime().toEpochMilli()).put("pct", Math.round(r.getPercentage().getValue() * 10) / 10.0));
            return a;
        }

        static JSONArray bp(Context c, int days) throws Exception {
            long now = System.currentTimeMillis();
            JSONArray a = new JSONArray();
            List<android.health.connect.datatypes.BloodPressureRecord> l = read(c, android.health.connect.datatypes.BloodPressureRecord.class, now - days * 86400_000L, now);
            l.sort((x, y) -> x.getTime().compareTo(y.getTime()));
            for (android.health.connect.datatypes.BloodPressureRecord r : l)
                a.put(new JSONObject().put("t", r.getTime().toEpochMilli()).put("sys", Math.round(r.getSystolic().getInMillimetersOfMercury()))
                        .put("dia", Math.round(r.getDiastolic().getInMillimetersOfMercury())));
            return a;
        }
    }
}
