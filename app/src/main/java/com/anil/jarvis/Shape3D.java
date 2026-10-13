package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The AI's shape model of an object (simple solids in centimetres: cylinder, ring, box, sphere, cone) turned into faces a
 * Canvas can draw with the painter's algorithm. Pure geometry, no Android classes, so it is tested on a desk.
 * Axes: x right, y into the scene, z up; the object's centre at the origin.
 */
final class Shape3D {
    private Shape3D() {}

    static final class Part {
        final JSONObject json;
        final String id, name, type, info, section;
        final int color;
        final boolean seen;
        final float[] c = new float[3];
        final List<Face> faces = new ArrayList<>();
        Part(JSONObject j) {
            json = j;
            id = j.optString("id");
            name = j.optString("name");
            type = j.optString("type");
            info = j.optString("info");
            section = j.optString("section");
            seen = j.optBoolean("seen", true);
            color = colour(j.optString("color"), type);
            JSONArray cc = j.optJSONArray("c");
            for (int i = 0; i < 3; i++) c[i] = cc == null ? 0 : (float) cc.optDouble(i, 0);
        }
    }

    /** One flat polygon of a part: points (x,y,z)*n, its outward normal, and whether it is a cap (end) face. */
    static final class Face {
        final Part part;
        final float[] pts;
        final float[] n = new float[3];
        final boolean cap;
        Face(Part part, float[] pts, boolean cap) {
            this.part = part;
            this.pts = pts;
            this.cap = cap;
            normal(pts, n);
        }
    }

    static final int SEG = 28;

    /** The parts of a shape {parts:[…]} with their faces built; empty when there is nothing usable. */
    static List<Part> build(JSONObject shape) {
        List<Part> out = new ArrayList<>();
        JSONArray parts = shape == null ? null : shape.optJSONArray("parts");
        for (int i = 0; parts != null && i < parts.length(); i++) {
            JSONObject j = parts.optJSONObject(i);
            if (j == null) continue;
            Part p = new Part(j);
            try { mesh(p); } catch (Exception e) { continue; }
            if (!p.faces.isEmpty()) out.add(p);
        }
        return out;
    }

    /** The radius of a ball around the whole thing (for fitting it on the screen); 1 when empty. */
    static float radius(List<Part> parts) {
        float r = 0;
        for (Part p : parts) for (Face f : p.faces) for (int i = 0; i < f.pts.length; i += 3) r = Math.max(r, (float) Math.sqrt(f.pts[i] * f.pts[i] + f.pts[i + 1] * f.pts[i + 1] + f.pts[i + 2] * f.pts[i + 2]));
        return r <= 0 ? 1 : r;
    }

    /** The lowest z over the whole thing (where its shadow sits). */
    static float bottom(List<Part> parts) {
        float z = Float.MAX_VALUE;
        for (Part p : parts) for (Face f : p.faces) for (int i = 2; i < f.pts.length; i += 3) z = Math.min(z, f.pts[i]);
        return z == Float.MAX_VALUE ? 0 : z;
    }

    private static void mesh(Part p) {
        JSONObject j = p.json;
        String ax = j.optString("axis", "z");
        float r = (float) j.optDouble("r", 0), r2 = (float) j.optDouble("r2", 0), h = (float) j.optDouble("h", 0);
        switch (p.type) {
            case "cylinder": tube(p, r, r, h, ax, true); break;
            case "cone": tube(p, r, r2, h, ax, true); break;
            case "ring": ring(p, r, r2, h, ax); break;
            case "sphere": sphere(p, r); break;
            case "box": {
                JSONArray s = j.optJSONArray("s");
                box(p, (float) s.optDouble(0, 1) / 2, (float) s.optDouble(1, 1) / 2, (float) s.optDouble(2, 1) / 2);
                break;
            }
            default: break;
        }
    }

    // ---- solids, built along z then turned to their axis and moved to their centre

