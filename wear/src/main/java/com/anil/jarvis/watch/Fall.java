package com.anil.jarvis.watch;

/**
 * W42 / W80: the watch's own feel for a fall (pure: tested on a desk). A fall needs all of:
 * - a moment of free fall (under 0.4 g for 100 ms) then a hard hit (3 g) within about a second, or a very hard hit alone
 *   (6 g, or near the top of what this sensor can read);
 * - the wrist turned to another angle (30°+) from how it was before the hit (a clap or a slap on the table leaves the
 *   hand much as it was);
 * - after 2 seconds to settle, 15 seconds of lying really still, the readings unbroken (taken off / a gap = dropped).
 * A hard knock (3.5 g) is also told by itself (rarely), for the ride's crash check. One clock throughout (ms).
 */
final class Fall {
    interface Out {
        void impact(float g, long t);
        void fell(long t);
    }

    static final float FREE = 0.4f, HIT = 3.0f;
    static final double TURN_DEG = 30;
    private float hard = 6f, knock = 3.5f;
    private long ffStart = -1, ffEnd = -1000_000, impactT = -1, lastKnock = -1000_000, quietUntil = -1, lastT = -1;
    private double sum, sumSq, sx, sy, sz;
    private int n, restarts;
    // the wrist's direction before: slowly smoothed, and kept from just before a free fall began
    private double gx, gy, gz, fx, fy, fz, px, py, pz;
    private boolean have;

    /** The sensor's top reading (in g): a very hard hit is one near it when it can't read 6 g. */
    void range(float maxG) {
        if (maxG < 4f) return; // (an odd small report: the usual thresholds stay)
        hard = Math.min(6f, maxG * 0.92f);
        knock = Math.min(3.5f, maxG * 0.85f);
    }

    void sample(float ax, float ay, float az, long t, Out out) {
        boolean gap = lastT >= 0 && t - lastT > 3000;
        lastT = t;
        float g = (float) Math.sqrt(ax * ax + ay * ay + az * az) / 9.81f;
        if (g >= knock && t - lastKnock > 10_000L) { lastKnock = t; out.impact(g, t); }
        if (impactT >= 0 && gap) impactT = -1; // (the readings stopped: the watch taken off, asleep: start over)
        if (impactT < 0) {
            if (!have || gap) { gx = ax; gy = ay; gz = az; have = true; }
            else if (g > 0.6f && g < 1.4f) { gx += 0.06 * (ax - gx); gy += 0.06 * (ay - gy); gz += 0.06 * (az - gz); }
        }
        // free fall is followed all the time (also while waiting after a hit)
        if (g < FREE) { if (ffStart < 0) { ffStart = t; if (impactT < 0) { fx = gx; fy = gy; fz = gz; } } }
        else {
            if (ffStart >= 0 && t - ffStart >= 100) ffEnd = t;
            ffStart = -1;
        }
        boolean afterFree = t - ffEnd <= 1200, hit = g >= hard || g >= HIT && afterFree;
        if (impactT < 0) {
            if (t < quietUntil) return;
            if (hit) {
                impactT = t;
                restarts = 0;
                if (afterFree) { px = fx; py = fy; pz = fz; } else { px = gx; py = gy; pz = gz; }
                sum = sumSq = sx = sy = sz = 0;
                n = 0;
            }
            return;
        }
        // a stumble, then the real fall: the wait starts again from the new hit (twice at most: not a run's every step);
        // the angle from before the first is kept
        if (hit && t - impactT > 300 && restarts < 2) { restarts++; impactT = t; sum = sumSq = sx = sy = sz = 0; n = 0; return; }
        long since = t - impactT;
        if (since < 2000) return; // (settling: bouncing, the arm coming down)
        if (since <= 17_000L) { sum += g; sumSq += (double) g * g; sx += ax; sy += ay; sz += az; n++; return; }
        double mean = n == 0 ? 0 : sum / n, sd = n == 0 ? 1 : Math.sqrt(Math.max(0, sumSq / n - mean * mean));
        long at = impactT;
        impactT = -1;
        ffEnd = -1000_000;
        have = false; // (the direction is learnt again from now)
        boolean still = n >= 200 && sd < 0.06 && Math.abs(mean - 1) < 0.12;
        if (still && angle(px, py, pz, sx, sy, sz) >= TURN_DEG) { quietUntil = t + 60_000L; out.fell(at); }
    }

    /** Degrees between two directions (0 when either is unknown). */
    static double angle(double ax, double ay, double az, double bx, double by, double bz) {
        double la = Math.sqrt(ax * ax + ay * ay + az * az), lb = Math.sqrt(bx * bx + by * by + bz * bz);
        if (la < 1e-6 || lb < 1e-6) return 0;
        double cos = (ax * bx + ay * by + az * bz) / (la * lb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }
}
