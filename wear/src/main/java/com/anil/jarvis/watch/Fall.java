package com.anil.jarvis.watch;

/**
 * W42 / W80: the watch's own feel for a fall (pure: tested on a desk). A fall = a moment of free fall (under 0.4 g for
 * 90 ms) then a hard hit (2.5 g) within about a second, or a very hard hit (4.5 g) alone; then, after 2 seconds to
 * settle, 8 seconds of lying still. A hard knock (3.5 g) is also told by itself (rarely), for the ride's crash check.
 * One clock throughout (ms).
 */
final class Fall {
    interface Out {
        void impact(float g, long t);
        void fell(long t);
    }

    static final float FREE = 0.4f, HIT = 2.5f, HARD = 4.5f, KNOCK = 3.5f;
    private long ffStart = -1, ffEnd = -1000_000, impactT = -1, lastKnock = -1000_000, quietUntil = -1;
    private double sum, sumSq;
    private int n;

    void sample(float ax, float ay, float az, long t, Out out) {
        float g = (float) Math.sqrt(ax * ax + ay * ay + az * az) / 9.81f;
        if (g >= KNOCK && t - lastKnock > 10_000L) { lastKnock = t; out.impact(g, t); }
        if (impactT < 0) {
            if (t < quietUntil) return;
            if (g < FREE) { if (ffStart < 0) ffStart = t; }
            else {
                if (ffStart >= 0 && t - ffStart >= 90) ffEnd = t;
                ffStart = -1;
            }
            if (g >= HARD || g >= HIT && t - ffEnd <= 1200) { impactT = t; sum = sumSq = 0; n = 0; }
            return;
        }
        long since = t - impactT;
        if (since < 2000) return; // (settling: bouncing, the arm coming down)
        if (since <= 10_000L) { sum += g; sumSq += (double) g * g; n++; return; }
        double mean = n == 0 ? 0 : sum / n, sd = n == 0 ? 1 : Math.sqrt(Math.max(0, sumSq / n - mean * mean));
        long at = impactT;
        impactT = -1;
        ffEnd = -1000_000;
        if (n >= 20 && sd < 0.1 && Math.abs(mean - 1) < 0.15) { quietUntil = t + 60_000L; out.fell(at); }
    }
}
