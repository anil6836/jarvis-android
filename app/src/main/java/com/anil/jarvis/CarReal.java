package com.anil.jarvis;

/**
 * 🚘 ㉑ (31 Oct): our four new "real look" cars for the 3D views (his "కార్లు కూడా మారాలి, రియల్ లుక్"): a coupe, an SUV,
 * a sports car and a sedan — our own shapes (no maker's car, no badge, a blank plate), smooth lofted bodies with wheel
 * arches cut round the tyres, a dark glass house with painted pillars (a black roof when he picks it), multi-spoke rims
 * in his rim colour, a full-width LED bar at the back (his switch), LED lines at the front. The same body maths as the
 * desk samples he saw (made lighter for the box: fewer rows). No Android here: desk-tested and desk-rendered. Sizes in
 * metres; the nose to −z, x right, y up, the ground at y = 0.
 */
final class CarReal {
    private CarReal() {}

    static final String[] KINDS = {"coupe_r", "suv_r", "sports_r", "sedan_r"};
    static final String[] NAMES = {"కూపే (కొత్త రియల్ లుక్)", "SUV (కొత్త రియల్ లుక్)", "స్పోర్ట్స్ (కొత్త రియల్ లుక్)", "సెడాన్ (కొత్త రియల్ లుక్)"};
    /** The rims' colours: silver, black, gold, white. */
    static final String[] RIMS = {"", "black", "gold", "white"};
    static final String[] RIM_NAMES = {"⚪ వెండి", "⚫ నలుపు", "🟡 బంగారు", "🤍 తెలుపు"};
    static final int[] RIM_RGB = {0xC4CCD6, 0x1E2228, 0xC9A24A, 0xE9EDF2};
    /** The roof: his colour, or black. */
    static final String[] ROOFS = {"", "black"};
    static final String[] ROOF_NAMES = {"అదే రంగు", "నలుపు"};

    static boolean is(String kind) { for (String k : KINDS) if (k.equals(kind)) return true; return false; }

    static int rimRgb(String rim) { for (int i = 0; i < RIMS.length; i++) if (RIMS[i].equals(rim)) return RIM_RGB[i]; return RIM_RGB[0]; }

    // ================================================================ smooth profiles

    /** A smooth line through keys {u, value, u, value, …} (Hermite with Catmull-Rom slopes: no flat steps). Pure. */
    static final class Prof {
        final float[] u, v, m;

        Prof(float... k) {
            int n = k.length / 2;
            u = new float[n]; v = new float[n]; m = new float[n];
            for (int i = 0; i < n; i++) { u[i] = k[2 * i]; v[i] = k[2 * i + 1]; }
            for (int i = 0; i < n; i++) {
                if (i == 0) m[i] = (v[1] - v[0]) / (u[1] - u[0]);
                else if (i == n - 1) m[i] = (v[i] - v[i - 1]) / (u[i] - u[i - 1]);
                else m[i] = (v[i + 1] - v[i - 1]) / (u[i + 1] - u[i - 1]);
            }
        }

        float at(float x) {
            int n = u.length;
            if (x <= u[0]) return v[0];
            for (int i = 0; i + 1 < n; i++) {
                if (x <= u[i + 1]) {
                    float h = u[i + 1] - u[i], t = (x - u[i]) / h, t2 = t * t, t3 = t2 * t;
                    return (2 * t3 - 3 * t2 + 1) * v[i] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * v[i + 1] + (t3 - t2) * h * m[i + 1];
                }
            }
            return v[n - 1];
        }
    }

    /** One kind's measurements and profiles (u: 0 the nose → 1 the tail). */
    static final class Kind {
        final float L, W, wheelR, clear, n, tumble, flare, tuck, cabU0, cabU1, cabW, cabTumble, roofEnd;
        final float[] wheels;
        final Prof top, shoulder, width, roof;
        final boolean wing, fourDoors;

        Kind(float L, float W, float wheelR, float[] wheels, float clear, Prof top, Prof shoulder, Prof width, float tuck, float cabU0, float cabU1, Prof roof,
             float cabW, float cabTumble, float roofEnd, float n, float tumble, float flare, boolean wing, boolean fourDoors) {
            this.L = L; this.W = W; this.wheelR = wheelR; this.wheels = wheels; this.clear = clear; this.top = top; this.shoulder = shoulder; this.width = width;
            this.tuck = tuck; this.cabU0 = cabU0; this.cabU1 = cabU1; this.roof = roof; this.cabW = cabW; this.cabTumble = cabTumble; this.roofEnd = roofEnd;
            this.n = n; this.tumble = tumble; this.flare = flare; this.wing = wing; this.fourDoors = fourDoors;
        }
    }

