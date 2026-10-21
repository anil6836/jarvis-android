package com.anil.jarvis;

import android.content.Context;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🧾 The trip diary: each drive / ride (while the drive alerts run) kept on the phone only — its line on the map
 * (a point every 250 m), when, km, time, the top speed, where he stopped for 5 minutes or more, where he was going
 * and the toll Google gave for it. "ఈ నెల ఎన్ని కి.మీ.?" and the list on Jarvis Maps read it. The newest 300 are
 * kept; he can take one or all off. Never sent anywhere, and not in the backup (where he has been is his alone).
 */
final class TripLog {
    private TripLog() {}

    static final String FILE = "jarvis_trips.json";
    private static final Object LOCK = new Object();

    // the trip being recorded (the drive service's thread: the main one)
    private static final List<double[]> pts = new ArrayList<>();
    private static final JSONArray stops = new JSONArray();
    private static long startAt, stillSince;
    private static double maxKmh;
    private static Location stillAt;
    private static String dest = "";

    static boolean on(Context c) { return new Prefs(c).sp.getBoolean("maps_trip_log", true); }

    /** Every GPS fix of the drive alerts (cheap: a point only every 250 m). */
    static void fix(Context c, Location l, double kmh) {
        if (l == null || l.getAccuracy() > 60) return;
        long now = System.currentTimeMillis();
        if (startAt == 0) {
            if (kmh < 8) return; // (begins when he really moves)
            startAt = now;
        }
        if (kmh > maxKmh && kmh < 250) maxKmh = kmh;
        double[] last = pts.isEmpty() ? null : pts.get(pts.size() - 1);
        if (last == null || MapMath.meters(last[0], last[1], l.getLatitude(), l.getLongitude()) >= 250) {
            if (pts.size() < 1500) pts.add(new double[]{l.getLatitude(), l.getLongitude()});
            else pts.set(pts.size() - 1, new double[]{l.getLatitude(), l.getLongitude()}); // (a very long trip: the end still follows him)
        }
        // a stop of 5 minutes or more (tea, fuel, food)
        if (kmh < 3) {
            if (stillSince == 0) { stillSince = now; stillAt = l; }
        } else if (kmh > 10 && stillSince != 0) {
            long mins = (now - stillSince) / 60_000;
            if (mins >= 5 && stillAt != null && stops.length() < 30) {
                try {
                    stops.put(new JSONObject().put("lat", r5(stillAt.getLatitude())).put("lon", r5(stillAt.getLongitude()))
                            .put("minutes", mins).put("at", stillSince));
                } catch (Exception ignored) {}
            }
            stillSince = 0;
            stillAt = null;
        }
    }

    /** Where he is going, as Jarvis knows it now (kept for the diary even after the arrival clears it). */
    static void destination(String name) { if (name != null && !name.trim().isEmpty()) dest = name.trim(); }

