package com.anil.jarvis;

/**
 * 🧊 Stage 3 (27 Oct): a growing list of flat-shaded triangles for the car screen's own 3D engine (no Android here:
 * desk-tested and desk-rendered). Each corner: x, y, z, its face's normal, a colour (r, g, b) and a material
 * ({@link #M_PAINT} the car's paint, {@link #M_LIGHT} lights, {@link #M_GLASS}, …). World axes: x east, y up, z south
 * (north is −z, the way a camera looks at first); a car's nose points to −z.
 */
final class Mesh3D {
    /** Floats per corner: position 3, normal 3, colour 3, material 1. */
    static final int STRIDE = 10;
    /** Materials (the shader's switch). */
    static final float M_PAINT = 0, M_LIGHT = 1, M_GLASS = 2, M_WALL = 3, M_MATTE = 4, M_TAIL = 5, M_IND_L = 6, M_IND_R = 7, M_HEAD = 8, M_NEON = 9,
            M_BEAM = 10, M_CHROME = 11, M_ROAD = 12, M_SHADOW = 13, M_GLOW = 14;

    float[] v = new float[STRIDE * 3 * 64];
    int n; // floats used

    int corners() { return n / STRIDE; }

    int triangles() { return n / STRIDE / 3; }

    private void grow(int more) {
        if (n + more <= v.length) return;
        float[] w = new float[Math.max(v.length * 2, n + more)];
        System.arraycopy(v, 0, w, 0, n);
        v = w;
    }

