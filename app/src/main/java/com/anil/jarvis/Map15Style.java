package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * 🏙️ Stage 4 (27 Oct): ⑮'s 3D map as plain JSON (no Android: desk-tested, desk-drawn with MapLibre GL JS) — the free
 * OpenFreeMap style (OpenStreetMap data) made ours: its colours in the screen's light (white glass by day, dark at
 * night), names in English letters (the map engine can't join Telugu letters), the buildings standing in 3D; and our
 * own things on it: the way (OSRM) glowing, a big 3D arrow floating over the next turn, our car standing on the road
 * (its body, cabin, wheels and lights raised from the ground) or his own photo standing up, the chips' places as
 * standing pins. Google content never comes here.
 */
final class Map15Style {
    private Map15Style() {}

    /** OpenFreeMap's style (free, no key; data OpenStreetMap). */
    static final String STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";

    static final String SRC_ROUTE = "j-route", SRC_TURN = "j-turn", SRC_CAR = "j-car", SRC_PHOTO = "j-photo", SRC_PLACES = "j-places";
    static final String IMG_PHOTO = "j-photo-img";

    // ================================================================ colours

    /** A style colour string ("#rgb", "#rrggbb", "#rrggbbaa", "rgb(…)", "rgba(…)", "hsl(…)", "hsla(…)") as ARGB; null when not a colour. Pure. */
    static Integer parse(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        try {
            if (t.startsWith("#")) {
                String h = t.substring(1);
                if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
                if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
                if (h.length() == 8) { int rgb = Integer.parseInt(h.substring(0, 6), 16), a = Integer.parseInt(h.substring(6), 16); return (a << 24) | rgb; }
                return null;
            }
            int o = t.indexOf('('), c = t.lastIndexOf(')');
            if (o < 0 || c < o) return null;
            String fn = t.substring(0, o).trim();
            String[] p = t.substring(o + 1, c).split(",");
            if ((fn.equals("rgb") && p.length == 3) || (fn.equals("rgba") && p.length == 4)) {
                int r = Math.round(Float.parseFloat(p[0].trim())), g = Math.round(Float.parseFloat(p[1].trim())), b = Math.round(Float.parseFloat(p[2].trim()));
                float a = p.length == 4 ? Float.parseFloat(p[3].trim()) : 1;
                return (Math.round(clamp(a) * 255) << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
            }
            if ((fn.equals("hsl") && p.length == 3) || (fn.equals("hsla") && p.length == 4)) {
                float h = Float.parseFloat(p[0].trim()), sat = Float.parseFloat(p[1].trim().replace("%", "")) / 100f, l = Float.parseFloat(p[2].trim().replace("%", "")) / 100f;
                float a = p.length == 4 ? Float.parseFloat(p[3].trim()) : 1;
                return (Math.round(clamp(a) * 255) << 24) | (hslToRgb(h, sat, l) & 0xFFFFFF);
            }
        } catch (Exception ignored) {}
        return null;
    }

    static String format(int argb) {
        int a = argb >>> 24;
        if (a == 255) return String.format(Locale.ROOT, "#%06x", argb & 0xFFFFFF);
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", (argb >> 16) & 255, (argb >> 8) & 255, argb & 255, a / 255f);
    }

    static float[] rgbToHsl(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), l = (mx + mn) / 2, h = 0, s = 0;
        if (mx != mn) {
            float d = mx - mn;
            s = l > 0.5f ? d / (2 - mx - mn) : d / (mx + mn);
            if (mx == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (mx == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h *= 60;
        }
        return new float[]{h, s, l};
    }

    static int hslToRgb(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s, hp = ((h % 360) + 360) % 360 / 60f, x = c * (1 - Math.abs(hp % 2 - 1)), m = l - c / 2;
        float r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; } else if (hp < 2) { r = x; g = c; } else if (hp < 3) { g = c; b = x; } else if (hp < 4) { g = x; b = c; } else if (hp < 5) { r = x; b = c; } else { r = c; b = x; }
        return 0xFF000000 | (clamp255(Math.round((r + m) * 255)) << 16) | (clamp255(Math.round((g + m) * 255)) << 8) | clamp255(Math.round((b + m) * 255));
    }

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }

    private static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }

    /**
     * One map colour in our look. "glass" (by day): the same colours, softer and lighter, a little cool (white glass);
     * "dark" (night): light turned to dark — the land deep blue-grey, roads lighter than the land, water and parks keep a
     * trace of their hue. Alpha kept. Pure.
     */
    static int recolour(int argb, String look) {
        float[] hsl = rgbToHsl(argb);
        float h = hsl[0], s = hsl[1], l = hsl[2];
        int out;
        if ("dark".equals(look)) {
            // (the light land deep blue-grey; the white roads a little lighter than it; water and parks keep a trace of their hue)
            float nl = 0.07f + (1 - l) * 0.10f + Math.max(0, l - 0.96f) * 4.75f;
            int r = (argb >> 16) & 255, g = (argb >> 8) & 255, bl = argb & 255;
            float chroma = (Math.max(r, Math.max(g, bl)) - Math.min(r, Math.min(g, bl))) / 255f; // (how coloured it really is; near-white land has a big HSL saturation)
            if (chroma < 0.1f) out = hslToRgb(218, 0.22f, Math.min(0.42f, nl));
            else out = hslToRgb(h, s * 0.35f, Math.min(0.42f, nl + 0.05f));
        } else {
            out = hslToRgb(h, s * 0.55f, Math.min(0.98f, 0.18f + l * 0.82f)); // (glass: softer, lighter)
        }
        return (argb & 0xFF000000) | (out & 0xFFFFFF);
    }

    /** Every colour string in a paint value (a colour, or an expression holding colours), recoloured. Pure. */
    static Object recolourValue(Object v, String look) {
        if (v instanceof String) {
            Integer c = parse((String) v);
            return c == null ? v : format(recolour(c, look));
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v, o = new JSONArray();
            for (int i = 0; i < a.length(); i++) o.put(recolourValue(a.opt(i), look));
            return o;
        }
        if (v instanceof JSONObject) { // (old-style stops {"stops": [[z, colour], …]})
            JSONObject a = (JSONObject) v, o = new JSONObject();
            for (java.util.Iterator<String> it = a.keys(); it.hasNext(); ) {
                String k = it.next();
                try { o.put(k, recolourValue(a.opt(k), look)); } catch (Exception ignored) {}
            }
            return o;
        }
        return v;
    }

    // ================================================================ the style

    /** Names in English letters: English (both spellings OpenMapTiles uses), else the Latin spelling, else the name itself. */
    static JSONArray englishNames() {
        return new JSONArray().put("coalesce").put(new JSONArray().put("get").put("name:en")).put(new JSONArray().put("get").put("name_en"))
                .put(new JSONArray().put("get").put("name:latin")).put(new JSONArray().put("get").put("name"));
    }

    /**
     * OpenFreeMap's style made ours (look "glass" or "dark", accent: the route's glow): colours, English names, the
     * buildings in 3D (white glass / dark with the accent's edge light), and our sources and layers added — the way on the
     * road under the buildings and names, our 3D things over everything. Pure.
     */
    static String make(String base, String look, int accent) throws Exception {
        JSONObject st = new JSONObject(base);
        JSONArray layers = st.optJSONArray("layers");
        if (layers == null) { layers = new JSONArray(); st.put("layers", layers); }
        JSONArray out = new JSONArray();
        boolean extrusion = false;
        int firstSymbol = -1, firstBuilding = -1;
        String buildingSource = null;
        for (int i = 0; i < layers.length(); i++) {
            JSONObject l = layers.optJSONObject(i);
            if (l == null) continue;
            String type = l.optString("type");
            JSONObject paint = l.optJSONObject("paint");
            if (paint != null) {
                JSONObject np = new JSONObject();
                for (java.util.Iterator<String> it = paint.keys(); it.hasNext(); ) {
                    String k = it.next();
                    np.put(k, k.endsWith("-color") ? recolourValue(paint.opt(k), look) : paint.opt(k));
                }
                l.put("paint", np);
                paint = np;
            }
            if ("symbol".equals(type)) {
                JSONObject lay = l.optJSONObject("layout");
                // (names only: a road number's shield keeps its own words)
                if (lay != null && lay.has("text-field") && String.valueOf(lay.opt("text-field")).contains("\"name")) lay.put("text-field", englishNames());
                if (firstSymbol < 0) firstSymbol = out.length();
                if ("dark".equals(look) && paint != null) { paint.put("text-color", "#dfe7f0"); paint.put("text-halo-color", "rgba(8,12,20,0.85)"); }
            }
            if ("building".equals(l.optString("source-layer"))) buildingSource = l.optString("source", buildingSource);
            if ("fill-extrusion".equals(type) && "building".equals(l.optString("source-layer"))) {
                extrusion = true;
                if (paint == null) { paint = new JSONObject(); l.put("paint", paint); }
                buildingPaint(paint, look, accent);
                l.put("minzoom", Math.min(l.optDouble("minzoom", 14), 14));
                if (firstBuilding < 0) firstBuilding = out.length();
            }
            out.put(l);
        }
        if (!extrusion && buildingSource != null) { // (no 3D buildings in the style: ours, from its own building shapes)
            JSONObject b = new JSONObject().put("id", "j-buildings-3d").put("type", "fill-extrusion").put("source", buildingSource)
                    .put("source-layer", "building").put("minzoom", 14);
            JSONObject paint = new JSONObject();
            buildingPaint(paint, look, accent);
            b.put("paint", paint);
            firstBuilding = firstSymbol < 0 ? out.length() : firstSymbol;
            insert(out, firstBuilding, b);
            if (firstSymbol >= 0) firstSymbol++;
        }
        // our sources
        JSONObject src = st.optJSONObject("sources");
        if (src == null) { src = new JSONObject(); st.put("sources", src); }
        for (String id : new String[]{SRC_ROUTE, SRC_TURN, SRC_CAR, SRC_PHOTO, SRC_PLACES})
            src.put(id, new JSONObject().put("type", "geojson").put("data", new JSONObject().put("type", "FeatureCollection").put("features", new JSONArray())));
        // the way on the road: under the 3D buildings (hidden where a building stands in front of it) and the names; a wide soft glow, then the line
        String glow = format(0x66000000 | (accent & 0xFFFFFF));
        JSONObject casing = new JSONObject().put("id", "j-route-glow").put("type", "line").put("source", SRC_ROUTE)
                .put("layout", new JSONObject().put("line-join", "round").put("line-cap", "round"))
                .put("paint", new JSONObject().put("line-color", glow).put("line-blur", 6).put("line-width", zoomed(10, 26, 18, 30)));
        JSONObject line = new JSONObject().put("id", "j-route").put("type", "line").put("source", SRC_ROUTE)
                .put("layout", new JSONObject().put("line-join", "round").put("line-cap", "round"))
                .put("paint", new JSONObject().put("line-color", "#ffcc1a").put("line-width", zoomed(10, 5, 18, 14)));
        int at = firstBuilding >= 0 ? firstBuilding : firstSymbol < 0 ? out.length() : firstSymbol;
        insert(out, at, casing);
        insert(out, at + 1, line);
        // our 3D things over everything: the floating arrow, the car, his photo, the places' pins
        out.put(new JSONObject().put("id", "j-turn").put("type", "fill-extrusion").put("source", SRC_TURN)
                .put("paint", new JSONObject().put("fill-extrusion-color", "#7fd0ff").put("fill-extrusion-opacity", 0.92)
                        .put("fill-extrusion-base", new JSONArray().put("get").put("b")).put("fill-extrusion-height", new JSONArray().put("get").put("h"))));
        out.put(new JSONObject().put("id", "j-car").put("type", "fill-extrusion").put("source", SRC_CAR)
                .put("paint", new JSONObject().put("fill-extrusion-color", new JSONArray().put("get").put("c")).put("fill-extrusion-opacity", 1)
                        .put("fill-extrusion-base", new JSONArray().put("get").put("b")).put("fill-extrusion-height", new JSONArray().put("get").put("h"))
                        .put("fill-extrusion-vertical-gradient", false)));
        out.put(new JSONObject().put("id", "j-photo").put("type", "symbol").put("source", SRC_PHOTO)
                .put("layout", new JSONObject().put("icon-image", IMG_PHOTO).put("icon-anchor", "bottom").put("icon-allow-overlap", true).put("icon-ignore-placement", true)
                        .put("icon-pitch-alignment", "viewport").put("icon-rotation-alignment", "viewport").put("icon-size", new JSONArray().put("get").put("s"))));
        out.put(new JSONObject().put("id", "j-places").put("type", "symbol").put("source", SRC_PLACES)
                .put("layout", new JSONObject().put("icon-image", new JSONArray().put("get").put("icon")).put("icon-anchor", "bottom").put("icon-allow-overlap", true)
                        .put("icon-pitch-alignment", "viewport").put("icon-rotation-alignment", "viewport").put("icon-size", zoomed(12, 0.55, 18, 1.0))));
        st.put("layers", out);
        if ("dark".equals(look)) st.put("light", new JSONObject().put("anchor", "viewport").put("color", "#9fb4ff").put("intensity", 0.35));
        else st.put("light", new JSONObject().put("anchor", "viewport").put("color", "#ffffff").put("intensity", 0.45));
        return st.toString();
    }

    /** The 3D buildings' paint: white glass by day, dark with a hint of the accent at night; the real heights. */
    private static void buildingPaint(JSONObject paint, String look, int accent) throws Exception {
        boolean dark = "dark".equals(look);
        paint.put("fill-extrusion-color", dark ? format(0xFF000000 | mix(0x2A3550, accent, 0.05f)) : "#eef2f7");
        paint.put("fill-extrusion-opacity", dark ? 0.92 : 0.9);
        paint.put("fill-extrusion-height", new JSONArray().put("coalesce").put(new JSONArray().put("get").put("render_height")).put(8));
        paint.put("fill-extrusion-base", new JSONArray().put("coalesce").put(new JSONArray().put("get").put("render_min_height")).put(0));
    }

    static int mix(int a, int b, float k) {
        int r = Math.round(((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k), g = Math.round(((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k),
                bl = Math.round((a & 255) * (1 - k) + (b & 255) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /** A value growing with the zoom: z0 → v0, z1 → v1 (straight between). */
    static JSONArray zoomed(double z0, double v0, double z1, double v1) {
        JSONArray a = new JSONArray().put("interpolate").put(new JSONArray().put("linear")).put(new JSONArray().put("zoom"));
        try { a.put(z0).put(v0).put(z1).put(v1); } catch (Exception ignored) {} // (finite numbers: never thrown)
        return a;
    }

    private static void insert(JSONArray a, int at, Object v) throws Exception {
        JSONArray o = new JSONArray();
        for (int i = 0; i < a.length(); i++) { if (i == at) o.put(v); o.put(a.get(i)); }
        if (at >= a.length()) o.put(v);
        while (a.length() > 0) a.remove(a.length() - 1);
        for (int i = 0; i < o.length(); i++) a.put(o.get(i));
    }

    /** When the free style can't be had (no net yet): a plain land of our look with our own layers on it. Pure. */
    static String plain(String look) {
        String bg = "dark".equals(look) ? "#0d1420" : "#eef1f4";
        return "{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"bg\",\"type\":\"background\",\"paint\":{\"background-color\":\"" + bg + "\"}}]}";
    }

    // ================================================================ our things as GeoJSON

    /** A point metres east (dx) and south (dz) of (lat, lon): {lat, lon}. Pure. */
    static double[] offset(double lat, double lon, double dx, double dz) {
        return new double[]{lat - dz / 110574.0, lon + dx / (111320.0 * Math.cos(Math.toRadians(lat)))};
    }

    /** A box's footprint (local x right, z back of a thing heading h radians, centred at cx, cz) as a GeoJSON ring. */
    static JSONArray ring(double lat, double lon, double h, double cx, double cz, double w, double l) {
        double[][] c = {{cx - w / 2, cz - l / 2}, {cx + w / 2, cz - l / 2}, {cx + w / 2, cz + l / 2}, {cx - w / 2, cz + l / 2}, {cx - w / 2, cz - l / 2}};
        return ringOf(lat, lon, h, c);
    }

    /** Local points (x right, z back; the thing heading h: its nose to h) into a ring of [lon, lat]. */
    static JSONArray ringOf(double lat, double lon, double h, double[][] pts) {
        JSONArray r = new JSONArray();
        double sn = Math.sin(h), cs = Math.cos(h);
        for (double[] p : pts) {
            // local (x right, z back) → world (east, south): forward = (sin h, −cos h), right = (cos h, sin h)
            double ex = p[0] * cs - p[1] * sn, ez = p[0] * sn + p[1] * cs;
            double[] q = offset(lat, lon, ex, ez);
            try { r.put(new JSONArray().put(round6(q[1])).put(round6(q[0]))); } catch (Exception ignored) {}
        }
        return r;
    }

    private static double round6(double v) { return Math.round(v * 1e7) / 1e7; }

    private static JSONObject part(JSONArray ring, double base, double top, String colour) throws Exception {
        return new JSONObject().put("type", "Feature").put("properties", new JSONObject().put("b", base).put("h", top).put("c", colour))
                .put("geometry", new JSONObject().put("type", "Polygon").put("coordinates", new JSONArray().put(ring)));
    }

    /**
     * Our car standing on the map at (lat, lon) heading h (radians, 0 north): its lower body and cabin in his paint
     * (the cabin's glass dark), four dark wheels, tail lights (bright when braking) and headlights (bright at night),
     * from the kind's own measurements, made k times bigger (so it stays seen from the camera's height: carScale). A GeoJSON
     * FeatureCollection (base b, top h, colour c). Pure.
     */
    static String car(double lat, double lon, double h, String kind, int paint, boolean brake, boolean night, double k) throws Exception {
        Car3D.Size s = Car3D.size(kind);
        JSONArray f = new JSONArray();
        String body = format(0xFF000000 | (paint & 0xFFFFFF)), glass = "#16202c", tyre = "#121418";
        double w = s.w * k, l = s.l * k, cabL = l * (s.rwBase - s.wsBase), cabZ = -l / 2 + l * (s.wsBase + s.rwBase) / 2;
        double clear = s.clear * k, belt = s.belt * k, top = s.h * k, roof = 0.06 * k, r2 = s.r * 2 * k;
        f.put(part(ring(lat, lon, h, 0, 0, w * 0.96, l * 0.98), clear, belt, body));
        f.put(part(ring(lat, lon, h, 0, cabZ, w * 0.8, cabL), belt, top - roof, glass));
        f.put(part(ring(lat, lon, h, 0, cabZ, w * 0.78, cabL * 0.82), top - roof, top, body)); // (the roof)
        for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2)
            f.put(part(ring(lat, lon, h, sx * (w / 2 - 0.08 * k), sz * s.base * k / 2, 0.26 * k, r2), 0, r2, tyre));
        String tail = brake ? "#ff2b2b" : night ? "#c81a24" : "#8c1018", head = night ? "#fffbe6" : "#dfe8f2";
        for (int sx = -1; sx <= 1; sx += 2) {
            f.put(part(ring(lat, lon, h, sx * (w / 2 - 0.25 * k), l / 2 - 0.02 * k, 0.4 * k, 0.06 * k), belt - 0.2 * k, belt - 0.06 * k, tail));
            f.put(part(ring(lat, lon, h, sx * (w / 2 - 0.28 * k), -l / 2 + 0.02 * k, 0.36 * k, 0.06 * k), belt - 0.22 * k, belt - 0.1 * k, head));
        }
        return new JSONObject().put("type", "FeatureCollection").put("features", f).toString();
    }

    /**
     * The big arrow floating over the next turn (lat, lon): its shaft coming along the way's heading (in, radians), its
     * head turned by the change (+ right), 7–8.5 m above the road — all k times bigger (arrowScale). A FeatureCollection;
     * empty when none. Pure.
     */
    static String arrow(double[] turn, double k) throws Exception {
        JSONArray f = new JSONArray();
        if (turn != null) {
            double lat = turn[0], lon = turn[1], in = turn[2], ch = Math.max(-Math.PI * 0.85, Math.min(Math.PI * 0.85, turn[3]));
            double b = 7 * k, t = 8.5 * k;
            // the shaft: from 14 m before the turn to the turn (local: z back is negative forward)
            f.put(part(ring(lat, lon, in, 0, 7 * k, 2.4 * k, 14 * k), b, t, ""));
            // the head's arm: from the turn 7 m along the new way, then the head
            double out = in + ch;
            double[][] arm = {{-1.2 * k, 0}, {1.2 * k, 0}, {1.2 * k, -7 * k}, {-1.2 * k, -7 * k}, {-1.2 * k, 0}};
            f.put(part(ringOf(lat, lon, out, arm), b, t, ""));
            double[][] head = {{-3.2 * k, -7 * k}, {3.2 * k, -7 * k}, {0, -12 * k}, {-3.2 * k, -7 * k}};
            f.put(part(ringOf(lat, lon, out, head), b, t, ""));
        }
        return new JSONObject().put("type", "FeatureCollection").put("features", f).toString();
    }

    /** The way still ahead of him (from at metres) as a LineString; empty when none. Pure. */
    static String route(List<double[]> pts, int fromIndex) throws Exception {
        JSONArray f = new JSONArray();
        if (pts != null && pts.size() - Math.max(0, fromIndex) >= 2) {
            JSONArray c = new JSONArray();
            for (int i = Math.max(0, fromIndex); i < pts.size(); i++) c.put(new JSONArray().put(round6(pts.get(i)[1])).put(round6(pts.get(i)[0])));
            f.put(new JSONObject().put("type", "Feature").put("properties", new JSONObject()).put("geometry", new JSONObject().put("type", "LineString").put("coordinates", c)));
        }
        return new JSONObject().put("type", "FeatureCollection").put("features", f).toString();
    }

    /**
     * The way still ahead of him: from his place on the way (where he stands, put on its nearest piece) to its end, as a
     * LineString — nothing of it behind him. Empty when there's no way or nothing left. Pure.
     */
    static String routeAhead(List<double[]> pts, double lat, double lon) throws Exception { return routeAhead(pts, lat, lon, Double.MAX_VALUE); }

    /** The same, only the next maxMetres of it (the camera never sees further; a long trip stays light to draw). Pure. */
    static String routeAhead(List<double[]> pts, double lat, double lon, double maxMetres) throws Exception {
        if (pts == null || pts.size() < 2) return route(null, 0);
        double k = Math.cos(Math.toRadians(lat)), best = Double.MAX_VALUE, pl = pts.get(0)[0], po = pts.get(0)[1];
        int seg = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i), b = pts.get(i + 1);
            double ax = a[1] * k, ay = a[0], bx = b[1] * k, by = b[0], px = lon * k, py = lat;
            double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy, t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
            double qx = ax + dx * t, qy = ay + dy * t, d = (px - qx) * (px - qx) + (py - qy) * (py - qy);
            if (d < best) { best = d; seg = i; pl = qy; po = qx / k; }
        }
        List<double[]> out = new java.util.ArrayList<>();
        out.add(new double[]{pl, po});
        double run = 0;
        for (int i = seg + 1; i < pts.size() && run < maxMetres; i++) {
            double[] prev = out.get(out.size() - 1), q = pts.get(i);
            run += MapMath.meters(prev[0], prev[1], q[0], q[1]);
            out.add(q); // (the point past the limit too: no gap before it)
        }
        return route(out, 0);
    }

    /** The index of the way's point nearest to (lat, lon) (the part behind him isn't drawn). Pure. */
    static int nearest(List<double[]> pts, double lat, double lon) {
        if (pts == null) return 0;
        int best = 0;
        double bd = Double.MAX_VALUE, k = Math.cos(Math.toRadians(lat));
        for (int i = 0; i < pts.size(); i++) {
            double dy = pts.get(i)[0] - lat, dx = (pts.get(i)[1] - lon) * k, d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** His photo standing on the road at (lat, lon) (s: its size on the map); empty when none. Pure. */
    static String photo(double lat, double lon, double size, boolean on) throws Exception {
        JSONArray f = new JSONArray();
        if (on) f.put(new JSONObject().put("type", "Feature").put("properties", new JSONObject().put("s", size))
                .put("geometry", new JSONObject().put("type", "Point").put("coordinates", new JSONArray().put(round6(lon)).put(round6(lat)))));
        return new JSONObject().put("type", "FeatureCollection").put("features", f).toString();
    }

    /** The chips' places as standing pins (icon: "j-pin-" + the chip's kind). Pure. */
    static String places(List<OsmPlaces.Place> list, String kind) throws Exception {
        JSONArray f = new JSONArray();
        for (int i = 0; list != null && i < list.size(); i++) {
            OsmPlaces.Place p = list.get(i);
            f.put(new JSONObject().put("type", "Feature").put("properties", new JSONObject().put("icon", "j-pin-" + kind).put("name", p.name))
                    .put("geometry", new JSONObject().put("type", "Point").put("coordinates", new JSONArray().put(round6(p.lon)).put(round6(p.lat)))));
        }
        return new JSONObject().put("type", "FeatureCollection").put("features", f).toString();
    }

    // ================================================================ the camera

    /** 👁️ The camera's height: near, middle, far → {zoom, tilt} in 3D; 2D: flat, a little further. Pure. */
    static double[] eye(int level, boolean threeD) {
        if (!threeD) return new double[]{level == 0 ? 17.5 : level == 2 ? 15.5 : 16.5, 0};
        switch (level) { // (MapLibre tilts at most 60°)
            case 0: return new double[]{19.2, 60};
            case 2: return new double[]{17.4, 52};
            default: return new double[]{18.4, 58};
        }
    }

    /** Ground metres per screen point at a latitude and zoom (MapLibre's 512-point tiles). Pure. */
    static double mpp(double lat, double zoom) {
        return 78271.517 * Math.cos(Math.toRadians(lat)) / Math.pow(2, zoom);
    }

    /** How much bigger our car is drawn so it stays about 40 points wide on the screen (never smaller than real, at most 5×). Pure. */
    static double carScale(double lat, double zoom, String kind) {
        return Math.max(1, Math.min(5, 40 * mpp(lat, zoom) / Car3D.size(kind).w));
    }

    /** The floating arrow likewise (its real size near, bigger when the camera is high; at most 4×). Pure. */
    static double arrowScale(double lat, double zoom) {
        return Math.max(1, Math.min(4, mpp(lat, zoom) / 0.12));
    }

    /** A little nearer at walking speed, further at highway speed (the way ahead seen in time). Pure. */
    static double speedZoom(double zoom, int kmh) {
        if (kmh <= 30) return zoom;
        return zoom - Math.min(1.2, (kmh - 30) / 70.0);
    }
}
