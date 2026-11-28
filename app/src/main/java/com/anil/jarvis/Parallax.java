package com.anil.jarvis;

/**
 * 🖼️ The 3D photo's maths (no Android here: desk-tested). The phone's own depth model says how near each part of one photo
 * is; the photo is laid on a grid, and when he tilts the phone (or drags) near parts slide one way and far parts the other,
 * round the thing in the middle that stays still — the photo seems to stand out of the screen. Front only: what the photo
 * never showed (behind the thing) cannot appear; the photo only stretches there.
 */
final class Parallax {
    private Parallax() {}

    /** A softer depth (a box blur of radius r) so the photo stretches smoothly at a thing's edge. */
    static float[] smooth(float[] d, int w, int h, int r) {
        if (r <= 0) return d.clone();
        float[] tmp = new float[w * h], out = new float[w * h];
        for (int y = 0; y < h; y++) {
            float s = 0;
            int c = 0;
            for (int x = -r; x <= r; x++) if (x >= 0 && x < w) { s += d[y * w + x]; c++; }
            for (int x = 0; x < w; x++) {
                tmp[y * w + x] = s / c;
                int out0 = x - r, in = x + r + 1;
                if (out0 >= 0) { s -= d[y * w + out0]; c--; }
                if (in < w) { s += d[y * w + in]; c++; }
            }
        }
        for (int x = 0; x < w; x++) {
            float s = 0;
            int c = 0;
            for (int y = -r; y <= r; y++) if (y >= 0 && y < h) { s += tmp[y * w + x]; c++; }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = s / c;
                int out0 = y - r, in = y + r + 1;
                if (out0 >= 0) { s -= tmp[out0 * w + x]; c--; }
                if (in < h) { s += tmp[in * w + x]; c++; }
            }
        }
        return out;
    }

    /** The depth that stays still: the middle value of the photo's centre third (the thing he pointed at). */
    static float focus(float[] d, int w, int h) {
        int x0 = w / 3, x1 = w * 2 / 3, y0 = h / 3, y1 = h * 2 / 3;
        float[] v = new float[Math.max(1, (x1 - x0) * (y1 - y0))];
        int k = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) v[k++] = d[y * w + x];
        if (k == 0) return 0.5f;
        java.util.Arrays.sort(v, 0, k);
        return v[k / 2];
    }

    /**
     * The grid's points for Canvas.drawBitmapMesh: (cols + 1) × (rows + 1) pairs over the photo's place (x0, y0)–(x1, y1),
     * each moved by (its depth − focus) × (sx, sy) pixels, the whole a little bigger (zoom) so the edges never show.
     */
    static float[] verts(float[] d, int w, int h, int cols, int rows, float x0, float y0, float x1, float y1, float sx, float sy, float focus, float zoom) {
        float[] out = new float[(cols + 1) * (rows + 1) * 2];
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        int k = 0;
        for (int r = 0; r <= rows; r++) {
            float fy = r / (float) rows;
            for (int c = 0; c <= cols; c++) {
                float fx = c / (float) cols;
                float dd = sample(d, w, h, fx, fy) - focus;
                float x = x0 + (x1 - x0) * fx, y = y0 + (y1 - y0) * fy;
                x = cx + (x - cx) * zoom + dd * sx;
                y = cy + (y - cy) * zoom + dd * sy;
                out[k++] = x;
                out[k++] = y;
            }
        }
        return out;
    }

    static float sample(float[] d, int w, int h, float fx, float fy) {
        float x = Math.max(0, Math.min(w - 1, fx * (w - 1))), y = Math.max(0, Math.min(h - 1, fy * (h - 1)));
        int xi = (int) x, yi = (int) y, x2 = Math.min(w - 1, xi + 1), y2 = Math.min(h - 1, yi + 1);
        float ax = x - xi, ay = y - yi;
        float top = d[yi * w + xi] * (1 - ax) + d[yi * w + x2] * ax, bot = d[y2 * w + xi] * (1 - ax) + d[y2 * w + x2] * ax;
        return top * (1 - ay) + bot * ay;
    }
}
