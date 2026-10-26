package com.anil.jarvis;

import android.content.Context;
import android.location.Location;

/**
 * 🎮 Stage 3 (27 Oct): ⑯'s 3D view kept with the car — every second the drive's place goes in (put on the road he is
 * on, on his side of it), the world round him is built again when he has gone 150 m, and our own free way ({@link
 * FreeWay}, shared with ⑮; started only by his tap) gives the lane, the arrows and the boards. Google content never
 * comes here.
 */
final class Chase3D {

    final Car3DView view;
    private final Context app;
    private final Prefs prefs;
    private final FreeWay way;
    private final World3DStore store;
    private World3D.Proj proj;
    private World3D.Area area;
    private float buildX = Float.NaN, buildZ;
    private boolean buildToy, building, rebuildAgain, closed;
    private long retryAt;
    private double lastLat = Double.NaN, lastLon;
    private float lastH = -1;
    private float marksX = Float.NaN, marksZ;
    private int marksKey;

    Chase3D(Context c, Prefs p, FreeWay way) {
        app = c.getApplicationContext();
        prefs = p;
        this.way = way;
        view = new Car3DView(c);
        store = new World3DStore(c);
    }

    void setShown(boolean on) { view.setRunning(on); }

    void close() {
        closed = true;
        view.setRunning(false);
        store.close();
    }

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
        }
        float x = proj.x(lon), z = proj.z(lat);
        // on the road: our way, else the road he is on
        float[] line = null;
        float at = 0, half = 3.5f, h = lastH;
        boolean oneway = false;
        float[] wl = way.lineIn(proj); // (our free way in these metres: the same array while it stays)
        if (wl != null) {
            float[] al = World3D.along(wl, x, z, lastH);
            if (al[0] < 45) { line = wl; at = al[1]; }
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
}
