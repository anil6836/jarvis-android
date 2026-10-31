package com.anil.jarvis;

/**
 * 🚗 Stage 3 (27 Oct): our own six 3D cars for the car screen's 3D view (no Android here: desk-tested, desk-rendered).
 * Generic shapes of our own — a compact SUV, a sedan, a hatchback, a sports coupe, an off-road jeep and a future
 * concept — never any maker's car or badge (his own car comes only as his own photos: {@link CarPhotos3D}). Sizes in
 * metres; the nose points to −z, x is the car's right, y up, the ground at y = 0. The paint is drawn in his colour
 * (the shader's), the lights light up with the brakes, the indicators and the night.
 */
final class Car3D {
    private Car3D() {}

    static final String[] KINDS = {"suv", "sedan", "hatch", "sports", "jeep", "concept"};
    static final String[] NAMES = {"కాంపాక్ట్ SUV", "సెడాన్", "హ్యాచ్‌బ్యాక్", "స్పోర్ట్స్ కూపే", "ఆఫ్-రోడ్ జీప్", "ఫ్యూచర్ కాన్సెప్ట్"};
    /** The paints' finish: glossy, matte, neon edges. */
    static final String[] FINISHES = {"", "matte", "neon"};
    static final String[] FINISH_NAMES = {"✨ మెరిసే", "🪨 మ్యాట్", "💠 నియాన్ అంచులు"};
    /** Headlights: white or yellow; the glow under the car: off, blue, pink. */
    static final String[] HEADS = {"", "yellow"};
    static final String[] HEAD_NAMES = {"⚪ తెల్ల హెడ్‌లైట్", "🟡 పసుపు హెడ్‌లైట్"};
    static final String[] GLOWS = {"", "blue", "pink"};
    static final String[] GLOW_NAMES = {"కింద వెలుగు: ఆఫ్", "🔵 నీలం", "🩷 గులాబీ"};

    /** One of our cars: the six, or the four new real-look ones (㉑). Pure. */
    static boolean valid(String k) { return kindIndex(k) >= 0 || CarReal.is(k); }

