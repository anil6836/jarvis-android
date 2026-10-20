package com.anil.jarvis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The camera HUD's small sums for the ✨ look (no Android classes here, so the desk tests can check them): rings that
 * glide instead of jumping, name tags that never sit on each other, near things drawn bigger, the depth map's numbers,
 * the soft picture behind the glass pills, the HUD's small lag when the phone turns, and where the horizon is.
 */
final class HudMath {
    private HudMath() {}

    /** Eases v toward target: after tau ms it is about 63% of the way there, at any frame rate. */
    static float ease(float v, float target, float dtMs, float tauMs) {
        if (dtMs <= 0) return v;
        if (tauMs <= 0 || dtMs > 1000) return target;
        float a = 1f - (float) Math.exp(-dtMs / tauMs);
        return v + (target - v) * a;
    }

    /**
     * How big a ring and its tag are drawn: near bigger, far smaller (0.75 … 1.2). distM: about how far in metres (0 =
     * unknown); near: 0..1 from the depth map (1 = nearest, below 0 = unknown). Unknown both: 1.
     */
    static float nearScale(float distM, float near) {
        if (distM > 0) {
            double k = (Math.log(distM) - Math.log(2)) / (Math.log(30) - Math.log(2));
            k = Math.max(0, Math.min(1, k));
            return (float) (1.2 - 0.45 * k);
        }
        if (near >= 0) return 0.75f + 0.45f * Math.max(0f, Math.min(1f, near));
        return 1f;
    }

