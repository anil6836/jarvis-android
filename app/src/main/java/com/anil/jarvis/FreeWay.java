package com.anil.jarvis;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🛣️ Stage 4 (27 Oct): our own free way, one for the car screen — shared by ⑮'s 3D map and ⑯'s 3D view, so it goes
 * on when he switches between them (🎮). OSM search (Nominatim) → the OSRM route, started only by his tap; every second
 * where he is along it, our turn card, off the way for a while → asked again (not often), at the end "చేరుకున్నాం". It
 * lives only while the app runs (never started again by itself, never written anywhere). OpenStreetMap only.
 */
final class FreeWay {

    interface Host {
        /** Our way's card ({words, distance, "1" right / "-1" left / "0"}) and what is left; card null: no way of ours. */
        void card(String[] card, String left);
        /** Our way ended at its place. */
        void arrived(String name);
        /** The way couldn't be found (no net, OSRM away): said once. */
        void failed(String why);
    }

    /** One OSM search answer. */
    static final class Place {
        final String name, detail;
        final double lat, lon;
        final float km;
        Place(String name, String detail, double lat, double lon, float km) { this.name = name; this.detail = detail; this.lat = lat; this.lon = lon; this.km = km; }
    }

    interface Found { void found(List<Place> places, String error); }

    private final Context app;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private String name;
    private double tLat, tLon;
    private JSONObject route;
    private World3D.Proj wp;
    private float[] xz;
    private float total, dur, at;
    private long askedAt, wait = 20_000;
    private int asks, off, gen;
    private boolean busy, failSaid, closed;
    private double lastLat = Double.NaN, lastLon;
    private float lastH = -1;
    // the line in another view's metres (⑯'s), made once per route and metres
    private World3D.Proj cacheP;
    private float[] cacheXz;
    private int cacheGen = -1;

    FreeWay(Context c, Host host) {
        app = c.getApplicationContext();
        this.host = host;
    }

    void close() {
        closed = true;
        work.shutdownNow();
    }

    boolean has() { return name != null; }

    String name() { return name == null ? "" : name; }

    /** Changes whenever the way's line changes (a new way, asked again, ended): the views draw it again. */
    int gen() { return gen; }

    /** His tap on a place: our way there, from where he is (or from the next place the drive gives). */
    void go(String placeName, double lat, double lon) {
        name = placeName == null || placeName.trim().isEmpty() ? String.format(Locale.ROOT, "%.4f, %.4f", lat, lon) : placeName.trim();
        tLat = lat;
        tLon = lon;
        route = null;
        xz = null;
        gen++;
        asks = 0;
        askedAt = 0;
        off = 0;
        failSaid = false;
        wait = 20_000;
        if (!Double.isNaN(lastLat)) ask(lastLat, lastLon);
    }

    /** ✖ Our way ended (his tap, or there). */
    void stop() {
        name = null;
        route = null;
        xz = null;
        gen++;
        host.card(null, "");
    }

    /** Every second: where he is (heading radians, −1 unknown; the fix's accuracy in metres). */
    void tick(double lat, double lon, float heading, float acc) {
        lastLat = lat;
        lastLon = lon;
        if (heading >= 0) lastH = heading;
        if (name == null || closed) return;
        if (xz == null) { if (System.currentTimeMillis() - askedAt > wait) ask(lat, lon); return; }
        float x = wp.x(lon), z = wp.z(lat);
        float[] al = World3D.along(xz, x, z, lastH);
        at = al[1];
        if (total - al[1] < 30 && al[0] < 60) { String n = name; stop(); host.arrived(n); return; }
        if (Way3D.off(xz, x, z, lastH, acc)) {
            if (++off >= 4 && System.currentTimeMillis() - askedAt > 30_000 && asks < 20) ask(lat, lon); // (off the way: asked again, not often)
        } else off = 0;
        host.card(Way3D.card(xz, at), Way3D.left(total, at, dur));
    }

    /** The way in a view's metres (⑯'s), the same array while the way and the metres stay; null: no way yet. */
    float[] lineIn(World3D.Proj p) {
        if (route == null || p == null) return null;
        if (p != cacheP || cacheGen != gen) { cacheP = p; cacheGen = gen; cacheXz = Way3D.line(route, p); }
        return cacheXz;
    }

