package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 🏙️ Stage 3 (27 Oct): the world round the car for the 3D view, from OpenStreetMap (no Android here: desk-tested).
 * The roads' real shapes and widths, the buildings raised from their real footprints (their floors when OSM knows
 * them, else a height of our own), our own trees and street lamps beside the roads, or — his "బొమ్మ బిల్డింగ్‌లు" —
 * our own toy blocks along the road. Places in metres round a fixed point: x east, z south. Also the road the car is
 * on (the GPS put on it), the way ahead and its next turn. Google content never comes here (OSM only).
 */
final class World3D {
    private World3D() {}

    // ================================================================ metres round a point

    /** Latitude / longitude ↔ metres round (lat0, lon0): x east, z south. */
    static final class Proj {
        final double lat0, lon0, kx;
        Proj(double lat0, double lon0) { this.lat0 = lat0; this.lon0 = lon0; kx = Math.cos(Math.toRadians(lat0)) * 111320.0; }
        float x(double lon) { return (float) ((lon - lon0) * kx); }
        float z(double lat) { return (float) (-(lat - lat0) * 110574.0); }
        double lat(float z) { return lat0 - z / 110574.0; }
        double lon(float x) { return lon0 + x / kx; }
    }

    /** A road: its line, its kind (OSM "highway"), lanes, width (m), one way, name. */
    static final class Road {
        long id;
        final float[] xz;
        final String kind, name;
        final int lanes;
        final float width;
        final boolean oneway;
        private float[] box, rev;
        Road(float[] xz, String kind, int lanes, float width, boolean oneway, String name) {
            this.xz = xz; this.kind = kind; this.lanes = lanes; this.width = width; this.oneway = oneway; this.name = name;
        }
        /** Its box {x0, z0, x1, z1} (worked out once). */
        float[] box() { if (box == null) box = World3D.box(xz); return box; }
        /** Its line the other way round (made once: the same array every time). */
        float[] reversed() { if (rev == null) rev = Mesh3D.reverse(xz); return rev; }
    }

    /** A building: its footprint (x, z pairs) and height (m); real = OSM said how tall. */
    static final class Building {
        long id;
        final float[] xz;
        final float h;
        final boolean real;
        Building(float[] xz, float h, boolean real) { this.xz = xz; this.h = h; this.real = real; }
    }

    static final class Area {
        final List<Road> roads = new ArrayList<>();
        final List<Building> buildings = new ArrayList<>();

        /** Several tiles' roads and buildings as one, each way once (a way crossing a tile's edge comes in both). Pure. */
        static Area merge(java.util.Collection<Area> parts) {
            Area out = new Area();
            java.util.HashSet<Long> seen = new java.util.HashSet<>();
            for (Area p : parts) {
                for (Road r : p.roads) if (r.id == 0 || seen.add(r.id)) out.roads.add(r);
                for (Building b : p.buildings) if (b.id == 0 || seen.add(-b.id)) out.buildings.add(b);
            }
            return out;
        }
    }

    static final String ROAD_KINDS = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|service|living_street|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link";