    /** The drive alerts ended: the trip is saved (2 km or more) — the places' names looked up off the main thread. */
    static void end(Context c, double meters, boolean bike) {
        final List<double[]> line = new ArrayList<>(pts);
        final JSONArray st = stops;
        final long a = startAt, b = System.currentTimeMillis();
        final double top = maxKmh;
        final String to = dest;
        pts.clear();
        startAt = 0;
        stillSince = 0;
        stillAt = null;
        maxKmh = 0;
        dest = "";
        final JSONArray stopsCopy;
        try { stopsCopy = new JSONArray(st.toString()); } catch (Exception e) { return; }
        while (stops.length() > 0) stops.remove(0);
        if (a == 0 || meters < 2000 || line.size() < 2 || !on(c)) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject t = new JSONObject().put("id", Long.toString(a, 36)).put("start", a).put("end", b).put("km", Math.round(meters / 100) / 10.0)
                        .put("minutes", Math.round((b - a) / 60_000.0)).put("max_kmh", Math.round(top)).put("by", bike ? "bike" : "car")
                        .put("line", MapMath.encode(line)).put("stops", stopsCopy);
                if (!to.isEmpty()) t.put("dest", to);
                String from = town(app, line.get(0)), till = town(app, line.get(line.size() - 1));
                if (!from.isEmpty()) t.put("from", from);
                if (!till.isEmpty()) t.put("to", till);
                android.content.SharedPreferences s = Drive.settings(app);
                if (!to.isEmpty() && to.equals(s.getString("trip_toll_dest", "")) && !s.getString("trip_toll", "").isEmpty() && s.getLong("trip_toll_at", 0) >= a)
                    t.put("toll", s.getString("trip_toll", "")); // (Google's toll for this very trip, never an older one)
                s.edit().remove("trip_toll").remove("trip_toll_dest").remove("trip_toll_at").apply();
                add(app, t);
            } catch (Exception ignored) {}
        }, "trip-log").start();
    }

    private static String town(Context c, double[] p) {
        try {
            List<android.location.Address> a = new android.location.Geocoder(c, new Locale("te", "IN")).getFromLocation(p[0], p[1], 1);
            if (a != null && !a.isEmpty()) {
                android.location.Address ad = a.get(0);
                String n = ad.getLocality() != null ? ad.getLocality() : ad.getSubLocality() != null ? ad.getSubLocality() : ad.getSubAdminArea();
                return n == null ? "" : n;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static double r5(double v) { return Math.round(v * 100000) / 100000.0; }

    // ---------------------------------------------------------------- the kept list

    static JSONArray all(Context c) {
        synchronized (LOCK) {
            try {
                File f = new File(c.getFilesDir(), FILE);
                if (!f.exists()) return new JSONArray();
                return new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return new JSONArray();
            }
        }
    }

    private static void write(Context c, JSONArray a) throws Exception {
        File f = new File(c.getFilesDir(), FILE), tmp = new File(c.getFilesDir(), FILE + ".part");
        java.nio.file.Files.write(tmp.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) { java.nio.file.Files.copy(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); tmp.delete(); }
    }

    static void add(Context c, JSONObject t) throws Exception {
        synchronized (LOCK) {
            JSONArray a = all(c), out = new JSONArray();
            int from = Math.max(0, a.length() - 299);
            for (int i = from; i < a.length(); i++) out.put(a.get(i));
            out.put(t);
            write(c, out);
        }
    }

    static boolean remove(Context c, String id) {
        synchronized (LOCK) {
            try {
                JSONArray a = all(c), out = new JSONArray();
                boolean gone = false;
                for (int i = 0; i < a.length(); i++) { JSONObject t = a.optJSONObject(i); if (t != null && id.equals(t.optString("id"))) gone = true; else out.put(t); }
                if (gone) write(c, out);
                return gone;
            } catch (Exception e) { return false; }
        }
    }

    static boolean clear(Context c) {
        synchronized (LOCK) { return new File(c.getFilesDir(), FILE).delete(); }
    }

    /** Newest first (at most n). */
    static List<JSONObject> latest(Context c, int n) {
        JSONArray a = all(c);
        List<JSONObject> out = new ArrayList<>();
        for (int i = a.length() - 1; i >= 0 && out.size() < n; i--) if (a.optJSONObject(i) != null) out.add(a.optJSONObject(i));
        return out;
    }

    /** A month's sums ("yyyyMM"): trips, km, hours, the tolls Google gave (₹, only for trips where it was known). */
    static JSONObject month(JSONArray a, String yyyyMM) throws Exception {
        int n = 0, tollRs = 0, tollTrips = 0;
        double km = 0, min = 0;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMM", Locale.US);
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null || !yyyyMM.equals(f.format(new java.util.Date(t.optLong("start"))))) continue;
            n++;
            km += t.optDouble("km", 0);
            min += t.optDouble("minutes", 0);
            String toll = t.optString("toll", "");
            if (toll.startsWith("₹")) { try { tollRs += Integer.parseInt(toll.substring(1).trim()); tollTrips++; } catch (Exception ignored) {} }
        }
        JSONObject o = new JSONObject().put("month", yyyyMM).put("trips", n).put("km", Math.round(km)).put("hours", Math.round(min / 6.0) / 10.0);
        if (tollTrips > 0) o.put("toll_rupees", tollRs).put("toll_known_for_trips", tollTrips);
        return o;
    }

    /** For the brain: this month's and last month's sums and the last 5 trips. */
    static JSONObject summary(Context c) throws Exception {
        JSONArray a = all(c);
        java.util.Calendar k = java.util.Calendar.getInstance();
        String now = new java.text.SimpleDateFormat("yyyyMM", Locale.US).format(k.getTime());
        k.add(java.util.Calendar.MONTH, -1);
        String prev = new java.text.SimpleDateFormat("yyyyMM", Locale.US).format(k.getTime());
        JSONArray last = new JSONArray();
        for (JSONObject t : latest(c, 5)) {
            last.put(new JSONObject().put("when", new java.text.SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(new java.util.Date(t.optLong("start"))))
                    .put("from", t.optString("from")).put("to", t.optString("to", t.optString("dest"))).put("km", t.optDouble("km")).put("minutes", t.optLong("minutes"))
                    .put("by", t.optString("by")).put("toll", t.optString("toll", "")).put("stops", t.optJSONArray("stops") == null ? 0 : t.optJSONArray("stops").length()));
        }
        return new JSONObject().put("ok", true).put("this_month", month(a, now)).put("last_month", month(a, prev)).put("last_trips", last)
                .put("diary_on", on(c)).put("next", a.length() == 0 ? "No trips kept yet: they are kept while the drive alerts run (2 km or more). Say so."
                        : "Say in short Telugu what he asked: this month's trips, km, hours (and tolls if known), or the last trips. The full list with each line is on Jarvis Maps → 🧾.");
    }
}
