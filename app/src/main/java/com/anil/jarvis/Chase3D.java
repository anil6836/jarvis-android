package com.anil.jarvis;

import android.content.Context;
import android.location.Location;
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
 * 🎮 Stage 3 (27 Oct): ⑯'s 3D view kept with the car — every second the drive's place goes in (put on the road he is
 * on, on his side of it), the world round him is built again when he has gone 150 m, and our own free route (OSM
 * search → the OSRM route, started only by his tap) gives the lane, the arrows, the boards and our turn card; off the
 * way for a while → the way asked again (not often); at the end, "చేరుకున్నాం". Google content never comes here.
 */
final class Chase3D {

    interface Host {
        /** Our way's card ({words, distance, "1" right / "-1" left / "0"}) and what is left; card null: no way of ours. */
        void card(String[] card, String left);
        /** Our way ended at its place. */
        void arrived(String name);
        /** The way couldn't be found (no net, OSRM away): said honestly. */
        void wayFailed(String why);
    }

    /** One OSM search answer. */
    static final class Place {
        final String name, detail;
        final double lat, lon;
        final float km;
        Place(String name, String detail, double lat, double lon, float km) { this.name = name; this.detail = detail; this.lat = lat; this.lon = lon; this.km = km; }
    }

    interface Found { void found(List<Place> places, String error); }

    final Car3DView view;
    private final Context app;
    private final Prefs prefs;
    private final Host host;
    private final World3DStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private World3D.Proj proj;
    private World3D.Area area;
    private float buildX = Float.NaN, buildZ;
    private boolean buildToy, building, rebuildAgain, closed;
    private long retryAt;
    private double lastLat = Double.NaN, lastLon;
    private float lastH = -1;
    // our way (OSRM), in metres round proj
    private String wName;
    private double wLat, wLon;
    private float[] wXz;
    private float wTotal, wDur;
    private long wAskedAt;
    private int wAsks, wOff;
    private boolean wBusy;
    private float marksX = Float.NaN, marksZ;
    private int marksKey;

    Chase3D(Context c, Prefs p, Host host) {
        app = c.getApplicationContext();
        prefs = p;
        this.host = host;
        view = new Car3DView(c);
        store = new World3DStore(c);
        // (our way lives only while the app runs: never started again by itself after a restart, never written anywhere)
    }

    void setShown(boolean on) { view.setRunning(on); }

    void close() {
        closed = true;
        view.setRunning(false);
        store.close();
        work.shutdownNow();
    }

    boolean hasWay() { return wName != null; }

    String wayName() { return wName == null ? "" : wName; }

    // ================================================================ every second

    /** The drive's place (null: none yet), the speed (km/h, −1 unknown). */
    void tick(Location l, int kmh) {
        if (l == null || closed) return;
        double lat = l.getLatitude(), lon = l.getLongitude();
        float mps = kmh >= 0 ? kmh / 3.6f : l.hasSpeed() ? l.getSpeed() : 0;
        if (l.hasBearing() && mps > 2) lastH = (float) Math.toRadians(l.getBearing());
        // standing: the GPS wanders a few metres — the car stays where it is
        if (mps < 1 && !Double.isNaN(lastLat) && Drive.meters(lat, lon, lastLat, lastLon) < 12 && proj != null) { lat = lastLat; lon = lastLon; }
        lastLat = lat;
        lastLon = lon;
        if (proj == null || Drive.meters(lat, lon, proj.lat0, proj.lon0) > 15_000) { // (a new middle: everything in its metres again)
            proj = new World3D.Proj(lat, lon);
            buildX = Float.NaN;
            area = null;
            wXz = null; // (asked again for the new metres)
        }
        float x = proj.x(lon), z = proj.z(lat);
        // on the road: our way, else the road he is on
        float[] line = null;
        float at = 0, half = 3.5f, h = lastH;
        boolean oneway = false;
        if (wXz != null) {
            float[] al = World3D.along(wXz, x, z, lastH);
            if (al[0] < 45) { line = wXz; at = al[1]; }
        }
        if (line == null && area != null) {
            float[] on = World3D.onRoad(area, x, z, lastH);
            if (on != null) {
                World3D.Road r = area.roads.get((int) on[0]);
                line = Way3D.facing(r.xz, x, z, lastH) == r.xz ? r.xz : r.reversed();
                at = World3D.along(line, x, z, -1)[1];
                half = r.width / 2;
                oneway = r.oneway;
            }
        } else if (line != null && area != null) {
            float[] on = World3D.onRoad(area, x, z, lastH);
            if (on != null) { World3D.Road r = area.roads.get((int) on[0]); half = r.width / 2; oneway = r.oneway; }
        }
        float sx = x, sz = z;
        if (line != null) {
            float[] p = World3D.pointAt(line, at);
            if (h < 0 || World3D.angleDiff(p[2], h) < Math.toRadians(40)) h = p[2]; // (the road's own way: no wobble)
            float off = World3D.laneOffset(half, oneway);
            sx = p[0] - (float) Math.cos(h) * off;
            sz = p[1] - (float) Math.sin(h) * off;
        }
        view.setFix(sx, sz, Math.max(0, h), mps);
        // the world round him
        boolean toy = "toy".equals(prefs.sp.getString("car_3d_city", ""));
        if (retryAt != 0 && System.currentTimeMillis() > retryAt) { retryAt = 0; buildX = Float.NaN; }
        if (Float.isNaN(buildX) || Math.hypot(x - buildX, z - buildZ) > 150 || toy != buildToy) rebuild(lat, lon, x, z, toy);
        // the way's paint, again every 15 m or when the way changed
        int key = System.identityHashCode(line);
        if (line == null) { if (marksKey != 0) { marksKey = 0; view.setMarks(null); } }
        else if (key != marksKey || Float.isNaN(marksX) || Math.hypot(x - marksX, z - marksZ) > 15) {
            marksKey = key; marksX = x; marksZ = z;
            view.setMarks(Way3D.marks(line, at, half, oneway, x, z));
        }
        wayTick(lat, lon, x, z, l.hasAccuracy() ? l.getAccuracy() : 10);
    }