    /** The four kinds (the desk samples' numbers). Unknown: the coupe. Pure. */
    static Kind kind(String k) {
        switch (k == null ? "" : k) {
            case "suv_r": return new Kind(4.6f, 1.98f, 0.39f, new float[]{0.17f, 0.80f}, 0.22f,
                    new Prof(0, 0.86f, 0.08f, 1.0f, 0.28f, 1.1f, 0.7f, 1.14f, 0.95f, 1.12f, 1, 1.06f),
                    new Prof(0, 0.72f, 0.25f, 0.9f, 1, 0.93f),
                    new Prof(0, 0.9f, 0.15f, 1.0f, 0.85f, 1.0f, 1, 0.96f), 0.1f,
                    0.3f, 0.99f, new Prof(0.3f, 1.1f, 0.45f, 1.64f, 0.86f, 1.67f, 0.99f, 1.52f), 0.88f, 0.14f, 0.8f, 8, 0.08f, 0.03f, false, true);
            case "sports_r": return new Kind(4.55f, 2.02f, 0.34f, new float[]{0.16f, 0.79f}, 0.11f,
                    new Prof(0, 0.56f, 0.12f, 0.68f, 0.32f, 0.8f, 0.62f, 0.92f, 0.92f, 0.96f, 1, 0.9f),
                    new Prof(0, 0.5f, 0.16f, 0.66f, 0.4f, 0.63f, 0.79f, 0.8f, 1, 0.76f),
                    new Prof(0, 0.84f, 0.17f, 1.0f, 0.45f, 0.9f, 0.78f, 1.04f, 1, 0.97f), 0.1f,
                    0.32f, 0.84f, new Prof(0.32f, 0.8f, 0.48f, 1.18f, 0.6f, 1.2f, 0.84f, 0.94f), 0.74f, 0.3f, 0.66f, 5.5f, 0.16f, 0.035f, true, false);
            case "sedan_r": return new Kind(4.85f, 1.9f, 0.345f, new float[]{0.17f, 0.8f}, 0.15f,
                    new Prof(0, 0.7f, 0.1f, 0.82f, 0.3f, 0.95f, 0.7f, 1.0f, 0.92f, 1.02f, 1, 0.96f),
                    new Prof(0, 0.62f, 0.25f, 0.8f, 0.75f, 0.84f, 1, 0.82f),
                    new Prof(0, 0.88f, 0.18f, 1.0f, 0.82f, 1.0f, 1, 0.95f), 0.1f,
                    0.3f, 0.84f, new Prof(0.3f, 0.95f, 0.46f, 1.44f, 0.66f, 1.45f, 0.84f, 1.02f), 0.86f, 0.22f, 0.66f, 7, 0.1f, 0.015f, false, true);
            default: return new Kind(4.75f, 1.96f, 0.355f, new float[]{0.165f, 0.80f}, 0.15f,
                    new Prof(0, 0.66f, 0.1f, 0.78f, 0.28f, 0.9f, 0.55f, 0.95f, 0.86f, 0.98f, 1, 0.92f),
                    new Prof(0, 0.58f, 0.2f, 0.74f, 0.6f, 0.79f, 0.9f, 0.86f, 1, 0.84f),
                    new Prof(0, 0.86f, 0.18f, 0.99f, 0.5f, 0.95f, 0.8f, 1.0f, 1, 0.94f), 0.1f,
                    0.3f, 0.93f, new Prof(0.3f, 0.9f, 0.47f, 1.36f, 0.6f, 1.38f, 0.78f, 1.19f, 0.93f, 1.0f), 0.86f, 0.28f, 0.66f, 6.5f, 0.12f, 0.02f, false, false);
        }
    }

    /** For the engine's shadow, lights and camera: length, width, height, wheel radius, wheelbase, clearance, belt, bonnet, boot (the cabin's places roughly). Pure. */
    static Car3D.Size size(String k) {
        Kind q = kind(k);
        float roofTop = 0;
        for (float u = q.cabU0; u <= q.cabU1; u += 0.02f) roofTop = Math.max(roofTop, q.roof.at(u));
        float base = (q.wheels[1] - q.wheels[0]) * q.L;
        return new Car3D.Size(q.L, q.W, roofTop, q.wheelR, base, q.clear, q.top.at(0.5f), q.top.at(0.1f), q.top.at(0.95f), q.cabU0, q.cabU0 + 0.15f, q.cabU1 - 0.12f, q.cabU1);
    }

    // ================================================================ the loft

    /** One section: half width w, bottom yb, shoulder ys (widest), top yt, squareness n, tumble (narrower on top), tuck (narrower below). */
    private static final class Sec { float w, yb, ys, yt, n, tumble, tuck; }

    private interface SecFn { Sec at(float u); }

    /** A wheel arch on the side: centre z, y, radius, how far in the well steps (xw), the lip's flare. */
    private static final class Arch { float z, y, r, xw, flare; }

    /** The loft's points: rings [N+1][M][3], a dark well flag [N+1][M], each ring's u, each point's sin (up / down) and |x| / w. */
    private static final class Rings { float[][][] p; boolean[][] well; float[] u; float[][] sn, ax; int N, M; }

    private static float spow(float v, float p) { return Math.signum(v) * (float) Math.pow(Math.abs(v), p); }