    private static void tube(Part p, float rb, float rt, float h, String ax, boolean caps) {
        float z0 = -h / 2, z1 = h / 2;
        float[] bot = new float[SEG * 3], top = new float[SEG * 3];
        for (int i = 0; i < SEG; i++) {
            double a = 2 * Math.PI * i / SEG;
            bot[i * 3] = (float) (rb * Math.cos(a)); bot[i * 3 + 1] = (float) (rb * Math.sin(a)); bot[i * 3 + 2] = z0;
            top[i * 3] = (float) (rt * Math.cos(a)); top[i * 3 + 1] = (float) (rt * Math.sin(a)); top[i * 3 + 2] = z1;
        }
        for (int i = 0; i < SEG; i++) {
            int k = (i + 1) % SEG;
            add(p, ax, false, bot[i * 3], bot[i * 3 + 1], z0, bot[k * 3], bot[k * 3 + 1], z0, top[k * 3], top[k * 3 + 1], z1, top[i * 3], top[i * 3 + 1], z1);
        }
        if (caps) {
            if (rt > 0.01f) add(p, ax, true, top);                 // top cap, counter-clockwise seen from above
            if (rb > 0.01f) add(p, ax, true, reverse(bot));        // bottom cap, facing down
        }
    }

    private static void ring(Part p, float ro, float ri, float h, String ax) {
        float z0 = -h / 2, z1 = h / 2;
        for (int i = 0; i < SEG; i++) {
            int k = (i + 1) % SEG;
            double a = 2 * Math.PI * i / SEG, b = 2 * Math.PI * k / SEG;
            float xo = (float) (ro * Math.cos(a)), yo = (float) (ro * Math.sin(a)), xo2 = (float) (ro * Math.cos(b)), yo2 = (float) (ro * Math.sin(b));
            float xi = (float) (ri * Math.cos(a)), yi = (float) (ri * Math.sin(a)), xi2 = (float) (ri * Math.cos(b)), yi2 = (float) (ri * Math.sin(b));
            add(p, ax, false, xo, yo, z0, xo2, yo2, z0, xo2, yo2, z1, xo, yo, z1);       // outer wall
            add(p, ax, false, xi2, yi2, z0, xi, yi, z0, xi, yi, z1, xi2, yi2, z1);       // inner wall (faces inwards)
            add(p, ax, true, xi, yi, z1, xo, yo, z1, xo2, yo2, z1, xi2, yi2, z1);        // top annulus piece
            add(p, ax, true, xo, yo, z0, xi, yi, z0, xi2, yi2, z0, xo2, yo2, z0);        // bottom annulus piece
        }
    }

    private static void sphere(Part p, float r) {
        int lat = 10, lon = 18;
        for (int i = 0; i < lat; i++) {
            double t0 = Math.PI * i / lat, t1 = Math.PI * (i + 1) / lat;
            for (int k = 0; k < lon; k++) {
                double a0 = 2 * Math.PI * k / lon, a1 = 2 * Math.PI * (k + 1) / lon;
                float[] q = {
                        (float) (r * Math.sin(t0) * Math.cos(a0)), (float) (r * Math.sin(t0) * Math.sin(a0)), (float) (r * Math.cos(t0)),
                        (float) (r * Math.sin(t1) * Math.cos(a0)), (float) (r * Math.sin(t1) * Math.sin(a0)), (float) (r * Math.cos(t1)),
                        (float) (r * Math.sin(t1) * Math.cos(a1)), (float) (r * Math.sin(t1) * Math.sin(a1)), (float) (r * Math.cos(t1)),
                        (float) (r * Math.sin(t0) * Math.cos(a1)), (float) (r * Math.sin(t0) * Math.sin(a1)), (float) (r * Math.cos(t0))};
                add(p, "z", false, q);
            }
        }
    }

    private static void box(Part p, float hx, float hy, float hz) {
        add(p, "z", true, -hx, -hy, hz, hx, -hy, hz, hx, hy, hz, -hx, hy, hz);       // top
        add(p, "z", true, -hx, hy, -hz, hx, hy, -hz, hx, -hy, -hz, -hx, -hy, -hz);   // bottom
        add(p, "z", false, -hx, -hy, -hz, hx, -hy, -hz, hx, -hy, hz, -hx, -hy, hz);  // front (-y)
        add(p, "z", false, hx, hy, -hz, -hx, hy, -hz, -hx, hy, hz, hx, hy, hz);      // back (+y)
        add(p, "z", false, hx, -hy, -hz, hx, hy, -hz, hx, hy, hz, hx, -hy, hz);      // right
        add(p, "z", false, -hx, hy, -hz, -hx, -hy, -hz, -hx, -hy, hz, -hx, hy, hz);  // left
    }

