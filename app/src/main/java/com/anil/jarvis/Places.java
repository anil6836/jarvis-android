package com.anil.jarvis;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anil's saved places ("సిస్టర్ ఇల్లు", office, a shop): from where he is now, from a Google Maps link
 * (shared to Jarvis or pasted) or from an address. Asked for later, Jarvis shows the place on the map,
 * starts navigation or gives a link to send. Kept on the phone with the places location reminders use.
 */
final class Places {
    private Places() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_geo", Context.MODE_PRIVATE); }

    private static JSONObject raw(Context c) {
        try { return new JSONObject(sp(c).getString("places", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /** His places, newest first (the parking spot is left out). */
    static List<JSONObject> all(Context c) {
        List<JSONObject> out = new ArrayList<>();
        JSONObject r = raw(c);
        Iterator<String> it = r.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equals("parking")) continue;
            JSONObject p = r.optJSONObject(k);
            if (p != null) out.add(p);
        }
        out.sort((a, b) -> Long.compare(b.optLong("saved"), a.optLong("saved")));
        return out;
    }

    static synchronized JSONObject save(Context c, String name, double lat, double lon, String address, String link) throws Exception {
        JSONObject r = raw(c);
        JSONObject p = new JSONObject().put("name", name.trim()).put("saved", System.currentTimeMillis());
        if (!Double.isNaN(lat) && !Double.isNaN(lon)) p.put("lat", lat).put("lon", lon);
        if (address != null && !address.trim().isEmpty()) p.put("address", address.trim());
        if (link != null && !link.trim().isEmpty()) p.put("link", link.trim());
        r.put(GeoReminders.key(name), p);
        sp(c).edit().putString("places", r.toString()).apply();
        return p;
    }

    static synchronized boolean remove(Context c, String name) {
        JSONObject p = find(c, name);
        if (p == null) return false;
        JSONObject r = raw(c);
        r.remove(GeoReminders.key(p.optString("name")));
        sp(c).edit().putString("places", r.toString()).apply();
        return true;
    }

    /** The saved place he means: the same name, one inside the other, or the most words in common. */
    static JSONObject find(Context c, String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) return null;
        String k = GeoReminders.key(spoken);
        List<JSONObject> all = all(c);
        for (JSONObject p : all) if (GeoReminders.key(p.optString("name")).equals(k)) return p;
        for (JSONObject p : all) {
            String pk = GeoReminders.key(p.optString("name"));
            if (!pk.isEmpty() && (pk.contains(k) || k.contains(pk))) return p;
        }
        Set<String> want = words(k);
        JSONObject best = null;
        int bestHits = 0;
        for (JSONObject p : all) {
            int hits = 0;
            for (String w : words(GeoReminders.key(p.optString("name")))) if (w.length() >= 3 && want.contains(w)) hits++;
            if (hits > bestHits) { bestHits = hits; best = p; }
        }
        return best;
    }

    private static Set<String> words(String s) {
        Set<String> w = new HashSet<>();
        for (String x : s.split("[^\\p{L}\\p{M}\\p{N}]+")) if (!x.isEmpty()) w.add(x);
        return w;
    }

    static JSONArray listJson(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject p : all(c)) {
            JSONObject o = new JSONObject().put("name", p.optString("name"));
            if (p.has("address")) o.put("address", p.optString("address"));
            o.put("on_map", p.has("lat") ? "exact point" : p.has("link") ? "maps link" : "address search");
            a.put(o);
        }
        return a;
    }

    // ---------------------------------------------------------------- opening, sharing

    /** Shows the place on the map (a pin with its name), or starts navigation to it. */
    static void open(Context c, JSONObject p, boolean navigate) {
        String name = p.optString("name");
        Uri u;
        if (p.has("lat")) {
            String ll = p.optDouble("lat") + "," + p.optDouble("lon");
            u = Uri.parse(navigate ? "google.navigation:q=" + ll : "geo:" + ll + "?q=" + ll + "(" + Uri.encode(name) + ")");
        } else if (!navigate && !p.optString("link").isEmpty()) {
            u = Uri.parse(p.optString("link"));
        } else {
            String q = Uri.encode(p.optString("address", name));
            u = Uri.parse(navigate ? "google.navigation:q=" + q : "geo:0,0?q=" + q);
        }
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(shareLink(p))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** A link anyone can open (WhatsApp, SMS). */
    static String shareLink(JSONObject p) {
        if (p.has("lat")) return "https://maps.google.com/?q=" + p.optDouble("lat") + "," + p.optDouble("lon");
        if (!p.optString("link").isEmpty()) return p.optString("link");
        return "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(p.optString("address", p.optString("name")));
    }

    // ---------------------------------------------------------------- Google Maps links

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    static String firstUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_IN_TEXT.matcher(text);
        return m.find() ? m.group().replaceAll("[)\\].,;!]+$", "") : null;
    }

