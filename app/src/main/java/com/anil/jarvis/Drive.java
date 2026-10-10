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

    private static double[] latLon(Context c, String place) {
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

    /** OSRM route (cached for 3 minutes for the same trip and nearly the same start). */
    private static JSONObject osrm(double la1, double lo1, double la2, double lo2) throws Exception {
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
    private static String line(List<double[]> pts) {
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
    private static double[] place(List<double[]> pts, double lat, double lon) {
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
        try { o.put("road_now", road(l.getLatitude(), l.getLongitude())); } catch (Exception ignored) {}
        Path p = ahead(c, l, 80);
        if (p == null) {
            return o.put("note", "He is standing still and no destination is known, so the direction is unknown.")
                    .put("next", "Say the road now and, if navigating, the next turn / arrival from 'navigation'. To know the whole route, "
                            + "he can start navigation through Jarvis (drive navigate) or ask again while moving.");
        }
        if (p.route) o.put("km_left", Math.round(p.totalKm)).put("minutes_left", Math.round(p.minutes)).put("via", p.via);
        else o.put("note", "No destination known: these are the towns straight ahead in the direction he is going.");
        try {
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
            for (int i = 0; i < towns.size() && next.length() < 8; i++) next.put(towns.get(i));
            o.put("towns_ahead", next);
        } catch (Exception ignored) {}
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
                + "toll gates count if any, and the next turn from 'navigation' if it is there.");
    }

    /** What he asked for -> an OpenStreetMap filter and a Telugu word. */
    static String[] kind(String what) {
        String w = what == null ? "" : what.toLowerCase(Locale.ROOT).trim();
        if (has(w, "room", "lodge", "lodging", "stay", "రూమ్", "లాడ్జ్", "బస")) return new String[]{"[tourism~\"^(hotel|motel|guest_house)$\"]", "లాడ్జ్ / హోటల్ రూమ్"};
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
        String safe = w.replaceAll("[^\\p{L}\\p{M}\\p{N} ]", "").trim(); // a name, e.g. "KFC", "Café Coffee Day"
        return safe.isEmpty() ? null : new String[]{"[~\"^(name|brand)$\"~\"" + safe + "\",i]", safe};
    }

    private static boolean has(String w, String... keys) {
        for (String k : keys) {
            if (k.length() <= 3 && k.matches("[a-z]+")) { if (w.matches(".*\\b" + k + "\\b.*")) return true; } // "ev", "atm", "tea": whole words
            else if (w.contains(k)) return true;
        }
        return false;
    }

    /** "దారిలో మంచి హోటల్", "ముందు పెట్రోల్ బంక్ ఎక్కడ?": places ahead on his way, nearest first. */
    static JSONObject along(Context c, String what, double km) throws Exception {
        String[] k = kind(what);
        if (k == null) return new JSONObject().put("ok", false).put("error", "missing").put("message", "What should I look for on the way?");
        Location l = here(c);
        if (l == null) return new JSONObject().put("ok", false).put("error", "no_location").put("message", "Could not get the phone's location. Is Location on?");
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
        return o.put("next", "Say the first 3 in short Telugu: name, km ahead, left / right side, and hours if known. Offer to add one as a stop (drive add_stop with its lat,lon).");
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
