package com.anil.jarvis;

/**
 * 🎨 Stage 4 (27 Oct): the free map (OpenStreetMap's own pictures) in each car look's colours — ⑫ soft white glass,
 * ⑭ neon (dark with cyan roads' edges and words), ⑪–⑭ dark at night (light land turned dark, the colours kept) — as
 * colour matrices on its pictures. Only ever on the free map: Google's pictures are never recoloured (Google's own
 * night style comes from Google). Pure maths (desk-tested); JarvisMapView makes the filters.
 */
final class MapTint {
    private MapTint() {}

    /** Which tint: "" as it always was (night: dimmed), "family" (⑪ ⑬: dark at night), "glass" (⑫), "neon" (⑭). */
    static final String NONE = "", FAMILY = "family", GLASS = "glass", NEON = "neon";

    /** The free map's tint for a car screen shape. Pure. */
    static String forShape(String shape) {
        if (CarLayout.GLASS.equals(shape) || CarLayout.LIQUID.equals(shape) || CarLayout.SOFTWHITE.equals(shape)) return GLASS; // (⑰ ⑲'s light glass too)
        if (CarLayout.NEON.equals(shape)) return NEON;
        return CarLayout.family(shape) ? FAMILY : NONE;
    }

    /** The night dimming every shape had (dimmed, a little blue). */
    static final float[] DIM = {
            0.38f, 0, 0, 0, 0,
            0, 0.40f, 0, 0, 0,
            0, 0, 0.46f, 0, 4,
            0, 0, 0, 1, 0};

    /**
     * Dark: the picture's light turned round with its colours kept (inverted, then the hue turned half round), then a
     * little blue and lifted: the land deep blue-grey, the roads darker lines with lighter edges, the main roads amber,
     * water and parks deep in their own colours, the words light.
     */
    static final float[] DARK = tinted(dark(0.92f), new float[]{0.85f, 0.92f, 1.1f}, new float[]{10, 12, 20});

    /** ⑫ white glass: the colours softer (less saturated), lighter and a little cool. */
    static final float[] GLASS_DAY = glass();

    /** ⑭ neon: dark like the night, then cyan — the words and the road edges glow. */
    static final float[] NEON_ALL = neon();

    /** The matrix for a tint at day / night (null: the picture as it is). Pure. */
    static float[] matrix(String tint, boolean night) {
        if (NEON.equals(tint)) return NEON_ALL;
        if (GLASS.equals(tint)) return night ? DARK : GLASS_DAY;
        if (FAMILY.equals(tint)) return night ? DARK : null;
        return night ? DIM : null;
    }

    private static float[] dark(float k) {
        // CSS's hue-rotate(180°) with Android's luminance weights; inverted first: M·(255 − x) = −M·x + 255·rowsum (each row sums to 1)
        float[][] h = {{-0.574f, 1.43f, 0.144f}, {0.426f, 0.43f, 0.144f}, {0.426f, 1.43f, -0.856f}};
        float[] m = new float[20];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) m[r * 5 + c] = -h[r][c] * k;
            m[r * 5 + 4] = 255f * k;
        }
        m[18] = 1;
        return m;
    }

    private static float[] glass() {
        float s = 0.45f, lr = 0.213f, lg = 0.715f, lb = 0.072f, keep = 0.82f, add = 46f;
        float[][] sat = {
                {lr + (1 - lr) * s, lg - lg * s, lb - lb * s},
                {lr - lr * s, lg + (1 - lg) * s, lb - lb * s},
                {lr - lr * s, lg - lg * s, lb + (1 - lb) * s}};
        float[] m = new float[20];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) m[r * 5 + c] = sat[r][c] * keep;
            m[r * 5 + 4] = add + (r == 2 ? 4 : r == 0 ? -2 : 0);
        }
        m[18] = 1;
        return m;
    }

    private static float[] neon() { return tinted(dark(1f), new float[]{0.55f, 0.95f, 1.25f}, new float[]{0, 0, 6}); }

    /** A matrix's rows scaled (each colour) and lifted. */
    private static float[] tinted(float[] d, float[] scale, float[] lift) {
        float[] m = new float[20];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 5; c++) m[r * 5 + c] = d[r * 5 + c] * scale[r];
            m[r * 5 + 4] += lift[r];
        }
        m[18] = 1;
        return m;
    }

    /** One colour through a matrix (as Android does: clamped 0..255), for the desk. Pure. */
    static int apply(float[] m, int rgb) {
        if (m == null) return rgb & 0xFFFFFF;
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int out = 0;
        for (int row = 0; row < 3; row++) {
            float v = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4];
            out = (out << 8) | Math.max(0, Math.min(255, Math.round(v)));
        }
        return out;
    }

    /** A colour's brightness 0..255 (for the desk). Pure. */
    static int lum(int rgb) {
        return Math.round(0.213f * ((rgb >> 16) & 255) + 0.715f * ((rgb >> 8) & 255) + 0.072f * (rgb & 255));
    }
}
