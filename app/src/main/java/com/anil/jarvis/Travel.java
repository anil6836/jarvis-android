package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Phase 5 on the road, most of it without internet: where he is (O18), how far and which way the parked bike, home or a
 * saved place is (O26; the watch's compass W52 shows an arrow), his speed and this trip's km (O27), his rides (O19), the
 * bike's service (O44); and on his wrist: rain in about half an hour (W53), great heat (W69), a turn while walking with
 * Maps (W78: two short buzzes left, one long right), a break on a long ride (W76) and the bike's spot when he starts
 * walking after a ride (W77).
 */
final class Travel {
    private Travel() {}

    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
    static final int NOTE_SERVICE = 263, NOTE_WEATHER = 264, NOTE_PARKED = 61;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_travel", Context.MODE_PRIVATE); }

    static boolean rainOn(Context c) { return sp(c).getBoolean("rain_soon", true); }
    static boolean heatOn(Context c) { return sp(c).getBoolean("heat", true); }
    static boolean turnsOn(Context c) { return sp(c).getBoolean("turn_buzz", true); }
    static boolean rideBreakOn(Context c) { return sp(c).getBoolean("ride_break", true); }

    static void set(Context c, String key, boolean on) { sp(c).edit().putBoolean(key, on).apply(); }

    // ================================================================ places

    private static final String[] MY_HOME = {"ఇల్లు", "ఇంటి", "మా ఇల్లు", "నా ఇల్లు", "మా ఇంటి", "home", "my home", "house"};

    /** His own home {name, lat, lon}, or null. */
    static JSONObject home(Context c) {
        for (JSONObject p : Places.all(c)) {
            if (!p.has("lat")) continue;
            String n = p.optString("name").trim().toLowerCase(Locale.ROOT);
            for (String h : MY_HOME) if (n.equals(h)) return place(p.optString("name"), p.optDouble("lat"), p.optDouble("lon"), 0);
        }
        double[] g = GeoReminders.place(c, "home");
        return g == null ? null : place("ఇల్లు", g[0], g[1], 0);
    }

    /** Where the bike was parked {name, lat, lon, at}, or null. */
    static JSONObject parking(Context c) {
        double[] g = GeoReminders.place(c, "parking");
        return g == null ? null : place("బండి", g[0], g[1], Life.parkedAt(c));
    }

    private static JSONObject place(String name, double lat, double lon, long at) {
        try { return new JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("at", at); } catch (Exception e) { return null; }
    }

    /** "bike" words: the parked bike. */
    static boolean bikeWord(String t) {
        return t.matches("(?s).*(బండి|బైక్|బైకు|స్కూటర్|bike|scooter|పార్కింగ్|parking|వెహికల్|కారు|car).*");
    }

    /** The place he names: the parked bike, home, or one of his saved places. Null when none fits. */
    static JSONObject target(Context c, String spoken) {
        String t = spoken == null ? "" : spoken.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || bikeWord(t)) return parking(c);
        if (t.matches("(?s).*(ఇల్లు|ఇంటి|ఇంటికి|ఇంట్లో|home|house).*") && !t.matches("(?s).*(అమ్మ|నాన్న|అక్క|అన్న|తమ్ము|చెల్లి|మామ|అత్త|బావ|ఫ్రెండ్|friend|sister|brother|mother|father).*"))
            return home(c);
        JSONObject p = Places.find(c, spoken);
        return p != null && p.has("lat") ? place(p.optString("name"), p.optDouble("lat"), p.optDouble("lon"), 0) : null;
    }

    /** The places for the watch's compass: the bike, home, then up to five saved places. */
    static JSONArray targets(Context c) {
        JSONArray a = new JSONArray();
        try {
            JSONObject b = parking(c), h = home(c);
            if (b != null) a.put(b.put("icon", "🏍️").put("key", "parking"));
            if (h != null) a.put(h.put("icon", "🏠").put("key", "home"));
            int n = 0;
            for (JSONObject p : Places.all(c)) {
                if (!p.has("lat") || n >= 5) continue;
                if (h != null && p.optString("name").equals(h.optString("name"))) continue;
                a.put(place(p.optString("name"), p.optDouble("lat"), p.optDouble("lon"), 0).put("icon", "📍").put("key", p.optString("name")));
                n++;
            }
        } catch (Exception ignored) {}
        return a;
    }

    // ================================================================ where he is

    /** A recent fix: the drive's, else the phone's last (15 minutes), else a fresh one (background thread, up to 8 s). */
    static Location here(Context c) {
        Location l = DriveService.last;
        if (l != null && System.currentTimeMillis() - l.getTime() < 2 * MIN) return l;
        l = Tools.lastLocation(c);
        if (l != null && System.currentTimeMillis() - l.getTime() < 15 * MIN) return l;
        Location f = Devices.freshFix(c);
        return f != null ? f : l;
    }

    /** 340 -> "340 మీటర్లు", 2400 -> "2.4 కి.మీ". */
    static String dist(double m) {
        if (m < 995) return Math.max(10, Math.round(m / 10.0) * 10) + " మీటర్లు";
        String km = String.format(Locale.ENGLISH, m < 9950 ? "%.1f" : "%.0f", m / 1000.0);
        if (km.endsWith(".0")) km = km.substring(0, km.length() - 2);
        return km + " కి.మీ";
    }

    /** "ఈశాన్యం వైపు". */
    static String side(double brg) {
        String d = Drive.direction((float) ((brg % 360 + 360) % 360));
        return d.isEmpty() ? "" : d + " వైపు";
    }

    private static String age(Location l) {
        long m = (System.currentTimeMillis() - l.getTime()) / MIN;
        return m < 3 ? "" : " (" + (m < 60 ? m + " నిమిషాల" : m / 60 + " గంటల") + " క్రితం GPS ప్రకారం)";
    }

    /** O18: "నేను ఎక్కడ ఉన్నాను?" without internet: the nearest of his places, its way and distance, and the GPS. */
    static String whereAmI(Context c) {
        Location l = here(c);
        if (l == null) return "లొకేషన్ దొరకలేదు. GPS ఆన్ చేసి కాసేపు బయట ఉండి మళ్లీ అడగండి.";
        JSONObject best = null;
        double bd = Double.MAX_VALUE;
        JSONArray all = targets(c);
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            if (p == null || "parking".equals(p.optString("key"))) continue;
            double d = Drive.meters(l.getLatitude(), l.getLongitude(), p.optDouble("lat"), p.optDouble("lon"));
            if (d < bd) { bd = d; best = p; }
        }
        String gps = String.format(Locale.ENGLISH, "%.5f, %.5f", l.getLatitude(), l.getLongitude());
        StringBuilder b = new StringBuilder();
        if (best != null && bd < 150) b.append("మీరు ").append(best.optString("name")).append(" దగ్గరే ఉన్నారు");
        else if (best != null && bd < 30_000) {
            double brg = Drive.bearing(best.optDouble("lat"), best.optDouble("lon"), l.getLatitude(), l.getLongitude());
            b.append("మీరు ").append(best.optString("name")).append(" నుంచి ").append(side(brg)).append(" ").append(dist(bd)).append(" దూరంలో ఉన్నారు");
        } else b.append("మీ సేవ్ చేసిన చోట్లకి దూరంగా ఉన్నారు");
        b.append(age(l)).append(". GPS: ").append(gps).append(".");
        if (!Net.online(c)) b.append(" ఊరి పేరు నెట్ లేకుండా చెప్పలేను; \"అమ్మకి నా లొకేషన్ పంపు\" అంటే SMS లో మ్యాప్ లింక్ పంపుతాను.");
        return b.toString();
    }

    /** O26: how far and which way the bike, home or a saved place is (no internet needed). */
    static String toPlace(Context c, String spoken) {
        JSONObject p = target(c, spoken);
        if (p == null) {
            if (bikeWord(spoken == null ? "" : spoken.toLowerCase(Locale.ROOT))) return "బండి పెట్టిన చోటు సేవ్ అయి లేదు. బండి పెట్టినప్పుడు \"బండి ఇక్కడ పెట్టాను\" అనండి.";
            return "ఆ చోటు నా దగ్గర సేవ్ అయి లేదు. అక్కడ ఉన్నప్పుడు \"ఈ చోటు … గా సేవ్ చెయ్\" అనండి.";
        }
        Location l = here(c);
        if (l == null) return "మీరు ఎక్కడ ఉన్నారో GPS దొరకలేదు. GPS ఆన్ చేసి మళ్లీ అడగండి.";
        double d = Drive.meters(l.getLatitude(), l.getLongitude(), p.optDouble("lat"), p.optDouble("lon"));
        String name = p.optString("name");
        if (d < 30) return name + " ఇక్కడే, మీకు దగ్గరలోనే ఉంది.";
        double brg = Drive.bearing(l.getLatitude(), l.getLongitude(), p.optDouble("lat"), p.optDouble("lon"));
        StringBuilder b = new StringBuilder(name).append(" ఇక్కడి నుంచి ").append(side(brg)).append(" ").append(dist(d)).append(" దూరంలో ఉంది");
        long at = p.optLong("at");
        if (at > 0) b.append(" (").append(Offline.sayWhen(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), java.time.ZoneId.systemDefault()), LocalDateTime.now())).append(" పెట్టారు)");
        b.append(age(l)).append(".");
        if (d < 3000 && WatchHub.known(c)) b.append(" వాచ్‌లో 🧭 దారి తెరిస్తే బాణం చూపిస్తుంది.");
        return b.toString();
    }

    /** O27: his speed now (the drive's, else a fresh GPS). */
    static String speed(Context c) {
        Location l = DriveService.last;
        if (l == null || System.currentTimeMillis() - l.getTime() > 20_000L) l = Devices.freshFix(c);
        if (l == null) return "GPS దొరకలేదు, స్పీడ్ చెప్పలేను.";
        if (!l.hasSpeed() || System.currentTimeMillis() - l.getTime() > 60_000L) return "ఇప్పుడు స్పీడ్ తెలియడం లేదు (GPS కి కదలిక కనిపించాలి).";
        int kmh = Math.round(l.getSpeed() * 3.6f);
        return kmh < 3 ? "ఇప్పుడు ఆగి ఉన్నారు." : "ఇప్పుడు గంటకు సుమారు " + kmh + " కి.మీ వేగంతో వెళ్తున్నారు.";
    }

    /** O27: this ride's / drive's km so far, else how far from home. */
    static String trip(Context c) {
        SharedPreferences b = c.getSharedPreferences("jarvis_bike", Context.MODE_PRIVATE);
        if (Bike.riding(c)) {
            long mins = (System.currentTimeMillis() - b.getLong("start", System.currentTimeMillis())) / MIN;
            return "ఈ రైడ్‌లో ఇప్పటికి సుమారు " + dist(b.getFloat("km", 0) * 1000.0) + ", " + mins + " నిమిషాలు.";
        }
        if (DriveService.running && DriveService.tripMeters > 50) return "ఈ ప్రయాణంలో ఇప్పటికి సుమారు " + dist(DriveService.tripMeters) + ".";
        JSONObject h = home(c);
        Location l = here(c);
        if (h == null || l == null) return "ఇప్పుడు రైడ్ లెక్క జరగడం లేదు.";
        return "ఇంటి నుంచి నేరుగా " + dist(Drive.meters(h.optDouble("lat"), h.optDouble("lon"), l.getLatitude(), l.getLongitude())) + " దూరంలో ఉన్నారు.";
    }

    /** O19: rides of today / yesterday / this week / this month, from the bike's log. */
    static String rides(Context c, String t) {
        long now = System.currentTimeMillis(), dayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long since = dayStart, until = Long.MAX_VALUE;
        String when = "ఈరోజు";
        if (t.contains("నిన్న")) { since = dayStart - DAY; until = dayStart; when = "నిన్న"; }
        else if (t.contains("వారం") || t.contains("week")) { since = dayStart - 6 * DAY; when = "ఈ వారం"; }
        else if (t.contains("నెల") || t.contains("month")) { since = java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(); when = "ఈ నెల"; }
        double km = 0;
        int mins = 0, n = 0;
        for (JSONObject r : Bike.list(c, "rides", since)) {
            if (r.optLong("start") >= until) continue;
            km += r.optDouble("km");
            mins += r.optInt("minutes");
            n++;
        }
        String now2 = Bike.riding(c) && until == Long.MAX_VALUE ? " ఇప్పుడు ఒక రైడ్ జరుగుతోంది." : "";
        if (n == 0) return when + " రైడ్స్ ఏమీ రాసి లేవు." + now2
                + (new Prefs(c).carBluetooth().isEmpty() ? " (సెట్టింగ్స్ → కార్/బైక్ లో బండి బ్లూటూత్ ఎంచుకుంటే రైడ్స్ వాటంతటవే రాస్తాను.)" : "");
        return when + " " + n + " రైడ్స్, మొత్తం సుమారు " + dist(km * 1000) + ", " + Status.hours(mins) + "." + now2;
    }

    // ================================================================ O44: the bike's service

    static int serviceMonths(Context c) { return Math.max(1, sp(c).getInt("svc_months", 6)); }
    static int serviceKm(Context c) { return Math.max(500, sp(c).getInt("svc_km", 5000)); }

    /** He had it serviced (today, or days ago). */
    static String serviceDone(Context c, int daysAgo) {
        long t = System.currentTimeMillis() - Math.max(0, daysAgo) * DAY;
        sp(c).edit().putLong("svc_t", t).remove("svc_told").remove("svc_told_at").apply();
        return "సరే, బైక్ సర్వీస్ " + (daysAgo <= 0 ? "ఈరోజు" : daysAgo + " రోజుల క్రితం") + " అయినట్టు రాశాను. తర్వాతి సర్వీస్ "
                + serviceMonths(c) + " నెలలకు లేదా " + serviceKm(c) + " కి.మీ కి (ఏది ముందైతే అది) గుర్తు చేస్తాను.";
    }

    static String serviceSet(Context c, int months, int km) {
        SharedPreferences.Editor e = sp(c).edit();
        if (months > 0 && months <= 24) e.putInt("svc_months", months);
        if (km >= 500 && km <= 50_000) e.putInt("svc_km", km);
        e.remove("svc_told").apply();
        return "సరే, బైక్ సర్వీస్ ప్రతి " + serviceMonths(c) + " నెలలకు లేదా " + serviceKm(c) + " కి.మీ కి.";
    }

    /** {days left (negative = overdue), km since, km left}; null when the last service isn't known. */
    static long[] serviceDue(Context c) {
        long t = sp(c).getLong("svc_t", 0);
        if (t == 0) return null;
        long due = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).plusMonths(serviceMonths(c)).toInstant().toEpochMilli();
        double km = 0;
        for (JSONObject r : Bike.list(c, "rides", t)) km += r.optDouble("km");
        long daysLeft = Math.floorDiv(due - System.currentTimeMillis(), DAY);
        return new long[]{daysLeft, Math.round(km), serviceKm(c) - Math.round(km)};
    }

    static String serviceWhen(Context c) {
        long[] d = serviceDue(c);
        if (d == null) return "చివరి బైక్ సర్వీస్ ఎప్పుడో నాకు తెలియదు. సర్వీస్ చేయించినప్పుడు \"బైక్ సర్వీస్ చేయించాను\" అనండి (లేదా \"10 రోజుల క్రితం సర్వీస్ చేయించాను\").";
        String km = d[1] > 0 ? " చివరి సర్వీస్ తర్వాత రైడ్స్ " + d[1] + " కి.మీ." : "";
        if (d[0] < 0 || d[2] <= 0) return "బైక్ సర్వీస్ టైమ్ అయిపోయింది" + (d[0] < 0 ? " (" + (-d[0]) + " రోజులు దాటింది)" : "") + "." + km + " సర్వీస్ సెంటర్‌కి చూపించండి.";
        return "తర్వాతి బైక్ సర్వీస్ " + d[0] + " రోజుల్లో" + (d[1] > 0 ? " లేదా ఇంకా " + d[2] + " కి.మీ తర్వాత" : "") + "." + km;
    }

    /** Once a day: the service is near (a week before, or 300 km before) or due (then again each week). */
    static void serviceTick(Context c) {
        SharedPreferences s = sp(c);
        long now = System.currentTimeMillis();
        if (s.getLong("svc_t", 0) == 0 || now - s.getLong("svc_checked", 0) < 6 * HOUR) return;
        s.edit().putLong("svc_checked", now).apply();
        long[] d = serviceDue(c);
        if (d == null) return;
        String told = s.getString("svc_told", "");
        boolean due = d[0] <= 0 || d[2] <= 0, soon = d[0] <= 7 || d[2] <= 300;
        if (due && (!"due".equals(told) || now - s.getLong("svc_told_at", 0) > 7 * DAY)) {
            s.edit().putString("svc_told", "due").putLong("svc_told_at", now).apply();
            Reminders.notify(c, "🛠️ బైక్ సర్వీస్ టైమ్", serviceWhen(c) + " సర్వీస్ అయ్యాక \"బైక్ సర్వీస్ చేయించాను\" అనండి.", NOTE_SERVICE);
        } else if (!due && soon && told.isEmpty()) {
            s.edit().putString("svc_told", "soon").putLong("svc_told_at", now).apply();
            Reminders.notify(c, "🛠️ బైక్ సర్వీస్ దగ్గర పడింది", serviceWhen(c), NOTE_SERVICE);
        }
    }

    // ================================================================ W53 / W69: rain soon, great heat (every ~30 minutes)

    static void weatherTick(Context c) {
        boolean rain = rainOn(c), heat = heatOn(c);
        if (!rain && !heat) return;
        int h = LocalDateTime.now().getHour();
        if (h < 6 || h >= 22) return;
        long now = System.currentTimeMillis();
        SharedPreferences s = sp(c);
        if (now - s.getLong("wx_at", 0) < 28 * MIN || !Net.online(c)) return;
        Location l = Tools.lastLocation(c);
        if (l == null || now - l.getTime() > 3 * HOUR) return;
        s.edit().putLong("wx_at", now).apply();
        try {
            JSONObject w = Http.get(String.format(Locale.ENGLISH, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                    + "&current=temperature_2m,apparent_temperature,precipitation&minutely_15=precipitation&forecast_minutely_15=6&timezone=auto",
                    l.getLatitude(), l.getLongitude()));
            JSONObject cur = w.optJSONObject("current");
            if (cur != null) { // (W70: the day's highest so far sets the water goal)
                String k = "max_t_" + java.time.LocalDate.now();
                float t = (float) cur.optDouble("apparent_temperature", cur.optDouble("temperature_2m", 0));
                if (t > s.getFloat(k, 0)) s.edit().putFloat(k, t).apply();
            }
            if (rain && now - s.getLong("rain_told", 0) > 3 * HOUR) {
                int in = rainIn(w, LocalDateTime.now(), cur == null ? 0 : cur.optDouble("precipitation", 0));
                if (in > 0) {
                    s.edit().putLong("rain_told", now).apply();
                    String what = Bike.riding(c) || DriveService.running ? "రెయిన్‌కోట్ దగ్గర పెట్టుకోండి, నెమ్మదిగా వెళ్లండి." : "బయట బట్టలు ఉంటే లోపల పెట్టండి, బండికి కవర్ వేయండి.";
                    weatherNote(c, "🌧️ సుమారు " + in + " నిమిషాల్లో వర్షం", "ఇక్కడ " + in + " నిమిషాల్లో వర్షం వచ్చేలా ఉంది (అంచనా). " + what);
                }
            }
            if (heat && h >= 10 && h < 17 && cur != null && now - s.getLong("heat_told", 0) > 4 * HOUR) {
                double feels = cur.optDouble("apparent_temperature", Double.NaN), temp = cur.optDouble("temperature_2m", Double.NaN);
                if ((feels >= 40 || temp >= 38) && outside(c, l)) {
                    s.edit().putLong("heat_told", now).apply();
                    String hr = heartUp(c);
                    weatherNote(c, "🥵 ఎండ ఎక్కువ (" + Math.round(Double.isNaN(feels) ? temp : feels) + "°)",
                            "బయట చాలా వేడిగా ఉంది." + hr + " నీళ్లు తాగండి, నీడలో ఉండండి, తల కప్పుకోండి.");
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Minutes until rain starts in the next hour (pure: tested on a desk): 0 when it is raining now or none is coming.
     * The 15-minute slots are the forecast's; it counts as coming when a slot ahead has 0.2 mm or the next hour 0.5 mm.
     */
    static int rainIn(JSONObject w, LocalDateTime now, double rainNow) {
        try {
            JSONObject m = w.getJSONObject("minutely_15");
            JSONArray time = m.getJSONArray("time"), p = m.getJSONArray("precipitation");
            if (rainNow >= 0.1 || time.length() == 0) return 0;
            int cur = -2; // the slot holding now; -1 when every slot is still ahead (the forecast starts at the next one)
            for (int i = 0; i < time.length(); i++) {
                LocalDateTime t = LocalDateTime.parse(time.getString(i));
                if (!t.isAfter(now) && now.isBefore(t.plusMinutes(15))) { cur = i; break; }
            }
            if (cur == -2) {
                LocalDateTime t0 = LocalDateTime.parse(time.getString(0));
                if (t0.isAfter(now) && !t0.isAfter(now.plusMinutes(15))) cur = -1; else return 0;
            }
            if (cur >= 0 && p.optDouble(cur, 0) >= 0.1) return 0; // raining in this slot already
            double sum = 0, max = 0;
            int first = -1;
            for (int i = cur + 1; i < time.length() && i <= cur + 4; i++) {
                double v = p.optDouble(i, 0);
                sum += v;
                max = Math.max(max, v);
                if (first < 0 && v >= 0.1) first = i;
            }
            if (first < 0 || max < 0.2 && sum < 0.5) return 0;
            long mins = java.time.Duration.between(now, LocalDateTime.parse(time.getString(first))).toMinutes();
            return (int) Math.max(5, Math.min(60, Math.round(mins / 5.0) * 5));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Out of the house: riding, or 300 m or more from home (unknown home: only while riding). */
    private static boolean outside(Context c, Location l) {
        if (Bike.riding(c) || DriveService.running) return true;
        JSONObject h = home(c);
        return h != null && Drive.meters(h.optDouble("lat"), h.optDouble("lon"), l.getLatitude(), l.getLongitude()) > 300;
    }

    /** " మీ గుండె వేగం సాధారణం కంటే ఎక్కువగా ఉంది (98)." when his last watch reading (30 min) is well above his normal. */
    private static String heartUp(Context c) {
        try {
            List<JSONObject> l = HeartLog.all(c);
            JSONObject last = HeartLog.last(l);
            int[] base = HeartLog.baseline(l, System.currentTimeMillis());
            if (last == null || base == null || System.currentTimeMillis() - last.optLong("t") > 30 * MIN) return "";
            int bpm = last.optInt("bpm");
            return bpm >= base[0] + 15 ? " మీ గుండె వేగం (" + bpm + ") మామూలు కంటే ఎక్కువగా ఉంది." : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void weatherNote(Context c, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_weather", "వర్షం, ఎండ హెచ్చరికలు", NotificationManager.IMPORTANCE_HIGH));
            nm.notify(NOTE_WEATHER, new Notification.Builder(c, "jarvis_weather").setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true).setTimeoutAfter(90 * MIN).build());
        } catch (Exception ignored) {}
    }

    // ================================================================ W78: turns while walking with Maps

    private static volatile String turnKey = "";

    /** The navigation notification changed (Drive): a turn within ~60 m while he walks -> his watch buzzes it. */
    static void navTurn(Context c, String title, String text) {
        if (!turnsOn(c) || !WatchHub.known(c) || !WatchHub.watchHere(c) || Boolean.FALSE.equals(WatchHub.worn(c)) || WatchHub.riding(c)) return;
        String[] t = turn(title, text);
        if (t == null) { turnKey = ""; return; }
        if (t[0].equals(turnKey)) return;
        turnKey = t[0];
        try { WatchHub.send(c, P_BUZZ, new JSONObject().put("turn", t[1])); } catch (Exception ignored) {}
    }

    static final String P_BUZZ = "/jarvis/buzz", P_OPEN = "/jarvis/open";

    /** {key, "left" / "right" / "uturn"} for a turn within 60 m (pure: tested on a desk), else null. */
    static String[] turn(String title, String text) {
        String all = ((title == null ? "" : title) + " · " + (text == null ? "" : text)).toLowerCase(Locale.ROOT);
        String dir = all.matches("(?s).*(u-turn|u turn|యూ-టర్న్|యూ టర్న్|వెనక్కి తిరగ).*") ? "uturn"
                : all.matches("(?s).*(\\bleft\\b|ఎడమ).*") ? "left" : all.matches("(?s).*(\\bright\\b|కుడి).*") ? "right" : null;
        if (dir == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|కి\\.?\\s*మీ|m\\b|మీ|meters|metres|ft|feet)").matcher(all);
        double meters = -1;
        if (m.find()) {
            double v = Double.parseDouble(m.group(1).replace(',', '.'));
            String u = m.group(2);
            meters = u.startsWith("k") || u.startsWith("కి") ? v * 1000 : u.startsWith("f") ? v * 0.3048 : v;
        }
        boolean now = all.matches("(?s).*(\\bnow\\b|ఇప్పుడు).*");
        if (!(meters >= 0 && meters <= 60 || meters < 0 && now)) return null;
        String street = all.replaceAll("\\d+(?:[.,]\\d+)?\\s*(km|కి\\.?\\s*మీ|m\\b|మీ|meters|metres|ft|feet)", "").replaceAll("\\s+", " ").trim();
        return new String[]{dir + "|" + street, dir};
    }

    // ================================================================ W76: a break on a long ride

    /** On the road (DriveService, each fix while moving): a long ride, or a shorter one after little sleep -> stop for 5 minutes. */
    static void rideTick(Context c, long movingSince, long now) {
        if (!rideBreakOn(c) || movingSince <= 0) return;
        SharedPreferences s = sp(c);
        long mins = (now - movingSince) / MIN;
        boolean tired = Sleep.sleptSince(c, now - DAY) < 300; // under 5 hours of real sleep in the last day
        long limit = tired ? 45 : 75;
        if (mins < limit || now - s.getLong("break_told", 0) < 30 * MIN) return;
        s.edit().putLong("break_told", now).apply();
        String text = mins + " నిమిషాలుగా ఆపకుండా బండి నడుపుతున్నారు" + (tired ? ", నిద్ర కూడా తక్కువైంది" : "") + ". ఒక 5 నిమిషాలు బండి పక్కకి ఆపి, నీళ్లు తాగి వెళ్లండి.";
        try {
            WatchHub.send(c, WatchAlerts.P_ALERT, new JSONObject().put("id", 7601).put("kind", "rest").put("title", "🛑 కాసేపు ఆగండి").put("text", text)
                    .put("quiet", true));
        } catch (Exception ignored) {}
        if (!CallControl.busyWithCall()) Announcer.say(c, new Prefs(c).name() + ", " + text);
    }

    // ================================================================ W77: he started walking after a ride

    /** The watch says a walk began (2 minutes of steps): if a drive is still going, the bike stopped where it stood still. */
    static void walkStarted(Context c) {
        Location stop = DriveService.stopPoint;
        if (!DriveService.running || stop == null || DriveService.tripMeters < 1000) return;
        if (System.currentTimeMillis() - stop.getTime() > 15 * MIN) return;
        if (new Prefs(c).sp.getBoolean("driving", false)) return; // (driving mode with the bike's Bluetooth: its own end saves the spot)
        GeoReminders.savePlace(c, "parking", stop.getLatitude(), stop.getLongitude());
        Life.markParked(c);
        Reminders.notify(c, "🅿️ బండి ఇక్కడ పెట్టారు", "నడక మొదలైంది కాబట్టి బండి ఆగిన చోటు సేవ్ చేశాను. వాచ్‌లో 🧭 దారి → 🏍️ బండి, లేదా \"బండి ఎక్కడ?\" అనండి.", NOTE_PARKED);
    }

    // ================================================================ the watch's compass (W52) and route (W51)

    private static final class M { static final Handler h = new Handler(Looper.getMainLooper()); } // (made when first used: desk tests)
    private static volatile Location followed;
    private static volatile long followUntil;
    private static LocationListener listener;

    /** The compass is open on the watch: GPS for the next minute (each ask keeps it on). Returns the best fix now. */
    static Location follow(Context c) {
        Context app = c.getApplicationContext();
        followUntil = System.currentTimeMillis() + 60_000L;
        M.h.post(() -> {
            if (listener != null) return;
            LocationManager lm = app.getSystemService(LocationManager.class);
            if (lm == null || app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            listener = l -> followed = l;
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, listener, Looper.getMainLooper()); } catch (Exception e) { listener = null; return; }
            M.h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (System.currentTimeMillis() < followUntil) { M.h.postDelayed(this, 10_000L); return; }
                    try { lm.removeUpdates(listener); } catch (Exception ignored) {}
                    listener = null;
                }
            }, 10_000L);
        });
        Location f = followed, d = DriveService.last, l = Tools.lastLocation(app);
        Location best = null;
        for (Location x : new Location[]{f, d, l}) if (x != null && (best == null || x.getTime() > best.getTime())) best = x;
        return best;
    }

    static JSONObject navPanel(Context c) throws Exception {
        return new JSONObject().put("kind", "nav").put("targets", targets(c)).put("riding", WatchHub.riding(c));
    }

    static JSONObject herePanel(Context c) throws Exception {
        Location l = follow(c);
        JSONObject o = new JSONObject().put("kind", "here");
        if (l == null) return o.put("none", true);
        o.put("lat", l.getLatitude()).put("lon", l.getLongitude()).put("acc", l.hasAccuracy() ? Math.round(l.getAccuracy()) : -1).put("t", l.getTime());
        if (l.hasSpeed()) o.put("spd", l.getSpeed());
        return o;
    }

    /** Opens a screen on his watch ({screen, target}): the compass, music, radio... False when the watch isn't near. */
    static boolean openOnWatch(Context c, String screen, String target) {
        if (!WatchHub.known(c) || !WatchHub.watchHere(c)) return false;
        try { WatchHub.send(c, P_OPEN, new JSONObject().put("screen", screen).put("target", target == null ? "" : target)); return true; }
        catch (Exception e) { return false; }
    }

    // ================================================================ his words (offline) and the "travel" tool

    /**
     * What he asked, understood without AI (pure: tested on a desk): {"where"} / {"to", place} / {"speed"} / {"trip"} /
     * {"rides", words} / {"service_done", days} / {"service_when"} / {"service_set", months, km} / {"charge_start", pct, target, fast} /
     * {"charge_done", pct} / {"charge_when"} / {"range", pct}; null when it isn't about these.
     */
    static String[] asks(String bare) {
        String t = bare == null ? "" : bare.trim().toLowerCase(Locale.ROOT).replaceAll("[?.!,]+", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        boolean send = t.matches("(?s).*(పంపు|పంపించు|పంపండి|send|షేర్|share).*");
        if (!send && t.matches("(?s).*(నేను|మనం)?\\s*(ఇప్పుడు\\s*)?ఎక్కడ\\s*(ఉన్నాను|ఉన్నా|ఉన్నాం|ఉన్నాము).*|.*where am i.*|.*నా\\s*(లొకేషన్|location)\\s*(ఏంటి|ఏది|ఎక్కడ|చెప్పు).*"))
            return new String[]{"where"};
        boolean bike = t.matches("(?s).*(బైక్|బైకు|బండి|స్కూటర్|bike|scooter).*");
        // the bike's charging (O43)
        boolean charge = t.matches("(?s).*(ఛార్జ్|ఛార్జింగ్|చార్జ్|చార్జింగ్|charg).*") && !t.matches("(?s).*(ఫోన్|phone|వాచ్|watch|మొబైల్).*");
        int pct = percent(t);
        if (charge && (bike || pct >= 0 || t.matches("(?s).*(బ్యాటరీ|battery).*"))) {
            if (t.matches("(?s).*(ఎప్పుడు|ఎంత\\s*(టైమ్|సేపు|సమయం)|when).*") && !t.matches("(?s).*(పెట్టాను|పెట్టా|పెట్టిన|వేశాను|plugged).*")) return new String[]{"charge_when"};
            if (t.matches("(?s).*(అయిపోయింది|అయింది|ఫుల్|full|తీసేశాను|ఆపాను).*") && !t.matches("(?s).*(ఎప్పుడు|ఎంత).*"))
            {
                int n = pct >= 0 ? pct : firstInt(t);
                return new String[]{"charge_done", String.valueOf(n >= 1 && n <= 100 ? n : t.matches("(?s).*(ఫుల్|full).*") ? 100 : -1)};
            }
            if (t.matches("(?s).*(పెట్టాను|పెట్టా|పెట్టిన|పెడుతున్నాను|వేశాను|plugged|started|మొదలుపెట్టాను).*")) {
                int target = -1;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,3})\\s*(%|శాతం|పర్సెంట్)?\\s*(వరకు|దాకా|until|till)").matcher(t);
                if (m.find()) target = Integer.parseInt(m.group(1));
                int from = pct;
                if (target > 0) { // "30 నుంచి 80 వరకు": the first number is the start
                    java.util.regex.Matcher f = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(t);
                    from = f.find() ? Integer.parseInt(f.group(1)) : -1;
                    if (from == target) from = -1;
                }
                return new String[]{"charge_start", String.valueOf(from), String.valueOf(target), String.valueOf(t.matches("(?s).*(ఫాస్ట్|fast|dc).*"))};
            }
        }
        if (bike && t.matches("(?s).*(రేంజ్|range|ఎన్ని కి\\.?\\s*మీ|ఎంత దూరం వెళ్ల|ఎంత దూరం పోత|వెళ్లగలను|వెళ్ళగలను).*") && !t.matches("(?s).*(రైడ్|నడిపాను|వెళ్ళాను|వెళ్లాను|చేశాను).*"))
            return new String[]{"range", String.valueOf(pct)};
        // the service (O44)
        if (bike && t.matches("(?s).*(సర్వీస్|సర్వీసింగ్|service).*")) {
            int n = firstInt(t);
            if (t.matches("(?s).*(ప్రతి|every).*") && n > 0) {
                boolean km = t.matches("(?s).*(కి\\.?\\s*మీ|km|కిలోమీటర్).*");
                return new String[]{"service_set", km ? "0" : String.valueOf(n), km ? String.valueOf(n) : "0"};
            }
            if (t.matches("(?s).*(చేయించాను|చేయించా|చేయించిన|అయింది|అయిపోయింది|ఇచ్చాను|done|did).*") && !t.matches("(?s).*(ఎప్పుడు|ఎన్ని రోజులు).*")) {
                int days = t.matches("(?s).*(రోజుల|రోజులు|days).*(క్రితం|ముందు|ago).*") && n > 0 ? n : t.contains("నిన్న") ? 1 : 0;
                return new String[]{"service_done", String.valueOf(days)};
            }
            if (t.matches("(?s).*(ఎప్పుడు|ఎన్ని రోజులు|ఎంత|when|due|టైమ్).*")) return new String[]{"service_when"};
        }
        // rides (O19)
        if (t.matches("(?s).*(రైడ్|రైడ్స్|ride).*|.*(బైక్|బండి)\\s*(మీద|పై).*") && t.matches("(?s).*(ఎంత|ఎన్ని|how much|how many|చెప్పు).*")
                && t.matches("(?s).*(ఈరోజు|ఈ రోజు|ఇవాళ|నిన్న|వారం|నెల|today|week|month).*") && !t.matches("(?s).*(ఖర్చు|రూపాయ|cost).*"))
            return new String[]{"rides", t};
        // speed and this trip (O27)
        if (t.matches("(?s).*(ఎంత|ఏ)\\s*స్పీడ్.*|.*స్పీడ్\\s*(ఎంత|ఎంతలో|ఎంత ఉంది).*|.*how fast.*|.*my speed.*|.*ఎంత వేగం.*")) return new String[]{"speed"};
        if (t.matches("(?s).*ఎంత\\s*దూరం\\s*(వచ్చాను|వచ్చాం|ప్రయాణించాను|నడిపాను|వెళ్లాను|వెళ్ళాను).*|.*how far (have i|did i).*")) return new String[]{"trip"};
        // how far / which way (O26)
        boolean far = t.matches("(?s).*(ఎంత\\s*దూరం|ఏ\\s*వైపు|ఏ\\s*దిక్కు|ఎటు\\s*వైపు|ఎక్కడ\\s*(ఉంది|పెట్టాను|పెట్టా)|how far|which way|where is).*");
        if (far && bike && !t.matches("(?s).*(రేంజ్|range|వెళ్లగలను|వెళ్ళగలను).*")) return new String[]{"to", "బండి"};
        if (far && t.matches("(?s).*(ఇంటికి|ఇల్లు|ఇంటి|home).*") && !t.matches("(?s).*(ఎన్ని నిమిషాలు|ఎంత\\s*(టైమ్|సేపు)|ట్రాఫిక్).*")) return new String[]{"to", "ఇల్లు"};
        return null;
    }

    /** "30%", "30 శాతం", "30 పర్సెంట్", "30 ఉంది" -> 30; -1 when no percent. */
    static int percent(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})\\s*(%|శాతం|పర్సెంట్|percent)").matcher(t);
        if (m.find()) { int v = Integer.parseInt(m.group(1)); return v <= 100 ? v : -1; }
        m = java.util.regex.Pattern.compile("(\\d{1,3})\\s*(ఉంది|ఉన్నప్పుడు|నుంచి|నుండి|దగ్గర|లో)").matcher(t);
        if (m.find()) { int v = Integer.parseInt(m.group(1)); return v <= 100 ? v : -1; }
        return -1;
    }

    private static int firstInt(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,5})").matcher(t);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** What to say for asks() (background thread). */
    static String answer(Context c, String[] a) {
        try {
            switch (a[0]) {
                case "where": return whereAmI(c);
                case "to": {
                    String s = toPlace(c, a[1]);
                    JSONObject p = target(c, a[1]);
                    if (p != null && WatchHub.known(c)) openOnWatch(c, "compass", "బండి".equals(a[1]) ? "parking" : "ఇల్లు".equals(a[1]) ? "home" : p.optString("name"));
                    return s;
                }
                case "speed": return speed(c);
                case "trip": return trip(c);
                case "rides": return rides(c, a[1]);
                case "service_done": return serviceDone(c, Integer.parseInt(a[1]));
                case "service_when": return serviceWhen(c);
                case "service_set": return serviceSet(c, Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                case "charge_start": {
                    int from = Integer.parseInt(a[1]);
                    if (from < 0) return "ఇప్పుడు బైక్‌లో ఎంత శాతం చూపిస్తోంది? ఉదాహరణకు \"బైక్ ఛార్జింగ్ పెట్టాను, 30 శాతం ఉంది\".";
                    JSONObject o = Bike.chargeStart(c, from, Integer.parseInt(a[2]), Boolean.parseBoolean(a[3]));
                    if (!o.optBoolean("ok")) return "ఎంత శాతం నుంచి పెట్టారో చెప్పండి (0-99).";
                    return "సరే, " + o.optString("from") + " నుంచి " + o.optString("until") + " కి సుమారు " + o.optString("ready_at").replace("AM", "ఉదయం").replace("PM", "సాయంత్రం")
                            + " కి అవుతుంది (" + o.optString("takes").replace(" h ", " గం ").replace(" min", " ని") + "). అప్పుడు గుర్తు చేస్తాను" + (WatchHub.known(c) ? ", వాచ్‌కి కూడా." : ".");
                }
                case "charge_done": {
                    int to = Integer.parseInt(a[1]);
                    if (to < 0) return "ఇప్పుడు బైక్‌లో ఎంత శాతం చూపిస్తోంది? ఉదాహరణకు \"ఛార్జింగ్ అయిపోయింది, 100\".";
                    JSONObject o = Bike.addCharge(c, -1, to, 0, true, false);
                    if (!o.optBoolean("ok")) return "ఎంత శాతం నుంచి ఎంత వరకు ఛార్జ్ అయిందో చెప్పండి (ఉదాహరణ: 30 నుంచి 100).";
                    return "సరే, " + o.optString("charged").replace(" -> ", " నుంచి ") + " రాశాను: సుమారు " + o.optDouble("units_kwh") + " యూనిట్లు, ₹" + o.optLong("cost_rupees")
                            + ", సుమారు " + o.optLong("km_added_about") + " కి.మీ.";
                }
                case "charge_when": {
                    JSONObject s = Bike.charging(c);
                    if (s == null || s.optBoolean("logged")) return "ఇప్పుడు బైక్ ఛార్జింగ్ నాకు తెలియదు. పెట్టినప్పుడు \"బైక్ ఛార్జింగ్ పెట్టాను, 30 శాతం\" అనండి.";
                    long left = (s.optLong("eta") - System.currentTimeMillis()) / MIN;
                    if (left <= 0) return "ఇప్పటికి " + s.optInt("to") + "% అయి ఉండాలి.";
                    return s.optInt("to") + "% కి ఇంకా సుమారు " + Status.hours(left) + " (" + Offline.sayWhen(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(s.optLong("eta")),
                            java.time.ZoneId.systemDefault()), LocalDateTime.now()) + ").";
                }
                case "range": {
                    int p = Integer.parseInt(a[1]);
                    if (p < 0) p = Bike.estimatePct(c);
                    if (p < 0) return "బైక్‌లో ఇప్పుడు ఎంత శాతం ఉందో చెప్పండి, ఉదాహరణకు \"60 శాతంతో ఎన్ని కి.మీ వెళ్లగలను?\".";
                    JSONObject o = Bike.range(c, p);
                    return p + "% తో సుమారు " + o.optInt("range_km") + " కి.మీ వెళ్లొచ్చు" + (o.has("range_keeping_10_percent_km") ? " (10% మిగుల్చుకుంటే " + o.optInt("range_keeping_10_percent_km") + " కి.మీ)" : "")
                            + ". స్పోర్ట్ మోడ్, ఇద్దరు, ఎత్తులు ఉంటే తగ్గుతుంది." + (a[1].equals("-1") ? " (" + p + "% నా అంచనా: చివరి ఛార్జ్ నుంచి రైడ్స్ తీసేసి.)" : "");
                }
                default: return null;
            }
        } catch (Exception e) {
            return "అది చేయలేకపోయాను: " + e.getMessage();
        }
    }

    /** The "travel" tool (online): the same answers, as JSON for the brain. */
    static String tool(Context c, JSONObject a) throws Exception {
        String act = a.optString("action", "where").trim().toLowerCase(Locale.ROOT);
        String say;
        switch (act) {
            case "where": say = whereAmI(c); break;
            case "to": say = answer(c, new String[]{"to", a.optString("place", "బండి")}); break;
            case "speed": say = speed(c); break;
            case "trip": say = trip(c); break;
            case "service_done": say = serviceDone(c, a.optInt("days_ago", 0)); break;
            case "service_when": say = serviceWhen(c); break;
            case "service_set": say = serviceSet(c, a.optInt("months", 0), a.optInt("km", 0)); break;
            case "watch_compass": {
                JSONObject p = target(c, a.optString("place", "బండి"));
                if (p == null) say = "That place is not saved (the bike's spot is saved when he parks or says 'బండి ఇక్కడ పెట్టాను').";
                else say = openOnWatch(c, "compass", p.optString("name").equals("బండి") ? "parking" : p.optString("name"))
                        ? "The compass is open on his watch, pointing to " + p.optString("name") + "." : "His watch is not near the phone now.";
                break;
            }
            default: return new JSONObject().put("ok", false).put("error", "Unknown action " + act).toString();
        }
        return new JSONObject().put("ok", true).put("say", say).put("next", "Say this to him in short Telugu, as it is.").toString();
    }
}