    /** The Overpass question for a box (south, west, north, east): the roads cars use and the buildings, with their shapes. Pure. */
    static String query(double s, double w, double n, double e) {
        String bb = String.format(java.util.Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", s, w, n, e);
        return "[out:json][timeout:25];(way[\"highway\"~\"^(" + ROAD_KINDS + ")$\"](" + bb + ");way[\"building\"](" + bb + "););out geom qt;";
    }

    /** Overpass's answer ("elements" with "geometry") as roads and buildings round p. Broken pieces are passed over. Pure. */
    static Area parse(JSONArray el, Proj p) {
        Area a = new Area();
        for (int i = 0; el != null && i < el.length(); i++) {
            JSONObject e = el.optJSONObject(i);
            if (e == null || !"way".equals(e.optString("type"))) continue;
            JSONArray g = e.optJSONArray("geometry");
            JSONObject t = e.optJSONObject("tags");
            if (g == null || g.length() < 2 || t == null) continue;
            float[] xz = new float[g.length() * 2];
            int k = 0;
            for (int j = 0; j < g.length(); j++) {
                JSONObject q = g.optJSONObject(j);
                if (q == null || !q.has("lat")) continue; // (a missing point in a way: skip it)
                xz[k++] = p.x(q.optDouble("lon"));
                xz[k++] = p.z(q.optDouble("lat"));
            }
            if (k < 4) continue;
            if (k < xz.length) xz = java.util.Arrays.copyOf(xz, k);
            String hw = t.optString("highway");
            if (!hw.isEmpty()) {
                int lanes = parseInt(t.optString("lanes"), 0);
                boolean one = "yes".equals(t.optString("oneway")) || hw.startsWith("motorway");
                Road rd = new Road(xz, hw, lanes, roadWidth(hw, lanes, one), one, t.optString("name:te", t.optString("name")));
                rd.id = e.optLong("id");
                a.roads.add(rd);
            } else if (t.has("building") && xz.length >= 6) {
                if (xz[0] == xz[xz.length - 2] && xz[1] == xz[xz.length - 1]) xz = java.util.Arrays.copyOf(xz, xz.length - 2); // (OSM closes the ring: once is enough)
                if (xz.length < 6) continue;
                float h = buildingHeight(t.optString("height"), t.optString("building:levels"));
                Building bd = new Building(xz, h > 0 ? h : toyHeight(xz), h > 0);
                bd.id = e.optLong("id");
                a.buildings.add(bd);
            }
        }
        return a;
    }

    static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim().split("[;. ]")[0]); } catch (Exception e) { return def; }
    }

    /** A road's width (m) from its kind and lanes (OSM rarely says the width). Pure. */
    static float roadWidth(String kind, int lanes, boolean oneway) {
        float lane = 3.3f;
        if (lanes > 0) return Math.max(3.2f, lanes * lane + 1);
        switch (kind) {
            case "motorway": case "trunk": return oneway ? 8 : 14;
            case "primary": return oneway ? 7 : 11;
            case "secondary": return 9;
            case "tertiary": return 7.5f;
            case "service": case "living_street": return 4;
            default: return kind.endsWith("_link") ? 6 : 6;
        }
    }

    /** OSM's height ("12", "12 m") or floors × 3.2 m + 1 m; 0 when it says neither. Pure. */
    static float buildingHeight(String height, String levels) {
        try {
            if (height != null && !height.trim().isEmpty()) {
                float h = Float.parseFloat(height.trim().replace("m", "").trim());
                if (h > 1 && h < 500) return h;
            }
        } catch (Exception ignored) {}
        int lv = parseInt(levels == null ? "" : levels, 0);
        return lv > 0 && lv < 150 ? lv * 3.2f + 1 : 0;
    }

    /** Our own height for a building OSM doesn't size: from its place (the same every time), mostly low, some taller. Pure. */
    static float toyHeight(float[] xz) {
        int h = hash(xz[0], xz[1]);
        float u = (h & 0xFFFF) / 65535f;
        return 6 + u * u * 22; // 6–28 m, most of them low
    }

    static int hash(float x, float z) {
        int a = Float.floatToIntBits(Math.round(x * 10) / 10f) * 73856093 ^ Float.floatToIntBits(Math.round(z * 10) / 10f) * 19349663;
        a ^= a >>> 13; a *= 0x5bd1e995; a ^= a >>> 15;
        return a & 0x7FFFFFFF;
    }

    // ================================================================ the meshes

    static final int ASPHALT = 0x3A3F47, ASPHALT_BIG = 0x34383F, LINE_WHITE = 0xE8ECEF, LINE_YELLOW = 0xF2C230, KERB = 0x8A9099,
            ROOF = 0x7C848E, LANE = 0x8A7A3C, TRUNK = 0x5A4632, LEAF = 0x2F6B3A, LAMP = 0x9AA3AD;
    static final int[] WALLS = {0xC2C8CF, 0xB4BCC6, 0xCFC6B8, 0xBFC9C2, 0xA9B2BD};

    /**
     * Everything round (cx, cz) within r, ready for the view, each kept round (cx, cz): {kerbs, asphalt, road paint,
     * the standing things (buildings — real or our toy blocks — trees, lamps)}. Pure.
     */
    static Mesh3D[] scene(Area a, float cx, float cz, float r, boolean toy) {
        Mesh3D[] rd = roads(a, cx, cz, r);
        Grid g = new Grid(a, cx, cz, r + 40);
        Mesh3D solid = toy ? toyCity(a, g, cx, cz, r) : buildings(a, cx, cz, r);
        solid.add(trees(a, g, cx, cz, r, true), 0, 0, 0, 0);
        Mesh3D[] out = {rd[0], rd[1], rd[2], solid};
        for (Mesh3D m : out) m.shift(-cx, 0, -cz);
        return out;
    }

    /** The roads within r of (cx, cz): {kerbs, asphalt, white paint (middle dashes, lanes of one-way roads)}. */
    static Mesh3D[] roads(Area a, float cx, float cz, float r) {
        Mesh3D kerbs = new Mesh3D(), asphalt = new Mesh3D(), paint = new Mesh3D();
        java.util.List<Road> order = new ArrayList<>(a.roads);
        order.sort((p, q) -> Float.compare(p.width, q.width)); // (the wide roads last: they cover the small ones where they meet)
        for (Road rd : order) {
            float[] xz = clip(rd.xz, cx, cz, r);
            if (xz == null) continue;
            float w = rd.width / 2;
            boolean big = isBig(rd.kind);
            asphalt.ribbon(xz, w, w, 0, big ? ASPHALT_BIG : ASPHALT, Mesh3D.M_ROAD, 0, 0);
            kerbs.ribbon(xz, w + 0.35f, w + 0.35f, 0, KERB, Mesh3D.M_MATTE, 0, 0); // (under the asphalt: only its edge shows)
            if (big && !rd.oneway) paint.ribbon(xz, 0.08f, 0.08f, 0, LINE_WHITE, Mesh3D.M_LIGHT, 3, 5);
            else if (big) { // lanes of a one-way road
                int lanes = Math.max(2, rd.lanes);
                for (int i = 1; i < lanes; i++) {
                    float off = -w + rd.width * i / lanes;
                    paint.ribbon(xz, -off + 0.07f, off + 0.07f, 0, LINE_WHITE, Mesh3D.M_LIGHT, 3, 6);
                }
            }
        }
        return new Mesh3D[]{kerbs, asphalt, paint};
    }

    static boolean isBig(String kind) {
        return !kind.equals("service") && !kind.equals("living_street") && !kind.equals("residential") && !kind.equals("road");
    }

    /** The real buildings within r: raised from their footprints. */
    static Mesh3D buildings(Area a, float cx, float cz, float r) {
        Mesh3D m = new Mesh3D();
        for (Building b : a.buildings) {
            if (!near(b.xz, cx, cz, r)) continue;
            m.extrudeUp(b.xz, 0, b.h, WALLS[hash(b.xz[0], b.xz[1]) % WALLS.length], Mesh3D.M_WALL, ROOF, Mesh3D.M_MATTE);
        }
        return m;
    }

    /**
     * Where roads and buildings are, in 4 m squares (trees, lamps and toy blocks keep off them). Roads: each square
     * their asphalt and kerb cover; buildings: their footprints' boxes; toy blocks add their own as they go.
     */
    static final class Grid {
        static final float CELL = 4f;
        final java.util.HashSet<Long> taken = new java.util.HashSet<>();
        Grid(Area a, float cx, float cz, float r) {
            for (Road rd : a.roads) {
                float[] xz = clip(rd.xz, cx, cz, r);
                if (xz == null) continue;
                float half = rd.width / 2 + 1.2f;
                for (int i = 0; i + 3 < xz.length; i += 2) {
                    float dx = xz[i + 2] - xz[i], dz = xz[i + 3] - xz[i + 1], len = (float) Math.hypot(dx, dz);
                    for (float s = 0; s <= len; s += 2) {
                        float t = len < 1e-3f ? 0 : s / len, px = xz[i] + dx * t, pz = xz[i + 1] + dz * t;
                        fill(px - half, pz - half, px + half, pz + half);
                    }
                }
            }
            for (Building b : a.buildings) if (near(b.xz, cx, cz, r)) { float[] bx = box(b.xz); fill(bx[0], bx[1], bx[2], bx[3]); }
        }
        static long key(int i, int j) { return ((long) i << 32) ^ (j & 0xFFFFFFFFL); }
        void fill(float x0, float z0, float x1, float z1) {
            for (int i = (int) Math.floor(x0 / CELL); i <= (int) Math.floor(x1 / CELL); i++)
                for (int j = (int) Math.floor(z0 / CELL); j <= (int) Math.floor(z1 / CELL); j++) taken.add(key(i, j));
        }
        boolean free(float x, float z) { return !taken.contains(key((int) Math.floor(x / CELL), (int) Math.floor(z / CELL))); }
        /** A footprint's every square free. */
        boolean free(float[] fp) {
            float[] b = box(fp);
            for (int i = (int) Math.floor(b[0] / CELL); i <= (int) Math.floor(b[2] / CELL); i++)
                for (int j = (int) Math.floor(b[1] / CELL); j <= (int) Math.floor(b[3] / CELL); j++) if (taken.contains(key(i, j))) return false;
            return true;
        }
    }

    /**
     * Our own trees and street lamps beside the roads within r (every 14 m a place: a lamp every third, a tree on most
     * others — the same places every time), never on a road or in a building.
     */
    static Mesh3D trees(Area a, Grid g, float cx, float cz, float r, boolean lamps) {
        Mesh3D m = new Mesh3D();
        for (Road rd : a.roads) {
            if (rd.kind.equals("service") || rd.kind.endsWith("_link")) continue;
            float[] xz = clip(rd.xz, cx, cz, r);
            if (xz == null) continue;
            float run = 0;
            for (int i = 0; i + 3 < xz.length; i += 2) {
                float dx = xz[i + 2] - xz[i], dz = xz[i + 3] - xz[i + 1], len = (float) Math.hypot(dx, dz);
                if (len < 1e-3f) continue;
                float ux = dx / len, uz = dz / len, sx = -uz, sz = ux; // (right of the way)
                for (float s = (float) Math.ceil(run / 14) * 14 - run; s < len; s += 14) {
                    float px = xz[i] + ux * s, pz = xz[i + 1] + uz * s;
                    int hs = hash(px, pz);
                    int slot = Math.round((run + s) / 14);
                    for (int sd = -1; sd <= 1; sd += 2) {
                        float off = rd.width / 2 + 2.4f;
                        float tx = px + sx * off * sd, tz = pz + sz * off * sd;
                        if (!g.free(tx, tz)) continue;
                        if (lamps && slot % 3 == (sd > 0 ? 0 : 1)) lamp(m, tx, tz, -sx * sd, -sz * sd);
                        else if ((hs >> (sd > 0 ? 3 : 7) & 3) != 0) tree(m, tx, tz, 0.8f + ((hs >> 11) & 7) / 14f);
                    }
                }
                run += len;
            }
        }
        return m;
    }

    static void tree(Mesh3D m, float x, float z, float k) {
        m.post(x, 0, z, 0.14f * k, 2.2f * k, 6, TRUNK, Mesh3D.M_MATTE);
        m.ball(x, 2.2f * k + 1.1f * k, z, 1.5f * k, 7, LEAF, Mesh3D.M_MATTE);
    }

    /** A street lamp, its arm reaching over the road (ux, uz: towards the road); its lamp lights at night. */
    static void lamp(Mesh3D m, float x, float z, float ux, float uz) {
        m.post(x, 0, z, 0.08f, 6.5f, 6, LAMP, Mesh3D.M_CHROME);
        float ax = x + ux * 1.4f, az = z + uz * 1.4f;
        m.box((x + ax) / 2, 6.45f, (z + az) / 2, Math.abs(ux) * 1.4f + 0.1f, 0.08f, Math.abs(uz) * 1.4f + 0.1f, LAMP, Mesh3D.M_CHROME);
        m.box(ax, 6.35f, az, 0.5f, 0.12f, 0.5f, 0xFFE6B0, Mesh3D.M_LIGHT);
    }

    /**
     * "బొమ్మ బిల్డింగ్‌లు": our own blocks along both sides of the main roads within r (every 22 m a place, now and then a
     * gap), the same every time for the same place; never on a road, a building or another block.
     */
    static Mesh3D toyCity(Area a, Grid g, float cx, float cz, float r) {
        Mesh3D m = new Mesh3D();
        java.util.List<Road> order = new ArrayList<>(a.roads);
        order.sort((p, q) -> Float.compare(q.width, p.width)); // (the big roads get their blocks first)
        for (Road rd : order) {
            if (rd.kind.equals("service") || rd.kind.endsWith("_link")) continue;
            float[] xz = clip(rd.xz, cx, cz, r);
            if (xz == null) continue;
            float run = 0, roadHalf = rd.width / 2;
            for (int i = 0; i + 3 < xz.length; i += 2) {
                float dx = xz[i + 2] - xz[i], dz = xz[i + 3] - xz[i + 1], len = (float) Math.hypot(dx, dz);
                if (len < 1e-3f) continue;
                float ux = dx / len, uz = dz / len, sx = -uz, sz = ux;
                for (float s = (float) Math.ceil(run / 22) * 22 - run; s < len; s += 22) {
                    float px = xz[i] + ux * s, pz = xz[i + 1] + uz * s;
                    int hs = hash(Math.round(px / 22) * 22, Math.round(pz / 22) * 22);
                    for (int sd = -1; sd <= 1; sd += 2) {
                        if (((hs >> (sd > 0 ? 2 : 5)) & 7) == 0) continue; // (a gap now and then)
                        float bw = 12 + ((hs >> 12) & 7), bd = 12 + ((hs >> 15) & 7), bh = 8 + ((hs >> 18) & 31);
                        float d = roadHalf + 4 + bd / 2;
                        float bx = px + sx * d * sd, bz = pz + sz * d * sd;
                        float[] fp = rect(bx, bz, ux, uz, bw, bd);
                        if (!g.free(fp)) continue;
                        float[] bb = box(fp);
                        g.fill(bb[0], bb[1], bb[2], bb[3]);
                        m.extrudeUp(fp, 0, bh, WALLS[(hs >> 22) & 3], Mesh3D.M_WALL, ROOF, Mesh3D.M_MATTE);
                    }
                }
                run += len;
            }
        }
        return m;
    }

    /** A rectangle centred on (x, z), its long side along (ux, uz). Pure. */
    static float[] rect(float x, float z, float ux, float uz, float along, float across) {
        float sx = -uz, sz = ux, a = along / 2, c = across / 2;
        return new float[]{x - ux * a - sx * c, z - uz * a - sz * c, x + ux * a - sx * c, z + uz * a - sz * c,
                x + ux * a + sx * c, z + uz * a + sz * c, x - ux * a + sx * c, z - uz * a + sz * c};
    }

    // ================================================================ the way: where the car is on it, what is ahead

    /** A line's points within r of (cx, cz) (with one point more each side, so it reaches the edge); null when none. Pure. */
    static float[] clip(float[] xz, float cx, float cz, float r) {
        int k = xz.length / 2, a = -1, b = -1;
        float r2 = r * r;
        for (int i = 0; i < k; i++) {
            float dx = xz[2 * i] - cx, dz = xz[2 * i + 1] - cz;
            if (dx * dx + dz * dz <= r2) { if (a < 0) a = i; b = i; }
        }
        if (a < 0) { // (no point inside: a long straight piece may still cross the circle)
            for (int i = 0; i + 1 < k; i++) if (segDist(xz, i, cx, cz)[0] <= r) { a = i; b = i + 1; break; }
            if (a < 0) return null;
        }
        a = Math.max(0, a - 1);
        b = Math.min(k - 1, b + 1);
        return java.util.Arrays.copyOfRange(xz, 2 * a, 2 * b + 2);
    }

    static boolean near(float[] xz, float cx, float cz, float r) {
        float dx = xz[0] - cx, dz = xz[1] - cz;
        return dx * dx + dz * dz <= r * r;
    }

    static float[] box(float[] xz) {
        float x0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < xz.length; i += 2) { x0 = Math.min(x0, xz[i]); x1 = Math.max(x1, xz[i]); z0 = Math.min(z0, xz[i + 1]); z1 = Math.max(z1, xz[i + 1]); }
        return new float[]{x0, z0, x1, z1};
    }

    static boolean inAny(List<float[]> boxes, float x, float z) {
        for (float[] b : boxes) if (x >= b[0] - 1 && x <= b[2] + 1 && z >= b[1] - 1 && z <= b[3] + 1) return true;
        return false;
    }

    /** {distance from (px, pz) to piece i of the line, how far along that piece (0–1)}. Pure. */
    static float[] segDist(float[] xz, int i, float px, float pz) {
        float ax = xz[2 * i], az = xz[2 * i + 1], bx = xz[2 * i + 2], bz = xz[2 * i + 3];
        float dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
        float t = l2 < 1e-9f ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / l2));
        float qx = ax + dx * t - px, qz = az + dz * t - pz;
        return new float[]{(float) Math.sqrt(qx * qx + qz * qz), t};
    }

    /**
     * Where on a line the car is: {distance from it, metres along the line, the line's heading there (radians, 0 =
     * north, clockwise)}; the nearest piece whose way agrees with the car's heading (within 70°) when he moves, else
     * simply the nearest. Pure.
     */
    static float[] along(float[] xz, float px, float pz, float heading) {
        int k = xz.length / 2;
        float best = Float.MAX_VALUE, at = 0, hd = 0, run = 0;
        for (int i = 0; i + 1 < k; i++) {
            float[] d = segDist(xz, i, px, pz);
            float dx = xz[2 * i + 2] - xz[2 * i], dz = xz[2 * i + 3] - xz[2 * i + 1], len = (float) Math.hypot(dx, dz);
            float h = headingOf(dx, dz);
            float pen = heading >= 0 && angleDiff(h, heading) > Math.toRadians(70) ? 25 : 0; // (the piece going the other way: only if nothing better)
            if (d[0] + pen < best) { best = d[0] + pen; at = run + d[1] * len; hd = h; }
            run += len;
        }
        return new float[]{best, at, hd};
    }

    /** The heading of a step (dx east, dz south): 0 = north, clockwise, radians in [0, 2π). Pure. */
    static float headingOf(float dx, float dz) {
        double a = Math.atan2(dx, -dz);
        return (float) (a < 0 ? a + 2 * Math.PI : a);
    }

    /** The smallest angle between two headings (0–π). Pure. */
    static float angleDiff(float a, float b) {
        double d = Math.abs(a - b) % (2 * Math.PI);
        return (float) (d > Math.PI ? 2 * Math.PI - d : d);
    }

    /** The line's length (m). Pure. */
    static float length(float[] xz) {
        float[] c = cum(xz);
        return c[c.length - 1];
    }

    /** Each point's distance from the start (worked out once per line and kept while the line lives). Pure. */
    private static final java.util.Map<float[], float[]> CUM = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static float[] cum(float[] xz) {
        float[] c = CUM.get(xz);
        if (c != null) return c;
        int k = Math.max(1, xz.length / 2);
        c = new float[k];
        for (int i = 1; i < k; i++) c[i] = c[i - 1] + (float) Math.hypot(xz[2 * i] - xz[2 * i - 2], xz[2 * i + 1] - xz[2 * i - 1]);
        CUM.put(xz, c);
        return c;
    }

    /** The point s metres along the line, and the heading there: {x, z, heading}. Pure. */
    static float[] pointAt(float[] xz, float s) {
        float[] c = cum(xz);
        int k = c.length;
        if (k < 2) return new float[]{xz.length > 0 ? xz[0] : 0, xz.length > 1 ? xz[1] : 0, 0};
        // the piece holding s: the last point at or before it (halving search)
        int lo = 0, hi = k - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (c[mid] <= s) lo = mid; else hi = mid; }
        if (s >= c[k - 1]) { lo = k - 2; hi = k - 1; }
        for (int i = lo; i < k - 1; i++) { // (a zero-length piece: the next one with a length)
            float dx = xz[2 * i + 2] - xz[2 * i], dz = xz[2 * i + 3] - xz[2 * i + 1], len = c[i + 1] - c[i];
            if (len > 1e-6f) {
                float t = Math.max(0, Math.min(1, (s - c[i]) / len));
                return new float[]{xz[2 * i] + dx * t, xz[2 * i + 1] + dz * t, headingOf(dx, dz)};
            }
        }
        int n = xz.length;
        return new float[]{xz[n - 2], xz[n - 1], n >= 4 ? headingOf(xz[n - 2] - xz[n - 4], xz[n - 1] - xz[n - 3]) : 0};
    }

    /** The part of the line from s0 to s1 metres along it (the points between, and the two ends). Pure. */
    static float[] part(float[] xz, float s0, float s1) {
        List<Float> out = new ArrayList<>();
        float[] a = pointAt(xz, s0);
        out.add(a[0]); out.add(a[1]);
        float run = 0;
        for (int i = 0; i + 3 < xz.length; i += 2) {
            run += (float) Math.hypot(xz[i + 2] - xz[i], xz[i + 3] - xz[i + 1]);
            if (run > s0 && run < s1) { out.add(xz[i + 2]); out.add(xz[i + 3]); }
        }
        float[] b = pointAt(xz, s1);
        out.add(b[0]); out.add(b[1]);
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /**
     * The next turn on the way from s metres on (within max metres): where the way's heading has changed by more than
     * 35° within 40 m. {metres to it, the change (radians, + right, − left), x, z}; null when the way runs on straight. Pure.
     */
    static float[] nextTurn(float[] xz, float s, float max) {
        float total = length(xz);
        float step = 5;
        for (float d = s + step; d < Math.min(total - 2, s + max); d += step) {
            float h0 = pointAt(xz, Math.max(0, d - 20))[2], h1 = pointAt(xz, Math.min(total, d + 20))[2];
            float ch = signedDiff(h0, h1);
            if (Math.abs(ch) > Math.toRadians(35)) {
                // the turn's middle: where the heading changes fastest near here
                float bestD = d, bestC = 0;
                for (float q = d - 15; q <= d + 25; q += 2.5f) {
                    float c = Math.abs(signedDiff(pointAt(xz, Math.max(0, q - 6))[2], pointAt(xz, Math.min(total, q + 6))[2]));
                    if (c > bestC) { bestC = c; bestD = q; }
                }
                float[] p = pointAt(xz, bestD);
                float whole = signedDiff(pointAt(xz, Math.max(0, bestD - 30))[2], pointAt(xz, Math.min(total, bestD + 30))[2]); // (the whole bend, not its start)
                return new float[]{bestD - s, Math.abs(whole) > Math.abs(ch) ? whole : ch, p[0], p[1]};
            }
        }
        return null;
    }

    /** From heading a to b: + clockwise (right), − to the left, in (−π, π]. Pure. */
    static float signedDiff(float a, float b) {
        double d = (b - a) % (2 * Math.PI);
        if (d > Math.PI) d -= 2 * Math.PI;
        if (d <= -Math.PI) d += 2 * Math.PI;
        return (float) d;
    }

    /** The words for a turn card: "కుడి వైపు తిరగండి" / "ఎడమ వైపు …" / "కొంచెం కుడి" / "వెనక్కి (U)". Pure. */
    static String turnWords(float change) {
        double a = Math.toDegrees(change);
        if (Math.abs(a) > 150) return "యూ-టర్న్ (వెనక్కి తిరగండి)";
        if (a > 60) return "కుడి వైపు తిరగండి";
        if (a < -60) return "ఎడమ వైపు తిరగండి";
        return a > 0 ? "కొంచెం కుడి వైపు" : "కొంచెం ఎడమ వైపు";
    }

    /** The distance on a turn card: "300 మీ", "1.2 కి.మీ", "ఇప్పుడు". Pure. */
    static String distWords(float m) {
        if (m < 25) return "ఇప్పుడు";
        if (m < 1000) return (Math.round(m / 10) * 10) + " మీ";
        return String.format(java.util.Locale.ROOT, "%.1f కి.మీ", m / 1000f);
    }

    /**
     * The road he is on: of the roads near (px, pz), the one closest that goes his way (within 15 m); with its line and
     * where he is along it: {road index, distance, metres along}; null when none is near. Pure.
     */
    static float[] onRoad(Area a, float px, float pz, float heading) {
        int best = -1;
        float bd = Float.MAX_VALUE, bs = 0;
        for (int i = 0; i < a.roads.size(); i++) {
            Road r = a.roads.get(i);
            float lim = r.width / 2 + 15;
            float[] b = r.box(); // (a long way's first point may be far: its box tells)
            if (px < b[0] - lim || px > b[2] + lim || pz < b[1] - lim || pz > b[3] + lim) continue;
            float[] al = along(r.xz, px, pz, r.oneway ? heading : -1); // (a two-way road drawn against him is his road too)
            if (al[0] <= lim && al[0] < bd) { bd = al[0]; best = i; bs = al[1]; }
        }
        return best < 0 ? null : new float[]{best, bd, bs};
    }

    // ================================================================ the way's paint: our lane, arrows, boards

    /**
     * The way's own lane: a yellow band along it on his side of the road (we drive on the left: the left half; the whole
     * road when it is one way). roadHalf: half the road's width.
     */
    static Mesh3D lane(float[] xz, float roadHalf, boolean oneway) {
        Mesh3D m = new Mesh3D();
        float l = oneway ? roadHalf * 0.8f : roadHalf * 0.88f, r = oneway ? roadHalf * 0.8f : -roadHalf * 0.12f;
        m.ribbon(xz, l, r, 0, LANE, Mesh3D.M_ROAD, 0, 0); // (the lane tinted gold, lit like the road)
        m.ribbon(xz, l, -l + 0.22f, 0, LINE_YELLOW, Mesh3D.M_LIGHT, 0, 0); // (its two bright edges)
        m.ribbon(xz, -r + 0.22f, r, 0, LINE_YELLOW, Mesh3D.M_LIGHT, 0, 0);
        return m;
    }

    /** How far left of the road's middle the car drives (m): the middle of his half; 0 on a one-way road. Pure. */
    static float laneOffset(float roadHalf, boolean oneway) { return oneway ? 0 : roadHalf * 0.5f; }

    /** A white arrow painted on the road at (x, z) pointing along heading h (radians), bent the turn's way (−1, 0, 1). */
    static void arrow(Mesh3D m, float x, float z, float h, int bend) {
        float ux = (float) Math.sin(h), uz = (float) -Math.cos(h), sx = -uz, sz = ux;
        float y = 0.05f;
        // the shaft
        float[] a = {x - sx * 0.25f, y, z - sz * 0.25f}, b = {x + sx * 0.25f, y, z + sz * 0.25f};
        float[] c = {b[0] + ux * 3, y, b[2] + uz * 3}, d = {a[0] + ux * 3, y, a[2] + uz * 3};
        m.quadFacing(a, b, c, d, 0, 1, 0, LINE_WHITE, Mesh3D.M_LIGHT);
        // the head, turned the bend's way
        float hx = (float) Math.sin(h + bend * 0.7f), hz = (float) -Math.cos(h + bend * 0.7f), px = -hz, pz = hx;
        float bx = x + ux * 3, bz = z + uz * 3;
        m.triFacing(new float[]{bx - px * 0.8f, y, bz - pz * 0.8f}, new float[]{bx + px * 0.8f, y, bz + pz * 0.8f}, new float[]{bx + hx * 1.6f, y, bz + hz * 1.6f}, 0, 1, 0, LINE_WHITE, Mesh3D.M_LIGHT);
    }

    /**
     * Yellow chevron boards on the outside of a bend at (x, z): standing just past the road ahead (where the car would go
     * on if it didn't turn), facing back at him (h: the way's heading before the bend), black chevrons pointing the
     * bend's way (+1 right, −1 left).
     */
    static void boards(Mesh3D m, float x, float z, float h, int bend, float roadHalf) {
        float ux = (float) Math.sin(h), uz = (float) -Math.cos(h), sx = -uz, sz = ux;
        int b = bend >= 0 ? 1 : -1;
        for (int i = -1; i <= 1; i++) {
            float bx = x + ux * (roadHalf + 2) + sx * i * 1.3f, bz = z + uz * (roadHalf + 2) + sz * i * 1.3f;
            m.post(bx, 0, bz, 0.05f, 1.2f, 5, 0x444A52, Mesh3D.M_MATTE);
            float[] p0 = {bx - sx * 0.55f, 1.2f, bz - sz * 0.55f}, p1 = {bx + sx * 0.55f, 1.2f, bz + sz * 0.55f};
            float[] p2 = {p1[0], 2.1f, p1[2]}, p3 = {p0[0], 2.1f, p0[2]};
            m.quadFacing(p0, p1, p2, p3, -ux, 0, -uz, 0xFFD21A, Mesh3D.M_LIGHT);
            // the black chevron, a little in front of the board: two strokes meeting at its point
            float cx = bx - ux * 0.04f, cz = bz - uz * 0.04f;
            float[] q0 = {cx - sx * 0.2f * b, 1.4f, cz - sz * 0.2f * b}, q1 = {cx + sx * 0.18f * b, 1.65f, cz + sz * 0.18f * b},
                    q2 = {cx - sx * 0.2f * b, 1.9f, cz - sz * 0.2f * b}, q3 = {cx - sx * 0.02f * b, 1.65f, cz - sz * 0.02f * b};
            m.triFacing(q0, q1, q3, -ux, 0, -uz, 0x1A1405, Mesh3D.M_MATTE);
            m.triFacing(q3, q1, q2, -ux, 0, -uz, 0x1A1405, Mesh3D.M_MATTE);
        }
    }
}
