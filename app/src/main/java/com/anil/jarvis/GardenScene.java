package com.anil.jarvis;

/**
 * Jarvis's garden: a small wooden window box with seven little plants in a row, one for each of the last seven days
 * (the oldest on the left, today on the right). Each plant shows how far its day got: 0 soil and a seed, 1 a sprout,
 * 2 a stem with four leaves, 3 a closed bud, 4 an open flower. The plants sway very gently, each in its own time;
 * today's has a faint warm glow behind it, and when it grows it gets a soft sparkle for two seconds.
 *
 * <p>Plain Java, drawn through SkyScene.Brush (its own Pen, so slots 0..SkyScene.SLOTS-1 are its own). It is shown
 * small (about 230 × 52 dp) on the tablet's navy panel and leaves the background to the panel. Everything is made in
 * set / setDim / resize; step and draw allocate nothing.
 */
final class GardenScene {

    static final int DAYS = 7;
    static final int SEED = 0, SPROUT = 1, LEAVES = 2, BUD = 3, FLOWER = 4;
    /** The panel's navy behind the garden (dim colours lean towards it). */
    static final int PANEL = 0xFF13233A;

    private static final int S_TODAY = 0, S_SPARK = 1;
    private static final float TAU = 6.2831855f, SPARK_LIFE = 2f;

    // the heights (in units: a hundredth of the scene's height) of the layout, from the top
    private static final float BASE = 61f, MOUND = 63f, RIM = 67.5f, RIM_W = 7f, BOX_TOP = 70.5f, BOX_BOT = 95.5f,
            PLANK1 = 79f, PLANK2 = 87.5f;
    /** Stem height per stage. */
    private static final float[] TALL = {0f, 13f, 29f, 38f, 41f};

    // each plant's flower: petals, their shade, the centre; and how many petals
    private static final int[] PETAL = {0xFFF4A9C0, 0xFFF7D35C, 0xFFC4A8EE, 0xFFF7F4EC, 0xFFF8AE78, 0xFFEA7479, 0xFFA3CDF2};
    private static final int[] PETAL_SH = {0xFFD88BA6, 0xFFDDB443, 0xFFA68AD6, 0xFFD6D2C9, 0xFFDF9060, 0xFFCC5A62, 0xFF83B0DE};
    private static final int[] CENTRE = {0xFFF7D57E, 0xFFB5733C, 0xFFF6DC8E, 0xFFF3C54C, 0xFFF6DA8A, 0xFFF7D67E, 0xFFF7E08C};
    private static final int[] PETALS = {5, 6, 5, 8, 5, 6, 5};
    /** A little lean of its own for each plant (radians), so the row is not stiff. */
    private static final float[] LEAN = {0.03f, -0.02f, 0.015f, -0.03f, 0.02f, -0.015f, 0.01f};
    /** And a height of its own (a fraction more or less). */
    private static final float[] SIZE = {0f, -0.04f, 0.03f, -0.02f, 0.035f, -0.03f, 0.01f};
    // today's sparkle: seven twinkles around the plant (angle, distance in units, start, size in units)
    private static final float[] TW_A = {-1.95f, -0.85f, 0.15f, 1.05f, 2.25f, 3.05f, -2.75f},
            TW_R = {15f, 19f, 15.5f, 18f, 14.5f, 17f, 18.5f}, TW_D = {0f, 0.14f, 0.05f, 0.22f, 0.09f, 0.18f, 0.27f},
            TW_S = {4.6f, 3.6f, 4.1f, 3.4f, 4.4f, 3.6f, 3.9f};
    private static final float[] GLOW_AT = {0f, 0.55f, 1f};

    private int w, h;
    /** One unit: a hundredth of the height (less on a strip narrower than 3 : 1, so seven plants still fit). */
    private float u;
    /** The top of the 100-unit layout (it sits on the bottom edge). */
    private float oy;
    private boolean dim;
    private final int[] stage = new int[DAYS];
    private boolean known;

    private float col, glowR;
    private final float[] px = new float[DAYS];
    private final float[] swayPh = new float[DAYS], swayF = new float[DAYS];
    private float glowPh, sparkT = -1f;