    /** The way's points {lat, lon} (⑮'s map draws them); null: no way yet. */
    List<double[]> points() {
        JSONObject g = route == null ? null : route.optJSONObject("geometry");
        JSONArray c = g == null ? null : g.optJSONArray("coordinates");
        if (c == null) return null;
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < c.length(); i++) {
            JSONArray q = c.optJSONArray(i);
            if (q != null && q.length() >= 2) out.add(new double[]{q.optDouble(1), q.optDouble(0)});
        }
        return out.size() < 2 ? null : out;
    }

    /** How far along the way he is (m) and the way's points still ahead start there (for ⑮: the way behind him not drawn). */
    float at() { return at; }

    /**
     * The next turn within 600 m, for ⑮'s floating arrow: {lat, lon, the heading coming in (radians), the change (+ right),
     * metres to it}; null when the way runs on straight or there is no way.
     */
    double[] nextTurn() {
        if (xz == null) return null;
        float[] t = World3D.nextTurn(xz, at, 600);
        if (t == null) return null;
        float in = World3D.pointAt(xz, Math.max(0, at + t[0] - 25))[2];
        return new double[]{wp.lat(t[3]), wp.lon(t[2]), in, t[1], t[0]};
    }

    /** The way didn't come: said once (not every try while he drives), and asked again less and less often (up to 5 min). */
    private void failed(String why) {
        wait = Math.min(300_000, wait * 2);
        if (failSaid) return;
        failSaid = true;
        host.failed(why);
    }

    private void ask(double lat, double lon) {
        if (busy || closed) return;
        if (!Net.online(app)) { askedAt = System.currentTimeMillis(); failed("ఇంటర్నెట్ లేదు Sir: దారి రాలేదు. నెట్ వచ్చాక మళ్ళీ అడుగుతాను."); return; }
        busy = true;
        askedAt = System.currentTimeMillis();
        asks++;
        final double toLat = tLat, toLon = tLon;
        work.execute(() -> {
            JSONObject r = null;
            try { r = Drive.osrm(lat, lon, toLat, toLon); } catch (Exception ignored) {}
            final JSONObject got = r;
            main.post(() -> {
                busy = false;
                if (closed || name == null || toLat != tLat || toLon != tLon) return; // (another way meanwhile)
                World3D.Proj p = new World3D.Proj(lat, lon);
                float[] line = got == null ? null : Way3D.line(got, p);
                if (line == null) { failed("దారి దొరకలేదు Sir (ఉచిత మ్యాప్ దారి సర్వర్ జవాబు ఇవ్వలేదు). కాసేపట్లో మళ్ళీ అడుగుతాను."); return; }
                failSaid = false;
                wait = 20_000;
                route = got;
                wp = p;
                xz = line;
                total = World3D.length(line);
                dur = (float) got.optDouble("duration", total / 11);
                at = 0;
                off = 0;
                gen++;
            });
        });
    }

    // ================================================================ 🔍 OSM search (his words, or typing while parked)

    /** Places for his words from OpenStreetMap (Nominatim), the nearest first; on the main thread. */
    void search(String q, Found f) {
        final double la = Double.isNaN(lastLat) ? 17.4 : lastLat, lo = Double.isNaN(lastLat) ? 78.5 : lastLon;
        work.execute(() -> {
            List<Place> out = new ArrayList<>();
            String err = null;
            try {
                String url = String.format(Locale.ROOT, "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&countrycodes=in&accept-language=te,en"
                        + "&viewbox=%.4f,%.4f,%.4f,%.4f&q=%s", lo - 0.6, la + 0.6, lo + 0.6, la - 0.6, URLEncoder.encode(q, "UTF-8"));
                JSONArray a = new JSONArray(get(url));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    double lat = o.optDouble("lat"), lon = o.optDouble("lon");
                    if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                    String disp = o.optString("display_name");
                    String nm = o.optString("name", "");
                    if (nm.isEmpty()) nm = disp.split(",")[0];
                    out.add(new Place(nm, shortPlace(disp), lat, lon, (float) (Drive.meters(la, lo, lat, lon) / 1000)));
                }
                out.sort((p1, p2) -> Float.compare(p1.km, p2.km));
            } catch (Exception e) { err = "ఉచిత మ్యాప్ వెతుకులాట జవాబు ఇవ్వలేదు Sir (నెట్ చూడండి)."; }
            final String e2 = err;
            main.post(() -> f.found(out, e2));
        });
    }

    /** Where he is last (for OSM look-ups round him): {lat, lon}, or null. */
    double[] here() { return Double.isNaN(lastLat) ? null : new double[]{lastLat, lastLon}; }

    /** The first three parts of an address ("street, area, town"). Pure. */
    static String shortPlace(String display) {
        String[] p = display == null ? new String[0] : display.split(",");
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < p.length && i < 4; i++) { if (b.length() > 0) b.append(", "); b.append(p[i].trim()); }
        return b.toString();
    }

    static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(12000);
            h.setReadTimeout(20000);
            h.setRequestProperty("User-Agent", "JarvisAndroid/1.0");
            if (h.getResponseCode() >= 400) throw new java.io.IOException("HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream()) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int k;
                while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
                return o.toString("UTF-8");
            }
        } finally {
            h.disconnect();
        }
    }
}
