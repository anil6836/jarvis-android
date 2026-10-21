package com.anil.jarvis;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Help while he drives: where he is and which road, where the road / route goes, the turn and arrival time from the
 * maps app's own notification (Google Maps, Waze, Mappls...), places along the way (food, tea, petrol, EV, hospital,
 * toilets, a brand by name), speed cameras and toll gates ahead, adding a stop, sharing his arrival time.
 * Maps data: OpenStreetMap (Overpass, free) and the free OSRM router. Live alerts while driving: DriveService.
 */
final class Drive {
    private Drive() {}

    // ================================================================ the maps app's navigation (its notification)

    private static final String[][] NAV_APPS = {
            {"com.google.android.apps.maps", "Google Maps"}, {"com.waze", "Waze"}, {"com.mmi.maps", "Mappls"},
            {"com.here.app.maps", "HERE WeGo"}, {"com.sygic.aura", "Sygic"}, {"com.mapmyindia.app.beta", "Mappls"}};

    private static volatile String navPkg = "", navKey = "", navTitle = "", navText = "", navSub = "", navBig = "";
    private static volatile long navAt;

    static boolean isNavApp(String pkg) {
        for (String[] a : NAV_APPS) if (a[0].equals(pkg)) return true;
        return false;
    }

    private static String appName(String pkg) {
        for (String[] a : NAV_APPS) if (a[0].equals(pkg)) return a[1];
        return pkg;
    }

    /** The turn-by-turn notification changed: keep its words (next turn, distance, arrival time). True if it was that one. */
    static boolean fromNotification(Context c, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return false;
        boolean nav = "navigation".equals(n.category) || (sbn.isOngoing() && !"com.google.android.apps.maps".equals(sbn.getPackageName()));
        if (!nav) return false;
        Bundle x = n.extras;
        String t = s(x.getCharSequence(Notification.EXTRA_TITLE)), tx = s(x.getCharSequence(Notification.EXTRA_TEXT));
        if (t.isEmpty() && tx.isEmpty()) return true;
        String sub = s(x.getCharSequence(Notification.EXTRA_SUB_TEXT));
        if (sub.isEmpty()) sub = s(x.getCharSequence(Notification.EXTRA_INFO_TEXT));
        boolean fresh = navAt == 0 || System.currentTimeMillis() - navAt > 10 * 60000L;
        navPkg = sbn.getPackageName();
        navKey = sbn.getKey();
        navTitle = t;
        navText = tx;
        navSub = sub;
        navBig = s(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        navAt = System.currentTimeMillis();
        try { Travel.navTurn(c, t, tx); } catch (Exception ignored) {} // W78: a turn close by while he walks -> his watch buzzes it
        // navigation started: offer the camera / speed alerts (a tap on the notification starts them; Android
        // lets them start by themselves only with "allow location all the time")
        if (fresh && !DriveService.running && settings(c).getBoolean("drive_auto", true)) {
            // (Android 8 / 9: the plain location permission is already "all the time")
            boolean always = c.checkSelfPermission(android.os.Build.VERSION.SDK_INT >= 29 ? android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    : android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean started = false;
            if (always) { try { started = DriveService.start(c); } catch (Exception ignored) {} }
            if (!started) offer(c);
        }
        return true;
    }

    /** "నావిగేషన్ మొదలైంది: స్పీడ్ కెమెరా అలర్ట్స్ ఆన్ చేయాలా?" as a notification; a tap opens Jarvis, which starts them. */
    private static void offer(Context c) {
        try {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            nm.createNotificationChannel(new android.app.NotificationChannel("jarvis_drive_offer", "డ్రైవ్ అలర్ట్స్ సూచన", android.app.NotificationManager.IMPORTANCE_DEFAULT));
            android.app.PendingIntent open = android.app.PendingIntent.getActivity(c, 133, new Intent(c, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_ASK, "డ్రైవ్ అలర్ట్స్ ఆన్ చెయ్ (drive start).").putExtra(MainActivity.EXTRA_LABEL, "🚗 డ్రైవ్ అలర్ట్స్ ఆన్")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(133, new android.app.Notification.Builder(c, "jarvis_drive_offer").setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle("🚗 నావిగేషన్ మొదలైంది")
                    .setContentText("స్పీడ్ కెమెరా, స్పీడ్ లిమిట్ అలర్ట్స్ ఆన్ చేయడానికి నొక్కండి")
                    .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(10 * 60000L).build());
        } catch (Exception ignored) {}
    }

    /** A notification of the maps app went away: if it was the navigation one, the trip is over (arrived or stopped). */
    static void navEnded(Context c, String key) {
        if (key == null || !key.equals(navKey)) return;
        navAt = 0;
        navKey = "";
        navTitle = navText = navSub = navBig = "";
        clearDest(c);
        try { c.getSystemService(android.app.NotificationManager.class).cancel(133); } catch (Exception ignored) {}
    }

    /** What the maps app shows now, or null: {app, next, detail, trip, more}. */
    static JSONObject navState() {
        if (navAt == 0 || System.currentTimeMillis() - navAt > 5 * 60000L) return null;
        try {
            JSONObject o = new JSONObject().put("app", appName(navPkg)).put("next", navTitle).put("detail", navText).put("trip", navSub);
            if (!navBig.isEmpty() && !navBig.equals(navText)) o.put("more", navBig);
            return o;
        } catch (Exception e) { return null; }
    }

    private static String s(CharSequence c) { return c == null ? "" : c.toString().trim(); }

    // ================================================================ where he is going (when Jarvis started the navigation)

    static SharedPreferences settings(Context c) { return c.getSharedPreferences("jarvis_drive", Context.MODE_PRIVATE); }

    static void clearDest(Context c) {
        settings(c).edit().remove("dest").remove("dest_ll").remove("dest_at").apply();
    }

    static void setDest(Context c, String name) {
        if (name == null || name.trim().isEmpty()) return;
        settings(c).edit().putString("dest", name.trim()).putLong("dest_at", System.currentTimeMillis()).remove("dest_ll").apply();
    }

    /** {name, lat, lon} of the place he is driving to (set within 12 hours), or null. Looks it up once (network). */
    static JSONObject dest(Context c) {
        SharedPreferences p = settings(c);
        String name = p.getString("dest", "");
        if (name.isEmpty() || System.currentTimeMillis() - p.getLong("dest_at", 0) > 12 * 3600000L) return null;
        double[] ll = null;
        String saved = p.getString("dest_ll", "");
        if (!saved.isEmpty()) {
            String[] xy = saved.split(",");
            try { ll = new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])}; } catch (Exception ignored) {}
        }
        if (ll == null) ll = latLon(c, name);
        if (ll != null) p.edit().putString("dest_ll", ll[0] + "," + ll[1]).apply();
        try {
            JSONObject o = new JSONObject().put("name", name);
            if (ll != null) o.put("lat", ll[0]).put("lon", ll[1]);
            return o;
        } catch (Exception e) { return null; }
    }

    /** A place's {lat, lon}: "lat,lon" itself, one of his saved places, else the phone's address finder (network). */
    static double[] latLon(Context c, String place) {
        String p = place.trim();
        if (p.matches("-?\\d+(\\.\\d+)?\\s*,\\s*-?\\d+(\\.\\d+)?")) {
            String[] xy = p.split(",");
            return new double[]{Double.parseDouble(xy[0].trim()), Double.parseDouble(xy[1].trim())};
        }
        double[] saved = GeoReminders.place(c, p);
        return saved != null ? saved : Places.geocode(c, p);
    }

    // ================================================================ his position