    private static Rings loft(float L, SecFn sec, int N, int M, float r, float rR, float endW, float endT, float endB, float even, Arch[] arches, float bottomFlat) {
        float band = Math.max(0.03f, L / N * 0.7f); // (the arch's snap band: as wide as the rows are apart)
        Rings o = new Rings();
        o.N = N; o.M = M;
        o.p = new float[N + 1][M][3];
        o.well = new boolean[N + 1][M];
        o.u = new float[N + 1];
        o.sn = new float[N + 1][M];
        o.ax = new float[N + 1][M];
        int DS = 360;
        float[] dx = new float[DS + 1], dy = new float[DS + 1], ds = new float[DS + 1], dc = new float[DS + 1], cum = new float[DS + 1];
        for (int i = 0; i <= N; i++) {
            float t = i / (float) N, uc = 0.5f - 0.5f * (float) Math.cos(Math.PI * t), u = uc + (t - uc) * even; // (more sections near both ends)
            float z = -L / 2 + u * L, d = Math.min(z + L / 2, L / 2 - z), re = z > 0 ? rR : r;
            Sec s = sec.at(u);
            float f = d < re ? (float) Math.sqrt(Math.max(0, 1 - Math.pow(1 - d / re, 2))) : 1; // (a quarter circle at the ends)
            float k = 1 - f;
            float w = s.w - k * re * endW, yt = s.yt - k * re * endT, yb = s.yb + k * re * endB;
            float ys = Math.min(Math.max(s.ys, yb + 0.02f), yt - 0.02f);
            float p = 2 / (s.n > 0 ? s.n : 4);
            for (int q = 0; q <= DS; q++) {
                double a = (q / (double) DS) * Math.PI * 2 - Math.PI / 2; // (from the bottom middle)
                float c = (float) Math.cos(a), sn = (float) Math.sin(a);
                float x = w * spow(c, p);
                float y = sn >= 0 ? ys + (yt - ys) * (float) Math.pow(sn, p) : ys + (ys - yb) * spow(sn, p * bottomFlat);
                if (sn > 0) x *= 1 - s.tumble * (float) Math.pow(sn, 1.6);
                else x *= 1 - s.tuck * (float) Math.pow(-sn, 1.5);
                dx[q] = x; dy[q] = y; ds[q] = sn; dc[q] = c;
            }
            cum[0] = 0;
            for (int q = 1; q <= DS; q++) cum[q] = cum[q - 1] + (float) Math.hypot(dx[q] - dx[q - 1], dy[q] - dy[q - 1]);
            float tot = cum[DS] > 0 ? cum[DS] : 1;
            int q = 0;
            for (int j = 0; j < M; j++) { // (even rows: mostly by length, a little by angle)
                float tg = j / (float) M;
                while (q < DS - 1 && meas(cum, tot, q + 1, DS) < tg) q++;
                float m0 = meas(cum, tot, q, DS), m1 = meas(cum, tot, q + 1, DS);
                float f2 = Math.max(0, Math.min(1, (tg - m0) / Math.max(1e-9f, m1 - m0)));
                float x = dx[q] + (dx[q + 1] - dx[q]) * f2, y = dy[q] + (dy[q + 1] - dy[q]) * f2;
                float sn = ds[q] + (ds[q + 1] - ds[q]) * f2, c = dc[q] + (dc[q + 1] - dc[q]) * f2;
                float zz = z, yy = y;
                boolean well = false;
                if (arches != null && sn < 0.2f) for (Arch A : arches) { // (the arches: inside the circle the side steps in to the dark well)
                    float dz = z - A.z, dyy = y - A.y, rr = (float) Math.hypot(dz, dyy);
                    if (rr > A.r) x *= 1 + A.flare * (float) Math.exp(-Math.pow((rr - A.r - 0.07f) / 0.075f, 2));
                    if (Math.abs(x) <= A.xw) continue;
                    boolean wall = Math.abs(c) > 0.35f;
                    if (wall && Math.abs(rr - A.r) < band && rr > 1e-6f) { zz = A.z + dz / rr * A.r; yy = A.y + dyy / rr * A.r; } // (near the edge: onto the circle — a clean curve)
                    if (rr < A.r) { x = Math.signum(x) * A.xw; well = true; }
                }
                o.p[i][j][0] = x; o.p[i][j][1] = yy; o.p[i][j][2] = zz;
                o.well[i][j] = well;
                o.sn[i][j] = sn;
                o.ax[i][j] = Math.abs(x) / Math.max(1e-3f, w);
            }
            o.u[i] = u;
        }
        return o;
    }

    private static float meas(float[] cum, float tot, int q, int DS) { return 0.78f * cum[q] / tot + 0.22f * q / (float) DS; }