    // colours (made in colours(); the gradient arrays are new only there, so a Brush's cache holds)
    private int leafHi, leafLo, sproutHi, sproutLo, stem, soil, mound, moundToday, seed, seedHi, plankA, plankB, plankC,
            plankLine, batten, rim, rimHi, rimShade, shadow, twinkle;
    private final int[] petal = new int[DAYS], petalSh = new int[DAYS], centre = new int[DAYS];
    private int centreHi;
    private int[] todayC, sparkC;

    private final float[] pts = new float[2 * 6];

    GardenScene() {
        for (int i = 0; i < DAYS; i++) {
            swayPh[i] = (i * 2.399963f) % TAU;                     // (golden-angle steps: never in step)
            swayF[i] = TAU / (5.5f + 3f * ((i * 0.618034f) % 1f));  // one sway in 5.5-8.5 s
        }
        colours();
    }

    // ================================================================ settings
    /** Seven stages (0..4), oldest first; today's plant sparkles when it has grown since the last call. */
    void set(int[] stages) {
        int before = stage[DAYS - 1];
        for (int i = 0; i < DAYS; i++) {
            int v = stages != null && i < stages.length ? stages[i] : 0;
            stage[i] = v < SEED ? SEED : v > FLOWER ? FLOWER : v;
        }
        if (known && stage[DAYS - 1] > before) sparkT = 0f;
        known = true;
    }

    void resize(int width, int height) {
        if (width == w && height == h) return;
        w = width;
        h = height;
        if (w <= 0 || h <= 0) return;
        u = Math.min(h / 100f, w / 300f);
        oy = h - 100f * u;
        col = (w - 14f * u) / DAYS;
        for (int i = 0; i < DAYS; i++) px[i] = 7f * u + (i + 0.5f) * col;
        glowR = Math.min(30f * u, w - px[DAYS - 1] - 1f * u); // (inside the rect on a narrow strip)
    }

    /** Night screen: softer, darker colours. */
    void setDim(boolean d) {
        if (d == dim) return;
        dim = d;
        colours();
    }

    /** The gentle sway never stops. */
    boolean moving() { return w > 0 && h > 0; }

    private void colours() {
        leafHi = sf(0xFF86C76A);
        leafLo = sf(0xFF5EA452);
        sproutHi = sf(0xFFA3D97E);
        sproutLo = sf(0xFF76BD5F);
        stem = sf(0xFF5A9C4D);
        soil = sf(0xFF573B29);
        mound = sf(0xFF6A4932);
        moundToday = sf(0xFF7A5639);
        seed = sf(0xFFD8B47E);
        seedHi = sf(0xFFF2DCB0);
        plankA = sf(0xFFA8754A);
        plankB = sf(0xFF9D6C44);
        plankC = sf(0xFF8F613C);
        plankLine = sf(0xFF6C482E);
        batten = sf(0xFF875939);
        rim = sf(0xFFC48D5A);
        rimHi = sf(0xFFDDAB74);
        rimShade = sf(0x50000000);
        shadow = sf(0x38000000);
        twinkle = sf(0xFFFFF3CF);
        centreHi = sf(0xB0FFF6D8);
        for (int i = 0; i < DAYS; i++) {
            petal[i] = sf(PETAL[i]);
            petalSh[i] = sf(PETAL_SH[i]);
            centre[i] = sf(CENTRE[i]);
        }
        todayC = new int[]{sf(0x40FFD9A0), sf(0x18FFD9A0), 0x00FFD9A0};
        sparkC = new int[]{sf(0x90FFF4D2), sf(0x34FFE4A6), 0x00FFE4A6};
    }

    // ================================================================ every frame
    void step(float dt) {
        if (!(dt > 0f)) return;
        if (dt > 0.25f) dt = 0.25f;
        for (int i = 0; i < DAYS; i++) {
            swayPh[i] += swayF[i] * dt;
            if (swayPh[i] >= TAU) swayPh[i] -= TAU;
        }
        glowPh += TAU / 7f * dt;
        if (glowPh >= TAU) glowPh -= TAU;
        if (sparkT >= 0f && (sparkT += dt) > SPARK_LIFE) sparkT = -1f;
    }