    static boolean isMapsLink(String url) {
        String l = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return l.contains("maps.app.goo.gl") || l.contains("goo.gl/maps") || l.contains("google.com/maps") || l.contains("google.co.in/maps")
                || l.contains("maps.google.") || l.contains("g.co/kgs") || l.contains("mappls.com") || l.contains("maps.apple.com");
    }

    /** The name part of a Google Maps share ("Kukatpally Metro Station\nhttps://maps.app.goo.gl/..."). */
    static String nameFromShare(String text, String url) {
        if (text == null) return "";
        String before = url == null ? text : text.replace(url, " ");
        for (String line : before.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("http")) return t.length() > 60 ? t.substring(0, 60) : t;
        }
        return "";
    }

    static final class Resolved {
        double lat = Double.NaN, lon = Double.NaN;
        String title = "";
        boolean found() { return !Double.isNaN(lat); }
    }

    private static final Pattern[] IN_URL = {
            Pattern.compile("!3d(-?\\d{1,2}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)"),      // the place itself
            Pattern.compile("[?&](?:q|query|ll|destination|center|daddr)=(-?\\d{1,2}\\.\\d+),\\s*\\+?\\s*(-?\\d{1,3}\\.\\d+)"),
            Pattern.compile("/(?:search|dir|place)/+(-?\\d{1,2}\\.\\d+),\\s*\\+?\\s*(-?\\d{1,3}\\.\\d+)"),
            Pattern.compile("@(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)"),          // the map's centre
            Pattern.compile("geo:(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)"),
    };
    private static final Pattern PLACE_NAME = Pattern.compile("/maps/place/([^/@?]+)");
    /** Inside the Maps page: [[[zoom, lon, lat]] (longitude first). */
    private static final Pattern IN_PAGE = Pattern.compile("\\[\\[\\[\\d+(?:\\.\\d+)?,(-?\\d{1,3}\\.\\d+),(-?\\d{1,2}\\.\\d+)\\]");

    /** Follows a (short) Maps link to the point it shows. Needs internet; call off the main thread. */
    static Resolved fromLink(String url) {
        Resolved r = new Resolved();
        String u = url;
        try {
            for (int hop = 0; hop < 7 && u != null; hop++) {
                if (coords(decode(u), r)) break;
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36");
                c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    u = loc == null ? null : new URL(new URL(u), loc).toString();
                    continue;
                }
                if (code == 200) {
                    String page = read(c.getInputStream(), 400_000);
                    Matcher m = IN_PAGE.matcher(page);
                    if (!coords(page, r) && m.find()) set(r, m.group(2), m.group(1));
                }
                c.disconnect();
                break;
            }
        } catch (Exception ignored) {}
        if (u != null) {
            Matcher m = PLACE_NAME.matcher(u);
            if (m.find()) r.title = decode(m.group(1)).replace('+', ' ').trim();
        }
        return r;
    }

    private static boolean coords(String s, Resolved r) {
        for (Pattern p : IN_URL) {
            Matcher m = p.matcher(s);
            if (m.find() && set(r, m.group(1), m.group(2))) return true;
        }
        return false;
    }

    private static boolean set(Resolved r, String lat, String lon) {
        try {
            double a = Double.parseDouble(lat), o = Double.parseDouble(lon);
            if (Math.abs(a) > 90 || Math.abs(o) > 180 || (a == 0 && o == 0)) return false;
            r.lat = a;
            r.lon = o;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String read(InputStream in, int max) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0 && b.size() < max) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    /** An address to a map point (the phone's own geocoder), or null. */
    @SuppressWarnings("deprecation")
    static double[] geocode(Context c, String address) {
        try {
            if (!Geocoder.isPresent()) return null;
            List<Address> a = new Geocoder(c, Locale.ENGLISH).getFromLocationName(address, 1);
            if (a != null && !a.isEmpty()) return new double[]{a.get(0).getLatitude(), a.get(0).getLongitude()};
        } catch (Exception ignored) {}
        return null;
    }
}