    static int kindIndex(String k) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(k)) return i;
        return -1;
    }

    /** One car's measurements: length, width, height, wheel radius, wheelbase, ground clearance, belt line, the cabin's four places along it. */
    static final class Size {
        final float l, w, h, r, base, clear, belt, hood, boot, wsBase, roofF, roofR, rwBase;
        Size(float l, float w, float h, float r, float base, float clear, float belt, float hood, float boot, float wsBase, float roofF, float roofR, float rwBase) {
            this.l = l; this.w = w; this.h = h; this.r = r; this.base = base; this.clear = clear; this.belt = belt; this.hood = hood; this.boot = boot;
            this.wsBase = wsBase; this.roofF = roofF; this.roofR = roofR; this.rwBase = rwBase;
        }
    }

    /** The measurements of each kind (the cabin's places as parts of the length from the nose). Pure. */
    static Size size(String kind) {
        if (CarReal.is(kind)) return CarReal.size(kind);
        switch (kind == null ? "" : kind) {
            case "sedan":   return new Size(4.40f, 1.76f, 1.46f, 0.31f, 2.62f, 0.16f, 0.84f, 0.80f, 0.86f, 0.30f, 0.43f, 0.68f, 0.82f);
            case "hatch":   return new Size(3.85f, 1.72f, 1.50f, 0.30f, 2.45f, 0.16f, 0.86f, 0.80f, 0.92f, 0.28f, 0.42f, 0.86f, 0.97f);
            case "sports":  return new Size(4.40f, 1.90f, 1.22f, 0.34f, 2.60f, 0.12f, 0.74f, 0.66f, 0.80f, 0.36f, 0.52f, 0.68f, 0.88f);
            case "jeep":    return new Size(3.95f, 1.84f, 1.86f, 0.39f, 2.40f, 0.30f, 1.12f, 1.08f, 1.10f, 0.30f, 0.33f, 0.92f, 0.97f);
            case "concept": return new Size(4.50f, 1.96f, 1.34f, 0.36f, 2.80f, 0.14f, 0.80f, 0.72f, 0.86f, 0.30f, 0.48f, 0.74f, 0.92f);
            default:        return new Size(4.05f, 1.80f, 1.62f, 0.34f, 2.50f, 0.22f, 0.98f, 0.94f, 1.02f, 0.29f, 0.40f, 0.88f, 0.96f); // suv
        }
    }

    // colours of the parts that are never his paint
    static final int TYRE = 0x1B1D21, RIM = 0xC9CED6, HUB = 0x55606B, GRILLE = 0x15181D, PLATE = 0xEEF1F4, ARCH = 0x22262C, TRIM = 0x2A2F36, RAIL = 0xA8B0BA;

    /** One of our cars (unknown kind: the SUV). */
    static Mesh3D build(String kind) { return build(kind, "", "", true); }

    /** The same with ㉑'s parts: the roof ("" / "black": the new cars), the rims' colour (CarReal.RIMS), the LED bar at the back (the new cars). */
    static Mesh3D build(String kind, String roof, String rim, boolean ledBar) {
        if (CarReal.is(kind)) return CarReal.build(kind, roof, rim, ledBar);
        int rimRgb = rim == null || rim.isEmpty() ? RIM : CarReal.rimRgb(rim);
        String k = kindIndex(kind) < 0 ? "suv" : kind;
        Size s = size(k);
        Mesh3D m = new Mesh3D();
        float l2 = s.l / 2, w2 = s.w / 2;
        float zf = -l2, zr = l2;
        // ---- the lower body: nose, bonnet to the windscreen, along the belt, the boot, the tail
        float zWs = zf + s.l * s.wsBase, zRw = zf + s.l * s.rwBase;
        float noseTop = s.clear + (s.hood - s.clear) * 0.86f, tailTop = s.clear + (s.boot - s.clear) * 0.9f;
        float[] lower;
        if ("jeep".equals(k)) {
            lower = new float[]{zf, s.clear, zf, s.hood, zWs, s.belt, zRw, s.belt, zr, s.boot, zr, s.clear};
        } else if ("sports".equals(k) || "concept".equals(k)) {
            lower = new float[]{zf + 0.05f, s.clear, zf, noseTop * 0.92f, zf + 0.35f, s.hood, zWs, s.belt, zRw, s.belt, zr - 0.12f, s.boot, zr, tailTop, zr - 0.03f, s.clear};
        } else {
            lower = new float[]{zf + 0.06f, s.clear, zf, noseTop, zf + 0.22f, s.hood, zWs, s.belt, zRw, s.belt, zr - 0.18f, s.boot, zr, tailTop, zr - 0.04f, s.clear};
        }
        m.loftX(lower, w2, "jeep".equals(k) ? 0.97f : 0.9f, 0, Mesh3D.M_PAINT, null, 0, Mesh3D.M_PAINT);
        // ---- the cabin: the windscreen, the roof, the back window (glass), its sides glass with the pillars painted
        float zRf = zf + s.l * s.roofF, zRr = zf + s.l * s.roofR;
        float[] cabin = {zWs, s.belt, zRf, s.h, zRr, s.h, zRw, s.belt};
        float[] cm = {Mesh3D.M_GLASS, Mesh3D.M_PAINT, Mesh3D.M_GLASS, Mesh3D.M_GLASS};
        float cw = w2 * ("jeep".equals(k) ? 0.95f : 0.88f);
        m.loftX(cabin, cw, "jeep".equals(k) ? 0.96f : 0.86f, 0, Mesh3D.M_PAINT, cm, 0x0B1018, Mesh3D.M_GLASS);
        // pillars (A, B, C) over the side glass, a little out from it
        float pillarX = cw * 0.97f + 0.004f;
        float midZ = (zRf + zRr) / 2;
        for (int sd = -1; sd <= 1; sd += 2) {
            pillar(m, sd * pillarX, s.belt, s.h, midZ, 0.07f);
        }
        // ---- wheels: tyres, rims and hubs, dark arches over them
        float zw1 = -s.base / 2, zw2 = s.base / 2;
        float tw = "sports".equals(k) || "jeep".equals(k) ? 0.27f : 0.22f;
        for (float zw : new float[]{zw1, zw2}) {
            for (int sd = -1; sd <= 1; sd += 2) {
                float xo = sd * (w2 - tw / 2 + 0.03f);
                m.wheelX(xo, s.r, zw, s.r, tw, 18, TYRE, Mesh3D.M_MATTE);
                float face = xo + sd * (tw / 2 + 0.004f);
                m.discX(face, s.r, zw, s.r * 0.66f, 18, sd, rimRgb, Mesh3D.M_CHROME);
                m.discX(face + sd * 0.002f, s.r, zw, s.r * 0.22f, 10, sd, HUB, Mesh3D.M_MATTE);
                spokes(m, face + sd * 0.003f, s.r, zw, s.r * 0.62f, sd);
                // the dark arch, never above the body's edge over the wheel (a low car's tight arches: none)
                float r1 = Math.min(s.r + ("jeep".equals(k) ? 0.16f : 0.09f), topAt(lower, zw) - 0.04f - s.r);
                if (r1 > s.r + 0.04f) arch(m, sd * (w2 + 0.006f), s.r, zw, s.r + 0.02f, r1, sd);
            }
        }
        // ---- lights, grille, plates, mirrors
        float headY = noseTop - 0.06f;
        if ("jeep".equals(k)) headY = s.hood - 0.16f;
        float hx = w2 - ("jeep".equals(k) ? 0.30f : 0.26f);
        for (int sd = -1; sd <= 1; sd += 2) {
            if ("jeep".equals(k)) roundLight(m, sd * hx, headY, zf - 0.006f, 0.09f);
            else m.box(sd * hx, headY, zf - 0.004f, 0.36f, "sports".equals(k) || "concept".equals(k) ? 0.05f : 0.08f, 0.02f, 0xFFFFFF, Mesh3D.M_HEAD);
            m.box(sd * (w2 - 0.06f), headY - 0.02f, zf + 0.004f, 0.06f, 0.05f, 0.04f, 0xFFB300, sd < 0 ? Mesh3D.M_IND_L : Mesh3D.M_IND_R);
            float tailY = tailTop - 0.08f;
            m.box(sd * (w2 - 0.22f), tailY, zr + 0.004f, 0.32f, 0.08f, 0.02f, 0xD0121B, Mesh3D.M_TAIL);
            m.box(sd * (w2 - 0.05f), tailY, zr - 0.004f, 0.05f, 0.06f, 0.04f, 0xFFB300, sd < 0 ? Mesh3D.M_IND_L : Mesh3D.M_IND_R);
            // mirrors at the windscreen's foot
            m.box(sd * (cw + 0.09f), s.belt + 0.06f, zWs + 0.12f, 0.14f, 0.09f, 0.12f, 0, Mesh3D.M_PAINT);
        }
        if ("concept".equals(k)) { // one light bar across the nose and the tail
            m.box(0, headY + 0.03f, zf - 0.006f, s.w - 0.3f, 0.025f, 0.02f, 0xFFFFFF, Mesh3D.M_HEAD);
            m.box(0, tailTop - 0.06f, zr + 0.006f, s.w - 0.3f, 0.03f, 0.02f, 0xD0121B, Mesh3D.M_TAIL);
        }
        m.box(0, headY - ("jeep".equals(k) ? 0.0f : 0.14f), zf - 0.003f, "jeep".equals(k) ? s.w - 0.9f : 0.62f, "jeep".equals(k) ? 0.26f : 0.14f, 0.02f, GRILLE, Mesh3D.M_MATTE);
        m.box(0, s.clear + 0.16f, zf - 0.007f, 0.42f, 0.11f, 0.01f, PLATE, Mesh3D.M_MATTE); // (a blank plate: no number)
        m.box(0, tailTop - 0.26f, zr + 0.007f, 0.42f, 0.11f, 0.01f, PLATE, Mesh3D.M_MATTE);
        m.box(0, s.clear + 0.05f, zf - 0.01f, s.w - 0.16f, 0.1f, 0.03f, TRIM, Mesh3D.M_MATTE); // bumpers' dark lips
        m.box(0, s.clear + 0.05f, zr + 0.01f, s.w - 0.16f, 0.1f, 0.03f, TRIM, Mesh3D.M_MATTE);
        // ---- each kind's own touches
        if ("suv".equals(k) || "jeep".equals(k)) { // roof rails
            for (int sd = -1; sd <= 1; sd += 2) m.box(sd * cw * 0.7f, s.h + 0.04f, (zRf + zRr) / 2, 0.04f, 0.04f, (zRr - zRf) * 0.9f, RAIL, Mesh3D.M_CHROME);
        }
        if ("sports".equals(k)) m.box(0, s.boot + 0.1f, zr - 0.12f, s.w - 0.3f, 0.03f, 0.22f, 0, Mesh3D.M_PAINT); // a spoiler
        if ("sports".equals(k)) for (int sd = -1; sd <= 1; sd += 2) m.box(sd * (s.w / 2 - 0.4f), s.boot + 0.05f, zr - 0.14f, 0.04f, 0.1f, 0.04f, TRIM, Mesh3D.M_MATTE);
        if ("jeep".equals(k)) { // the spare wheel on the back, a bull bar
            Mesh3D spare = new Mesh3D();
            spare.wheelX(0, 0, 0, s.r * 0.9f, 0.22f, 16, TYRE, Mesh3D.M_MATTE);
            spare.discX(0.115f, 0, 0, s.r * 0.6f, 16, 1, RIM, Mesh3D.M_CHROME);
            m.add(spare, 0, s.belt - 0.05f, zr + 0.14f, (float) (Math.PI / 2));
            m.box(0, s.clear + 0.22f, zf - 0.08f, s.w - 0.5f, 0.06f, 0.06f, TRIM, Mesh3D.M_MATTE);
        }
        if ("concept".equals(k)) for (int sd = -1; sd <= 1; sd += 2) m.box(sd * (w2 + 0.004f), s.clear + 0.09f, 0, 0.012f, 0.025f, s.l * 0.7f, 0x35F0FF, Mesh3D.M_LIGHT); // its light lines
        return m;
    }

    /** 💠 The neon edges (his "neon" finish): lines of light along the sills and the belt, and round the tail. */
    static Mesh3D neon(String kind) {
        Size s = size(valid(kind) ? kind : "suv");
        Mesh3D m = new Mesh3D();
        float w2 = s.w / 2;
        for (int sd = -1; sd <= 1; sd += 2) {
            m.box(sd * (w2 + 0.01f), s.clear + 0.04f, 0, 0.014f, 0.022f, s.l * 0.78f, 0, Mesh3D.M_NEON);
            m.box(sd * (w2 * 0.93f + 0.006f), s.belt + 0.01f, 0, 0.012f, 0.018f, s.l * 0.6f, 0, Mesh3D.M_NEON);
        }
        m.box(0, s.clear + 0.04f, s.l / 2 + 0.012f, s.w - 0.2f, 0.022f, 0.014f, 0, Mesh3D.M_NEON);
        return m;
    }

    /** The top of a side outline (pairs z, y) at z: its highest crossing there; 0 when z is outside it. Pure. */
    static float topAt(float[] zy, float z) {
        int k = zy.length / 2;
        float top = 0;
        for (int i = 0; i < k; i++) {
            int j = (i + 1) % k;
            float z0 = zy[2 * i], y0 = zy[2 * i + 1], z1 = zy[2 * j], y1 = zy[2 * j + 1];
            if ((z < Math.min(z0, z1)) || (z > Math.max(z0, z1))) continue;
            float y = Math.abs(z1 - z0) < 1e-6f ? Math.max(y0, y1) : y0 + (y1 - y0) * (z - z0) / (z1 - z0);
            top = Math.max(top, y);
        }
        return top;
    }

    /**
     * 🔦 The headlights' beams at night: two soft cones from the lamps forward, about 14 m, spreading and falling to the
     * road (drawn as added light: the shader fades them along their length).
     */
    static Mesh3D beams(String kind) {
        Size s = size(valid(kind) ? kind : "suv");
        Mesh3D m = new Mesh3D();
        float zf = -s.l / 2, y = s.clear + (s.hood - s.clear) * 0.7f, hx = s.w / 2 - 0.3f, len = 14f, seg = 8;
        for (int sd = -1; sd <= 1; sd += 2) {
            float x0 = sd * hx, x1 = sd * (hx + 0.6f), z1 = zf - len;
            for (int i = 0; i < seg; i++) {
                double a0 = Math.PI * i / seg, a1 = Math.PI * (i + 1) / seg; // (the upper half: the road takes the rest)
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                float[] p0 = {x0 + c0 * 0.08f, y + s0 * 0.05f, zf - 0.02f}, p1 = {x0 + c1 * 0.08f, y + s1 * 0.05f, zf - 0.02f};
                float[] q1 = {x1 + c1 * 2.2f, 0.02f + s1 * 1.0f, z1}, q0 = {x1 + c0 * 2.2f, 0.02f + s0 * 1.0f, z1};
                m.quadFacing(p0, p1, q1, q0, c0 + c1, s0 + s1, 0, 0xFFFFFF, Mesh3D.M_BEAM);
            }
        }
        return m;
    }

    private static void pillar(Mesh3D m, float x, float y0, float y1, float z, float wz) {
        float[] a = {x, y0, z - wz / 2}, b = {x, y0, z + wz / 2}, c = {x, y1, z + wz / 2}, d = {x, y1, z - wz / 2};
        m.quadFacing(a, b, c, d, Math.signum(x), 0, 0, 0, Mesh3D.M_PAINT);
    }

    /** Five spokes on a rim (a little out from it). */
    private static void spokes(Mesh3D m, float x, float cy, float cz, float r, int sd) {
        for (int i = 0; i < 5; i++) {
            double a = 2 * Math.PI * i / 5 + 0.3;
            float c = (float) Math.cos(a), s = (float) Math.sin(a), pc = (float) Math.cos(a + Math.PI / 2), ps = (float) Math.sin(a + Math.PI / 2);
            float w = r * 0.13f;
            float[] p0 = {x, cy + s * r * 0.2f + ps * w, cz + c * r * 0.2f + pc * w}, p1 = {x, cy + s * r + ps * w, cz + c * r + pc * w},
                    p2 = {x, cy + s * r - ps * w, cz + c * r - pc * w}, p3 = {x, cy + s * r * 0.2f - ps * w, cz + c * r * 0.2f - pc * w};
            m.quadFacing(p0, p1, p2, p3, sd, 0, 0, HUB, Mesh3D.M_MATTE);
        }
    }

    /** A dark arch over a wheel (on the body's side): a half ring from r0 to r1. */
    private static void arch(Mesh3D m, float x, float cy, float cz, float r0, float r1, int sd) {
        int seg = 12;
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * i / seg, a1 = Math.PI * (i + 1) / seg;
            float[] p0 = {x, cy + r0 * (float) Math.sin(a0), cz + r0 * (float) Math.cos(a0)}, p1 = {x, cy + r1 * (float) Math.sin(a0), cz + r1 * (float) Math.cos(a0)},
                    p2 = {x, cy + r1 * (float) Math.sin(a1), cz + r1 * (float) Math.cos(a1)}, p3 = {x, cy + r0 * (float) Math.sin(a1), cz + r0 * (float) Math.cos(a1)};
            m.quadFacing(p0, p1, p2, p3, sd, 0, 0, ARCH, Mesh3D.M_MATTE);
        }
    }

    /** A round lamp (the jeep's): a small disc facing forward (−z). */
    private static void roundLight(Mesh3D m, float x, float y, float z, float r) {
        int seg = 14;
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            m.triFacing(new float[]{x, y, z}, new float[]{x + r * (float) Math.cos(a0), y + r * (float) Math.sin(a0), z},
                    new float[]{x + r * (float) Math.cos(a1), y + r * (float) Math.sin(a1), z}, 0, 0, -1, 0xFFFFFF, Mesh3D.M_HEAD);
        }
    }
}