    private void rebuild(double lat, double lon, float x, float z, boolean toy) {
        if (building) { rebuildAgain = true; return; }
        building = true;
        buildX = x; buildZ = z; buildToy = toy;
        store.build(proj, lat, lon, 450, toy, new World3DStore.Done() {
            @Override public void built(Car3DView.World w, World3D.Area a, int missing) {
                building = false;
                area = a;
                view.setWorld(w);
                retryAt = missing > 0 ? System.currentTimeMillis() + 60_000 : 0; // (squares not here: asked again in a minute, parked too)
                if (rebuildAgain) { rebuildAgain = false; buildX = Float.NaN; } // (asked again on the next second)
            }
            @Override public void failed() { building = false; retryAt = System.currentTimeMillis() + 60_000; }
        }, () -> buildX = Float.NaN); // (a new square came: the world again)
    }

    // ================================================================ our way

    /** His tap on a place: our way there (OSRM), from where he is. */
    void go(String name, double lat, double lon) {
        wName = name == null || name.trim().isEmpty() ? String.format(Locale.ROOT, "%.4f, %.4f", lat, lon) : name.trim();
        wLat = lat;
        wLon = lon;
        wXz = null;
        wAsks = 0;
        wAskedAt = 0;
        wOff = 0;
        wFailSaid = false;
        wWait = 20_000;
        if (!Double.isNaN(lastLat)) ask(lastLat, lastLon);
    }

    /** ✖ Our way ended (his tap, or there). */
    void stopWay() {
        wName = null;
        wXz = null;
        marksKey = -1; // (the paint again: the road only)
        host.card(null, "");
    }

    private void wayTick(double lat, double lon, float x, float z, float acc) {
        if (wName == null) return;
        if (wXz == null) { if (System.currentTimeMillis() - wAskedAt > wWait) ask(lat, lon); return; }
        float[] al = World3D.along(wXz, x, z, lastH);
        if (wTotal - al[1] < 30 && al[0] < 60) { String n = wName; stopWay(); host.arrived(n); return; }
        if (Way3D.off(wXz, x, z, lastH, acc)) {
            if (++wOff >= 4 && System.currentTimeMillis() - wAskedAt > 30_000 && wAsks < 20) ask(lat, lon); // (off the way: asked again, not often)
        } else wOff = 0;
        host.card(Way3D.card(wXz, al[1]), Way3D.left(wTotal, al[1], wDur));
    }

    private boolean wFailSaid;
    private long wWait = 20_000;

    /** The way didn't come: said once (not every try while he drives), and asked again less and less often (up to 5 min). */
    private void failed(String why) {
        wWait = Math.min(300_000, wWait * 2);
        if (wFailSaid) return;
        wFailSaid = true;
        host.wayFailed(why);
    }

    private void ask(double lat, double lon) {
        if (wBusy || closed) return;
        if (!Net.online(app)) { wAskedAt = System.currentTimeMillis(); failed("ఇంటర్నెట్ లేదు Sir: దారి రాలేదు. నెట్ వచ్చాక మళ్ళీ అడుగుతాను."); return; }
        wBusy = true;
        wAskedAt = System.currentTimeMillis();
        wAsks++;
        final double tLat = wLat, tLon = wLon;
        final World3D.Proj p = proj;
        work.execute(() -> {
            JSONObject r = null;
            try { r = Drive.osrm(lat, lon, tLat, tLon); } catch (Exception ignored) {}
            final JSONObject got = r;
            main.post(() -> {
                wBusy = false;
                if (closed || wName == null || tLat != wLat || tLon != wLon || p != proj) return; // (another way / new metres meanwhile)
                float[] xz = got == null ? null : Way3D.line(got, p);
                if (xz == null) { failed("దారి దొరకలేదు Sir (ఉచిత మ్యాప్ దారి సర్వర్ జవాబు ఇవ్వలేదు). కాసేపట్లో మళ్ళీ అడుగుతాను."); return; }
                wFailSaid = false;
                wWait = 20_000;
                wXz = xz;
                wTotal = World3D.length(xz);
                wDur = (float) got.optDouble("duration", wTotal / 11);
                wOff = 0;
                marksKey = -1;
            });
        });
    }

    // ================================================================ 🔍 OSM search (his words or typing while parked)

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
                    String name = o.optString("name", "");
                    if (name.isEmpty()) name = disp.split(",")[0];
                    out.add(new Place(name, shortPlace(disp), lat, lon, (float) (Drive.meters(la, lo, lat, lon) / 1000)));
                }
                out.sort((p1, p2) -> Float.compare(p1.km, p2.km));
            } catch (Exception e) { err = "ఉచిత మ్యాప్ వెతుకులాట జవాబు ఇవ్వలేదు Sir (నెట్ చూడండి)."; }
            final String e2 = err;
            main.post(() -> f.found(out, e2));
        });
    }

    /** The first three parts of an address ("street, area, town"). Pure. */
    static String shortPlace(String display) {
        String[] p = display == null ? new String[0] : display.split(",");
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < p.length && i < 4; i++) { if (b.length() > 0) b.append(", "); b.append(p[i].trim()); }
        return b.toString();
    }

    private static String get(String url) throws Exception {
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