    private static void add(Part p, String ax, boolean cap, float... pts) {
        float[] q = pts.clone();
        for (int i = 0; i < q.length; i += 3) {
            float x = q[i], y = q[i + 1], z = q[i + 2], nx = x, ny = y, nz = z;
            if ("x".equals(ax)) { nx = z; ny = y; nz = -x; }        // z-axis solid laid along x
            else if ("y".equals(ax)) { nx = x; ny = z; nz = -y; }   // along y
            q[i] = nx + p.c[0];
            q[i + 1] = ny + p.c[1];
            q[i + 2] = nz + p.c[2];
        }
        p.faces.add(new Face(p, q, cap));
    }

    private static float[] reverse(float[] pts) {
        int n = pts.length / 3;
        float[] out = new float[pts.length];
        for (int i = 0; i < n; i++) System.arraycopy(pts, (n - 1 - i) * 3, out, i * 3, 3);
        return out;
    }

    /** Newell's method: the polygon's normal, unit length. */
    static void normal(float[] pts, float[] out) {
        float nx = 0, ny = 0, nz = 0;
        int n = pts.length / 3;
        for (int i = 0; i < n; i++) {
            int k = (i + 1) % n;
            float x0 = pts[i * 3], y0 = pts[i * 3 + 1], z0 = pts[i * 3 + 2], x1 = pts[k * 3], y1 = pts[k * 3 + 1], z1 = pts[k * 3 + 2];
            nx += (y0 - y1) * (z0 + z1);
            ny += (z0 - z1) * (x0 + x1);
            nz += (x0 - x1) * (y0 + y1);
        }
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-6f) { out[0] = 0; out[1] = 0; out[2] = 1; return; }
        out[0] = nx / l; out[1] = ny / l; out[2] = nz / l;
    }

    /** "#rrggbb" → ARGB; a material-ish default by type when missing or odd. */
    static int colour(String hex, String type) {
        try {
            String h = hex == null ? "" : hex.trim();
            if (h.matches("#[0-9a-fA-F]{6}")) return 0xFF000000 | Integer.parseInt(h.substring(1), 16);
        } catch (Exception ignored) {}
        switch (type == null ? "" : type) {
            case "ring": return 0xFF8A99A8;
            case "sphere": return 0xFFB8C4CC;
            case "box": return 0xFF9A7B55;
            default: return 0xFFA8845A;
        }
    }

    // ---- the camera: yaw around z, pitch towards the viewer, then a mild perspective

    /** Rotates a point: yaw (deg, about z) then pitch (deg, about the screen's x axis). out = {x right, y up, depth towards the viewer}. */
    static void turn(float x, float y, float z, float yawDeg, float pitchDeg, float[] out) {
        double ya = Math.toRadians(yawDeg), pa = Math.toRadians(pitchDeg);
        double cx = Math.cos(ya), sx = Math.sin(ya);
        double x1 = x * cx - y * sx, y1 = x * sx + y * cx; // yaw
        double cp = Math.cos(pa), sp = Math.sin(pa);
        // the viewer looks along -y; pitch tilts the scene so he sees the top (pitch > 0) or the underside (pitch < 0)
        double yv = y1 * cp - z * sp;   // depth component (positive = away)
        double zv = y1 * sp + z * cp;   // up on the screen
        out[0] = (float) x1;
        out[1] = (float) zv;
        out[2] = (float) -yv;           // towards the viewer
    }

    /** Explodes a part's centre away from the origin by `k` of its own offset (k = 0 → where it belongs). */
    static float[] explode(Part p, float k, float minPush) {
        float x = p.c[0], y = p.c[1], z = p.c[2];
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-3f) return new float[]{0, 0, 0};
        float push = Math.max(l * k, k > 0 ? minPush : 0);
        return new float[]{x / l * push, y / l * push, z / l * push};
    }
}