    void draw(SkyScene.Brush b) {
        if (w <= 0 || h <= 0) return;
        int last = DAYS - 1;
        float tx = px[last], focus = y(focusOf(stage[last]));
        // today's soft warm glow, and the sparkle's light when it has just grown
        b.radial(S_TODAY, tx, y(36f), glowR, 36f * u, todayC, GLOW_AT,
                (0.82f + 0.18f * (float) Math.sin(glowPh)) * (dim ? 0.55f : 1f));
        float p = sparkT < 0f ? -1f : sparkT / SPARK_LIFE;
        if (p >= 0f) {
            float env = p < 0.22f ? p / 0.22f : (1f - p) / 0.78f;
            env = env * env * (3f - 2f * env);
            b.radial(S_SPARK, tx, focus, 26f * u, 26f * u, sparkC, GLOW_AT, env * (dim ? 0.6f : 1f));
        }

        // the soil showing above the rim
        b.rect(4.5f * u, y(60.5f), w - 4.5f * u, y(RIM), soil);
        for (int i = 0; i < DAYS; i++) {
            float k = 1f;
            if (i == last && p >= 0f) { // (it grows into its new shape)
                float e = Math.min(1f, sparkT / 1.1f);
                k = 1f - 0.16f * (1f - e) * (1f - e);
            }
            plant(b, i, k);
        }
        for (int i = 0; i < DAYS; i++) {
            b.oval(px[i], y(MOUND), 10.5f * u, 3.6f * u, i == last ? moundToday : mound);
            if (stage[i] == SEED) {
                float sx = px[i] + 0.6f * u, sy = y(MOUND - 3.5f);
                b.oval(sx, sy, 2.7f * u, 1.9f * u, seed);
                b.circle(sx - 0.9f * u, sy - 0.6f * u, 0.8f * u, seedHi);
            }
        }

        drawBox(b);

        if (p >= 0f) drawTwinkles(b, tx, focus, p, stage[last]);
    }

    /** The window box: a rim with round ends over three planks, plank lines, end battens and a soft shadow. */
    private void drawBox(SkyScene.Brush b) {
        float l = 4f * u, r = w - 4f * u, bl = 5.5f * u, br = w - 5.5f * u;
        float top = y(BOX_TOP), bot = y(BOX_BOT), p1 = y(PLANK1), p2 = y(PLANK2);
        b.oval(w * 0.5f, y(97.6f), w * 0.5f - 6f * u, 1.9f * u, shadow);
        // three planks, a little narrower towards the bottom
        float l1 = l + (bl - l) * (p1 - top) / (bot - top), r1 = w - l1;
        float l2 = l + (bl - l) * (p2 - top) / (bot - top), r2 = w - l2;
        b.quad(l, top, r, top, r1, p1, l1, p1, plankA);
        b.quad(l1, p1, r1, p1, r2, p2, l2, p2, plankB);
        b.quad(l2, p2, r2, p2, br, bot, bl, bot, plankC);
        b.line(l1 + 1.5f * u, p1, r1 - 1.5f * u, p1, 1.1f * u, plankLine);
        b.line(l2 + 1.5f * u, p2, r2 - 1.5f * u, p2, 1.1f * u, plankLine);
        float bw = 6f * u;
        b.quad(l, top, l + bw, top, bl + bw, bot, bl, bot, batten);
        b.quad(r - bw, top, r, top, br, bot, br - bw, bot, batten);
        // the rim on top, with its shadow on the box and a lit top edge
        b.line(l + 1f * u, y(RIM + RIM_W * 0.5f + 0.4f), r - 1f * u, y(RIM + RIM_W * 0.5f + 0.4f), 1.6f * u, rimShade);
        b.line(1.5f * u + RIM_W * 0.5f * u, y(RIM), w - 1.5f * u - RIM_W * 0.5f * u, y(RIM), RIM_W * u, rim);
        b.line(1.5f * u + RIM_W * 0.5f * u, y(RIM - 2.2f), w - 1.5f * u - RIM_W * 0.5f * u, y(RIM - 2.2f), 1.5f * u, rimHi);
    }

