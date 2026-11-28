package com.anil.jarvis;

/**
 * 📸 Is a photo good enough to send (no Android here: desk-tested)? Too dark, or blurred by a moving hand, makes the AI
 * guess: the 3D scans check every photo on the phone first (₹0) and say so, and he can still send it.
 */
final class ShotCheck {
    private ShotCheck() {}

    static final int OK = 0, DARK = 1, BLUR = 2;

    /** How bright (0..255) and how sharp (the spread of the Laplacian) a small picture is: {brightness, sharpness}. */
    static float[] measure(int[] argb, int w, int h) {
        if (argb == null || w < 3 || h < 3) return new float[]{0, 0};
        float[] l = new float[w * h];
        double sum = 0;
        for (int i = 0; i < l.length; i++) {
            int c = argb[i];
            float y = 0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF);
            l[i] = y;
            sum += y;
        }
        double m = 0, m2 = 0;
        int n = 0;
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                double lap = 4 * l[i] - l[i - 1] - l[i + 1] - l[i - w] - l[i + w];
                m += lap;
                m2 += lap * lap;
                n++;
            }
        m /= n;
        return new float[]{(float) (sum / l.length), (float) (m2 / n - m * m)};
    }

    /** OK, DARK or BLUR, for a picture about 200 pixels across. */
    static int judge(int[] argb, int w, int h) {
        float[] v = measure(argb, w, h);
        if (v[0] < 38) return DARK;
        if (v[1] < 22) return BLUR;
        return OK;
    }

    static String words(int verdict) {
        return verdict == DARK ? "చీకటిగా ఉంది" : verdict == BLUR ? "బ్లర్‌గా ఉంది (కదిలింది)" : "";
    }
}