    /** Smooth normals of a loft (each point: the faces round it), outward. */
    private static float[][][] normals(Rings o) {
        int N = o.N, M = o.M;
        float[][][] nr = new float[N + 1][M][3], all = new float[N + 1][M][3];
        for (int i = 0; i < N; i++) for (int j = 0; j < M; j++) {
            int j1 = (j + 1) % M;
            float[] a = o.p[i][j], b = o.p[i][j1], c = o.p[i + 1][j];
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
            float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            boolean wellFace = o.well[i][j] && o.well[i][j1] && o.well[i + 1][j] && o.well[i + 1][j1]; // (as emit() colours it)
            for (int[] k : new int[][]{{i, j}, {i, j1}, {i + 1, j}, {i + 1, j1}}) {
                all[k[0]][k[1]][0] += nx; all[k[0]][k[1]][1] += ny; all[k[0]][k[1]][2] += nz;
                if (o.well[k[0]][k[1]] != wellFace) continue; // (the arch's step: the paint's light never mixed with the well's)
                nr[k[0]][k[1]][0] += nx; nr[k[0]][k[1]][1] += ny; nr[k[0]][k[1]][2] += nz;
            }
        }
        for (int i = 0; i <= N; i++) for (int j = 0; j < M; j++) { // (a corner with no face of its own part: all its faces)
            float[] v = nr[i][j];
            if (v[0] * v[0] + v[1] * v[1] + v[2] * v[2] < 1e-18f) { v[0] = all[i][j][0]; v[1] = all[i][j][1]; v[2] = all[i][j][2]; }
        }
        // outward: the top middle's normal points up (else every one turned round; the ring starts at the bottom middle)
        float flip = nr[N / 2][M / 2][1] < 0 ? -1 : 1;
        for (int i = 0; i <= N; i++) for (int j = 0; j < M; j++) {
            float[] v = nr[i][j];
            float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            if (l < 1e-12f) { v[1] = 1; continue; }
            v[0] = v[0] / l * flip; v[1] = v[1] / l * flip; v[2] = v[2] / l * flip;
        }
        return nr;
    }

    /** Which part a corner is: the paint, the dark trim, the black well, the glass. */
    private interface Paint { int at(int i, int j); }
    static final int P_PAINT = 0, P_TRIM = 1, P_WELL = 2, P_GLASS = 3, P_ROOF = 4;

    private static void emit(Mesh3D m, Rings o, float[][][] nr, Paint part, boolean blackRoof) {
        int N = o.N, M = o.M;
        for (int i = 0; i < N; i++) for (int j = 0; j < M; j++) {
            int j1 = (j + 1) % M;
            int[][] q = {{i, j}, {i, j1}, {i + 1, j}, {i + 1, j1}};
            int pa = part.at(i, j), pb = part.at(i, j1), pc = part.at(i + 1, j), pd = part.at(i + 1, j1);
            int kind = pa == P_WELL && pb == P_WELL && pc == P_WELL && pd == P_WELL ? P_WELL // (only the well itself dark: its edge then follows the arch's curve)
                    : pa != P_WELL ? pa : pb != P_WELL ? pb : pc != P_WELL ? pc : pd;
            tri(m, o, nr, q[0], q[1], q[2], kind, blackRoof);
            tri(m, o, nr, q[1], q[3], q[2], kind, blackRoof);
        }
        for (int e = 0; e < 2; e++) { // the end caps (a fan to the ring's middle)
            int i = e == 0 ? 0 : N;
            float cx = 0, cy = 0, cz = 0;
            for (int j = 0; j < M; j++) { cx += o.p[i][j][0]; cy += o.p[i][j][1]; cz += o.p[i][j][2]; }
            float[] c = {cx / M, cy / M, cz / M}, n = {0, 0, e == 0 ? -1 : 1};
            for (int j = 0; j < M; j++) {
                int kind = part.at(i, j);
                m.triN(c, n, o.p[i][j], n, o.p[i][(j + 1) % M], n, rgb(kind, blackRoof), mat(kind, blackRoof));
            }
        }
    }

    private static void tri(Mesh3D m, Rings o, float[][][] nr, int[] a, int[] b, int[] c, int kind, boolean blackRoof) {
        m.triN(o.p[a[0]][a[1]], nr[a[0]][a[1]], o.p[b[0]][b[1]], nr[b[0]][b[1]], o.p[c[0]][c[1]], nr[c[0]][c[1]], rgb(kind, blackRoof), mat(kind, blackRoof));
    }

    private static int rgb(int kind, boolean blackRoof) {
        switch (kind) {
            case P_TRIM: return 0x15181D;
            case P_WELL: return 0x060607;
            case P_GLASS: return 0x0B1018;
            case P_ROOF: return blackRoof ? 0x0B0D11 : 0;
            default: return 0;
        }
    }

    private static float mat(int kind, boolean blackRoof) {
        switch (kind) {
            case P_TRIM: return Mesh3D.M_CHROME;
            case P_WELL: return Mesh3D.M_MATTE;
            case P_GLASS: return Mesh3D.M_GLASS;
            case P_ROOF: return blackRoof ? Mesh3D.M_GLASS : Mesh3D.M_PAINT;
            default: return Mesh3D.M_PAINT;
        }
    }

    // ================================================================ the car