    /** One triangle (counter-clockwise seen from outside); its normal from its corners. */
    void tri(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, int rgb, float mat) {
        float ux = bx - ax, uy = by - ay, uz = bz - az, wx = cx - ax, wy = cy - ay, wz = cz - az;
        float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-9f) return; // (no area: nothing to draw)
        nx /= l; ny /= l; nz /= l;
        grow(STRIDE * 3);
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        put(ax, ay, az, nx, ny, nz, r, g, b, mat);
        put(bx, by, bz, nx, ny, nz, r, g, b, mat);
        put(cx, cy, cz, nx, ny, nz, r, g, b, mat);
    }

    private void put(float x, float y, float z, float nx, float ny, float nz, float r, float g, float b, float m) {
        v[n++] = x; v[n++] = y; v[n++] = z; v[n++] = nx; v[n++] = ny; v[n++] = nz; v[n++] = r; v[n++] = g; v[n++] = b; v[n++] = m;
    }

    /** A flat four-cornered face a, b, c, d (counter-clockwise from outside). */
    void quad(float[] a, float[] b, float[] c, float[] d, int rgb, float mat) {
        tri(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], rgb, mat);
        tri(a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2], rgb, mat);
    }

    /** A triangle turned to face the way (fx, fy, fz) (its corners swapped when they wound the other way). */
    void triFacing(float[] a, float[] b, float[] c, float fx, float fy, float fz, int rgb, float mat) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
        float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        if (nx * fx + ny * fy + nz * fz >= 0) tri(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], rgb, mat);
        else tri(a[0], a[1], a[2], c[0], c[1], c[2], b[0], b[1], b[2], rgb, mat);
    }

    /** A four-cornered face turned to face the way (fx, fy, fz). */
    void quadFacing(float[] a, float[] b, float[] c, float[] d, float fx, float fy, float fz, int rgb, float mat) {
        triFacing(a, b, c, fx, fy, fz, rgb, mat);
        triFacing(a, c, d, fx, fy, fz, rgb, mat);
    }

    /** All of another mesh, moved by (dx, dy, dz) and turned round the up axis by yaw (radians). */
    void add(Mesh3D o, float dx, float dy, float dz, float yaw) {
        grow(o.n);
        float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
        for (int i = 0; i < o.n; i += STRIDE) {
            float x = o.v[i], z = o.v[i + 2], nx = o.v[i + 3], nz = o.v[i + 5];
            v[n++] = x * c + z * s + dx; v[n++] = o.v[i + 1] + dy; v[n++] = -x * s + z * c + dz;
            v[n++] = nx * c + nz * s; v[n++] = o.v[i + 4]; v[n++] = -nx * s + nz * c;
            for (int k = 6; k < STRIDE; k++) v[n++] = o.v[i + k];
        }
    }

    /** Every corner moved by (dx, dy, dz) (the world's pieces kept round their own middle: small numbers for the GPU). */
    void shift(float dx, float dy, float dz) {
        for (int i = 0; i < n; i += STRIDE) { v[i] += dx; v[i + 1] += dy; v[i + 2] += dz; }
    }

    /** {minX, minY, minZ, maxX, maxY, maxZ} of every corner; zeros when empty. */
    float[] bounds() {
        if (n == 0) return new float[6];
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < n; i += STRIDE) for (int k = 0; k < 3; k++) { b[k] = Math.min(b[k], v[i + k]); b[k + 3] = Math.max(b[k + 3], v[i + k]); }
        return b;
    }

    // ================================================================ solids

    /** A box: centre, size; one colour and material. */
    void box(float cx, float cy, float cz, float sx, float sy, float sz, int rgb, float mat) {
        float x0 = cx - sx / 2, x1 = cx + sx / 2, y0 = cy - sy / 2, y1 = cy + sy / 2, z0 = cz - sz / 2, z1 = cz + sz / 2;
        float[] a = {x0, y0, z1}, b = {x1, y0, z1}, c = {x1, y1, z1}, d = {x0, y1, z1};
        float[] e = {x0, y0, z0}, f = {x1, y0, z0}, g = {x1, y1, z0}, h = {x0, y1, z0};
        quad(a, b, c, d, rgb, mat); // south (+z)
        quad(f, e, h, g, rgb, mat); // north (−z)
        quad(b, f, g, c, rgb, mat); // east
        quad(e, a, d, h, rgb, mat); // west
        quad(d, c, g, h, rgb, mat); // top
        quad(e, f, b, a, rgb, mat); // bottom
    }

    /** A cylinder lying across (its axis along x): a wheel. Its round faces too. */
    void wheelX(float cx, float cy, float cz, float r, float width, int seg, int rgb, float mat) {
        float x0 = cx - width / 2, x1 = cx + width / 2;
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            float y0 = cy + r * (float) Math.sin(a0), z0 = cz + r * (float) Math.cos(a0), y1 = cy + r * (float) Math.sin(a1), z1 = cz + r * (float) Math.cos(a1);
            quad(new float[]{x0, y0, z0}, new float[]{x0, y1, z1}, new float[]{x1, y1, z1}, new float[]{x1, y0, z0}, rgb, mat);
            tri(x1, cy, cz, x1, y0, z0, x1, y1, z1, rgb, mat);
            tri(x0, cy, cz, x0, y1, z1, x0, y0, z0, rgb, mat);
        }
    }

    /** A flat round plate facing +x (side = 1) or −x (−1): a wheel's rim. */
    void discX(float cx, float cy, float cz, float r, int seg, int side, int rgb, float mat) {
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            float y0 = cy + r * (float) Math.sin(a0), z0 = cz + r * (float) Math.cos(a0), y1 = cy + r * (float) Math.sin(a1), z1 = cz + r * (float) Math.cos(a1);
            if (side > 0) tri(cx, cy, cz, cx, y0, z0, cx, y1, z1, rgb, mat);
            else tri(cx, cy, cz, cx, y1, z1, cx, y0, z0, rgb, mat);
        }
    }

    /** A cylinder standing (axis up): a tree's trunk, a lamp's post. */
    void post(float cx, float y0, float cz, float r, float h, int seg, int rgb, float mat) {
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            float x0 = cx + r * (float) Math.cos(a0), z0 = cz - r * (float) Math.sin(a0), x1 = cx + r * (float) Math.cos(a1), z1 = cz - r * (float) Math.sin(a1);
            quad(new float[]{x0, y0, z0}, new float[]{x1, y0, z1}, new float[]{x1, y0 + h, z1}, new float[]{x0, y0 + h, z0}, rgb, mat);
            tri(cx, y0 + h, cz, x0, y0 + h, z0, x1, y0 + h, z1, rgb, mat);
        }
    }

    /** A rounded crown (a low ball of flat faces): a tree's leaves. */
    void ball(float cx, float cy, float cz, float r, int seg, int rgb, float mat) {
        int rings = Math.max(3, seg / 2);
        for (int j = 0; j < rings; j++) {
            double p0 = Math.PI * j / rings - Math.PI / 2, p1 = Math.PI * (j + 1) / rings - Math.PI / 2;
            for (int i = 0; i < seg; i++) {
                double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
                float[] a = sph(cx, cy, cz, r, p0, a0), b = sph(cx, cy, cz, r, p0, a1), c = sph(cx, cy, cz, r, p1, a1), d = sph(cx, cy, cz, r, p1, a0);
                quad(a, b, c, d, rgb, mat);
            }
        }
    }

    private static float[] sph(float cx, float cy, float cz, float r, double p, double a) {
        return new float[]{cx + r * (float) (Math.cos(p) * Math.cos(a)), cy + r * (float) Math.sin(p), cz - r * (float) (Math.cos(p) * Math.sin(a))};
    }

    /**
     * A side outline (pairs z, y going round counter-clockwise when seen from +x) pulled across from −half to +half,
     * narrower towards its top (taper: the half-width at the outline's top as a part of the half-width at its foot).
     * Each edge of the outline gets its own material (mats[i] for the edge from point i to i+1; null: all mat); the two
     * ends (the outline itself, at ±x) get endMat.
     */
    void loftX(float[] zy, float half, float taper, int rgb, float mat, float[] mats, int endRgb, float endMat) {
        int k = zy.length / 2;
        float ylo = Float.MAX_VALUE, yhi = -Float.MAX_VALUE;
        for (int i = 0; i < k; i++) { ylo = Math.min(ylo, zy[2 * i + 1]); yhi = Math.max(yhi, zy[2 * i + 1]); }
        float[] hw = new float[k];
        for (int i = 0; i < k; i++) {
            float t = yhi > ylo ? (zy[2 * i + 1] - ylo) / (yhi - ylo) : 0;
            hw[i] = half * (1 - (1 - taper) * t * t); // (round shoulders: narrower at the top)
        }
        float cz = 0, cy = 0;
        for (int i = 0; i < k; i++) { cz += zy[2 * i]; cy += zy[2 * i + 1]; }
        cz /= k; cy /= k;
        for (int i = 0; i < k; i++) {
            int j = (i + 1) % k;
            float m = mats == null ? mat : mats[i];
            float z0 = zy[2 * i], y0 = zy[2 * i + 1], z1 = zy[2 * j], y1 = zy[2 * j + 1];
            // outward: away from the outline's middle, across the edge
            float ez = z1 - z0, ey = y1 - y0, oz = ey, oy = -ez;
            if (oz * ((z0 + z1) / 2 - cz) + oy * ((y0 + y1) / 2 - cy) < 0) { oz = -oz; oy = -oy; }
            quadFacing(new float[]{-hw[i], y0, z0}, new float[]{hw[i], y0, z0}, new float[]{hw[j], y1, z1}, new float[]{-hw[j], y1, z1}, 0, oy, oz,
                    m == M_GLASS ? 0x0B1018 : rgb, m);
        }
        int[] idx = earClip(zy);
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t], b = idx[t + 1], c = idx[t + 2];
            triFacing(new float[]{hw[a], zy[2 * a + 1], zy[2 * a]}, new float[]{hw[b], zy[2 * b + 1], zy[2 * b]}, new float[]{hw[c], zy[2 * c + 1], zy[2 * c]}, 1, 0, 0, endRgb, endMat);
            triFacing(new float[]{-hw[a], zy[2 * a + 1], zy[2 * a]}, new float[]{-hw[b], zy[2 * b + 1], zy[2 * b]}, new float[]{-hw[c], zy[2 * c + 1], zy[2 * c]}, -1, 0, 0, endRgb, endMat);
        }
    }

    // ================================================================ flat shapes on the ground

    /** A polygon (pairs x, z) raised from y0 to y1: walls (wallMat) and a flat roof (roofMat). */
    void extrudeUp(float[] xz, float y0, float y1, int rgb, float wallMat, int roofRgb, float roofMat) {
        int k = xz.length / 2;
        if (k < 3) return;
        float[] p = xz;
        float ccw = area(p) > 0 ? 1 : -1;
        for (int i = 0; i < k; i++) {
            int j = (i + 1) % k;
            float ax = p[2 * i], az = p[2 * i + 1], bx = p[2 * j], bz = p[2 * j + 1];
            // outward of an edge of a polygon going counter-clockwise in (x, z): (dz, −dx); the other way round: the opposite
            float ox = (bz - az) * ccw, oz = -(bx - ax) * ccw;
            quadFacing(new float[]{ax, y0, az}, new float[]{bx, y0, bz}, new float[]{bx, y1, bz}, new float[]{ax, y1, az}, ox, 0, oz, rgb, wallMat);
        }
        int[] idx = earClip(p);
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t], b = idx[t + 1], c = idx[t + 2];
            triFacing(new float[]{p[2 * a], y1, p[2 * a + 1]}, new float[]{p[2 * b], y1, p[2 * b + 1]}, new float[]{p[2 * c], y1, p[2 * c + 1]}, 0, 1, 0, roofRgb, roofMat);
        }
    }

    /**
     * A band along a line on the ground (pairs x, z): from −left to +right of it (metres, seen going along it), at height
     * y; joints mitred (never more than 3× wide at a sharp corner). dash > 0: only the first dash metres of every
     * dash + gap.
     */
    void ribbon(float[] xz, float left, float right, float y, int rgb, float mat, float dash, float gap) {
        int k = xz.length / 2;
        if (k < 2) return;
        float[] lx = new float[k], lz = new float[k], rx = new float[k], rz = new float[k], along = new float[k];
        for (int i = 0; i < k; i++) {
            float[] nrm = sideAt(xz, i);
            float sc = nrm[2];
            lx[i] = xz[2 * i] - nrm[0] * left * sc; lz[i] = xz[2 * i + 1] - nrm[1] * left * sc;
            rx[i] = xz[2 * i] + nrm[0] * right * sc; rz[i] = xz[2 * i + 1] + nrm[1] * right * sc;
            if (i > 0) along[i] = along[i - 1] + (float) Math.hypot(xz[2 * i] - xz[2 * i - 2], xz[2 * i + 1] - xz[2 * i - 1]);
        }
        for (int i = 0; i + 1 < k; i++) {
            if (dash > 0) { // (a dashed line: the parts of this piece that fall on a dash)
                float s0 = along[i], s1 = along[i + 1], per = dash + gap;
                if (s1 - s0 < 1e-4f) continue;
                for (float d0 = (float) Math.floor(s0 / per) * per; d0 < s1; d0 += per) {
                    float a = Math.max(s0, d0), b = Math.min(s1, d0 + dash);
                    if (b <= a) continue;
                    float ta = (a - s0) / (s1 - s0), tb = (b - s0) / (s1 - s0);
                    piece(lerp(lx[i], lx[i + 1], ta), lerp(lz[i], lz[i + 1], ta), lerp(rx[i], rx[i + 1], ta), lerp(rz[i], rz[i + 1], ta),
                            lerp(lx[i], lx[i + 1], tb), lerp(lz[i], lz[i + 1], tb), lerp(rx[i], rx[i + 1], tb), lerp(rz[i], rz[i + 1], tb), y, rgb, mat);
                }
                continue;
            }
            piece(lx[i], lz[i], rx[i], rz[i], lx[i + 1], lz[i + 1], rx[i + 1], rz[i + 1], y, rgb, mat);
        }
    }

    private void piece(float lx0, float lz0, float rx0, float rz0, float lx1, float lz1, float rx1, float rz1, float y, int rgb, float mat) {
        quadFacing(new float[]{lx0, y, lz0}, new float[]{rx0, y, rz0}, new float[]{rx1, y, rz1}, new float[]{lx1, y, lz1}, 0, 1, 0, rgb, mat);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** The unit vector to the right of the line at point i (x, z), and the mitre's stretch there. Pure. */
    static float[] sideAt(float[] xz, int i) {
        int k = xz.length / 2;
        float dx0, dz0, dx1, dz1;
        if (i > 0) { dx0 = xz[2 * i] - xz[2 * i - 2]; dz0 = xz[2 * i + 1] - xz[2 * i - 1]; } else { dx0 = xz[2] - xz[0]; dz0 = xz[3] - xz[1]; }
        if (i + 1 < k) { dx1 = xz[2 * i + 2] - xz[2 * i]; dz1 = xz[2 * i + 3] - xz[2 * i + 1]; } else { dx1 = dx0; dz1 = dz0; }
        float l0 = (float) Math.hypot(dx0, dz0), l1 = (float) Math.hypot(dx1, dz1);
        if (l0 < 1e-6f) { dx0 = dx1; dz0 = dz1; l0 = l1; }
        if (l1 < 1e-6f) { dx1 = dx0; dz1 = dz0; l1 = l0; }
        if (l0 < 1e-6f) return new float[]{1, 0, 1};
        dx0 /= l0; dz0 /= l0; dx1 /= l1; dz1 /= l1;
        // going along (dx, dz) with x east and z south, the right hand is (−dz, dx)
        float nx0 = -dz0, nz0 = dx0, nx1 = -dz1, nz1 = dx1;
        float mx = nx0 + nx1, mz = nz0 + nz1, ml = (float) Math.hypot(mx, mz);
        if (ml < 1e-6f) return new float[]{nx1, nz1, 1};
        mx /= ml; mz /= ml;
        float cos = mx * nx1 + mz * nz1;
        return new float[]{mx, mz, Math.min(3f, 1f / Math.max(0.334f, cos))};
    }

    // ================================================================ polygons

    /** Twice the signed area of a polygon (pairs a, b): > 0 counter-clockwise in (a, b). Pure. */
    static float area(float[] p) {
        int k = p.length / 2;
        float s = 0;
        for (int i = 0; i < k; i++) { int j = (i + 1) % k; s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]; }
        return s;
    }

    static float[] reverse(float[] p) {
        int k = p.length / 2;
        float[] r = new float[p.length];
        for (int i = 0; i < k; i++) { r[2 * i] = p[2 * (k - 1 - i)]; r[2 * i + 1] = p[2 * (k - 1 - i) + 1]; }
        return r;
    }

    /**
     * Triangles of a simple polygon (pairs a, b; either way round) by cutting off its ears: corner indexes, three per
     * triangle, each counter-clockwise in (a, b) when the polygon is. A broken (self-crossing) one still gets a fan for
     * what is left. Pure.
     */
    static int[] earClip(float[] p) {
        int k = p.length / 2;
        if (k < 3) return new int[0];
        boolean ccw = area(p) > 0;
        java.util.List<Integer> left = new java.util.ArrayList<>();
        for (int i = 0; i < k; i++) left.add(i);
        int[] out = new int[(k - 2) * 3];
        int o = 0, guard = 0;
        while (left.size() > 3 && guard++ < k * k) {
            boolean cut = false;
            for (int i = 0; i < left.size(); i++) {
                int a = left.get((i + left.size() - 1) % left.size()), b = left.get(i), c = left.get((i + 1) % left.size());
                float cr = cross(p, a, b, c);
                if (ccw ? cr <= 1e-9f : cr >= -1e-9f) continue; // (a reflex corner: no ear)
                boolean empty = true;
                for (int q : left) {
                    if (q == a || q == b || q == c) continue;
                    if (inside(p, q, a, b, c)) { empty = false; break; }
                }
                if (!empty) continue;
                if (ccw) { out[o++] = a; out[o++] = b; out[o++] = c; } else { out[o++] = a; out[o++] = c; out[o++] = b; }
                left.remove(i);
                cut = true;
                break;
            }
            if (!cut) break;
        }
        // what is left (three corners, or a broken shape): a fan
        for (int i = 1; i + 1 < left.size() && o + 3 <= out.length; i++) {
            int a = left.get(0), b = left.get(i), c = left.get(i + 1);
            if (ccw || cross(p, a, b, c) > 0) { out[o++] = a; out[o++] = b; out[o++] = c; } else { out[o++] = a; out[o++] = c; out[o++] = b; }
        }
        return o == out.length ? out : java.util.Arrays.copyOf(out, o);
    }

    private static float cross(float[] p, int a, int b, int c) {
        return (p[2 * b] - p[2 * a]) * (p[2 * c + 1] - p[2 * a + 1]) - (p[2 * b + 1] - p[2 * a + 1]) * (p[2 * c] - p[2 * a]);
    }

    private static boolean inside(float[] p, int q, int a, int b, int c) {
        float d1 = sgn(p, q, a, b), d2 = sgn(p, q, b, c), d3 = sgn(p, q, c, a);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    private static float sgn(float[] p, int q, int a, int b) {
        return (p[2 * q] - p[2 * b]) * (p[2 * a + 1] - p[2 * b + 1]) - (p[2 * a] - p[2 * b]) * (p[2 * q + 1] - p[2 * b + 1]);
    }
}