    static boolean overlap(float[] a, float[] b) { return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]; }

    /**
     * Name tags that never sit on each other. Each one wants to go just under its ring, else just above it, else a step
     * further down / up; one that still collides with a tag already placed (or with `blocked`), or comes after `max`
     * shown ones, is left out (its ring stays). Tried in the given order (the most important first).
     * want[i] = {cx, ringTop, ringBottom, w, h}; blocked = {l, t, r, b} rects already taken (may be null).
     * Returns {l, t, r, b} per tag; null = not shown.
     */
    static float[][] placeTags(float[][] want, float[][] blocked, int max, float W, float top, float bottom, float gap, float margin) {
        float[][] out = new float[want.length][];
        List<float[]> taken = new ArrayList<>();
        if (blocked != null) for (float[] b : blocked) if (b != null) taken.add(b);
        int shown = 0;
        for (int i = 0; i < want.length; i++) {
            if (shown >= max) break;
            float cx = want[i][0], rt = want[i][1], rb = want[i][2], w = want[i][3], h = want[i][4];
            if (w <= 0 || h <= 0 || h > bottom - top) continue;
            float l = Math.max(margin, Math.min(W - margin - w, cx - w / 2));
            float[] tries = {rb + gap, rt - gap - h, rb + gap + h * 1.1f, rt - gap - h * 2.1f, rb + gap + h * 2.2f};
            for (float y : tries) {
                float t = Math.max(top, Math.min(bottom - h, y));
                float[] r = {l, t, l + w, t + h};
                boolean free = true;
                for (float[] o : taken) if (overlap(r, o)) { free = false; break; }
                if (!free) continue;
                out[i] = r;
                taken.add(r);
                shown++;
                break;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- the depth map (🌊 11)

    /** The model's raw numbers (bigger = nearer) to 0..1 (1 = nearest); a few odd pixels don't stretch it (2nd–98th percentile). */
    static float[] normalizeDepth(float[] raw) {
        int n = raw.length;
        float[] out = new float[n];
        if (n == 0) return out;
        int step = Math.max(1, n / 4096);
        float[] s = new float[(n + step - 1) / step];
        int k = 0;
        for (int i = 0; i < n && k < s.length; i += step) s[k++] = raw[i];
        s = Arrays.copyOf(s, k);
        Arrays.sort(s);
        float lo = s[(int) Math.floor((k - 1) * 0.02)], hi = s[(int) Math.ceil((k - 1) * 0.98)];
        if (!(hi - lo > 1e-6f)) { Arrays.fill(out, 0.5f); return out; }
        float span = hi - lo;
        for (int i = 0; i < n; i++) {
            float v = (raw[i] - lo) / span;
            out[i] = v < 0 ? 0 : v > 1 ? 1 : v;
        }
        return out;
    }

    /** A w×h map made f times smaller each way (each cell = the average of its f×f block). */
    static float[] shrink(float[] src, int w, int h, int f) {
        if (f <= 1) return src.clone();
        int gw = w / f, gh = h / f;
        float[] out = new float[gw * gh];
        float inv = 1f / (f * f);
        for (int gy = 0; gy < gh; gy++)
            for (int gx = 0; gx < gw; gx++) {
                float sum = 0;
                for (int y = gy * f; y < gy * f + f; y++)
                    for (int x = gx * f; x < gx * f + f; x++) sum += src[y * w + x];
                out[gy * gw + gx] = sum * inv;
            }
        return out;
    }

    /** How near (0..1) the thing in this box (0..1 of the screen) is: the middle value of the cells under it; -1 none. */
    static float boxNear(float[] grid, int gw, int gh, float[] box) {
        if (grid == null || gw <= 0 || gh <= 0 || grid.length < gw * gh) return -1;
        int x0 = clampI((int) Math.floor(box[0] * gw), 0, gw - 1), x1 = clampI((int) Math.ceil(box[2] * gw) - 1, 0, gw - 1);
        int y0 = clampI((int) Math.floor(box[1] * gh), 0, gh - 1), y1 = clampI((int) Math.ceil(box[3] * gh) - 1, 0, gh - 1);
        if (x1 < x0) x1 = x0;
        if (y1 < y0) y1 = y0;
        float[] v = new float[(x1 - x0 + 1) * (y1 - y0 + 1)];
        int k = 0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) v[k++] = grid[y * gw + x];
        Arrays.sort(v);
        return v[v.length / 2];
    }

    private static int clampI(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

    // ---------------------------------------------------------------- the glass pills' soft picture (✨ 5)

    /** A quick soft blur of a small picture's pixels (ARGB), radius r, twice (≈ a gentle Gaussian); in place. */
    static void boxBlur(int[] px, int w, int h, int r) {
        if (r <= 0 || w <= 0 || h <= 0) return;
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 2; pass++) {
            blurLine(px, tmp, w, h, r, true);
            blurLine(tmp, px, w, h, r, false);
        }
    }

    private static void blurLine(int[] src, int[] dst, int w, int h, int r, boolean across) {
        int lines = across ? h : w, len = across ? w : h;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int p = across ? src[l * w + k] : src[k * w + l];
                    a += p >>> 24; rr += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; b += p & 0xFF; n++;
                }
                int o = across ? l * w + i : i * w + l;
                dst[o] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
    }

    // ---------------------------------------------------------------- the HUD's small lag when the phone turns (✨ 7)

    /**
     * The HUD's edges lag a little behind a turn and settle back (pixels): move = how far the world slid on the screen
     * this frame; it never goes past ±max and eases home in about a third of a second.
     */
    static float parallax(float off, float move, float dtMs, float max) {
        float v = off + move * 0.35f;
        if (dtMs > 0) v *= (float) Math.exp(-dtMs / 320f);
        if (!(Math.abs(v) >= 0.05f)) return 0f; // (settled: exactly home, so the HUD can stop redrawing; a NaN from a sensor too)
        return Math.max(-max, Math.min(max, v));
    }

    // ---------------------------------------------------------------- where the horizon is (✨ 8)

    /**
     * How far below the middle of the screen the level horizon is (pixels; negative = above), for a pitch in degrees
     * (the same sign the reticle's horizon has always used) and the screen's view in degrees (0 = unknown: the old
     * gentle 0.22 H per 45°).
     */
    static float horizonDy(float pitchDeg, float fovDeg, float H) {
        if (fovDeg <= 1f || fovDeg >= 179f) return pitchDeg / 45f * H * 0.22f;
        double kpx = H / (2 * Math.tan(Math.toRadians(fovDeg / 2)));
        double p = Math.max(-80, Math.min(80, pitchDeg));
        return (float) (Math.tan(Math.toRadians(p)) * kpx);
    }

    /**
     * The floor grid's rows: screen y below the horizon for ground distances (metres) seen from about camH metres up;
     * kpx = pixels per unit of tan (H / (2·tan(fov/2))). Rows past the bottom are left out.
     */
    static float[] groundRows(float horizonY, float kpx, float camH, float[] distances, float bottom) {
        float[] tmp = new float[distances.length];
        int n = 0;
        for (float d : distances) {
            if (d <= 0) continue;
            float y = horizonY + kpx * camH / d;
            if (y > horizonY && y <= bottom) tmp[n++] = y;
        }
        return Arrays.copyOf(tmp, n);
    }
}