    /** One of our four (unknown: the coupe); roof "" / "black", rim one of RIMS, the LED bar at the back. Pure. */
    static Mesh3D build(String kindName, String roof, String rim, boolean ledBar) {
        final Kind k = kind(kindName);
        final boolean black = "black".equals(roof);
        Mesh3D m = new Mesh3D();
        final float L = k.L, W = k.W, R = k.wheelR, wW = 0.27f;
        // where the wheels sit: the tyre's face flush with the side, the arch cut round it
        Arch[] arches = new Arch[k.wheels.length];
        for (int i = 0; i < arches.length; i++) {
            Arch a = new Arch();
            float u = k.wheels[i];
            a.z = -L / 2 + u * L; a.y = R; a.r = R + 0.055f; a.xw = wx(k, u) - wW / 2 - 0.05f; a.flare = k.flare;
            arches[i] = a;
        }
        // ---- the body: his paint; the sills, the lower bumpers and the wells dark
        SecFn body = u -> { Sec s = new Sec(); s.w = W / 2 * k.width.at(u); s.yb = k.clear + 0.1f; s.ys = k.shoulder.at(u); s.yt = k.top.at(u); s.n = k.n; s.tumble = k.tumble; s.tuck = k.tuck; return s; };
        final Rings b = loft(L, body, 96, 48, 0.26f, 0.13f, 0.75f, 0.5f, 0.3f, 0.6f, arches, 0.6f);
        emit(m, b, normals(b), (i, j) -> {
            float u = b.u[i], y = b.p[i][j][1];
            if (b.well[i][j]) return P_WELL;
            if (y < k.clear + 0.17f || (u > 0.955f && y < k.clear + 0.4f) || (u < 0.035f && y < k.clear + 0.28f)) return P_TRIM;
            return P_PAINT;
        }, black);
        // ---- the glass house: windows dark, the roof and the A / B / C pillars painted (black roof: his pick)
        final float cL = (k.cabU1 - k.cabU0) * L, cz0 = -L / 2 + k.cabU0 * L;
        SecFn cab = v -> {
            float u = k.cabU0 + v * (k.cabU1 - k.cabU0), belt = k.top.at(u) - 0.04f;
            Sec s = new Sec();
            s.w = W / 2 * k.width.at(u) * (1 - k.tumble) * k.cabW / 0.84f; s.yb = belt - 0.22f; s.ys = belt + 0.02f; s.yt = Math.max(belt + 0.04f, k.roof.at(u));
            s.n = 7; s.tumble = k.cabTumble; s.tuck = 0;
            return s;
        };
        final Rings c = loft(cL, cab, 34, 36, 0.16f, 0.16f, 0.6f, 0, 0, 0, null, 0.6f);
        for (float[][] ring : c.p) for (float[] p : ring) p[2] += cz0 + cL / 2;
        emit(m, c, normals(c), (i, j) -> {
            float v = c.u[i], sn = c.sn[i][j], ax = c.ax[i][j];
            boolean side = sn < 0.9f;
            if (sn > 0.9f && v > 0.2f && v < k.roofEnd) return P_ROOF;
            if (v > 0.84f) return ax < 0.62f ? P_GLASS : P_ROOF;
            if (v < 0.06f) return ax < 0.72f ? P_GLASS : P_ROOF;
            boolean aP = side && v < 0.13f && ax > 0.6f, bP = side && v > 0.45f && v < 0.485f && ax > 0.64f, cP = side && v > 0.7f && ax > 0.7f;
            return aP || bP || cP ? P_ROOF : P_GLASS;
        }, black);
        // ---- the wheels, flush in their arches
        int rimC = rimRgb(rim);
        for (float u : k.wheels) for (int sd = -1; sd <= 1; sd += 2) wheel(m, sd * wx(k, u), R, -L / 2 + u * L, R, wW, sd, rimC);
        // ---- lights: a full-width LED line at the back (his switch) and the corner lamps; LED lines at the front; a blank plate
        float zr = L / 2, zf = -L / 2;
        float yT = k.top.at(0.97f) - 0.13f, wr = W / 2 * k.width.at(1);
        float headY = k.top.at(0.035f) - 0.1f;
        int ti = ringSpanning(b, yT, true), hi = ringSpanning(b, headY, false);
        float tailZ = surfaceZ(b, ti, yT, true) + 0.006f, headZ = surfaceZ(b, hi, headY, false) - 0.004f; // (on the body at that very height)
        float wf = W / 2 * k.width.at(0);
        float tailW = Math.max(0.3f, halfWidthAt(b, ti, yT) * 0.96f), headW = Math.max(0.3f, halfWidthAt(b, hi, headY) * 0.96f);
        m.box(0, yT - 0.03f, tailZ - 0.004f, tailW * 2, 0.1f, 0.012f, 0x120306, Mesh3D.M_GLASS); // (a smoked lens band across the back)
        if (ledBar) m.box(0, yT, tailZ, tailW * 2, 0.016f, 0.01f, 0xD0121B, Mesh3D.M_TAIL);
        for (int sd = -1; sd <= 1; sd += 2) {
            m.box(sd * tailW * 0.8f, yT - 0.054f, tailZ, tailW * 0.38f, 0.014f, 0.01f, 0xD0121B, Mesh3D.M_TAIL);
            m.box(sd * tailW * 0.96f, yT - 0.03f, tailZ, tailW * 0.1f, 0.07f, 0.01f, 0xD0121B, Mesh3D.M_TAIL);
            m.box(sd * tailW * 0.98f, yT - 0.085f, tailZ, tailW * 0.06f, 0.025f, 0.01f, 0xFFB300, sd < 0 ? Mesh3D.M_IND_L : Mesh3D.M_IND_R);
            m.box(sd * headW * 0.82f, headY - 0.03f, headZ, headW * 0.34f, 0.035f, 0.01f, 0x9FB4C8, Mesh3D.M_HEAD); // (the lamps under the line)
            m.box(sd * headW * 0.98f, headY - 0.03f, headZ, headW * 0.05f, 0.03f, 0.01f, 0xFFB300, sd < 0 ? Mesh3D.M_IND_L : Mesh3D.M_IND_R);
        }
        m.box(0, headY, headZ, headW * 2, 0.016f, 0.01f, 0xFFFFFF, Mesh3D.M_HEAD); // (the front LED line)
        float gy = k.clear + 0.23f;
        m.box(0, gy, edgeZ(b, gy, 0.08f, false) - 0.004f, wf * 1.0f, 0.14f, 0.01f, 0x07080A, Mesh3D.M_MATTE); // (the grille)
        float py = k.clear + 0.4f;
        m.box(0, py, edgeZ(b, py, 0.07f, true) + 0.008f, 0.5f, 0.12f, 0.012f, 0x1B1E23, Mesh3D.M_MATTE); // (a blank plate: no number, no name)
        // ---- mirrors, door cuts, handles
        float um = k.cabU0 + 0.045f, xm = W / 2 * k.width.at(um) * (1 - k.tumble * 0.7f), zm = -L / 2 + um * L;
        for (int sd = -1; sd <= 1; sd += 2) {
            m.box(sd * (xm + 0.06f), k.top.at(um) + 0.05f, zm, 0.2f, 0.1f, 0.1f, 0, Mesh3D.M_PAINT);
            m.box(sd * xm, k.top.at(um) + 0.03f, zm + 0.01f, 0.09f, 0.025f, 0.05f, 0x15181D, Mesh3D.M_CHROME);
        }
        doors(m, b, k);
        if (k.wing) { // the sports car's wing
            m.box(0, k.top.at(0.95f) + 0.2f, zr - 0.3f, W * 0.8f, 0.035f, 0.24f, 0x15181D, Mesh3D.M_CHROME);
            for (int sd = -1; sd <= 1; sd += 2) m.box(sd * 0.42f, k.top.at(0.95f) + 0.08f, zr - 0.3f, 0.06f, 0.24f, 0.14f, 0x15181D, Mesh3D.M_CHROME);
        }
        return m;
    }