    /** One plant at its stage, swaying about its foot; k scales it (today's, as it grows). */
    private void plant(SkyScene.Brush b, int i, float k) {
        int s = stage[i];
        if (s == SEED) return;
        float H = TALL[s] * (1f + SIZE[i]) * u * k, x0 = px[i], y0 = y(BASE);
        float amp = s == SPROUT ? 0.04f : 0.055f;
        float th = LEAN[i] * (s == SPROUT ? 0.6f : 1f) + amp * (float) Math.sin(swayPh[i]);
        // the stem bends more towards its top: x = x0 + th * H * (0.35 f + 0.65 f^2)
        for (int j = 0; j < 6; j++) {
            float f = j / 5f;
            pts[2 * j] = x0 + th * H * (0.35f * f + 0.65f * f * f);
            pts[2 * j + 1] = y0 - H * f;
        }
        float topX = pts[10], topY = pts[11];
        b.polyline(pts, 6, (s == SPROUT ? 2.1f : 2.6f) * u * k, stem);
        if (s == SPROUT) { // two seed leaves in a V
            float a = th * 1.35f;
            leaf(b, topX, topY, 0.5f - a, 9f * u * k, 6f * u * k, sproutHi, sproutLo);
            leaf(b, topX, topY, (float) Math.PI - 0.5f - a, 9f * u * k, 6f * u * k, sproutHi, sproutLo);
            return;
        }
        // four leaves in two pairs, the upper ones smaller and more upright
        boolean tall = s >= BUD;
        float f1 = tall ? 0.22f : 0.34f, f2 = tall ? 0.48f : 0.68f;
        float len1 = (tall ? 13f : 11.5f) * u * k, len2 = (tall ? 11f : 9.8f) * u * k;
        float wid = (tall ? 6.4f : 5.8f) * u * k;
        stemLeaf(b, x0, y0, H, th, f1, true, 0.36f, len1, wid);
        stemLeaf(b, x0, y0, H, th, f1 + 0.05f, false, 0.36f, len1, wid);
        stemLeaf(b, x0, y0, H, th, f2 + 0.05f, true, 0.62f, len2, wid * 0.9f);
        stemLeaf(b, x0, y0, H, th, f2, false, 0.62f, len2, wid * 0.9f);
        if (s == LEAVES) leaf(b, topX, topY + 1f * u * k, (float) Math.PI / 2 - th * 1.6f, 5.4f * u * k, 3.6f * u * k,
                sproutHi, sproutLo); // (its growing tip)
        else if (s == BUD) bud(b, i, topX, topY, th, k);
        else if (s == FLOWER) flower(b, i, topX, topY, th, k);
    }

    /** A leaf on the stem at height fraction f, to the left or right, rising at angle a (radians above level). */
    private void stemLeaf(SkyScene.Brush b, float x0, float y0, float H, float th, float f, boolean left, float a,
                          float len, float wid) {
        float x = x0 + th * H * (0.35f * f + 0.65f * f * f), y = y0 - H * f;
        float tilt = th * (0.35f + 1.3f * f);
        leaf(b, x, y, (left ? (float) Math.PI - a : a) - tilt, len, wid, leafHi, leafLo);
    }

    /**
     * A leaf from (x, y) at angle a (radians, 0 = to the right, counted upwards): six corners, widest from a third
     * to two thirds along, its upper half lit and its lower half in shade.
     */
    private static void leaf(SkyScene.Brush b, float x, float y, float a, float len, float wid, int hi, int lo) {
        float dx = (float) Math.cos(a), dy = -(float) Math.sin(a);
        float nx = dy, ny = -dx;
        if (ny > 0f || ny == 0f && nx > 0f) { nx = -nx; ny = -ny; } // (the upper side)
        float tx = x + dx * len, ty = y + dy * len;
        float ax = x + dx * len * 0.32f, ay = y + dy * len * 0.32f;
        float cx = x + dx * len * 0.68f, cy = y + dy * len * 0.68f;
        float w1 = wid * 0.5f, w2 = wid * 0.4f;
        b.quad(x, y, ax + nx * w1, ay + ny * w1, cx + nx * w2, cy + ny * w2, tx, ty, hi);
        b.quad(x, y, tx, ty, cx - nx * w2, cy - ny * w2, ax - nx * w1, ay - ny * w1, lo);
    }