    /** A fresh position: the live drive fix, else a new fix (up to 12 s), else the last known. Call off the main thread. */
    static Location here(Context c) {
        Location live = DriveService.last;
        if (live != null && System.currentTimeMillis() - live.getTime() < 30000) return live;
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm != null && android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
                final Location[] got = {null};
                CountDownLatch done = new CountDownLatch(1);
                lm.getCurrentLocation(provider, null, c.getMainExecutor(), l -> { got[0] = l; done.countDown(); });
                done.await(12, TimeUnit.SECONDS);
                if (got[0] != null) return got[0];
            } catch (Exception ignored) {}
        }
        return Tools.lastLocation(c);
    }

    /** Which way he is going (degrees), or -1 when not known (standing still). */
    static float heading(Location l) {
        if (DriveService.running && DriveService.heading >= 0) return DriveService.heading;
        if (l != null && l.hasBearing() && l.hasSpeed() && l.getSpeed() > 2) return l.getBearing();
        return -1;
    }

    // ================================================================ geometry

    static double meters(double la1, double lo1, double la2, double lo2) {
        double r = 6371000, p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = p2 - p1, dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    static double bearing(double la1, double lo1, double la2, double lo2) {
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2), dl = Math.toRadians(lo2 - lo1);
        double y = Math.sin(dl) * Math.cos(p2), x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    static double angle(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    private static double[] ahead(double lat, double lon, double brg, double m) {
        double r = 6371000, d = m / r, b = Math.toRadians(brg), p1 = Math.toRadians(lat), l1 = Math.toRadians(lon);
        double p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b));
        double l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
        return new double[]{Math.toDegrees(p2), Math.toDegrees(l2)};
    }

    static String direction(float brg) {
        if (brg < 0) return "";
        String[] d = {"ఉత్తరం", "ఈశాన్యం", "తూర్పు", "ఆగ్నేయం", "దక్షిణం", "నైరుతి", "పడమర", "వాయువ్యం"};
        return d[(int) Math.round(brg / 45.0) % 8];
    }

    // ================================================================ the way ahead

    /** The road ahead as points, and whether it is the real route (to his destination) or just straight on. */
    static final class Path {
        final List<double[]> pts;
        final boolean route;
        final double totalKm, minutes;
        final String via;
        Path(List<double[]> pts, boolean route, double totalKm, double minutes, String via) {
            this.pts = pts; this.route = route; this.totalKm = totalKm; this.minutes = minutes; this.via = via;
        }
    }

    private static volatile String routeKey = "";
    private static volatile JSONObject routeCache;
    private static volatile long routeAt;

    /** Up to km of the way ahead from where he is: the router's route to his destination, else straight on his heading; null if neither. */
    static Path ahead(Context c, Location l, double km) {
        JSONObject d = dest(c);
        if (d != null && d.has("lat")) {
            try {
                JSONObject r = osrm(l.getLatitude(), l.getLongitude(), d.optDouble("lat"), d.optDouble("lon"));
                if (r != null) {
                    JSONArray co = r.optJSONObject("geometry").optJSONArray("coordinates");
                    List<double[]> pts = new ArrayList<>();
                    double run = 0;
                    double[] prev = null;
                    for (int i = 0; co != null && i < co.length(); i++) {
                        JSONArray p = co.optJSONArray(i);
                        double[] q = {p.optDouble(1), p.optDouble(0)};
                        if (prev != null) run += meters(prev[0], prev[1], q[0], q[1]);
                        pts.add(q);
                        prev = q;
                        if (run > km * 1000) break;
                    }
                    JSONArray legs = r.optJSONArray("legs");
                    String via = legs != null && legs.length() > 0 ? legs.optJSONObject(0).optString("summary", "") : "";
                    if (pts.size() >= 2) return new Path(pts, true, r.optDouble("distance") / 1000.0, r.optDouble("duration") / 60.0, via);
                }
            } catch (Exception ignored) {}
        }
        float h = heading(l);
        if (h < 0) return null;
        List<double[]> pts = new ArrayList<>();
        for (double m = 0; m <= km * 1000; m += 1000) pts.add(ahead(l.getLatitude(), l.getLongitude(), h, m));
        return new Path(pts, false, 0, 0, "");
    }

    /** The free router's whole way from A to B (no traffic, no toll), or null. */
    static Path freeRoute(double la1, double lo1, double la2, double lo2) {
        try {
            JSONObject r = osrm(la1, lo1, la2, lo2);
            if (r == null) return null;
            JSONArray co = r.optJSONObject("geometry").optJSONArray("coordinates");
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; co != null && i < co.length(); i++) { JSONArray p = co.optJSONArray(i); pts.add(new double[]{p.optDouble(1), p.optDouble(0)}); }
            JSONArray legs = r.optJSONArray("legs");
            String via = legs != null && legs.length() > 0 ? legs.optJSONObject(0).optString("summary", "") : "";
            return pts.size() >= 2 ? new Path(pts, true, r.optDouble("distance") / 1000.0, r.optDouble("duration") / 60.0, via) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The towns and big villages along a way (free map), nearest first: {name, name_en, km, kind}. */
    static JSONArray townsAlong(Path p, int max) throws Exception {
        String ln = line(p.pts);
        JSONArray el = overpass("[out:json][timeout:25];(node(around:4000," + ln + ")[place~\"^(city|town)$\"];node(around:" + (p.route ? 1500 : 3000) + ","
                + ln + ")[place=village];);out 150;");
        List<JSONObject> towns = new ArrayList<>();
        for (int i = 0; el != null && i < el.length(); i++) {
            JSONObject e = el.getJSONObject(i), t = e.optJSONObject("tags");
            if (t == null) continue;
            double[] at = place(p.pts, e.optDouble("lat"), e.optDouble("lon"));
            boolean big = !"village".equals(t.optString("place"));
            if (at[0] < 1 || at[1] > (big ? 4000 : 1500)) continue;
            towns.add(new JSONObject().put("name", t.optString("name:te", t.optString("name"))).put("name_en", t.optString("name:en", t.optString("name")))
                    .put("km", Math.round(at[0])).put("kind", t.optString("place")));
        }
        Collections.sort(towns, (a, b) -> Long.compare(a.optLong("km"), b.optLong("km")));
        JSONArray next = new JSONArray();
        for (int i = 0; i < towns.size() && next.length() < max; i++) next.put(towns.get(i));
        return next;
    }

    /** OSRM route (cached for 3 minutes for the same trip and nearly the same start). */
    static JSONObject osrm(double la1, double lo1, double la2, double lo2) throws Exception {
        String key = String.format(Locale.ENGLISH, "%.3f,%.3f>%.4f,%.4f", la1, lo1, la2, lo2);
        if (key.equals(routeKey) && routeCache != null && System.currentTimeMillis() - routeAt < 180000) return routeCache;
        JSONObject res = new JSONObject(get(String.format(Locale.ENGLISH,
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true", lo1, la1, lo2, la2)));
        JSONArray routes = res.optJSONArray("routes");
        if (routes == null || routes.length() == 0) return null;
        JSONObject r = routes.getJSONObject(0);
        // the steps are only needed for the summary; drop them to keep it small
        JSONArray legs = r.optJSONArray("legs");
        for (int i = 0; legs != null && i < legs.length(); i++) legs.getJSONObject(i).remove("steps");
        routeKey = key;
        routeCache = r;
        routeAt = System.currentTimeMillis();
        return r;
    }

    /** Overpass "around a line" argument: the path thinned to about every few hundred metres (at most 150 points). */
    static String line(List<double[]> pts) {
        double total = 0;
        for (int i = 1; i < pts.size(); i++) total += meters(pts.get(i - 1)[0], pts.get(i - 1)[1], pts.get(i)[0], pts.get(i)[1]);
        double every = Math.max(250, total / 300);
        StringBuilder b = new StringBuilder();
        double run = every;
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) run += meters(pts.get(i - 1)[0], pts.get(i - 1)[1], pts.get(i)[0], pts.get(i)[1]);
            if (run >= every || i == pts.size() - 1) {
                if (b.length() > 0) b.append(',');
                b.append(String.format(Locale.ENGLISH, "%.5f,%.5f", pts.get(i)[0], pts.get(i)[1]));
                run = 0;
            }
        }
        return b.toString();
    }

    /** How far along the path a point is (km), how far off it (m) and on which side: {kmAhead, offM, side(-1 left, 1 right)}. */
    static double[] place(List<double[]> pts, double lat, double lon) {
        double best = Double.MAX_VALUE, at = 0, run = 0, side = 0;
        for (int i = 0; i < pts.size() - 1; i++) {
            double[] a = pts.get(i), b = pts.get(i + 1);
            double seg = meters(a[0], a[1], b[0], b[1]);
            // flat projection is fine at these distances
            double k = Math.cos(Math.toRadians(a[0]));
            double ax = a[1] * k, ay = a[0], bx = b[1] * k, by = b[0], px = lon * k, py = lat;
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
            double cx = ax + t * dx, cy = ay + t * dy;
            double off = meters(lat, lon, cy, cx / k);
            if (off < best) {
                best = off;
                at = run + t * seg;
                side = (dx * (py - ay) - dy * (px - ax)) > 0 ? -1 : 1; // left of the way = -1
            }
            run += seg;
        }
        return new double[]{at / 1000.0, best, side};
    }

    // ================================================================ map look-ups (OpenStreetMap)

    static JSONArray overpass(String q) throws Exception {
        Exception last = null;
        for (String server : new String[]{"https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"}) {
            try {
                return new JSONObject(post(server, "data=" + URLEncoder.encode(q, "UTF-8"))).optJSONArray("elements");
            } catch (Exception e) { last = e; }
        }
        throw last;
    }

    private static String post(String url, String form) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(12000);
            h.setReadTimeout(30000);
            h.setDoOutput(true);
            h.setRequestMethod("POST");
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            h.setRequestProperty("User-Agent", "JarvisAndroid/1.0");
            try (OutputStream o = h.getOutputStream()) { o.write(form.getBytes(StandardCharsets.UTF_8)); }
            if (h.getResponseCode() >= 400) throw new java.io.IOException("HTTP " + h.getResponseCode());
            return read(h.getInputStream());
        } finally {
            h.disconnect();
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(12000);
            h.setReadTimeout(25000);
            h.setRequestProperty("User-Agent", "JarvisAndroid/1.0");
            if (h.getResponseCode() >= 400) throw new java.io.IOException("HTTP " + h.getResponseCode());
            return read(h.getInputStream());
        } finally {
            h.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int r; (r = in.read(buf)) > 0; ) b.write(buf, 0, r);
        return b.toString("UTF-8");
    }

    /** The road under him: {name, ref, kind, maxspeed (0 = not on the map), lanes, goes_to}. */
    static JSONObject road(double lat, double lon) throws Exception {
        JSONArray el = overpass(String.format(Locale.ENGLISH, "[out:json][timeout:15];way(around:25,%.6f,%.6f)[highway~\"^(motorway|trunk|primary|secondary|tertiary|"
                + "unclassified|residential|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|living_street|service)$\"];out tags 8;", lat, lon));
        String[] rank = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service"};
        JSONObject best = null;
        int bestRank = 99;
        for (int i = 0; el != null && i < el.length(); i++) {
            JSONObject t = el.getJSONObject(i).optJSONObject("tags");
            if (t == null) continue;
            String hw = t.optString("highway").replace("_link", "");
            int r = 98;
            for (int k = 0; k < rank.length; k++) if (rank[k].equals(hw)) r = k;
            if (r < bestRank) { bestRank = r; best = t; }
        }
        JSONObject o = new JSONObject();
        if (best == null) return o;
        String hw = best.optString("highway").replace("_link", "");
        String kind = hw.equals("motorway") ? "ఎక్స్‌ప్రెస్‌వే" : hw.equals("trunk") ? "జాతీయ రహదారి / హైవే" : hw.equals("primary") ? "ప్రధాన రహదారి"
                : hw.equals("secondary") ? "రాష్ట్ర / జిల్లా రహదారి" : hw.equals("residential") || hw.equals("living_street") ? "కాలనీ రోడ్డు" : "రోడ్డు";
        return o.put("name", best.optString("name:te", best.optString("name", ""))).put("ref", best.optString("ref", "")).put("kind", kind)
                .put("maxspeed", speed(best.optString("maxspeed", ""))).put("lanes", best.optString("lanes", ""))
                .put("goes_to", best.optString("destination", best.optString("destination:forward", "")));
    }

    /** "60", "60 km/h", "40 mph" -> km/h; 0 when not a number. */
    static int speed(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,3})").matcher(s);
        if (!m.find()) return 0;
        int v = Integer.parseInt(m.group(1));
        return s.contains("mph") ? (int) Math.round(v * 1.609) : v;
    }

    // ================================================================ answers

    /** "నేను ఎక్కడ ఉన్నాను?", "ఇది ఏ రోడ్డు?". */
    static JSONObject where(Context c) throws Exception {
        Location l = here(c);
        if (l == null) return new JSONObject().put("ok", false).put("error", "no_location").put("message", "Could not get the phone's location. Is Location on?");
        JSONObject o = new JSONObject().put("ok", true).put("lat", round(l.getLatitude())).put("lon", round(l.getLongitude()));
        long age = (System.currentTimeMillis() - l.getTime()) / 1000;
        if (age > 120) o.put("position_age_min", age / 60);
        try {
            List<android.location.Address> a = new android.location.Geocoder(c, Locale.ENGLISH).getFromLocation(l.getLatitude(), l.getLongitude(), 1);
            if (a != null && !a.isEmpty()) {
                android.location.Address ad = a.get(0);
                o.put("address", ad.getAddressLine(0)).put("area", nz(ad.getSubLocality())).put("town", nz(ad.getLocality()))
                        .put("district", nz(ad.getSubAdminArea())).put("state", nz(ad.getAdminArea()));
            }
        } catch (Exception ignored) {}
        try { o.put("road", road(l.getLatitude(), l.getLongitude())); } catch (Exception ignored) {}
        if (l.hasSpeed()) o.put("speed_kmh", Math.round(l.getSpeed() * 3.6));
        float h = heading(l);
        if (h >= 0) o.put("heading", direction(h));
        JSONObject nav = navState();
        if (nav != null) o.put("navigation", nav);
        JSONObject d = dest(c);
        if (d != null) o.put("going_to", d.optString("name"));
        return o.put("next", "Say in short Telugu: the area / town and district, the road (name or number, e.g. NH 65) and its kind; "
                + "if navigating, the next turn and arrival time from 'navigation'.");
    }

    /** "ఈ రూట్ ఎక్కడికి వెళ్తుంది?", "ఇంకా ఎంత దూరం?", "తర్వాత ఏ ఊరు వస్తుంది?". */
    static JSONObject route(Context c) throws Exception {
        Location l = here(c);
        if (l == null) return new JSONObject().put("ok", false).put("error", "no_location").put("message", "Could not get the phone's location. Is Location on?");
        JSONObject o = new JSONObject().put("ok", true);
        JSONObject nav = navState();
        if (nav != null) o.put("navigation", nav);
        JSONObject d = dest(c);
        if (d != null) o.put("going_to", d.optString("name"));
        if (GMaps.has(c)) { // 🗺️ Google first; the free map below only when there is no key or Google failed
            try {
                JSONObject g = googleRoute(c, l);
                if (g.has("roads_ahead") || g.has("notes")) {
                    return o.put("google", g).put("source", "Google Maps").put("next", "Say in short Telugu from 'google' (Google Maps' own data): where this road / route goes "
                            + "(the roads and next towns with km), km and time left with today's traffic if a destination is known, the toll if any, and from 'notes' "
                            + "(Gemini with Google Maps, in English: say it in Telugu) the next towns and one useful stop. Say once that it is from Google Maps. "
                            + "If 'navigation' is there, add the next turn. If 'notes_error' is there, skip the notes quietly.");
                }
                o.put("google_error", g.optString("notes_error", heading(l) < 0 && dest(c) == null ? "Google: standing still and no destination, so no way ahead."
                        : "Google gave no way ahead from here."));
            } catch (Exception e) { o.put("google_error", GMaps.telugu(e)); }
        }
        o.put("source", "free map (OpenStreetMap)");
        try { o.put("road_now", road(l.getLatitude(), l.getLongitude())); } catch (Exception ignored) {}
        Path p = ahead(c, l, 80);
        if (p == null) {
            return o.put("note", "He is standing still and no destination is known, so the direction is unknown.")
                    .put("next", "Say the road now and, if navigating, the next turn / arrival from 'navigation'. To know the whole route, "
                            + "he can start navigation through Jarvis (drive navigate) or ask again while moving.");
        }
        if (p.route) o.put("km_left", Math.round(p.totalKm)).put("minutes_left", Math.round(p.minutes)).put("via", p.via);
        else o.put("note", "No destination known: these are the towns straight ahead in the direction he is going.");
        try { o.put("towns_ahead", townsAlong(p, 8)); } catch (Exception ignored) {}
        if (p.route) {
            try {
                Path whole = ahead(c, l, Math.min(400, Math.max(10, p.totalKm + 1)));
                JSONArray el = overpass("[out:json][timeout:25];node(around:500," + line(whole.pts) + ")[barrier=toll_booth];out 80;");
                List<JSONObject> found = new ArrayList<>();
                for (int i = 0; el != null && i < el.length(); i++) {
                    JSONObject e = el.getJSONObject(i), t = e.optJSONObject("tags");
                    double[] at = place(whole.pts, e.optDouble("lat"), e.optDouble("lon")); // measured on the real road
                    if (at[1] > 150 || at[0] < 0.2) continue;
                    found.add(new JSONObject().put("name", t == null ? "" : t.optString("name", "")).put("km", at[0]));
                }
                Collections.sort(found, (a, b) -> Double.compare(a.optDouble("km"), b.optDouble("km")));
                JSONArray tolls = new JSONArray();
                double lastKm = -10;
                for (JSONObject f : found) { // both sides of a divided highway are one plaza
                    if (f.optDouble("km") - lastKm < 1.5) continue;
                    lastKm = f.optDouble("km");
                    tolls.put(new JSONObject().put("name", f.optString("name")).put("km", Math.round(f.optDouble("km"))));
                }
                o.put("toll_gates", tolls);
            } catch (Exception ignored) {}
        }
        return o.put("next", "Say in short Telugu: where this road / route goes (via roads, next 3-4 towns with km), km and time left if known, "
                + "toll gates count if any, and the next turn from 'navigation' if it is there. If 'google_error' is there, say in one line that Google "
                + "did not answer (its reason) and this is from the free map.");
    }

    /**
     * 🗺️ Google's view of the way ahead (his Maps key): the route to his destination, else the road straight on in his
     * direction for 60 km (Google drives it from where he is, the way he faces), with the roads it follows, the time
     * with traffic and the toll; and what Google Maps knows about it (Gemini grounded on Google Maps, English).
     */
    static JSONObject googleRoute(Context c, Location l) throws Exception {
        JSONObject g = new JSONObject();
        float h = heading(l);
        JSONObject d = dest(c);
        boolean toDest = d != null && d.has("lat"); // (a destination whose place is really known)
        double[] to = toDest ? new double[]{d.optDouble("lat"), d.optDouble("lon")}
                : h >= 0 ? MapMath.ahead(l.getLatitude(), l.getLongitude(), h, 60_000) : null;
        if (to != null) {
            // to his destination: time with traffic and the toll (Google's dearer kind); straight on: just the road's line (the cheap kind)
            List<GMaps.Route> rs = toDest ? GMaps.routes(c, l.getLatitude(), l.getLongitude(), h, to[0], to[1], Bike.riding(c), false)
                    : GMaps.line(c, l.getLatitude(), l.getLongitude(), h, to[0], to[1]);
            if (!rs.isEmpty()) {
                GMaps.Route r = rs.get(0);
                g.put("to", toDest ? d.optString("name") : "straight on in his direction (no destination set)");
                if (!r.via.isEmpty()) g.put("via", r.via);
                if (toDest) g.put("km_left", Math.round(r.meters / 1000)).put("minutes_left_with_traffic", Math.round(r.seconds / 60.0))
                        .put("minutes_without_traffic", Math.round(r.staticSeconds / 60.0));
                if (!r.toll.isEmpty()) g.put("toll", r.toll);
                if (toDest && !r.toll.isEmpty()) settings(c).edit().putString("trip_toll", r.toll).putString("trip_toll_dest", d.optString("name"))
                        .putLong("trip_toll_at", System.currentTimeMillis()).apply(); // (🧾 the diary's toll, for this trip only)
                JSONArray roads = new JSONArray();
                for (int i = 0; i < r.steps.size() && roads.length() < 6; i++) roads.put(r.steps.get(i));
                g.put("roads_ahead", roads);
                saveAhead(c, r.polyline, l);
            }
        }
        if (new Prefs(c).sp.getBoolean("maps_notes", true) && !new Prefs(c).geminiKey().trim().isEmpty()) {
            try {
                String road = g.optString("via", "");
                GMaps.Notes n = GMaps.roadNotes(c, l.getLatitude(), l.getLongitude(), h, road, toDest ? "He is driving to " + d.optString("name") + "." : "");
                if (!n.text.isEmpty()) g.put("notes", n.text);
                JSONArray src = new JSONArray(), links = new JSONArray();
                for (String[] sx : n.sources) { src.put(sx[0]); links.put(sx[1]); }
                if (src.length() > 0) g.put("notes_from_google_maps", src).put("notes_links", links); // (shown with their links on Jarvis Maps: Google's rule)
            } catch (Exception e) { g.put("notes_error", GMaps.telugu(e)); }
        }
        return g;
    }

    /** The way ahead Google gave last (for Jarvis Maps and the stops along it), with where he was then; kept a few minutes. */
    static void saveAhead(Context c, String polyline, Location l) {
        if (polyline == null || polyline.isEmpty()) return;
        JSONObject d = dest(c);
        settings(c).edit().putString("g_ahead", polyline).putLong("g_ahead_at", System.currentTimeMillis())
                .putString("g_ahead_from", l.getLatitude() + "," + l.getLongitude())
                .putString("g_ahead_to", d != null && d.has("lat") ? d.optString("name") : "").apply();
    }

    /**
     * The way ahead to search along (km long): Google's line from the last 3 minutes while he is still within 300 m of
     * where it was made (no new paid call per chip / press), else a new one (the cheap kind): to his destination, or
     * straight on in his direction. null: no direction and no destination; empty: Google gave none.
     */
    static List<double[]> aheadPts(Context c, Location l, double km) throws Exception {
        SharedPreferences st = settings(c);
        String from = st.getString("g_ahead_from", "");
        if (System.currentTimeMillis() - st.getLong("g_ahead_at", 0) < 180_000 && !from.isEmpty()) {
            try {
                String[] xy = from.split(",");
                JSONObject d0 = dest(c);
                String toNow = d0 != null && d0.has("lat") ? d0.optString("name") : "";
                if (meters(l.getLatitude(), l.getLongitude(), Double.parseDouble(xy[0]), Double.parseDouble(xy[1])) < 300 && toNow.equals(st.getString("g_ahead_to", ""))) {
                    List<double[]> kept = MapMath.decode(st.getString("g_ahead", ""));
                    if (kept.size() >= 2 && (!toNow.isEmpty() || MapMath.km(kept) >= km * 0.95)) return MapMath.cut(kept, km); // (the same trip: to his destination it is the whole way; straight on, long enough for this ask)
                }
            } catch (Exception ignored) {}
        }
        float h = heading(l);
        JSONObject d = dest(c);
        double[] to = d != null && d.has("lat") ? new double[]{d.optDouble("lat"), d.optDouble("lon")}
                : h >= 0 ? MapMath.ahead(l.getLatitude(), l.getLongitude(), h, Math.max(5, Math.min(150, km)) * 1000) : null;
        if (to == null) return null;
        List<GMaps.Route> rs = GMaps.line(c, l.getLatitude(), l.getLongitude(), h, to[0], to[1]);
        if (rs.isEmpty()) return new ArrayList<>();
        saveAhead(c, rs.get(0).polyline, l);
        return MapMath.cut(rs.get(0).pts, km);
    }

    /** What he asked for -> an OpenStreetMap filter and a Telugu word. */
    static String[] kind(String what) {
        String w = what == null ? "" : what.toLowerCase(Locale.ROOT).trim();
        if (has(w, "room", "lodge", "lodging", "stay", "రూమ్", "లాడ్జ్")) return new String[]{"[tourism~\"^(hotel|motel|guest_house)$\"]", "లాడ్జ్ / హోటల్ రూమ్"};
        if (has(w, "atm", "ఏటీఎం")) return new String[]{"[amenity=atm]", "ATM"};
        if (has(w, "coffee", "cafe", "tea", "chai", "కాఫీ", "టీ", "చాయ్", "ఛాయ్")) return new String[]{"[amenity=cafe]", "టీ / కాఫీ"};
        if (has(w, "petrol", "diesel", "fuel", "bunk", "cng", "పెట్రోల్", "డీజిల్", "బంక్")) return new String[]{"[amenity=fuel]", "పెట్రోల్ బంక్"};
        if (has(w, "ev", "charg", "ఛార్జ")) return new String[]{"[amenity=charging_station]", "EV ఛార్జింగ్"};
        if (has(w, "medical", "pharma", "మెడికల్", "మందుల")) return new String[]{"[amenity=pharmacy]", "మెడికల్ షాప్"};
        if (has(w, "hospital", "clinic", "ఆసుపత్రి", "హాస్పిటల్", "క్లినిక్")) return new String[]{"[amenity~\"^(hospital|clinic)$\"]", "ఆసుపత్రి"};
        if (has(w, "toilet", "washroom", "restroom", "టాయిలెట్", "బాత్రూమ్", "బాత్ రూమ్")) return new String[]{"[amenity=toilets]", "టాయిలెట్"};
        if (has(w, "police", "పోలీస్")) return new String[]{"[amenity=police]", "పోలీస్ స్టేషన్"};
        if (has(w, "church", "చర్చి")) return new String[]{"[amenity=place_of_worship][religion=christian]", "చర్చి"};
        if (has(w, "temple", "గుడి", "ఆలయం", "దేవాలయం")) return new String[]{"[amenity=place_of_worship][religion=hindu]", "గుడి"};
        if (has(w, "mosque", "మసీదు")) return new String[]{"[amenity=place_of_worship][religion=muslim]", "మసీదు"};
        if (has(w, "parking", "పార్కింగ్")) return new String[]{"[amenity=parking]", "పార్కింగ్"};
        if (has(w, "mechanic", "puncture", "garage", "tyre", "మెకానిక్", "పంక్చర్", "గ్యారేజ్")) return new String[]{"[shop~\"^(car_repair|tyres|motorcycle_repair)$\"]", "మెకానిక్ / పంక్చర్"};
        if (has(w, "bank", "బ్యాంక్")) return new String[]{"[amenity=bank]", "బ్యాంక్"};
        if (has(w, "restaurant", "food", "dhaba", "hotel", "biryani", "tiffin", "lunch", "dinner", "breakfast", "హోటల్", "భోజనం", "బిర్యానీ", "టిఫిన్", "ధాబా", "తినడానికి", "రెస్టారెంట్"))
            return new String[]{"[amenity~\"^(restaurant|fast_food|food_court)$\"]", "హోటల్ / రెస్టారెంట్"};
        if (w.isEmpty()) return null;
        PlaceKinds.K pk = PlaceKinds.matchWhole(w); // (malls, theatres, beaches, bus stands… from Jarvis Maps' list; "Inorbit mall" stays a name)
        if (pk != null) return new String[]{pk.osm, pk.te};
        String safe = w.replaceAll("[^\\p{L}\\p{M}\\p{N} ]", "").trim(); // a name, e.g. "KFC", "Café Coffee Day"
        return safe.isEmpty() ? null : new String[]{"[~\"^(name|brand)$\"~\"" + safe + "\",i]", safe};
    }

    private static boolean has(String w, String... keys) {
        String[] words = w.split("[\\s,.!?;:()/]+");
        for (String k : keys) {
            if (k.matches("[a-z]+")) { // English: whole words ("ev", "atm", "tea"); longer ones may start a word ("charging", "restaurants")
                if (k.length() <= 3 ? w.matches(".*\\b" + k + "\\b.*") : w.matches(".*\\b" + k + ".*")) return true;
                continue;
            }
            if (k.contains(" ")) { if (w.contains(k)) return true; continue; }
            for (String t : words) // Telugu: the word itself, or it with a short ending ("ఆసుపత్రికి"); never inside another word ("సిటీ" is not "టీ")
                if (t.equals(k) || (k.length() >= 3 && t.startsWith(k) && t.length() - k.length() <= 4)) return true;
        }
        return false;
    }

    /** Small words that may stand with a kind ("టీ షాప్", "petrol bunk near me"): the ask is still that kind. */
    private static final java.util.Set<String> KIND_FILL = new java.util.HashSet<>(java.util.Arrays.asList(
            "shop", "shops", "stall", "near", "nearby", "me", "any", "a", "the", "for", "best", "good", "open",
            "షాప్", "షాపు", "కొట్టు", "దగ్గర", "దగ్గర్లో", "ఏదైనా", "మంచి", "ఎక్కడ", "ఉంది", "తెరిచి"));

    /** Every word of the ask is a kind of place or a small word ("టీ", "petrol bunk"); a name with a kind in it is not ("Apollo hospital", "Coffee Day"). */
    static boolean kindOnly(String what) {
        String w = what == null ? "" : what.toLowerCase(Locale.ROOT).trim();
        if (w.isEmpty()) return false;
        boolean any = false;
        for (String t : w.split("[\\s,.!?;:()/]+")) {
            if (t.isEmpty() || KIND_FILL.contains(t)) continue;
            String[] k = kind(t);
            if (k == null || k[0].startsWith("[~")) return false; // (a word that is no kind: a name)
            any = true;
        }
        return any;
    }

    /** "దారిలో మంచి హోటల్", "ముందు పెట్రోల్ బంక్ ఎక్కడ?": places ahead on his way, nearest first. */
    static JSONObject along(Context c, String what, double km) throws Exception { return along(c, what, km, false); }

    /** freeOnly: the free map even with a key (Jarvis Maps when Google's map is not showing: Google's places never go on another map). */
    static JSONObject along(Context c, String what, double km, boolean freeOnly) throws Exception {
        String[] k = kind(what);
        if (k == null) return new JSONObject().put("ok", false).put("error", "missing").put("message", "What should I look for on the way?");
        Location l = here(c);
        if (l == null) return new JSONObject().put("ok", false).put("error", "no_location").put("message", "Could not get the phone's location. Is Location on?");
        String googleErr = null;
        if (GMaps.has(c) && !freeOnly) {
            try { return googleAlong(c, l, googleWords(what), k[1], km, false); }
            catch (Exception e) { googleErr = GMaps.telugu(e); }
        }
        JSONObject free = alongFree(c, l, k, km);
        if (googleErr != null) free.put("google_error", googleErr).put("source", "free map (OpenStreetMap), because Google failed");
        return free;
    }

    /** The free map's places along the way (OpenStreetMap). */
    static JSONObject alongFree(Context c, Location l, String[] k, double km) throws Exception {
        Path p = ahead(c, l, Math.max(3, Math.min(150, km)));
        String q = p == null
                ? String.format(Locale.ENGLISH, "[out:json][timeout:25];nwr(around:5000,%.6f,%.6f)%s;out center 80;", l.getLatitude(), l.getLongitude(), k[0])
                : "[out:json][timeout:25];nwr(around:" + (p.route ? 700 : 1500) + "," + line(p.pts) + ")" + k[0] + ";out center 120;";
        JSONArray el = overpass(q);
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; el != null && i < el.length(); i++) {
            JSONObject e = el.getJSONObject(i);
            JSONObject cc = e.has("lat") ? e : e.optJSONObject("center");
            if (cc == null) continue;
            JSONObject t = e.optJSONObject("tags");
            if (t == null) t = new JSONObject();
            double la = cc.optDouble("lat"), lo = cc.optDouble("lon");
            String name = t.optString("name:te", t.optString("name", t.optString("brand", "")));
            JSONObject item = new JSONObject().put("name", name.isEmpty() ? "(పేరు లేదు)" : name).put("lat", round(la)).put("lon", round(lo));
            if (p != null) {
                double[] at = place(p.pts, la, lo);
                if (at[0] < 0.1 || at[1] > (p.route ? 800 : 1700)) continue; // behind him or too far off the way
                item.put("km_ahead", Math.round(at[0] * 10) / 10.0).put("off_road_m", Math.round(at[1]))
                        .put("side", at[1] < 40 ? "" : at[2] < 0 ? "ఎడమ వైపు" : "కుడి వైపు");
            } else {
                item.put("km", Math.round(meters(l.getLatitude(), l.getLongitude(), la, lo) / 100) / 10.0);
            }
            if (!t.optString("opening_hours").isEmpty()) item.put("hours", t.optString("opening_hours"));
            if (!t.optString("cuisine").isEmpty()) item.put("cuisine", t.optString("cuisine"));
            if ("24/7".equals(t.optString("opening_hours"))) item.put("open_24h", true);
            list.add(item);
        }
        final String by = p != null ? "km_ahead" : "km";
        Collections.sort(list, (a, b) -> Double.compare(a.optDouble(by), b.optDouble(by)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.size() && out.length() < 8; i++) out.put(list.get(i));
        JSONObject o = new JSONObject().put("ok", true).put("looking_for", k[1]).put("places", out)
                .put("searched", p == null ? "around him (5 km; he is not moving and no destination is known)"
                        : p.route ? "along his route for " + Math.round(Math.min(km, p.totalKm)) + " km" : "straight ahead in his direction for " + Math.round(km) + " km");
        if (out.length() == 0) o.put("note", "Nothing of this kind on the free map along this stretch (rural places are often missing). Say so honestly; offer to search wider (km) or Google Maps.");
        o.put("source", "free map (OpenStreetMap)");
        return o.put("next", "Say the first 3 in short Telugu: name, km ahead, left / right side, and hours if known. Offer to add one as a stop (drive add_stop with its lat,lon).");
    }

    /** What he asked for, as words Google's search knows. */
    static String googleWords(String what) {
        String w = what == null ? "" : what.toLowerCase(Locale.ROOT).trim();
        if (has(w, "room", "lodge", "lodging", "stay", "రూమ్", "లాడ్జ్")) return "lodge or hotel rooms";
        if (has(w, "atm", "ఏటీఎం")) return "ATM";
        if (has(w, "coffee", "cafe", "tea", "chai", "కాఫీ", "టీ", "చాయ్", "ఛాయ్")) return "tea stall or coffee shop";
        if (has(w, "petrol", "diesel", "fuel", "bunk", "cng", "పెట్రోల్", "డీజిల్", "బంక్")) return "petrol pump";
        if (has(w, "ev", "charg", "ఛార్జ")) return "EV charging station";
        if (has(w, "medical", "pharma", "మెడికల్", "మందుల")) return "medical shop pharmacy";
        if (has(w, "hospital", "clinic", "ఆసుపత్రి", "హాస్పిటల్", "క్లినిక్")) return "hospital";
        if (has(w, "toilet", "washroom", "restroom", "టాయిలెట్", "బాత్రూమ్", "బాత్ రూమ్")) return "public toilet";
        if (has(w, "police", "పోలీస్")) return "police station";
        if (has(w, "church", "చర్చి")) return "church";
        if (has(w, "temple", "గుడి", "ఆలయం", "దేవాలయం")) return "temple";
        if (has(w, "mosque", "మసీదు")) return "mosque";
        if (has(w, "parking", "పార్కింగ్")) return "parking";
        if (has(w, "mechanic", "puncture", "garage", "tyre", "మెకానిక్", "పంక్చర్", "గ్యారేజ్")) return "puncture shop or mechanic";
        if (has(w, "bank", "బ్యాంక్")) return "bank";
        if (has(w, "restaurant", "food", "dhaba", "hotel", "biryani", "tiffin", "lunch", "dinner", "breakfast", "హోటల్", "భోజనం", "బిర్యానీ", "టిఫిన్", "ధాబా", "తినడానికి", "రెస్టారెంట్"))
            return "restaurant or dhaba";
        PlaceKinds.K pk = PlaceKinds.matchWhole(w);
        if (pk != null) return pk.google;
        return what == null ? "" : what.trim();
    }

    /**
     * 🗺️ Google's places along his way (his Maps key): along the route to his destination, else along the road straight
     * on in his direction (Google drives it from where he is), each with its km and minutes ahead by road; around him
     * (5 km) when he is standing still with no destination. openOnly: only the ones open now.
     */
    static JSONObject googleAlong(Context c, Location l, String words, String label, double km, boolean openOnly) throws Exception {
        JSONObject d = dest(c);
        List<double[]> pts = aheadPts(c, l, Math.max(3, Math.min(150, km)));
        boolean noWay = pts != null && pts.size() < 2;
        String line = null;
        if (pts != null && pts.size() >= 2) line = MapMath.encode(pts);
        else pts = null;
        List<GMaps.Place> ps = GMaps.search(c, words, l.getLatitude(), l.getLongitude(), 5000, line, openOnly, 12, false, true);
        List<JSONObject> list = new ArrayList<>();
        for (GMaps.Place p : ps) {
            JSONObject item = item(p);
            if (line != null) {
                if (p.aheadM < 0) continue;
                item.put("km_ahead", Math.round(p.aheadM / 100) / 10.0);
                if (p.aheadS > 0) item.put("minutes_ahead", Math.round(p.aheadS / 60.0));
                double[] at = place(pts, p.lat, p.lon);
                if (at[1] < 1500) item.put("side", at[1] < 40 ? "" : at[2] < 0 ? "ఎడమ వైపు" : "కుడి వైపు");
                Boolean then = GMaps.openAt(p.periods, System.currentTimeMillis() + Math.max(0, p.aheadS) * 1000); // (when he gets there, not now)
                if (then != null) item.put("open_when_he_gets_there", then);
            } else item.put("km", Math.round(p.aheadM / 100) / 10.0);
            list.add(item);
        }
        final String by = line != null ? "km_ahead" : "km";
        Collections.sort(list, (a, b) -> Double.compare(a.optDouble(by), b.optDouble(by)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.size() && out.length() < 8; i++) out.put(list.get(i));
        JSONObject o = new JSONObject().put("ok", true).put("looking_for", label).put("places", out).put("source", "Google Maps")
                .put("searched", line == null ? (noWay ? "around him (5 km; Google gave no way ahead)" : "around him (5 km; he is not moving and no destination is known)")
                        : d != null && d.has("lat") ? "along his route for " + Math.round(km) + " km" : "along the road ahead in his direction for " + Math.round(km) + " km");
        if (out.length() == 0) o.put("note", "Google found none of this kind on this stretch" + (openOnly ? " open now" : "") + ". Say so; offer a wider search (more km).");
        return o.put("next", "Say the first 2-3 in short Telugu: name, km ahead (and minutes), left / right side, open now if known. "
                + "Offer to set the way to one (drive navigate with its 'lat,lon'); set it only after he says yes.");
    }

    /** A Google place as the answer's item: name, place, address, open now, rating, phone, website, its Maps link. */
    static JSONObject item(GMaps.Place p) throws Exception {
        JSONObject item = new JSONObject().put("name", p.name).put("lat", round(p.lat)).put("lon", round(p.lon));
        if (!p.address.isEmpty()) item.put("address", p.address);
        if (!p.type.isEmpty()) item.put("type", p.type);
        if (p.open != null) item.put("open_now", p.open);
        if (p.rating > 0) item.put("rating", p.rating).put("ratings", p.ratings);
        if (!p.phone.isEmpty()) item.put("phone", p.phone);
        if (!p.website.isEmpty()) item.put("website", p.website);
        if (!p.mapsUri.isEmpty()) item.put("maps_link", p.mapsUri);
        if (p.periods != null && p.periods.length() == 1 && p.periods.optJSONObject(0) != null && !p.periods.optJSONObject(0).has("close")) item.put("open_24h", true);
        return item;
    }

    // ---------------------------------------------------------------- 😴 a tea / coffee stop when he is sleepy

    /** The last stop found: what, when, where he was (one object: never half-updated). */
    private static final class Stop {
        final JSONObject r;
        final long at;
        final double lat, lon;
        Stop(JSONObject r, long at, double lat, double lon) { this.r = r; this.at = at; this.lat = lat; this.lon = lon; }
    }

    private static volatile Stop lastStop;

    /** The stop Jarvis offered him (said or in the notification): his "yes" is to this one only. */
    private static final class Offer {
        final String name;
        final double lat, lon;
        final long at;
        Offer(String name, double lat, double lon, long at) { this.name = name; this.lat = lat; this.lon = lon; this.at = at; }
    }

    private static volatile Offer offered;
    /** When the brain last told him a stop (so the alarm's offer doesn't say it again over it). */
    static volatile long stopToldAt;

    /**
     * The nearest tea / coffee / food stop ahead that is open now (Google with his Maps key; the free map otherwise),
     * kept for 10 minutes so "దారి పెట్టు" after the offer finds the same one without asking Google again.
     */
    static synchronized JSONObject restStop(Context c) throws Exception {
        Location l = here(c);
        if (l == null) return new JSONObject().put("ok", false).put("error", "no_location").put("message", "Could not get the phone's location. Is Location on?");
        Stop kept = lastStop;
        if (kept != null && System.currentTimeMillis() - kept.at < 10 * 60_000 && (meters(l.getLatitude(), l.getLongitude(), kept.lat, kept.lon) < 500 || stillAhead(kept.r, l, kept)))
            return withOffer(new JSONObject(kept.r.toString()).put("found_minutes_ago", (System.currentTimeMillis() - kept.at) / 60_000), l); // (the same one while it is still ahead of him)
        JSONObject r = null;
        String googleErr = null;
        if (GMaps.has(c)) {
            try {
                r = googleAlong(c, l, "tea stall or coffee shop or dhaba", "టీ / కాఫీ / దాబా", 30, true);
                if (r.optJSONArray("places").length() == 0) r = googleAlong(c, l, "petrol pump with restaurant or restaurant", "హోటల్ / పెట్రోల్ బంక్", 40, true);
            } catch (Exception e) { googleErr = GMaps.telugu(e); r = null; }
        }
        if (r == null) {
            r = alongFree(c, l, kind("tea"), 30);
            if (r.optJSONArray("places") == null || r.optJSONArray("places").length() == 0) r = alongFree(c, l, kind("restaurant"), 40);
            if (googleErr != null) r.put("google_error", googleErr);
        }
        boolean google = r.optString("source").startsWith("Google");
        r.put("next", "He is sleepy at the wheel. Say in short Telugu: the nearest " + (google ? "open one" : "one (the free map does not know if it is open now: say so)")
                + " (name, km ahead, side), then ask 'అక్కడికి దారి పెట్టనా?'. Set the way only after he says yes: drive add_stop when he is going somewhere, "
                + "else drive navigate, with place = its 'lat,lon'. If none, tell him to stop at the first safe place.");
        lastStop = new Stop(r, System.currentTimeMillis(), l.getLatitude(), l.getLongitude());
        return withOffer(new JSONObject(r.toString()), l);
    }

    /** The brain is telling him this stop (drive rest_stop): it is now the one his "yes" is to, and the alarm's offer stays quiet a while. */
    static void told(JSONObject r) {
        stopToldAt = System.currentTimeMillis();
        JSONArray ps = r == null ? null : r.optJSONArray("places");
        JSONObject p = ps == null || ps.length() == 0 ? null : ps.optJSONObject(0);
        if (p != null && p.has("lat")) offered = new Offer(p.optString("name"), p.optDouble("lat"), p.optDouble("lon"), System.currentTimeMillis());
    }

    /** The first stop found is still ahead: he is nearer to it now than when it was found (not driven past it). */
    private static boolean stillAhead(JSONObject r, Location l, Stop kept) {
        JSONArray ps = r.optJSONArray("places");
        JSONObject p = ps == null || ps.length() == 0 ? null : ps.optJSONObject(0);
        if (p == null || !p.has("lat")) return false;
        double now = meters(l.getLatitude(), l.getLongitude(), p.optDouble("lat"), p.optDouble("lon"));
        double then = meters(kept.lat, kept.lon, p.optDouble("lat"), p.optDouble("lon"));
        return now > 150 && now < then + 300;
    }

    /** Whether this is the stop offered to him: his "yes" sets the way only to that one; another one is told and asked again. */
    private static JSONObject withOffer(JSONObject r, Location l) throws Exception {
        Offer o = offered;
        if (o == null || System.currentTimeMillis() - o.at > 10 * 60_000) return r;
        JSONArray ps = r.optJSONArray("places");
        JSONObject p = ps == null || ps.length() == 0 ? null : ps.optJSONObject(0);
        boolean same = p != null && p.optString("name").equals(o.name) && meters(p.optDouble("lat"), p.optDouble("lon"), o.lat, o.lon) < 200;
        if (same) return r.put("offered_to_him", true);
        r.put("offered_before", o.name);
        r.put("next", "This is NOT the stop offered to him before (" + o.name + "; he has probably passed it). Tell him this one in short Telugu "
                + "and ask again 'అక్కడికి దారి పెట్టనా?'; set the way only after a new yes. If none, tell him to stop at the first safe place.");
        return r;
    }

    /**
     * After the car's sleep alarm (or "నిద్ర వస్తోంది"): look for the nearest open stop ahead and offer it — said aloud,
     * and a notification whose tap starts the way there in Google Maps. Never sets the way by itself. Off the main thread.
     */
    static boolean offerRestStop(Context c) {
        final Context app = c.getApplicationContext();
        if (!Net.online(app)) return false;
        SharedPreferences st = settings(app);
        if (System.currentTimeMillis() - st.getLong("stop_offered_at", 0) < 10 * 60_000) return false; // (once in 10 minutes)
        st.edit().putLong("stop_offered_at", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                JSONObject r = restStop(app);
                JSONArray ps = r.optJSONArray("places");
                if (ps == null || ps.length() == 0) return;
                JSONObject p = ps.getJSONObject(0);
                for (int i = 0; i < 40 && (MainActivity.busyTalking() || CarCare.asking(app)); i++) Thread.sleep(500); // (never over his talk / the car's question)
                boolean quiet = MainActivity.busyTalking() || CarCare.asking(app) || System.currentTimeMillis() - stopToldAt < 120_000; // (still busy, or the brain just said it: the notification only)
                String name = p.optString("name");
                String where = p.has("km_ahead") ? p.optDouble("km_ahead") + " కి.మీ. ముందు" : p.optDouble("km") + " కి.మీ. దూరంలో";
                String side = p.optString("side", "");
                String text = "దగ్గర్లో " + where + (side.isEmpty() ? "" : ", " + side) + " " + name + (p.optBoolean("open_now", false) ? " తెరిచి ఉంది" : " ఉంది")
                        + ". అక్కడ ఆగి టీ తాగండి. దారి కావాలంటే నోటిఫికేషన్ నొక్కండి, లేదా 'Jarvis, దారి పెట్టు' అనండి.";
                offered = new Offer(name, p.optDouble("lat"), p.optDouble("lon"), System.currentTimeMillis()); // (his "yes" is to this one)
                if (!quiet) Announcer.say(app, text);
                android.app.NotificationManager nm = app.getSystemService(android.app.NotificationManager.class);
                nm.createNotificationChannel(new android.app.NotificationChannel("jarvis_rest_stop", "ఆగే చోటు (నిద్ర వచ్చినప్పుడు)", android.app.NotificationManager.IMPORTANCE_HIGH));
                String ll = p.optDouble("lat") + "," + p.optDouble("lon");
                JSONObject d = dest(app);
                Intent nav = d != null ? viaStop(destWhere(d), ll, Bike.riding(app)) : navigate(ll, "", Bike.riding(app)); // (his trip keeps its destination)
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(app, 7071, nav, android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                nm.notify(7071, new Notification.Builder(app, "jarvis_rest_stop").setSmallIcon(android.R.drawable.ic_dialog_map)
                        .setContentTitle("☕ " + name + " · " + where).setContentText("నొక్కితే Google Maps లో దారి")
                        .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(30 * 60_000L).build());
            } catch (Exception ignored) {}
        }, "jarvis-rest-stop").start();
        return true;
    }

    /** His destination for Google Maps: its place ("lat,lon") when known, else its name. */
    static String destWhere(JSONObject d) {
        return d != null && d.has("lat") ? d.optDouble("lat") + "," + d.optDouble("lon") : d == null ? "" : d.optString("name");
    }

    /** "రూట్‌లో స్పీడ్ కెమెరాలు ఎక్కడ?": cameras ahead on the map, nearest first. */
    static JSONObject cameras(Context c, double km) throws Exception {
        Location l = here(c);
        if (l == null) return new JSONObject().put("ok", false).put("error", "no_location").put("message", "Could not get the phone's location. Is Location on?");
        Path p = ahead(c, l, Math.max(5, Math.min(300, km)));
        String q = p == null
                ? String.format(Locale.ENGLISH, "[out:json][timeout:25];node(around:15000,%.6f,%.6f)[highway=speed_camera];out 60;", l.getLatitude(), l.getLongitude())
                : "[out:json][timeout:25];node(around:" + (p.route ? 400 : 250) + "," + line(p.pts) + ")[highway=speed_camera];out 100;";
        JSONArray el = overpass(q);
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; el != null && i < el.length(); i++) {
            JSONObject e = el.getJSONObject(i), t = e.optJSONObject("tags");
            double la = e.optDouble("lat"), lo = e.optDouble("lon");
            JSONObject item = new JSONObject().put("limit_kmh", t == null ? 0 : speed(t.optString("maxspeed", "")));
            if (p != null) {
                double[] at = place(p.pts, la, lo);
                if (at[0] < 0.05 || at[1] > (p.route ? 100 : 250)) continue; // measured on the real road
                item.put("km_ahead", Math.round(at[0] * 10) / 10.0);
            } else item.put("km", Math.round(meters(l.getLatitude(), l.getLongitude(), la, lo) / 100) / 10.0);
            list.add(item);
        }
        for (double[] m : myCams(c)) { // the ones he marked himself
            JSONObject item = new JSONObject().put("limit_kmh", (int) m[2]).put("marked_by_him", true);
            if (p != null) {
                double[] at = place(p.pts, m[0], m[1]);
                if (at[0] < 0.05 || at[1] > (p.route ? 150 : 300)) continue;
                item.put("km_ahead", Math.round(at[0] * 10) / 10.0);
            } else {
                double d = meters(l.getLatitude(), l.getLongitude(), m[0], m[1]);
                if (d > 15000) continue;
                item.put("km", Math.round(d / 100) / 10.0);
            }
            list.add(item);
        }
        final String by = p != null ? "km_ahead" : "km";
        Collections.sort(list, (a, b) -> Double.compare(a.optDouble(by), b.optDouble(by)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.size() && out.length() < 10; i++) out.put(list.get(i));
        return new JSONObject().put("ok", true).put("cameras", out).put("count", list.size())
                .put("searched", p == null ? "15 km around him" : p.route ? "along his route" : "straight ahead in his direction")
                .put("live_alerts", DriveService.running && settings(c).getBoolean("drive_cameras", true))
                .put("note", "Speed cameras come from the free OpenStreetMap; many in India are not on it, so this can miss some. Say this once, briefly.")
                .put("next", "Say how many and the nearest ones with km and limit. If live_alerts is false, offer drive start so Jarvis warns him as he comes near.");
    }

    /** "ఆలస్యం అవుతుంది అని చెప్పు": his arrival time and where he is now, as a message (sent only after he says send). */
    static JSONObject shareText(Context c) throws Exception {
        Location l = here(c);
        JSONObject nav = navState();
        JSONObject d = dest(c);
        String eta = "";
        if (nav != null) eta = nav.optString("trip");
        if (eta.isEmpty() && d != null && d.has("lat") && l != null) {
            try {
                JSONObject r = osrm(l.getLatitude(), l.getLongitude(), d.optDouble("lat"), d.optDouble("lon"));
                if (r != null) {
                    long min = Math.round(r.optDouble("duration") / 60.0);
                    eta = "సుమారు " + (min >= 60 ? (min / 60) + " గంటల " + (min % 60) + " నిమిషాల్లో" : min + " నిమిషాల్లో") + " చేరుకుంటాను ("
                            + Math.round(r.optDouble("distance") / 1000.0) + " కి.మీ.)";
                }
            } catch (Exception ignored) {}
        }
        StringBuilder m = new StringBuilder();
        m.append(d != null ? d.optString("name") + " కి వస్తున్నాను. " : "దారిలో ఉన్నాను. ");
        if (!eta.isEmpty()) m.append(eta).append(". ");
        if (l != null) m.append("ఇప్పుడు ఇక్కడ ఉన్నాను: https://maps.google.com/?q=").append(round(l.getLatitude())).append(',').append(round(l.getLongitude()));
        return new JSONObject().put("ok", true).put("message", m.toString().trim())
                .put("next", "Read the message to him and ask whom to send it to; send only after he says 'పంపు' (whatsapp_message / send_sms).");
    }

    // ================================================================ cameras he marked himself ("ఇక్కడ స్పీడ్ కెమెరా ఉంది")

    private static volatile List<double[]> mine; // {lat, lon, limit, id (negative), heading (-1 = any direction)}

    /** His own cameras (cached; the drive alerts read this on every fix). */
    static List<double[]> myCams(Context c) {
        List<double[]> m = mine;
        if (m != null) return m;
        List<double[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(settings(c).getString("my_cams", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new double[]{o.optDouble("lat"), o.optDouble("lon"), o.optInt("limit", 0), -o.optLong("id", i + 1), o.optDouble("heading", -1)});
            }
        } catch (Exception ignored) {}
        mine = out;
        return out;
    }

    /** Marks a camera where he is now, for the direction he is going (the same spot again just updates it). */
    static JSONObject addCamera(Context c, int limit) throws Exception {
        Location l = here(c);
        if (l == null) return new JSONObject().put("ok", false).put("error", "no_location").put("message", "Could not get the phone's location. Is Location on?");
        float h = heading(l);
        JSONArray a = new JSONArray(settings(c).getString("my_cams", "[]")), keep = new JSONArray();
        int oldLimit = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            boolean same = meters(o.optDouble("lat"), o.optDouble("lon"), l.getLatitude(), l.getLongitude()) < 100
                    && (h < 0 || o.optDouble("heading", -1) < 0 || angle(h, o.optDouble("heading")) < 60);
            if (same) oldLimit = o.optInt("limit", 0); else keep.put(o);
        }
        keep.put(new JSONObject().put("id", System.currentTimeMillis()).put("lat", round(l.getLatitude())).put("lon", round(l.getLongitude()))
                .put("heading", h >= 0 ? Math.round(h) : -1).put("limit", limit > 0 ? limit : oldLimit).put("at", System.currentTimeMillis()));
        settings(c).edit().putString("my_cams", keep.toString()).apply();
        mine = null;
        return new JSONObject().put("ok", true).put("saved", true).put("total_mine", keep.length()).put("direction_known", h >= 0)
                .put("accuracy_m", Math.round(l.getAccuracy()))
                .put("next", "Say in one short line that this camera is saved and Jarvis will warn him here next time"
                        + (h >= 0 ? " (when going this same way)." : " (from either side, since he was standing still)."));
    }

    /** Takes off the camera he marked near here (within 500 m), or all of them. */
    static JSONObject removeCamera(Context c, boolean all) throws Exception {
        JSONArray a = new JSONArray(settings(c).getString("my_cams", "[]"));
        if (all) {
            settings(c).edit().putString("my_cams", "[]").apply();
            mine = null;
            return new JSONObject().put("ok", true).put("removed", a.length());
        }
        Location l = here(c);
        if (l == null) return new JSONObject().put("ok", false).put("error", "no_location").put("message", "Could not get the phone's location. Is Location on?");
        int best = -1;
        double bestD = 500;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            double d = meters(o.optDouble("lat"), o.optDouble("lon"), l.getLatitude(), l.getLongitude());
            if (d < bestD) { bestD = d; best = i; }
        }
        if (best < 0) return new JSONObject().put("ok", false).put("error", "none_near").put("message", "No camera he marked within 500 m of here.");
        JSONArray keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (i != best) keep.put(a.get(i));
        settings(c).edit().putString("my_cams", keep.toString()).apply();
        mine = null;
        return new JSONObject().put("ok", true).put("removed", 1).put("was_m_away", Math.round(bestD)).put("total_mine", keep.length());
    }

    /** How many he marked, and the nearest few with km. */
    static JSONObject listCameras(Context c) throws Exception {
        List<double[]> m = myCams(c);
        JSONObject o = new JSONObject().put("ok", true).put("total_mine", m.size());
        Location l = here(c);
        if (l != null && !m.isEmpty()) {
            List<double[]> sorted = new ArrayList<>(m);
            Collections.sort(sorted, (x, y) -> Double.compare(meters(l.getLatitude(), l.getLongitude(), x[0], x[1]), meters(l.getLatitude(), l.getLongitude(), y[0], y[1])));
            JSONArray near = new JSONArray();
            for (int i = 0; i < sorted.size() && i < 5; i++) {
                double[] x = sorted.get(i);
                near.put(new JSONObject().put("km", Math.round(meters(l.getLatitude(), l.getLongitude(), x[0], x[1]) / 100) / 10.0).put("limit_kmh", (int) x[2]));
            }
            o.put("nearest", near);
        }
        return o;
    }

    // ================================================================ Google Maps navigation

    /** Turn-by-turn in Google Maps, with tolls / highways avoided and two-wheeler mode if asked. */
    static Intent navigate(String place, String avoid, boolean twoWheeler) {
        String a = avoid == null ? "" : avoid.toLowerCase(Locale.ROOT), av = "";
        boolean both = a.contains("both") || a.contains("రెండూ");
        if (both || a.contains("toll") || a.contains("టోల్")) av += "t";
        if (both || a.contains("highway") || a.contains("హైవే")) av += "h";
        if (a.contains("ferr")) av += "f";
        String q = "google.navigation:q=" + Uri.encode(place.trim()) + "&mode=" + (twoWheeler ? "l" : "d") + (av.isEmpty() ? "" : "&avoid=" + av);
        return new Intent(Intent.ACTION_VIEW, Uri.parse(q)).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Navigation to his destination with a stop on the way (restaurant, petrol bunk...). */
    static Intent viaStop(String destination, String stop, boolean twoWheeler) {
        String u = "https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination.trim()) + "&waypoints=" + Uri.encode(stop.trim())
                + "&travelmode=" + (twoWheeler ? "two-wheeler" : "driving") + "&dir_action=navigate";
        return new Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ================================================================ starting / ending a drive

    /** Drive alerts on (needs location permission); false if Android refused. */
    static boolean startDrive(Context c) {
        try { return DriveService.start(c); } catch (Exception e) { return false; }
    }

    /** Drive alerts off; the spot where he stopped is kept as his parking place. Returns true if it was saved. */
    static boolean stopDrive(Context c) {
        Location l = DriveService.last;
        double trip = DriveService.tripMeters;
        boolean was = DriveService.running;
        DriveService.stop(c);
        clearDest(c);
        if (was && l != null && trip > 1000 && System.currentTimeMillis() - l.getTime() < 10 * 60000L) {
            GeoReminders.savePlace(c, "parking", l.getLatitude(), l.getLongitude());
            Life.markParked(c);
            return true;
        }
        return false;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static double round(double v) { return Math.round(v * 100000) / 100000.0; }
}