    /**
     * 💠 The neon finish on our new cars: a line of light along each side's shoulder (the body's widest line, so it lies
     * on the paint), front wheel to back. Pure.
     */
    static Mesh3D neon(String kindName) {
        Kind k = kind(kindName);
        Mesh3D m = new Mesh3D();
        int seg = 18;
        for (int sd = -1; sd <= 1; sd += 2) for (int i = 0; i < seg; i++) {
            float u0 = 0.1f + 0.8f * i / seg, u1 = 0.1f + 0.8f * (i + 1) / seg, um = (u0 + u1) / 2;
            float x = sd * (k.W / 2 * k.width.at(um) + 0.008f), y = k.shoulder.at(um), z0 = -k.L / 2 + u0 * k.L, z1 = -k.L / 2 + u1 * k.L;
            m.box(x, y, (z0 + z1) / 2, 0.012f, 0.02f, z1 - z0 + 0.004f, 0, Mesh3D.M_NEON);
        }
        return m;
    }

    /** The wheels' x: the tyre's outer face flush with the side. */
    private static float wx(Kind k, float u) { return k.W / 2 * k.width.at(u) * (1 - k.tuck * 0.45f) - 0.27f / 2 - 0.015f; }

    /** The ring farthest back (or forward) that reaches height y: where the body's real surface is at that height (its lights go there). */
    private static int ringSpanning(Rings b, float y, boolean back) {
        for (int s = 0; s <= b.N; s++) {
            int i = back ? b.N - s : s;
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int j = 0; j < b.M; j++) { lo = Math.min(lo, b.p[i][j][1]); hi = Math.max(hi, b.p[i][j][1]); }
            if (y >= lo && y <= hi) return i;
        }
        return back ? b.N : 0;
    }

    /** The body's last (or first) z round height y (within dy, near the middle): the lights stand just outside it, never buried in a curve. */
    private static float edgeZ(Rings b, float y, float dy, boolean back) {
        float best = back ? -Float.MAX_VALUE : Float.MAX_VALUE;
        for (int i = 0; i <= b.N; i++) for (int j = 0; j < b.M; j++) {
            float[] p = b.p[i][j];
            if (Math.abs(p[1] - y) > dy) continue;
            best = back ? Math.max(best, p[2]) : Math.min(best, p[2]);
        }
        return Math.abs(best) > 1e6f ? (back ? b.p[b.N][0][2] : b.p[0][0][2]) : best;
    }

    /**
     * The body's real front (or back) at height y: between ring i (the outermost reaching y) and the next one out (which
     * does not), where its top crosses y. Pure.
     */
    private static float surfaceZ(Rings b, int i, float y, boolean back) {
        int o = back ? i + 1 : i - 1;
        float zi = b.p[i][b.M / 2][2];
        if (o < 0 || o > b.N) return zi;
        float ti = top(b, i), to = top(b, o), zo = b.p[o][b.M / 2][2];
        if (ti - to < 1e-4f || y <= to) return zi;
        float f = Math.max(0, Math.min(1, (y - to) / (ti - to)));
        return zo + (zi - zo) * f;
    }

    private static float top(Rings b, int i) {
        float t = -Float.MAX_VALUE;
        for (int j = 0; j < b.M; j++) t = Math.max(t, b.p[i][j][1]);
        return t;
    }

    /** The body's half width at height y on ring i. */
    private static float halfWidthAt(Rings b, int i, float y) {
        float best = 0;
        for (int j = 0; j < b.M; j++) {
            float[] p = b.p[i][j], q = b.p[i][(j + 1) % b.M];
            if ((p[1] - y) * (q[1] - y) <= 0) best = Math.max(best, Math.abs(p[0] + (q[0] - p[0]) * (y - p[1]) / (q[1] - p[1] == 0 ? 1 : q[1] - p[1])));
        }
        return best > 0 ? best : 0.6f;
    }

    /** The doors' cuts on both sides (thin dark lines on the body's side) and a handle on each door. */
    private static void doors(Mesh3D m, Rings b, Kind k) {
        float L = k.L, R = k.wheelR;
        float zF = -L / 2 + k.wheels[0] * L, zR = -L / 2 + k.wheels[1] * L;
        float uB = k.cabU0 + 0.465f * (k.cabU1 - k.cabU0), zB = -L / 2 + uB * L;
        float[] cuts = k.fourDoors ? new float[]{zF + R + 0.16f, zB, zR - R - 0.06f} : new float[]{zF + R + 0.16f, zB + 0.55f};
        float sill = k.clear + 0.2f;
        for (int sd = -1; sd <= 1; sd += 2) {
            for (float zc : cuts) {
                int i = ringAt(b, zc);
                float belt = k.top.at(b.u[i]) - 0.055f;
                for (int j = 0; j < b.M; j++) {
                    float[] p = b.p[i][j], q = b.p[i][(j + 1) % b.M];
                    if (Math.signum(p[0]) != sd || Math.signum(q[0]) != sd || b.well[i][j]) continue;
                    float y0 = Math.min(p[1], q[1]), y1 = Math.max(p[1], q[1]);
                    if (y1 < sill || y0 > belt) continue;
                    float ya = Math.max(y0, sill), yb = Math.min(y1, belt);
                    float xa = p[0] + (q[0] - p[0]) * ((ya - p[1]) / (q[1] - p[1] == 0 ? 1 : q[1] - p[1])), xb = p[0] + (q[0] - p[0]) * ((yb - p[1]) / (q[1] - p[1] == 0 ? 1 : q[1] - p[1]));
                    float o = sd * 0.004f;
                    m.quadFacing(new float[]{xa + o, ya, zc - 0.004f}, new float[]{xa + o, ya, zc + 0.004f}, new float[]{xb + o, yb, zc + 0.004f}, new float[]{xb + o, yb, zc - 0.004f},
                            sd, 0, 0, 0x050608, Mesh3D.M_MATTE);
                }
            }
            for (int d = 0; d + 1 < cuts.length; d++) { // (a handle near each door's back edge, at the shoulder)
                float zc = cuts[d + 1] - 0.2f;
                int i = ringAt(b, zc);
                float yc = k.shoulder.at(b.u[i]) - 0.02f, xc = sideX(b, i, yc, sd);
                m.box(xc + sd * 0.006f, yc, zc, 0.012f, 0.024f, 0.17f, 0x1A1D22, Mesh3D.M_CHROME);
            }
        }
    }

    private static int ringAt(Rings b, float z) {
        int best = 0;
        for (int i = 0; i <= b.N; i++) if (Math.abs(b.p[i][0][2] - z) < Math.abs(b.p[best][0][2] - z)) best = i;
        return best;
    }

    /** The side's x at height y on ring i, the right (sd 1) or the left (−1). */
    private static float sideX(Rings b, int i, float y, int sd) {
        float best = 0;
        for (int j = 0; j < b.M; j++) {
            float[] p = b.p[i][j], q = b.p[i][(j + 1) % b.M];
            if (Math.signum(p[0]) != sd || (p[1] - y) * (q[1] - y) > 0) continue;
            float x = p[0] + (q[0] - p[0]) * ((y - p[1]) / (q[1] - p[1] == 0 ? 1 : q[1] - p[1]));
            if (Math.abs(x) > Math.abs(best)) best = x;
        }
        return best;
    }

    /** A wheel: a rounded tyre turned round its axle, a ten-spoke rim on its outer face, a dark hub behind it. */
    private static void wheel(Mesh3D m, float cx, float cy, float cz, float R, float width, int sd, int rimRgb) {
        int seg = 28, prof = 8;
        float hw = width / 2, rr = R * 0.72f;
        // the tyre: a rounded profile (radius, x) round the axle (x)
        float[][] pr = new float[prof + 3][2];
        pr[0] = new float[]{rr, -hw * 0.9f};
        for (int i = 0; i <= prof; i++) { double a = -Math.PI / 2 + Math.PI * i / prof; pr[i + 1] = new float[]{R - 0.06f + (float) Math.cos(a) * 0.06f, (float) Math.sin(a) * hw}; }
        pr[prof + 2] = new float[]{rr, hw * 0.9f};
        for (int s = 0; s < seg; s++) {
            double a0 = 2 * Math.PI * s / seg, a1 = 2 * Math.PI * (s + 1) / seg;
            for (int i = 0; i + 1 < pr.length; i++) {
                float[] p0 = pr[i], p1 = pr[i + 1];
                float[] A = tyreAt(cx, cy, cz, p0, a0), B = tyreAt(cx, cy, cz, p0, a1), C = tyreAt(cx, cy, cz, p1, a1), D = tyreAt(cx, cy, cz, p1, a0);
                float[] nA = tyreN(p0, pr, i, a0), nB = tyreN(p0, pr, i, a1), nC = tyreN(p1, pr, i + 1, a1), nD = tyreN(p1, pr, i + 1, a0);
                m.triN(A, nA, B, nB, C, nC, 0x121316, Mesh3D.M_MATTE);
                m.triN(A, nA, C, nC, D, nD, 0x121316, Mesh3D.M_MATTE);
            }
        }
        float face = cx + sd * hw * 0.62f;
        m.discX(face - sd * 0.06f, cy, cz, rr * 0.96f, seg, sd, 0x0B0D10, Mesh3D.M_MATTE); // (the dark hub inside)
        // the rim: its lip, ten spokes, the cap
        int spokes = 10;
        for (int s = 0; s < seg; s++) {
            double a0 = 2 * Math.PI * s / seg, a1 = 2 * Math.PI * (s + 1) / seg;
            float r0 = rr * 0.86f, r1 = rr;
            m.quadFacing(new float[]{face, cy + r0 * (float) Math.sin(a0), cz + r0 * (float) Math.cos(a0)}, new float[]{face, cy + r1 * (float) Math.sin(a0), cz + r1 * (float) Math.cos(a0)},
                    new float[]{face, cy + r1 * (float) Math.sin(a1), cz + r1 * (float) Math.cos(a1)}, new float[]{face, cy + r0 * (float) Math.sin(a1), cz + r0 * (float) Math.cos(a1)},
                    sd, 0, 0, rimRgb, Mesh3D.M_CHROME);
        }
        for (int s = 0; s < spokes; s++) {
            double a = 2 * Math.PI * s / spokes + 0.2;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a), cp = (float) Math.cos(a + Math.PI / 2), sp = (float) Math.sin(a + Math.PI / 2);
            float wIn = rr * 0.07f, wOut = rr * 0.11f, rIn = rr * 0.3f, rOut = rr * 0.88f, x = face + sd * 0.004f;
            m.quadFacing(new float[]{x, cy + sa * rIn + sp * wIn, cz + ca * rIn + cp * wIn}, new float[]{x, cy + sa * rOut + sp * wOut, cz + ca * rOut + cp * wOut},
                    new float[]{x, cy + sa * rOut - sp * wOut, cz + ca * rOut - cp * wOut}, new float[]{x, cy + sa * rIn - sp * wIn, cz + ca * rIn - cp * wIn},
                    sd, 0, 0, rimRgb, Mesh3D.M_CHROME);
        }
        m.discX(face + sd * 0.006f, cy, cz, rr * 0.32f, 18, sd, rimRgb, Mesh3D.M_CHROME);
        m.discX(face + sd * 0.008f, cy, cz, rr * 0.12f, 12, sd, 0x2A2F36, Mesh3D.M_MATTE);
    }

    private static float[] tyreAt(float cx, float cy, float cz, float[] p, double a) {
        return new float[]{cx + p[1], cy + p[0] * (float) Math.sin(a), cz + p[0] * (float) Math.cos(a)};
    }

    /** The tyre's normal at a profile point (the profile's own slope turned round the axle). */
    private static float[] tyreN(float[] p, float[][] pr, int i, double a) {
        float[] q0 = pr[Math.max(0, i - 1)], q1 = pr[Math.min(pr.length - 1, i + 1)];
        float dr = q1[0] - q0[0], dx = q1[1] - q0[1];
        float nr = dx, nx = -dr; // (perpendicular to the profile, pointing out)
        float l = (float) Math.sqrt(nr * nr + nx * nx);
        if (l < 1e-9f) { nr = 1; nx = 0; l = 1; }
        nr /= l; nx /= l; // (perpendicular to the profile: already pointing out)
        return new float[]{nx, nr * (float) Math.sin(a), nr * (float) Math.cos(a)};
    }
}