    /** A closed bud in the flower's colour, pointed at the top, held in a small green cup of sepals. */
    private void bud(SkyScene.Brush b, int i, float x, float y, float th, float k) {
        float s = u * k;
        float bx = x + th * 5f * s, by = y - 6.5f * s;
        b.quad(bx - 4f * s, by - 2.4f * s, bx + th * 4f * s, by - 12.5f * s, bx + 4f * s, by - 2.4f * s, bx, by,
                petalSh[i]);
        b.oval(bx, by, 4.9f * s, 6.4f * s, petalSh[i]);
        b.oval(bx - 1f * s, by - 1.2f * s, 2.8f * s, 5f * s, petal[i]);
        b.oval(x + th * 1.5f * s, y - 1.6f * s, 3.4f * s, 2.4f * s, leafLo);
        leaf(b, x, y - 0.5f * s, 1.22f - th, 8.6f * s, 4f * s, leafHi, leafLo);
        leaf(b, x, y - 0.5f * s, (float) Math.PI - 1.22f - th, 8.6f * s, 4f * s, leafHi, leafLo);
    }

    /** An open flower: round petals (each with a soft shaded edge) about a round centre. */
    private void flower(SkyScene.Brush b, int i, float x, float y, float th, float k) {
        float R = 14f * u * k;
        int n = PETALS[i];
        float pr = n >= 8 ? 0.36f * R : n == 6 ? 0.42f * R : 0.47f * R, d = R - pr;
        float rot = i * 0.7f - th * 0.6f - (float) Math.PI / 2;
        for (int j = 0; j < n; j++) {
            double a = rot + j * TAU / n;
            float qx = x + (float) Math.cos(a) * d, qy = y + (float) Math.sin(a) * d;
            b.circle(qx, qy + 0.9f * u * k, pr, petalSh[i]);
            b.circle(qx, qy, pr * 0.9f, petal[i]);
        }
        float cr = (n >= 8 ? 0.38f : 0.34f) * R;
        b.circle(x, y + 0.5f * u * k, cr, petalSh[i]);
        b.circle(x, y, cr, centre[i]);
        b.circle(x - cr * 0.3f, y - cr * 0.32f, cr * 0.34f, centreHi);
    }

    /** Today's sparkle: small four-pointed twinkles that open, drift up a little and fade, one after another. */
    private void drawTwinkles(SkyScene.Brush b, float x, float y, float p, int s) {
        float spread = 0.55f + 0.45f * TALL[s] / TALL[FLOWER];
        for (int k = 0; k < TW_A.length; k++) {
            float q = (p - TW_D[k]) / 0.68f;
            if (q <= 0f || q >= 1f) continue;
            float e = (float) Math.sin(Math.PI * q);
            float r = (TW_R[k] * spread + 5f * q) * u;
            float cx = x + (float) Math.cos(TW_A[k]) * r, cy = y + (float) Math.sin(TW_A[k]) * r - 4f * q * u;
            float L = TW_S[k] * u * (0.5f + 0.5f * e), t = L * 0.26f;
            int c = SkyScene.fade(twinkle, e * (dim ? 0.6f : 0.95f));
            b.quad(cx, cy - L, cx + t, cy, cx, cy + L, cx - t, cy, c);
            b.quad(cx - L, cy, cx, cy - t, cx + L, cy, cx, cy + t, c);
        }
    }

    // ================================================================ helpers
    /** A layout height (units from the top of the 100-unit layout) in pixels. */
    private float y(float units) { return oy + units * u; }

    /** Where today's sparkle centres (units from the top), by its stage. */
    private static float focusOf(int s) {
        switch (s) {
            case SEED: return 54f;
            case SPROUT: return 49f;
            case LEAVES: return 43f;
            default: return 35f;
        }
    }

    /** Softer when dim: towards the panel's navy, and fainter if see-through. */
    private int sf(int c) {
        if (!dim) return c;
        int a = c >>> 24;
        int rgb = SkyScene.mix(c | 0xFF000000, PANEL, 0.38f) & 0xFFFFFF;
        return a == 255 ? 0xFF000000 | rgb : Math.round(a * 0.6f) << 24 | rgb;
    }
}
